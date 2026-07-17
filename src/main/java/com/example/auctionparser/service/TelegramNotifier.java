package com.example.auctionparser.service;

import com.example.auctionparser.model.AppSettings;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.TelegramSettings;
import com.example.auctionparser.telegram.MessageFormatter;
import com.example.auctionparser.telegram.TelegramClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * High-level Telegram delivery: pulls credentials and media preferences from
 * settings, formats the lot and sends text + photos (+ optional video).
 */
@Service
public class TelegramNotifier {

    private static final int MAX_PHOTOS_PER_GROUP = 10;
    /** Telegram's caption length limit for photos/albums. */
    private static final int MAX_CAPTION_LENGTH = 1024;

    private final TelegramClient client;
    private final MessageFormatter formatter;
    private final SettingsService settingsService;

    public TelegramNotifier(TelegramClient client, MessageFormatter formatter,
                            SettingsService settingsService) {
        this.client = client;
        this.formatter = formatter;
        this.settingsService = settingsService;
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
        String chatId = (chatIdOverride != null && !chatIdOverride.isBlank())
                ? chatIdOverride : tg.getChatId();
        String text = formatter.format(lot);

        boolean hasPhotos = app.isSendPhotos()
                && lot.getPhotoUrls() != null && !lot.getPhotoUrls().isEmpty();
        boolean captionFits = text.length() <= MAX_CAPTION_LENGTH;

        boolean delivered;
        if (!hasPhotos) {
            delivered = client.sendMessage(token, chatId, text).ok();
        } else if (!captionFits) {
            // Too long to ride along as a caption: text first, then plain photos.
            delivered = client.sendMessage(token, chatId, text).ok();
            for (List<String> chunk : chunk(lot.getPhotoUrls(), MAX_PHOTOS_PER_GROUP)) {
                sendPhotoChunk(token, chatId, chunk, null);
            }
        } else {
            // Text + photos as one message: caption rides on the first chunk.
            List<List<String>> chunks = chunk(lot.getPhotoUrls(), MAX_PHOTOS_PER_GROUP);
            delivered = sendPhotoChunk(token, chatId, chunks.get(0), text).ok();
            for (int i = 1; i < chunks.size(); i++) {
                sendPhotoChunk(token, chatId, chunks.get(i), null);
            }
        }

        if (app.isSendVideo() && lot.getVideoUrl() != null && !lot.getVideoUrl().isBlank()) {
            client.sendVideo(token, chatId, lot.getVideoUrl());
        }
        return delivered;
    }

    /** Sends one photo chunk, using sendPhoto for a single image (a 1-item album is rejected). */
    private TelegramClient.ApiResult sendPhotoChunk(String token, String chatId,
                                                    List<String> chunk, String caption) throws IOException {
        if (chunk.size() == 1) {
            return client.sendPhoto(token, chatId, chunk.get(0), caption);
        }
        return client.sendPhotoGroup(token, chatId, chunk, caption);
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        java.util.List<List<T>> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
