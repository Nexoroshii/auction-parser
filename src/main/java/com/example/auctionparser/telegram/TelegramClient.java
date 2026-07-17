package com.example.auctionparser.telegram;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.auctionparser.util.RetryableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Low-level Telegram Bot API client. Each method takes the bot token so the
 * client stays stateless; higher-level formatting/settings live in
 * {@link TelegramNotifier}.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    private static final String API_BASE = "https://api.telegram.org/bot";

    private final RetryableHttpClient http;
    private final ObjectMapper mapper;

    public TelegramClient(RetryableHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** Verifies the token via getMe. */
    public ApiResult getMe(String botToken) throws IOException {
        HttpRequest request = http.requestBuilder(url(botToken, "getMe"))
                .GET()
                .build();
        return execute(request);
    }

    public ApiResult sendMessage(String botToken, String chatId, String text) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        return post(botToken, "sendMessage", body);
    }

    /**
     * Sends a single photo by URL, optionally with a caption, so a lot's text and
     * its photo arrive as one message. Caption is capped at Telegram's 1024 chars.
     */
    public ApiResult sendPhoto(String botToken, String chatId, String photoUrl, String caption)
            throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("photo", photoUrl);
        if (caption != null && !caption.isBlank()) {
            body.put("caption", caption);
        }
        return post(botToken, "sendPhoto", body);
    }

    /**
     * Sends 2–10 photos as an album (Telegram's per-group limit). Callers should
     * chunk larger sets. When {@code caption} is set it is attached to the first
     * photo, so the album renders as a single captioned message.
     */
    public ApiResult sendPhotoGroup(String botToken, String chatId, List<String> photoUrls,
                                    String caption) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        ArrayNode media = body.putArray("media");
        boolean first = true;
        for (String url : photoUrls) {
            ObjectNode item = media.addObject();
            item.put("type", "photo");
            item.put("media", url);
            if (first && caption != null && !caption.isBlank()) {
                item.put("caption", caption);
            }
            first = false;
        }
        return post(botToken, "sendMediaGroup", body);
    }

    public ApiResult sendVideo(String botToken, String chatId, String videoUrl) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("video", videoUrl);
        return post(botToken, "sendVideo", body);
    }

    private ApiResult post(String botToken, String method, ObjectNode body) throws IOException {
        HttpRequest request = http.requestBuilder(url(botToken, method))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return execute(request);
    }

    private ApiResult execute(HttpRequest request) throws IOException {
        HttpResponse<String> response = http.sendForString(request);
        try {
            JsonNode json = mapper.readTree(response.body());
            boolean ok = json.path("ok").asBoolean(false);
            String description = json.path("description").asText(null);
            if (!ok) {
                log.warn("Telegram API error ({}): {}", response.statusCode(), description);
            }
            return new ApiResult(ok, description, response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to parse Telegram response (HTTP {})", response.statusCode(), e);
            return new ApiResult(false, "Invalid response: " + response.statusCode(), response.statusCode());
        }
    }

    private String url(String botToken, String method) {
        return API_BASE + botToken + "/" + method;
    }

    /** Outcome of an API call. */
    public record ApiResult(boolean ok, String description, int statusCode) {
    }
}
