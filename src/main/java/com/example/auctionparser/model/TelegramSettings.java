package com.example.auctionparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Telegram Bot API credentials. {@code chatId} is the default destination;
 * individual filters may override it via {@link SearchFilter#getTelegramChatId()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramSettings {

    private String botToken;
    private String chatId;

    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank()
                && chatId != null && !chatId.isBlank();
    }
}
