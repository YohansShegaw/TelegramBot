package com.enterprise.gateway.service;

import com.enterprise.gateway.config.TelegramProperties;
import com.enterprise.gateway.domain.Branch;
import com.enterprise.gateway.domain.CustomerTicket;
import com.enterprise.gateway.domain.RelayedMessage;
import com.enterprise.gateway.repository.BranchRepository;
import com.enterprise.gateway.repository.CustomerTicketRepository;
import com.enterprise.gateway.repository.RelayedMessageRepository;
import com.enterprise.gateway.telegram.AlbumBufferService;
import com.enterprise.gateway.telegram.TelegramApiClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class RoutingService {

    private static final String DELETE_CALLBACK = "del";
    private static final String DELETE_MINE_PREFIX = "delmine:";

    /**
     * Reacting to a message with this emoji deletes it on both sides.
     * It must be one of Telegram's allowed reaction emojis (there is no 🗑 or ❌),
     * and should be one nobody uses as a normal reaction, because reactions are
     * also relayed to the other side.
     */
    private static final String DELETE_REACTION_EMOJI = "🙈";

    /**
     * Telegram bots cannot put buttons on messages a person sent, so for the
     * sender the bot posts a tiny reply under their message that carries the
     * Delete button. Set to false to turn these control messages off.
     */
    private static final boolean SENDER_DELETE_ENABLED = false;

    /**
     * true: the bot re-posts the sender's message itself with the Delete button
     * attached directly to it (no extra message, no text), and removes the
     * original. false: fall back to the separate button bubble.
     */
    private static final boolean REPOST_WITH_BUTTON = false;

    /** How long the "Delete for everyone" control stays visible before it removes itself. */
    private static final long CONTROL_TTL_SECONDS = 180L;

    private final ScheduledExecutorService controlCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-control-cleaner");
        t.setDaemon(true);
        return t;
    });

    private static final String CUSTOMER_DELETE_HINT =
            "To delete any message (sent or received) on both sides, react to it with "
                    + DELETE_REACTION_EMOJI + ". You can also tap <b>🗑 Delete</b> under messages you received.";

    private final TelegramProperties properties;

    /** From application.yml: telegram.delete.customer-enabled */
    private boolean customerDeleteEnabled() {
        return properties.customerDeleteEnabled();
    }

    /** Hint text for the welcome message; empty when customers cannot delete. */
    private String customerDeleteHint() {
        return customerDeleteEnabled() ? "\n\n" + CUSTOMER_DELETE_HINT : "";
    }

    private static boolean isPrivateChat(JsonNode chat) {
        return "private".equals(chat.path("type").asText(""));
    }

    private final BranchRepository branchRepository;
    private final CustomerTicketRepository ticketRepository;
    private final RelayedMessageRepository relayedMessageRepository;
    private final TelegramApiClient telegramApiClient;
    private final AlbumBufferService albumBufferService;

    public RoutingService(
            BranchRepository branchRepository,
            CustomerTicketRepository ticketRepository,
            RelayedMessageRepository relayedMessageRepository,
            TelegramApiClient telegramApiClient,
            AlbumBufferService albumBufferService,
            TelegramProperties properties
    ) {
        this.branchRepository = branchRepository;
        this.ticketRepository = ticketRepository;
        this.relayedMessageRepository = relayedMessageRepository;
        this.telegramApiClient = telegramApiClient;
        this.albumBufferService = albumBufferService;
        this.properties = properties;
    }

    private void sout(String msg) {
        System.out.println("[BOT-DEBUG] " + msg);
    }

    private void serr(String msg) {
        System.err.println("[BOT-ERROR] " + msg);
    }

    /** Inline keyboard with a single "Delete" button, attached to every relayed copy (shown to the receiver). */
    private static Map<String, Object> deleteKeyboard() {
        return Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", "🗑 Delete", "callback_data", DELETE_CALLBACK)
        )));
    }

    /**
     * Telegram does not allow a bot to attach buttons to a message a person sent.
     * The closest result is a button-only bubble posted right after it (not a
     * reply, no visible text), so the button appears directly under the message.
     * It removes itself after CONTROL_TTL_SECONDS; afterwards the message can
     * still be removed with /del.
     */
    private void sendSenderControl(long chatId, Integer threadId, int sentMessageId) {
        if (!SENDER_DELETE_ENABLED) {
            return;
        }
        Map<String, Object> keyboard = Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", "🗑 Delete for everyone",
                        "callback_data", DELETE_MINE_PREFIX + sentMessageId)
        )));

        JsonNode sent;
        try {
            // U+2800 (blank braille) renders as an empty bubble, leaving only the button visible.
            sent = telegramApiClient.sendMessage(chatId, "\u2800", threadId, null, keyboard);
        } catch (Exception first) {
            try {
                sent = telegramApiClient.sendMessage(chatId, "🗑", threadId, null, keyboard);
            } catch (Exception ex) {
                serr("Failed to send sender delete control for msg " + sentMessageId
                        + " in chat " + chatId + ": " + ex.getMessage());
                return;
            }
        }

        int controlMsgId = sent.path("message_id").asInt();
        if (controlMsgId != 0) {
            controlCleaner.schedule(() -> {
                try {
                    telegramApiClient.deleteMessage(chatId, controlMsgId);
                } catch (Exception ignored) {
                    // already removed (button pressed); nothing to do
                }
            }, CONTROL_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Re-post a message in the same chat as a bot message that carries the
     * Delete button, then delete the original. Returns the new message id, or
     * null if anything failed (the original is left untouched in that case).
     */
    private Integer repostWithButton(long chatId, Integer threadId, int originalMsgId, Integer replyToMsgId) {
        try {
            JsonNode reposted = telegramApiClient.copyMessage(
                    chatId, chatId, originalMsgId, threadId, replyToMsgId, deleteKeyboard());
            int newId = reposted.path("message_id").asInt();
            if (newId == 0) {
                return null;
            }
            safeDeleteMessage(chatId, originalMsgId);
            sout("Re-posted msg " + originalMsgId + " as " + newId + " with delete button in chat " + chatId);
            return newId;
        } catch (Exception ex) {
            serr("Re-post with button failed for msg " + originalMsgId + ": " + ex.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void shutdownControlCleaner() {
        controlCleaner.shutdownNow();
    }

    @Transactional
    public void handleUpdate(JsonNode update) {
        long updateId = update.path("update_id").asLong(-1);
        sout("Received update_id=" + updateId);

        if (update.has("callback_query")) {
            sout("Update type: callback_query");
            handleCallbackQuery(update.path("callback_query"));
            return;
        }

        if (update.has("edited_message")) {
            sout("Update type: edited_message");
            handleEditedMessage(update.path("edited_message"));
            return;
        }

        if (update.has("message_reaction")) {
            sout("Update type: message_reaction");
            handleMessageReaction(update.path("message_reaction"));
            return;
        }

        if (update.has("message")) {
            sout("Update type: message");
            handleMessage(update.path("message"));
            return;
        }

        sout("Unhandled update keys: " + update.asOptional());
    }

    private void handleCallbackQuery(JsonNode callbackQuery) {
        String callbackId = callbackQuery.path("id").asText("");
        String data = callbackQuery.path("data").asText("");
        JsonNode from = callbackQuery.path("from");
        JsonNode message = callbackQuery.path("message");
        long chatId = message.path("chat").path("id").asLong();

        // Customers may be blocked from deleting (telegram.delete.customer-enabled=false).
        if ((DELETE_CALLBACK.equals(data) || data.startsWith(DELETE_MINE_PREFIX))
                && isPrivateChat(message.path("chat"))
                && !customerDeleteEnabled()) {
            telegramApiClient.answerCallbackQuery(callbackId, "Deleting messages is not available.");
            return;
        }

        // "Delete my message" control posted under the sender's own message.
        if (data.startsWith(DELETE_MINE_PREFIX)) {
            int controlMsgId = message.path("message_id").asInt();
            int targetMsgId;
            try {
                targetMsgId = Integer.parseInt(data.substring(DELETE_MINE_PREFIX.length()));
            } catch (NumberFormatException ex) {
                telegramApiClient.answerCallbackQuery(callbackId, "Invalid request");
                return;
            }
            sout("Sender delete pressed by user=" + from.path("id").asLong()
                    + " chatId=" + chatId + " targetMsgId=" + targetMsgId);

            DeleteOutcome outcome = deletePairedMessage(chatId, targetMsgId);
            if (outcome.errors().isEmpty()) {
                safeDeleteMessage(chatId, controlMsgId);
            }
            try {
                telegramApiClient.answerCallbackQuery(
                        callbackId,
                        outcome.errors().isEmpty()
                                ? "Deleted"
                                : "Could not delete (older than 48h, or the bot lacks the Delete Messages right)"
                );
            } catch (Exception ex) {
                serr("answerCallbackQuery failed: " + ex.getMessage());
            }
            return;
        }

        // Delete button under a received message (admin side or customer side).
        if (DELETE_CALLBACK.equals(data)) {
            int msgId = message.path("message_id").asInt();
            sout("Delete button pressed by user=" + from.path("id").asLong()
                    + " chatId=" + chatId + " msgId=" + msgId);

            DeleteOutcome outcome = deletePairedMessage(chatId, msgId);
            String toast = outcome.errors().isEmpty()
                    ? "Deleted"
                    : "Could not delete (older than 48h, or the bot lacks the Delete Messages right)";
            try {
                telegramApiClient.answerCallbackQuery(callbackId, toast);
            } catch (Exception ex) {
                serr("answerCallbackQuery failed: " + ex.getMessage());
            }
            return;
        }

        telegramApiClient.answerCallbackQuery(callbackId, null);
        sout("Callback from user=" + from.path("id").asLong() + " chatId=" + chatId + " data=" + data);

        if (data.startsWith("cat_")) {
            String department = "cat_realestate".equals(data) ? "Real Estate" : "Other Sales";
            openOrReuseTicket(chatId, from, department, null);
        }
    }

    private void handleEditedMessage(JsonNode editedMessage) {
        JsonNode chat = editedMessage.path("chat");
        String chatType = chat.path("type").asText("");
        long chatId = chat.path("id").asLong();
        int editedMessageId = editedMessage.path("message_id").asInt();

        sout("Edited message in chatType=" + chatType + " chatId=" + chatId + " msgId=" + editedMessageId);

        Optional<RelayedMessage> mappingOpt = relayedMessageRepository
                .findFirstBySourceChatIdAndSourceMessageIdOrderByIdDesc(chatId, editedMessageId);

        if (mappingOpt.isEmpty()) {
            sout("No relayed_messages mapping found for edited source chatId=" + chatId + " msgId=" + editedMessageId);
            return;
        }

        RelayedMessage mapping = mappingOpt.get();
        boolean isCustomerEdit = "private".equals(chatType);
        sout("Found mapping -> targetChatId=" + mapping.getTargetChatId()
                + " targetMsgId=" + mapping.getTargetMessageId()
                + " isCustomerEdit=" + isCustomerEdit);

        // Editing without reply_markup strips the button, so always resend it:
        // every relayed copy (topic side and customer side) carries a button.
        // Copies in the customer's chat only carry the button if customers may delete.
        Object keyboard = (isCustomerEdit || customerDeleteEnabled()) ? deleteKeyboard() : null;

        try {
            if (editedMessage.has("text")) {
                String rawText = editedMessage.path("text").asText("");
                String updatedText = isCustomerEdit
                        ? escapeHtml(rawText) + "\n<i>(edited)</i>"
                        : rawText;
                String parseMode = isCustomerEdit ? "HTML" : null;
                telegramApiClient.editMessageText(
                        mapping.getTargetChatId(),
                        mapping.getTargetMessageId(),
                        updatedText,
                        parseMode,
                        keyboard
                );
                sout("Synced edited text to targetMsgId=" + mapping.getTargetMessageId());
            } else if (editedMessage.has("caption")) {
                String rawCaption = editedMessage.path("caption").asText("");
                String updatedCaption = isCustomerEdit
                        ? rawCaption + "\n(edited)"
                        : rawCaption;
                telegramApiClient.editMessageCaption(
                        mapping.getTargetChatId(),
                        mapping.getTargetMessageId(),
                        updatedCaption,
                        null,
                        keyboard
                );
                sout("Synced edited caption to targetMsgId=" + mapping.getTargetMessageId());
            }
        } catch (Exception ex) {
            serr("Sync of edited message " + editedMessageId + " failed: " + ex.getMessage());
        }
    }

    private void handleMessage(JsonNode message) {
        if (message.has("forum_topic_created")) {
            sout("Ignoring forum_topic_created service message");
            return;
        }

        JsonNode chat = message.path("chat");
        String chatType = chat.path("type").asText("");
        long chatId = chat.path("id").asLong();
        JsonNode from = message.path("from");
        int msgId = message.path("message_id").asInt();

        sout("Message msgId=" + msgId + " chatType=" + chatType + " chatId=" + chatId
                + " fromId=" + from.path("id").asLong()
                + " isBot=" + from.path("is_bot").asBoolean(false)
                + " threadId=" + message.path("message_thread_id").asInt(-1));

        if ("private".equals(chatType)) {
            handlePrivateCustomerMessage(chatId, from, message);
            return;
        }

        if (("group".equals(chatType) || "supergroup".equals(chatType))
                && message.has("message_thread_id")
                && !from.path("is_bot").asBoolean(false)) {
            int topicId = message.path("message_thread_id").asInt();
            handleBranchAdminReply(chatId, topicId, message);
        }
    }

    private void handlePrivateCustomerMessage(long customerUserId, JsonNode from, JsonNode message) {
        String text = message.path("text").asText("");
        int messageId = message.path("message_id").asInt();

        if (text.equals("/start") || text.startsWith("/start ")) {
            String payload = text.length() > 6 ? text.substring(6).trim() : "";
            sout("Customer " + customerUserId + " sent /start payload='" + payload + "'");
            if (!payload.isBlank()) {
                openOrReuseTicket(customerUserId, from, "Channel Inquiry", payload);
            } else {
                sendDepartmentMenu(customerUserId);
            }
            return;
        }

        // Customer-side /del: delete a message on both sides.
        String customerCmd = text.trim().split("\\s+")[0].split("@")[0].toLowerCase();
        if ("/del".equals(customerCmd) || "/delete".equals(customerCmd)) {
            if (!customerDeleteEnabled()) {
                telegramApiClient.sendMessage(
                        customerUserId,
                        "Deleting messages is not available.",
                        null, null, null);
                return;
            }
            handleCustomerDelete(customerUserId, messageId, message);
            return;
        }

        Optional<CustomerTicket> ticketOpt = ticketRepository
                .findFirstByTelegramUserIdAndStatusOrderByIdDesc(customerUserId, "OPEN");

        if (ticketOpt.isEmpty()) {
            sout("No OPEN ticket for customerUserId=" + customerUserId);
            telegramApiClient.sendMessage(
                    customerUserId,
                    "Please tap /start first to select a department.",
                    null,
                    null,
                    null
            );
            return;
        }

        CustomerTicket ticket = ticketOpt.get();
        ticket.setUpdatedAt(Instant.now());
        ticketRepository.save(ticket);

        String mediaGroupId = message.path("media_group_id").asText(null);
        String highestResPhoto = AlbumBufferService.extractHighestResPhotoFileId(message);

        // Albums go through sendMediaGroup, which does not support inline buttons,
        // so album photos get no button (use /del on them).
        if (highestResPhoto != null && mediaGroupId != null && !mediaGroupId.isBlank()) {
            String caption = message.path("caption").asText("");
            sout("Queueing album photo mediaGroupId=" + mediaGroupId
                    + " sourceMsgId=" + messageId
                    + " for topicId=" + ticket.getTopicId());
            albumBufferService.queueTopicPhoto(
                    mediaGroupId,
                    ticket.getId(),
                    ticket.getTelegramGroupId(),
                    ticket.getTopicId(),
                    customerUserId,
                    messageId,
                    highestResPhoto,
                    caption
            );
            return;
        }

        // If the customer is replying to an earlier bot-relayed message, thread
        // the copy under the admin's original in the topic.
        Integer replyToTopicMsgId = null;
        if (message.has("reply_to_message")) {
            int repliedCustomerMsgId = message.path("reply_to_message").path("message_id").asInt();
            Optional<RelayedMessage> mapping = relayedMessageRepository
                    .findFirstByTargetChatIdAndTargetMessageIdOrderByIdDesc(customerUserId, repliedCustomerMsgId);
            if (mapping.isPresent()) {
                replyToTopicMsgId = mapping.get().getSourceMessageId();
                sout("Customer reply threads under topic msgId=" + replyToTopicMsgId);
            }
        }

        sout("Relaying customer msgId=" + messageId + " -> groupId=" + ticket.getTelegramGroupId()
                + " topicId=" + ticket.getTopicId()
                + " replyToTopicMsgId=" + replyToTopicMsgId);
        JsonNode copied = telegramApiClient.copyMessage(
                ticket.getTelegramGroupId(),
                customerUserId,
                messageId,
                ticket.getTopicId(),
                replyToTopicMsgId,
                deleteKeyboard()
        );
        int copiedMsgId = copied.path("message_id").asInt();
        sout("Copied to topic with targetMsgId=" + copiedMsgId);
        Integer customerReplyId = message.has("reply_to_message")
                ? message.path("reply_to_message").path("message_id").asInt()
                : null;
        Integer repostId = (REPOST_WITH_BUTTON && customerDeleteEnabled())
                ? repostWithButton(customerUserId, null, messageId, customerReplyId)
                : null;
        saveMapping(
                ticket.getId(),
                customerUserId,
                repostId != null ? repostId : messageId,
                ticket.getTelegramGroupId(),
                copiedMsgId
        );
        if (repostId == null && customerDeleteEnabled()) {
            sendSenderControl(customerUserId, null, messageId);
        }
    }

    private void handleCustomerDelete(long customerUserId, int commandMsgId, JsonNode message) {
        sout("Customer /del from chatId=" + customerUserId + " cmdMsgId=" + commandMsgId
                + " hasReply=" + message.has("reply_to_message"));

        if (!message.has("reply_to_message")) {
            telegramApiClient.sendMessage(
                    customerUserId,
                    "Reply directly to the message you want to delete with /del.",
                    null,
                    null,
                    null
            );
            return;
        }

        int repliedMsgId = message.path("reply_to_message").path("message_id").asInt();
        DeleteOutcome outcome = deletePairedMessage(customerUserId, repliedMsgId);
        safeDeleteMessage(customerUserId, commandMsgId);

        if (!outcome.errors().isEmpty()) {
            try {
                telegramApiClient.sendMessage(
                        customerUserId,
                        "Some messages could not be deleted (they may be older than 48 hours).",
                        null,
                        null,
                        null
                );
            } catch (Exception ex) {
                serr("Failed to notify customer of delete errors: " + ex.getMessage());
            }
        }
    }

    private void handleBranchAdminReply(long groupId, int topicId, JsonNode message) {
        Optional<CustomerTicket> ticketOpt = ticketRepository
                .findFirstByTelegramGroupIdAndTopicIdAndStatusOrderByIdDesc(groupId, topicId, "OPEN");

        if (ticketOpt.isEmpty()) {
            sout("No OPEN ticket for groupId=" + groupId + " topicId=" + topicId);
            return;
        }

        CustomerTicket ticket = ticketOpt.get();
        ticket.setUpdatedAt(Instant.now());
        ticketRepository.save(ticket);

        String text = message.path("text").asText("").trim();
        int adminMsgId = message.path("message_id").asInt();
        String commandPart = text.split("\\s+")[0].split("@")[0].toLowerCase();

        // /del stays available as a fallback: it is the only way to delete the
        // admin's own messages, albums, and messages relayed before buttons existed.
        if ("/del".equals(commandPart) || "/delete".equals(commandPart)) {
            boolean hasReply = message.has("reply_to_message");
            sout("Admin /del command in groupId=" + groupId + " topicId=" + topicId
                    + " adminMsgId=" + adminMsgId + " hasReply=" + hasReply);

            if (!hasReply) {
                telegramApiClient.sendMessage(
                        groupId,
                        "Reply directly to the message you want to delete with /del.",
                        topicId,
                        null,
                        null
                );
                return;
            }

            int repliedTopicMsgId = message.path("reply_to_message").path("message_id").asInt();
            sout("Admin /del targeting repliedTopicMsgId=" + repliedTopicMsgId);

            DeleteOutcome outcome = deletePairedMessage(groupId, repliedTopicMsgId);
            safeDeleteMessage(groupId, adminMsgId);
            notifyDeleteOutcome(groupId, topicId, repliedTopicMsgId, outcome);
            return;
        }

        // If the admin is replying to a customer-relayed message in the topic,
        // thread the reply under the customer's original message so that:
        //   1. the customer sees it as a proper reply, and
        //   2. if the customer deleted their original natively, Telegram
        //      renders "Reply to deleted message" automatically.
        Integer replyToCustomerMsgId = null;
        if (message.has("reply_to_message")) {
            int repliedTopicMsgId = message.path("reply_to_message").path("message_id").asInt();
            Optional<RelayedMessage> mapping = relayedMessageRepository
                    .findFirstByTargetChatIdAndTargetMessageIdOrderByIdDesc(groupId, repliedTopicMsgId);
            if (mapping.isPresent()) {
                replyToCustomerMsgId = mapping.get().getSourceMessageId();
                sout("Admin reply threads under customer msgId=" + replyToCustomerMsgId);
            }
        }

        sout("Relaying admin msgId=" + adminMsgId + " -> customerUserId=" + ticket.getTelegramUserId()
                + " replyToCustomerMsgId=" + replyToCustomerMsgId);

        // The customer receives this copy, so it carries the Delete button.
        // Pressing it removes the message on both sides.
        JsonNode copied = telegramApiClient.copyMessage(
                ticket.getTelegramUserId(),
                groupId,
                adminMsgId,
                null,
                replyToCustomerMsgId,
                customerDeleteEnabled() ? deleteKeyboard() : null
        );
        int copiedMsgId = copied.path("message_id").asInt();
        sout("Copied to customer with targetMsgId=" + copiedMsgId);
        Integer adminReplyId = null;
        if (message.has("reply_to_message")) {
            int rid = message.path("reply_to_message").path("message_id").asInt();
            if (rid != topicId) { // replies to the topic root are implicit; do not show them
                adminReplyId = rid;
            }
        }
        Integer repostId = REPOST_WITH_BUTTON
                ? repostWithButton(groupId, topicId, adminMsgId, adminReplyId)
                : null;
        saveMapping(
                ticket.getId(),
                groupId,
                repostId != null ? repostId : adminMsgId,
                ticket.getTelegramUserId(),
                copiedMsgId
        );
        if (repostId == null) {
            sendSenderControl(groupId, topicId, adminMsgId);
        }
    }

    private void handleMessageReaction(JsonNode reactionUpdate) {
        long chatId = reactionUpdate.path("chat").path("id").asLong();
        int messageId = reactionUpdate.path("message_id").asInt();
        JsonNode newReactionNode = reactionUpdate.path("new_reaction");

        sout("Reaction event in chatId=" + chatId + " msgId=" + messageId
                + " new_reaction=" + newReactionNode);

        // Delete trigger: the delete emoji was just added (not already present).
        if (containsEmoji(newReactionNode, DELETE_REACTION_EMOJI)
                && !containsEmoji(reactionUpdate.path("old_reaction"), DELETE_REACTION_EMOJI)) {
            if (isPrivateChat(reactionUpdate.path("chat")) && !customerDeleteEnabled()) {
                sout("Delete reaction ignored: customer delete is disabled");
                return;
            }
            sout("Delete reaction in chatId=" + chatId + " msgId=" + messageId);
            DeleteOutcome outcome = deletePairedMessage(chatId, messageId);
            if (!outcome.errors().isEmpty()) {
                serr("Delete via reaction failed: " + outcome.errors());
                if ("private".equals(reactionUpdate.path("chat").path("type").asText(""))) {
                    try {
                        telegramApiClient.sendMessage(
                                chatId,
                                "Could not delete that message (it may be older than 48 hours).",
                                null, null, null);
                    } catch (Exception ex) {
                        serr("Failed to notify about delete failure: " + ex.getMessage());
                    }
                }
            }
            return;
        }

        List<Map<String, Object>> reactions = new ArrayList<>();
        if (newReactionNode.isArray() && !newReactionNode.isEmpty()) {
            JsonNode first = newReactionNode.get(0);
            String type = first.path("type").asText("");
            if ("emoji".equals(type)) {
                String emoji = first.path("emoji").asText("");
                if (!emoji.isBlank()) {
                    reactions.add(Map.of("type", "emoji", "emoji", emoji));
                }
            }
        }

        findMappedTarget(chatId, messageId).ifPresentOrElse(
                target -> {
                    sout("Relaying reaction -> targetChatId=" + target.chatId()
                            + " targetMsgId=" + target.messageId() + " reactions=" + reactions);
                    try {
                        telegramApiClient.setMessageReaction(
                                target.chatId(),
                                target.messageId(),
                                reactions,
                                false
                        );
                        sout("Reaction relayed successfully");
                    } catch (Exception ex) {
                        serr("Relaying reaction for message " + messageId + " failed: " + ex.getMessage());
                    }
                },
                () -> sout("No mapped message found for reaction in chatId=" + chatId + " msgId=" + messageId)
        );
    }

    private static boolean containsEmoji(JsonNode reactionArray, String emoji) {
        if (reactionArray == null || !reactionArray.isArray()) {
            return false;
        }
        for (JsonNode r : reactionArray) {
            if ("emoji".equals(r.path("type").asText("")) && emoji.equals(r.path("emoji").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private Optional<MappedMessage> findMappedTarget(long chatId, int messageId) {
        Optional<RelayedMessage> asSource = relayedMessageRepository
                .findFirstBySourceChatIdAndSourceMessageIdOrderByIdDesc(chatId, messageId);
        if (asSource.isPresent()) {
            RelayedMessage m = asSource.get();
            return Optional.of(new MappedMessage(m.getTargetChatId(), m.getTargetMessageId()));
        }

        Optional<RelayedMessage> asTarget = relayedMessageRepository
                .findFirstByTargetChatIdAndTargetMessageIdOrderByIdDesc(chatId, messageId);
        if (asTarget.isPresent()) {
            RelayedMessage m = asTarget.get();
            return Optional.of(new MappedMessage(m.getSourceChatId(), m.getSourceMessageId()));
        }

        return Optional.empty();
    }

    private record MappedMessage(Long chatId, Integer messageId) {}

    private record DeleteOutcome(List<String> deleted, List<String> errors) {}

    /**
     * Delete a message and its relayed counterpart. Works from either side:
     * the given (chatId, messageId) may be an original (source) or a relayed
     * copy (target). The mapping row is removed once both deletes succeeded.
     */
    private DeleteOutcome deletePairedMessage(long chatId, int messageId) {
        List<String> deleted = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Optional<RelayedMessage> asTarget = relayedMessageRepository
                .findFirstByTargetChatIdAndTargetMessageIdOrderByIdDesc(chatId, messageId);
        if (asTarget.isPresent()) {
            RelayedMessage m = asTarget.get();
            sout("Deleting paired (asTarget): source=(" + m.getSourceChatId() + "," + m.getSourceMessageId()
                    + ") target=(" + m.getTargetChatId() + "," + m.getTargetMessageId() + ")");
            tryDelete(m.getSourceChatId(), m.getSourceMessageId(), deleted, errors);
            tryDelete(m.getTargetChatId(), m.getTargetMessageId(), deleted, errors);
            removeMappingIfClean(m, errors);
            return new DeleteOutcome(deleted, errors);
        }

        Optional<RelayedMessage> asSource = relayedMessageRepository
                .findFirstBySourceChatIdAndSourceMessageIdOrderByIdDesc(chatId, messageId);
        if (asSource.isPresent()) {
            RelayedMessage m = asSource.get();
            sout("Deleting paired (asSource): source=(" + m.getSourceChatId() + "," + m.getSourceMessageId()
                    + ") target=(" + m.getTargetChatId() + "," + m.getTargetMessageId() + ")");
            tryDelete(m.getTargetChatId(), m.getTargetMessageId(), deleted, errors);
            tryDelete(m.getSourceChatId(), m.getSourceMessageId(), deleted, errors);
            removeMappingIfClean(m, errors);
            return new DeleteOutcome(deleted, errors);
        }

        sout("No mapping found for messageId=" + messageId
                + ", deleting only in chatId=" + chatId);
        tryDelete(chatId, messageId, deleted, errors);
        return new DeleteOutcome(deleted, errors);
    }

    private void removeMappingIfClean(RelayedMessage mapping, List<String> errors) {
        if (!errors.isEmpty()) {
            return; // keep the row so the delete can be retried
        }
        try {
            relayedMessageRepository.delete(mapping);
            sout("Removed mapping id=" + mapping.getId());
        } catch (Exception ex) {
            serr("Failed to remove mapping id=" + mapping.getId() + ": " + ex.getMessage());
        }
    }

    private void tryDelete(long chatId, int messageId, List<String> deleted, List<String> errors) {
        try {
            sout("Calling deleteMessage chatId=" + chatId + " messageId=" + messageId);
            telegramApiClient.deleteMessage(chatId, messageId);
            deleted.add("chat=" + chatId + " msg=" + messageId);
            sout("Deleted messageId=" + messageId + " in chatId=" + chatId);
        } catch (Exception ex) {
            String reason = ex.getMessage() == null ? "" : ex.getMessage();

            // The other side may have been deleted natively by the user.
            // Telegram does not emit an event for that, but the delete call
            // reports "not found". Treat as success.
            if (reason.contains("message to delete not found")) {
                sout("Message " + messageId + " in chat " + chatId
                        + " was already deleted natively, treating as success.");
                deleted.add("chat=" + chatId + " msg=" + messageId + " (already gone)");
                return;
            }

            serr("Deleting message " + messageId + " in chat " + chatId + " failed: " + reason);
            errors.add("chat=" + chatId + " msg=" + messageId + " - " + reason);
        }
    }

    private void notifyDeleteOutcome(long groupId, int topicId, int repliedTopicMsgId, DeleteOutcome outcome) {
        if (outcome.errors().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ Some messages could not be deleted (msgId=")
                .append(repliedTopicMsgId).append("):\n");
        for (String err : outcome.errors()) {
            sb.append("• ").append(escapeHtml(err)).append("\n");
        }
        sb.append("\nThis is usually one of:\n")
                .append("• the bot is not a group admin, or lacks the <b>Delete Messages</b> right\n")
                .append("• the message is older than 48 hours (Telegram limit)\n\n")
                .append("Tip: tap <b>🗑 Delete</b> under a customer message, or reply to any message ")
                .append("here with <code>/del</code>, to remove it on <b>both</b> sides.");
        try {
            telegramApiClient.sendMessage(groupId, sb.toString(), topicId, "HTML", null);
        } catch (Exception ex) {
            serr("Failed to notify admin of delete errors: " + ex.getMessage());
        }
    }

    private void safeDeleteMessage(long chatId, int messageId) {
        try {
            telegramApiClient.deleteMessage(chatId, messageId);
        } catch (Exception ex) {
            serr("Best-effort delete of message " + messageId + " in chat " + chatId
                    + " failed: " + ex.getMessage());
        }
    }

    private void sendDepartmentMenu(long chatId) {
        Map<String, Object> realEstateBtn = Map.of(
                "text", "🏢 Real Estate Inquiry",
                "callback_data", "cat_realestate"
        );
        Map<String, Object> otherSalesBtn = Map.of(
                "text", "🛍️ Other Sales Inquiry",
                "callback_data", "cat_sales"
        );
        Map<String, Object> inlineKeyboard = Map.of(
                "inline_keyboard", List.of(
                        List.of(realEstateBtn),
                        List.of(otherSalesBtn)
                )
        );

        telegramApiClient.sendMessage(
                chatId,
                "Welcome! Please select the department you would like to reach:",
                null,
                null,
                inlineKeyboard
        );
    }

    private void openOrReuseTicket(long customerUserId, JsonNode from, String department, String listingRef) {
        Optional<CustomerTicket> existing = ticketRepository
                .findFirstByTelegramUserIdAndStatusOrderByIdDesc(customerUserId, "OPEN");

        if (existing.isPresent()) {
            CustomerTicket ticket = existing.get();
            if (listingRef != null && !listingRef.isBlank()) {
                ticket.setListingRef(listingRef);
                ticket.setUpdatedAt(Instant.now());
                ticketRepository.save(ticket);
            }
            telegramApiClient.sendMessage(
                    customerUserId,
                    "✅ You are connected to <b>" + escapeHtml(ticket.getBranchCode())
                            + " (" + escapeHtml(ticket.getDepartment()) + ")</b>.\n\n"
                            + "Type your message or send photos below. Only our sales team can see your messages."
                            + customerDeleteHint(),
                    null,
                    "HTML",
                    null
            );
            return;
        }

        Branch branch = branchRepository.findByBranchCodeAndActiveTrue("branch_01")
                .orElseGet(() -> branchRepository.findAllByActiveTrueOrderByBranchCodeAsc()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No active branch configured in database")));

        String firstName = from.path("first_name").asText("Customer");
        String username = from.path("username").asText("");
        String label = (listingRef != null && !listingRef.isBlank()) ? listingRef : department;
        String topicTitle = (firstName + " (" + label + ")").trim();
        if (topicTitle.length() > 128) {
            topicTitle = topicTitle.substring(0, 128);
        }

        JsonNode createdTopic = telegramApiClient.createForumTopic(branch.getTelegramGroupId(), topicTitle);
        int topicId = createdTopic.path("message_thread_id").asInt();

        CustomerTicket ticket = new CustomerTicket();
        ticket.setTelegramUserId(customerUserId);
        ticket.setCustomerName(firstName);
        ticket.setCustomerUsername(username.isBlank() ? null : username);
        ticket.setBranchCode(branch.getBranchCode());
        ticket.setTelegramGroupId(branch.getTelegramGroupId());
        ticket.setTopicId(topicId);
        ticket.setDepartment(department);
        ticket.setListingRef(listingRef);
        ticket.setStatus("OPEN");
        ticketRepository.save(ticket);
        sout("Created ticket id=" + ticket.getId() + " topicId=" + topicId + " for customerUserId=" + customerUserId);

        String safeName = escapeHtml(firstName);
        String safeUsername = username.isBlank() ? "no_username" : "@" + escapeHtml(username);
        String safeDept = escapeHtml(department);
        String listingLine = (listingRef != null && !listingRef.isBlank())
                ? "• <b>Listing / Ref:</b> <code>" + escapeHtml(listingRef) + "</code>\n"
                : "";

        String leadSummary = "🆕 <b>New Lead Assigned to " + escapeHtml(branch.getBranchName()) + "</b>\n"
                + "• <b>Customer:</b> " + safeName + " (" + safeUsername + ")\n"
                + "• <b>Department:</b> " + safeDept + "\n"
                + listingLine
                + "• <b>User ID:</b> <code>" + customerUserId + "</code>\n\n"
                + "<i>Reply to a customer message below to answer them. "
                + "The reply will appear threaded under that message.</i>\n"
                + "<i>To delete any message (yours or the customer's) on both sides, react to it with</i> "
                + DELETE_REACTION_EMOJI + " <i>. You can also tap</i> <b>🗑 Delete</b> <i>under customer messages, "
                + "or reply to a message with</i> <code>/del</code>.\n"
                + "<i>Telegram's native Delete button only removes the message on your side.</i>";

        telegramApiClient.sendMessage(
                branch.getTelegramGroupId(),
                leadSummary,
                topicId,
                "HTML",
                null
        );

        telegramApiClient.sendMessage(
                customerUserId,
                "✅ You are connected to <b>" + escapeHtml(branch.getBranchName())
                        + " (" + safeDept + ")</b>.\n\n"
                        + "Type your message or send photos below. Only our sales team can see your messages."
                        + customerDeleteHint(),
                null,
                "HTML",
                null
        );
    }

    private void saveMapping(
            Long ticketId,
            Long sourceChatId,
            Integer sourceMessageId,
            Long targetChatId,
            Integer targetMessageId
    ) {
        if (targetMessageId == null || targetMessageId == 0) {
            serr("Skipping mapping save because targetMessageId=" + targetMessageId);
            return;
        }
        RelayedMessage mapping = new RelayedMessage();
        mapping.setTicketId(ticketId);
        mapping.setSourceChatId(sourceChatId);
        mapping.setSourceMessageId(sourceMessageId);
        mapping.setTargetChatId(targetChatId);
        mapping.setTargetMessageId(targetMessageId);
        relayedMessageRepository.save(mapping);
        sout("Saved mapping ticketId=" + ticketId + " source=(" + sourceChatId + "," + sourceMessageId
                + ") -> target=(" + targetChatId + "," + targetMessageId + ")");
    }

    private static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}