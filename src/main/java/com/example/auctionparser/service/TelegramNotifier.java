package com.example.auctionparser.service;

import com.example.auctionparser.model.AppSettings;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.TelegramSettings;
import com.example.auctionparser.repository.TelegramTopicRepository;
import com.example.auctionparser.telegram.MessageFormatter;
import com.example.auctionparser.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * High-level Telegram delivery: pulls credentials and media preferences from
 * settings, formats the lot and sends text + photos (+ optional video).
 */
@Service
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final int MAX_PHOTOS_PER_GROUP = 10;
    /** Telegram's caption length limit for photos/albums. */
    private static final int MAX_CAPTION_LENGTH = 1024;

    private final TelegramClient client;
    private final MessageFormatter formatter;
    private final SettingsService settingsService;
    private final TelegramTopicRepository topicRepository;

    public TelegramNotifier(TelegramClient client, MessageFormatter formatter,
                            SettingsService settingsService, TelegramTopicRepository topicRepository) {
        this.client = client;
        this.formatter = formatter;
        this.settingsService = settingsService;
        this.topicRepository = topicRepository;
    }

    /** Tests the currently saved bot token via getMe. */
    public TelegramClient.ApiResult testConnection() throws IOException {
        return testConnection(settingsService.getTelegramSettings().getBotToken());
    }

    /** Tests an arbitrary (e.g. not-yet-saved) bot token via getMe. */
    public TelegramClient.ApiResult testConnection(String botToken) throws IOException {
        if (botToken == null || botToken.isBlank()) {
            return new TelegramClient.ApiResult(false, "Bot token is empty", 0);
        }
        return client.getMe(botToken);
    }

    /**
     * Sends a lot as a single text+photo message (the lot text becomes the photo/
     * album caption), plus an optional video. Falls back to a separate text
     * message when there are no photos, or when the text exceeds Telegram's
     * caption limit.
     *
     * @param chatIdOverride optional per-filter chat id; null uses the default.
     * @return true if the primary message was delivered.
     */
    public boolean sendLot(Lot lot, String chatIdOverride) throws IOException {
        TelegramSettings tg = settingsService.getTelegramSettings();
        if (!tg.isConfigured()) {
            throw new IOException("Telegram is not configured (token/chat id missing)");
        }
        AppSettings app = settingsService.getAppSettings();
        String token = tg.getBotToken();
        boolean usingDefaultChat = chatIdOverride == null || chatIdOverride.isBlank();
        String chatId = usingDefaultChat ? tg.getChatId() : chatIdOverride;
        String text = formatter.format(lot);
        Integer threadId = app.isTopicsEnabled() ? resolveThreadId(token, chatId, lot.getMake()) : null;

        boolean hasPhotos = app.isSendPhotos()
                && lot.getPhotoUrls() != null && !lot.getPhotoUrls().isEmpty();
        boolean captionFits = text.length() <= MAX_CAPTION_LENGTH;

        boolean delivered;
        if (!hasPhotos) {
            delivered = client.sendMessage(token, chatId, text, threadId).ok();
        } else if (!captionFits) {
            // Too long to ride along as a caption: text first, then plain photos.
            delivered = client.sendMessage(token, chatId, text, threadId).ok();
            for (List<String> chunk : chunk(lot.getPhotoUrls(), MAX_PHOTOS_PER_GROUP)) {
                sendPhotoChunk(token, chatId, chunk, null, threadId);
            }
        } else {
            // Text + photos as one message: caption rides on the first chunk.
            List<List<String>> chunks = chunk(lot.getPhotoUrls(), MAX_PHOTOS_PER_GROUP);
            delivered = sendPhotoChunk(token, chatId, chunks.get(0), text, threadId).ok();
            for (int i = 1; i < chunks.size(); i++) {
                sendPhotoChunk(token, chatId, chunks.get(i), null, threadId);
            }
        }

        if (app.isSendVideo() && lot.getVideoUrl() != null && !lot.getVideoUrl().isBlank()) {
            client.sendVideo(token, chatId, lot.getVideoUrl(), threadId);
        }
        healMigrationIfSeen(chatId, usingDefaultChat, tg);
        return delivered;
    }

    /**
     * Self-heals the common "basic group upgraded to a supergroup" case (e.g.
     * turning on Topics changes the chat's id, so a previously-configured id
     * starts failing every send). Telegram only reports the new id
     * ({@code migrate_to_chat_id}) on write-type calls (send*, createForumTopic),
     * not on a plain lookup — so rather than a dedicated up-front probe, this
     * reacts to whatever {@link TelegramClient} observed during THIS lot's calls.
     * This lot's own delivery may still have failed (it'll retry next cycle), but
     * every subsequent lot — even later in the same cycle — picks up the fix.
     *
     * <p>Per-filter chat overrides are intentionally not auto-corrected (no safe
     * way to know which filter to update); the new id is only logged for those.
     */
    private void healMigrationIfSeen(String chatId, boolean isDefaultChat, TelegramSettings tg) {
        Long migrated = client.consumeMigratedChatId();
        if (migrated == null) {
            return;
        }
        String corrected = String.valueOf(migrated);
        if (isDefaultChat) {
            log.warn("Telegram chat {} was upgraded to a supergroup; switching the default chat_id to {} "
                    + "and saving it", chatId, corrected);
            settingsService.saveTelegramSettings(
                    TelegramSettings.builder().botToken(tg.getBotToken()).chatId(corrected).build());
        } else {
            log.warn("Telegram chat {} was upgraded to a supergroup; new chat_id is {} — this filter's "
                    + "chat override must be updated manually", chatId, corrected);
        }
    }

    /** Sends one photo chunk, using sendPhoto for a single image (a 1-item album is rejected). */
    private TelegramClient.ApiResult sendPhotoChunk(String token, String chatId, List<String> chunk,
                                                    String caption, Integer threadId) throws IOException {
        if (chunk.size() == 1) {
            return client.sendPhoto(token, chatId, chunk.get(0), caption, threadId);
        }
        return client.sendPhotoGroup(token, chatId, chunk, caption, threadId);
    }

    /**
     * Resolves the forum topic a make should post into, creating it on first use
     * via {@code createForumTopic} and caching the id thereafter. Returns null
     * (send to the chat's General topic) when there's no make, the target chat
     * isn't a topics-enabled forum, or topic creation otherwise fails.
     */
    private synchronized Integer resolveThreadId(String token, String chatId, String make) {
        if (make == null || make.isBlank()) {
            return null;
        }
        String key = make.trim().toUpperCase();
        Integer existing = topicRepository.find(chatId, key);
        if (existing != null) {
            return existing;
        }
        try {
            Integer created = client.createForumTopic(token, chatId, make.trim());
            if (created != null) {
                topicRepository.save(chatId, key, created);
                return created;
            }
            log.warn("Could not create Telegram forum topic for make '{}' in chat {} "
                    + "(is the chat a forum with Topics enabled, and the bot an admin with "
                    + "'Manage Topics'?); sending to the General topic instead", make, chatId);
        } catch (IOException e) {
            log.warn("Error creating Telegram forum topic for make '{}': {}", make, e.toString());
        }
        return null;
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        java.util.List<List<T>> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
