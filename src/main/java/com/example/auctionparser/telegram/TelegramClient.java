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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Low-level Telegram Bot API client. Each method takes the bot token so the
 * client stays stateless; higher-level formatting/settings live in
 * {@link TelegramNotifier}.
 *
 * <p>Two flood-control safeguards sit in front of every call: a minimum
 * spacing between consecutive requests (Telegram flags bursts well under a
 * message/second as abuse), and a hard skip while a prior 429's
 * {@code retry_after} window is still open — hitting the API again before
 * then only risks the cooldown being extended.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    /** Telegram recommends staying under ~1 request/second per chat. */
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(1100);

    private final RetryableHttpClient http;
    private final ObjectMapper mapper;

    private volatile Instant blockedUntil = Instant.EPOCH;
    private volatile Instant lastRequestAt = Instant.EPOCH;
    private final java.util.concurrent.atomic.AtomicReference<Long> lastMigratedChatId =
            new java.util.concurrent.atomic.AtomicReference<>();

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

    public ApiResult sendMessage(String botToken, String chatId, String text, Integer threadId) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        putThreadId(body, threadId);
        return post(botToken, "sendMessage", body);
    }

    /**
     * Sends a single photo by URL, optionally with a caption, so a lot's text and
     * its photo arrive as one message. Caption is capped at Telegram's 1024 chars.
     */
    public ApiResult sendPhoto(String botToken, String chatId, String photoUrl, String caption, Integer threadId)
            throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("photo", photoUrl);
        if (caption != null && !caption.isBlank()) {
            body.put("caption", caption);
        }
        putThreadId(body, threadId);
        return post(botToken, "sendPhoto", body);
    }

    /**
     * Sends 2–10 photos as an album (Telegram's per-group limit). Callers should
     * chunk larger sets. When {@code caption} is set it is attached to the first
     * photo, so the album renders as a single captioned message.
     */
    public ApiResult sendPhotoGroup(String botToken, String chatId, List<String> photoUrls,
                                    String caption, Integer threadId) throws IOException {
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
        putThreadId(body, threadId);
        return post(botToken, "sendMediaGroup", body);
    }

    public ApiResult sendVideo(String botToken, String chatId, String videoUrl, Integer threadId) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("video", videoUrl);
        putThreadId(body, threadId);
        return post(botToken, "sendVideo", body);
    }

    /**
     * Creates a new topic in a forum-enabled supergroup (the group must have
     * "Topics" turned on and the bot must be an admin with "Manage Topics"
     * rights). Returns the new topic's {@code message_thread_id}, or {@code null}
     * if creation failed (missing rights, not a forum, currently rate-limited, …)
     * — callers should fall back to sending without a thread id.
     */
    public Integer createForumTopic(String botToken, String chatId, String name) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("name", name);
        HttpRequest request = http.requestBuilder(url(botToken, "createForumTopic"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        JsonNode json = executeJson(request);
        if (json == null || !json.path("ok").asBoolean(false)) {
            return null;
        }
        int threadId = json.path("result").path("message_thread_id").asInt(0);
        return threadId > 0 ? threadId : null;
    }

    private static void putThreadId(ObjectNode body, Integer threadId) {
        if (threadId != null) {
            body.put("message_thread_id", threadId);
        }
    }

    private ApiResult post(String botToken, String method, ObjectNode body) throws IOException {
        HttpRequest request = http.requestBuilder(url(botToken, method))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return execute(request);
    }

    private ApiResult execute(HttpRequest request) throws IOException {
        Instant blocked = blockedUntil;
        if (Instant.now().isBefore(blocked)) {
            log.debug("Skipping Telegram call; rate-limited until {}", blocked);
            return new ApiResult(false, "Rate limited until " + blocked, 429, null);
        }
        throttle();
        HttpResponse<String> response = http.sendForString(request);
        try {
            JsonNode json = mapper.readTree(response.body());
            boolean ok = json.path("ok").asBoolean(false);
            String description = json.path("description").asText(null);
            Long migrateToChatId = migrateToChatId(json);
            if (!ok) {
                log.warn("Telegram API error ({}): {}{}", response.statusCode(), description,
                        migrateToChatId != null ? " [new chat_id: " + migrateToChatId + "]" : "");
            }
            if (migrateToChatId != null) {
                lastMigratedChatId.set(migrateToChatId);
            }
            if (response.statusCode() == 429) {
                applyRetryAfter(json);
            }
            return new ApiResult(ok, description, response.statusCode(), migrateToChatId);
        } catch (Exception e) {
            log.warn("Failed to parse Telegram response (HTTP {})", response.statusCode(), e);
            return new ApiResult(false, "Invalid response: " + response.statusCode(), response.statusCode(), null);
        }
    }

    /**
     * Returns and clears the most recently observed "chat was upgraded to a
     * supergroup" target id (from ANY call: send, createForumTopic, …), or null
     * if none has been seen since the last call to this method. Callers use this
     * to self-heal a stale chat id after the fact, since the migration hint only
     * comes back on write-type calls (not e.g. getChat) and isn't worth a
     * dedicated up-front probe.
     */
    public Long consumeMigratedChatId() {
        return lastMigratedChatId.getAndSet(null);
    }

    /** The new chat id from a "group was upgraded to a supergroup" error, or null. */
    private static Long migrateToChatId(JsonNode json) {
        JsonNode node = json.path("parameters").path("migrate_to_chat_id");
        return node.isMissingNode() || node.isNull() ? null : node.asLong();
    }

    /** Same request/response handling as {@link #execute}, but returns the raw JSON (or null). */
    private JsonNode executeJson(HttpRequest request) throws IOException {
        Instant blocked = blockedUntil;
        if (Instant.now().isBefore(blocked)) {
            log.debug("Skipping Telegram call; rate-limited until {}", blocked);
            return null;
        }
        throttle();
        HttpResponse<String> response = http.sendForString(request);
        try {
            JsonNode json = mapper.readTree(response.body());
            if (!json.path("ok").asBoolean(false)) {
                Long migrateToChatId = migrateToChatId(json);
                log.warn("Telegram API error ({}): {}{}", response.statusCode(),
                        json.path("description").asText(null),
                        migrateToChatId != null ? " [new chat_id: " + migrateToChatId + "]" : "");
                if (migrateToChatId != null) {
                    lastMigratedChatId.set(migrateToChatId);
                }
            }
            if (response.statusCode() == 429) {
                applyRetryAfter(json);
            }
            return json;
        } catch (Exception e) {
            log.warn("Failed to parse Telegram response (HTTP {})", response.statusCode(), e);
            return null;
        }
    }

    /** Pauses all further sends until the flood-control window Telegram reported has passed. */
    private void applyRetryAfter(JsonNode json) {
        int retryAfterSeconds = json.path("parameters").path("retry_after").asInt(0);
        if (retryAfterSeconds > 0) {
            blockedUntil = Instant.now().plusSeconds(retryAfterSeconds);
            log.warn("Telegram rate limit hit; pausing all sends for {}s (until {})",
                    retryAfterSeconds, blockedUntil);
        }
    }

    /** Sleeps just enough to keep consecutive requests at least {@link #MIN_REQUEST_INTERVAL} apart. */
    private synchronized void throttle() {
        Duration since = Duration.between(lastRequestAt, Instant.now());
        if (since.compareTo(MIN_REQUEST_INTERVAL) < 0) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL.minus(since).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = Instant.now();
    }

    private String url(String botToken, String method) {
        return API_BASE + botToken + "/" + method;
    }

    /** Outcome of an API call. {@code migrateToChatId} is set only on a supergroup-migration error. */
    public record ApiResult(boolean ok, String description, int statusCode, Long migrateToChatId) {
        public ApiResult(boolean ok, String description, int statusCode) {
            this(ok, description, statusCode, null);
        }
    }
}
