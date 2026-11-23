package com.enterprise.gateway.telegram;

import com.enterprise.gateway.domain.RelayedMessage;
import com.enterprise.gateway.repository.RelayedMessageRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class AlbumBufferService {

    private static final long FLUSH_DELAY_MS = 700L;

    private final TelegramApiClient telegramApiClient;
    private final RelayedMessageRepository relayedMessageRepository;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-album-flusher");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, PendingAlbum> pendingAlbums = new ConcurrentHashMap<>();

    public AlbumBufferService(
            TelegramApiClient telegramApiClient,
            RelayedMessageRepository relayedMessageRepository
    ) {
        this.telegramApiClient = telegramApiClient;
        this.relayedMessageRepository = relayedMessageRepository;
    }

    /**
     * Queue a single photo belonging to an album.
     *
     * @param mediaGroupId     Telegram album id
     * @param ticketId         owning ticket id (used for mapping row)
     * @param targetGroupId    destination branch group
     * @param topicId          destination forum topic id
     * @param sourceChatId     original chat id (customer user id)
     * @param sourceMessageId  original message id in customer chat
     * @param photoFileId      Telegram file_id for the photo
     * @param caption          album caption (first non-blank wins)
     */
    public void queueTopicPhoto(
            String mediaGroupId,
            Long ticketId,
            long targetGroupId,
            int topicId,
            Long sourceChatId,
            int sourceMessageId,
            String photoFileId,
            String caption
    ) {
        String key = "topic:" + targetGroupId + ":" + topicId + ":" + mediaGroupId;

        pendingAlbums.compute(key, (k, existing) -> {
            PendingAlbum entry = existing != null
                    ? existing
                    : new PendingAlbum(targetGroupId, topicId);

            entry.photos.add(new PhotoEntry(sourceChatId, sourceMessageId, ticketId, photoFileId));
            if (entry.caption == null && caption != null && !caption.isBlank()) {
                entry.caption = caption;
            }

            if (entry.future != null) {
                entry.future.cancel(false);
            }

            entry.future = scheduler.schedule(() -> flushAlbum(key), FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            return entry;
        });
    }

    private void flushAlbum(String key) {
        PendingAlbum entry = pendingAlbums.remove(key);
        if (entry == null || entry.photos.isEmpty()) {
            return;
        }

        List<Map<String, Object>> media = new ArrayList<>();
        for (int i = 0; i < entry.photos.size(); i++) {
            PhotoEntry p = entry.photos.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "photo");
            item.put("media", p.fileId());
            if (i == 0 && entry.caption != null && !entry.caption.isBlank()) {
                item.put("caption", entry.caption);
            }
            media.add(item);
        }

        JsonNode result;
        try {
            result = telegramApiClient.sendMediaGroup(entry.targetGroupId, media, entry.topicId);
        } catch (Exception ex) {
            System.err.println("[BOT-ERROR] Failed to flush album to topic "
                    + entry.topicId + ": " + ex.getMessage());
            return;
        }

        if (result == null || !result.isArray()) {
            System.err.println("[BOT-ERROR] Unexpected sendMediaGroup response for topic "
                    + entry.topicId + ": " + result);
            return;
        }

        int saved = 0;
        int count = Math.min(result.size(), entry.photos.size());
        for (int i = 0; i < count; i++) {
            PhotoEntry p = entry.photos.get(i);
            int targetMsgId = result.get(i).path("message_id").asInt();
            if (targetMsgId == 0) {
                System.err.println("[BOT-ERROR] Album flush returned empty message_id at index " + i);
                continue;
            }
            try {
                RelayedMessage mapping = new RelayedMessage();
                mapping.setTicketId(p.ticketId());
                mapping.setSourceChatId(p.sourceChatId());
                mapping.setSourceMessageId(p.sourceMessageId());
                mapping.setTargetChatId(entry.targetGroupId);
                mapping.setTargetMessageId(targetMsgId);
                relayedMessageRepository.save(mapping);
                saved++;
                System.out.println("[BOT-DEBUG] Saved album mapping ticketId=" + p.ticketId()
                        + " source=(" + p.sourceChatId() + "," + p.sourceMessageId()
                        + ") -> target=(" + entry.targetGroupId + "," + targetMsgId + ")");
            } catch (Exception ex) {
                System.err.println("[BOT-ERROR] Failed to save album mapping for sourceMsg="
                        + p.sourceMessageId() + ": " + ex.getMessage());
            }
        }

        if (saved != entry.photos.size()) {
            System.err.println("[BOT-ERROR] Album mapping mismatch: "
                    + saved + "/" + entry.photos.size() + " saved for topic " + entry.topicId);
        }
    }

    public static String extractHighestResPhotoFileId(JsonNode message) {
        JsonNode photos = message.path("photo");
        if (!photos.isArray() || photos.isEmpty()) {
            return null;
        }
        return photos.get(photos.size() - 1).path("file_id").asText(null);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private record PhotoEntry(
            Long sourceChatId,
            int sourceMessageId,
            Long ticketId,
            String fileId
    ) {}

    private static final class PendingAlbum {
        private final long targetGroupId;
        private final int topicId;
        private final List<PhotoEntry> photos = new ArrayList<>();
        private String caption;
        private ScheduledFuture<?> future;

        private PendingAlbum(long targetGroupId, int topicId) {
            this.targetGroupId = targetGroupId;
            this.topicId = topicId;
        }
    }
}