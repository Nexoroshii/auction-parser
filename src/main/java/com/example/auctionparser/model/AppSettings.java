package com.example.auctionparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User-adjustable application settings persisted in the {@code settings} table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppSettings {

    /** Monitoring interval in minutes. */
    @Builder.Default
    private int intervalMinutes = 5;

    /** Start the app when Windows starts. */
    @Builder.Default
    private boolean launchOnStartup = false;

    /** Keep running in the tray when the window is closed. */
    @Builder.Default
    private boolean minimizeToTray = true;

    /** Include photos in Telegram notifications. */
    @Builder.Default
    private boolean sendPhotos = true;

    /** Include video (when present) in Telegram notifications. */
    @Builder.Default
    private boolean sendVideo = true;

    /** How many days ahead (starting today) an auction may fall to be reported: 1 = today only, 2 = today+tomorrow. */
    @Builder.Default
    private int auctionDayRange = 2;

    /** Route each lot into a per-make forum topic instead of the chat's General topic. */
    @Builder.Default
    private boolean topicsEnabled = false;
}
