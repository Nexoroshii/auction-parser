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
     * Sends a lot as its own message followed by photos and optional video.
     *
     * @param chatIdOverride optional per-filter chat id; null uses the default.
     * @return true if the primary text message was delivered.
     */
    public boolean sendLot(Lot lot, String chatIdOverride) throws IOException {
        TelegramSettings tg = settingsService.getTelegramSettings();
        if (!tg.isConfigured()) {
            throw new IOException("Telegram is not configured (token/chat id missing)");
        }
        AppSettings app = settingsService.getAppSettings();
        String chatId = (chatIdOverride != null && !chatIdOverride.isBlank())
                ? chatIdOverride : tg.getChatId();

        TelegramClient.ApiResult text = client.sendMessage(tg.getBotToken(), chatId, formatter.format(lot));

        if (app.isSendPhotos() && lot.getPhotoUrls() != null && !lot.getPhotoUrls().isEmpty()) {
            for (List<String> chunk : chunk(lot.getPhotoUrls(), MAX_PHOTOS_PER_GROUP)) {
                client.sendPhotoGroup(tg.getBotToken(), chatId, chunk);
            }
        }
        if (app.isSendVideo() && lot.getVideoUrl() != null && !lot.getVideoUrl().isBlank()) {
            client.sendVideo(tg.getBotToken(), chatId, lot.getVideoUrl());
        }
        return text.ok();
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        java.util.List<List<T>> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
