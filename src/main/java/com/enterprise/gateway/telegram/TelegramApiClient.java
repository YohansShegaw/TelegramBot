package com.enterprise.gateway.telegram;

import com.enterprise.gateway.config.TelegramProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TelegramApiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TelegramApiClient(TelegramProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String baseUrl = "https://api.telegram.org/bot" + properties.bot().token();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public JsonNode getUpdates(long offset, int timeoutSeconds) {
        String json = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", timeoutSeconds)
                        .queryParam(
                                "allowed_updates",
                                "[\"message\",\"edited_message\",\"message_reaction\",\"callback_query\"]"
                        )
                        .build())
                .retrieve()
                .body(String.class);
        return parseResponse(json);
    }

    public JsonNode setMessageReaction(
            Object chatId,
            int messageId,
            List reaction,
            Boolean isBig
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("reaction", reaction != null ? reaction : List.of());
        if (isBig != null) {
            body.put("is_big", isBig);
        }
        return postJson("/setMessageReaction", body);
    }

    public JsonNode createForumTopic(long chatId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("name", name);
        return postJson("/createForumTopic", body);
    }

    public JsonNode sendMessage(
            Object chatId,
            String text,
            Integer messageThreadId,
            String parseMode,
            Object replyMarkup
    ) {
        return sendMessage(chatId, text, messageThreadId, parseMode, replyMarkup, null);
    }

    public JsonNode sendMessage(
            Object chatId,
            String text,
            Integer messageThreadId,
            String parseMode,
            Object replyMarkup,
            Integer replyToMessageId
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (replyToMessageId != null) {
            body.put("reply_parameters", Map.of("message_id", replyToMessageId));
        }
        if (messageThreadId != null) {
            body.put("message_thread_id", messageThreadId);
        }
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        return postJson("/sendMessage", body);
    }

    public JsonNode sendPhoto(
            Object chatId,
            String photoFileIdOrUrl,
            String caption,
            Integer messageThreadId,
            String parseMode,
            Object replyMarkup
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("photo", photoFileIdOrUrl);
        if (caption != null && !caption.isBlank()) {
            body.put("caption", caption);
        }
        if (messageThreadId != null) {
            body.put("message_thread_id", messageThreadId);
        }
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        return postJson("/sendPhoto", body);
    }

    public JsonNode sendMediaGroup(
            Object chatId,
            List media,
            Integer messageThreadId
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("media", media);
        if (messageThreadId != null) {
            body.put("message_thread_id", messageThreadId);
        }
        return postJson("/sendMediaGroup", body);
    }

    public JsonNode copyMessage(
            Object chatId,
            Object fromChatId,
            int messageId,
            Integer messageThreadId
    ) {
        return copyMessage(chatId, fromChatId, messageId, messageThreadId, null, null);
    }

    public JsonNode copyMessage(
            Object chatId,
            Object fromChatId,
            int messageId,
            Integer messageThreadId,
            Integer replyToMessageId
    ) {
        return copyMessage(chatId, fromChatId, messageId, messageThreadId, replyToMessageId, null);
    }

    /**
     * Copy a message and optionally thread it under an existing message in the
     * target chat via reply_parameters, and optionally attach an inline keyboard.
     * Pass {@code replyToMessageId = null} for a plain (non-threaded) copy and
     * {@code replyMarkup = null} for no buttons.
     *
     * If {@code replyToMessageId} refers to a message the user has since
     * deleted, Telegram renders the copy as a "Reply to deleted message",
     * giving the recipient honest context.
     */
    public JsonNode copyMessage(
            Object chatId,
            Object fromChatId,
            int messageId,
            Integer messageThreadId,
            Integer replyToMessageId,
            Object replyMarkup
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("from_chat_id", fromChatId);
        body.put("message_id", messageId);
        if (messageThreadId != null) {
            body.put("message_thread_id", messageThreadId);
        }
        if (replyToMessageId != null) {
            body.put("reply_parameters", Map.of("message_id", replyToMessageId));
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        return postJson("/copyMessage", body);
    }

    public JsonNode editMessageText(Object chatId, int messageId, String text, String parseMode) {
        return editMessageText(chatId, messageId, text, parseMode, null);
    }

    /**
     * Note: editing a message without reply_markup removes its inline keyboard,
     * so pass the keyboard again whenever the message should keep its buttons.
     */
    public JsonNode editMessageText(
            Object chatId,
            int messageId,
            String text,
            String parseMode,
            Object replyMarkup
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        return postJson("/editMessageText", body);
    }

    public JsonNode editMessageCaption(Object chatId, int messageId, String caption, String parseMode) {
        return editMessageCaption(chatId, messageId, caption, parseMode, null);
    }

    public JsonNode editMessageCaption(
            Object chatId,
            int messageId,
            String caption,
            String parseMode,
            Object replyMarkup
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("caption", caption != null ? caption : "");
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        return postJson("/editMessageCaption", body);
    }

    public JsonNode deleteMessage(Object chatId, int messageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        return postJson("/deleteMessage", body);
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {
            body.put("text", text);
        }
        postJson("/answerCallbackQuery", body);
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        String json = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseResponse(json);
    }

    private JsonNode parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.path("ok").asBoolean(false)) {
                throw new IllegalStateException("Telegram API error: " + json);
            }
            return root.path("result");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Telegram response: " + json, e);
        }
    }
}