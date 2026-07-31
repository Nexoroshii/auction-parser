package com.example.auctionparser.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Static, file-backed configuration (bootstrap defaults, timeouts, paths).
 * Runtime-editable values live in the SQLite settings table instead.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Directory for the SQLite database and any local files. */
    private String dataDir;

    private final Monitoring monitoring = new Monitoring();
    private final Http http = new Http();
    private final Copart copart = new Copart();
    private final Proxy proxy = new Proxy();

    /**
     * Optional outbound proxy so the auction sites see a US IP without a
     * system-wide VPN. Applied to both the Copart browser and the HTTP client
     * (IAAI/Telegram). An HTTP/HTTPS proxy works everywhere; SOCKS is only honored
     * by the Copart browser.
     */
    @Data
    public static class Proxy {
        private boolean enabled = false;
        /** "http" (recommended, works for all traffic) or "socks5" (Copart only). */
        private String scheme = "http";
        private String host = "";
        private int port = 0;
        private String username = "";
        private String password = "";

        public boolean isUsable() {
            return enabled && host != null && !host.isBlank() && port > 0;
        }

        public String serverUrl() {
            return scheme + "://" + host + ":" + port;
        }
    }

    @Data
    public static class Monitoring {
        private int defaultIntervalMinutes = 5;
        private boolean demoProviderEnabled = false;
        /** Default auction-date look-ahead: 1 = today only, 2 = today+tomorrow. */
        private int defaultDayRange = 2;
    }

    @Data
    public static class Http {
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 30;
        private int maxRetries = 3;
        private long retryBackoffMillis = 1000;
    }

    @Data
    public static class Copart {
        /** Run Chromium without a visible window. Set false to debug login. */
        private boolean headless = true;
        /**
         * Playwright browser channel. Using a real installed browser instead of
         * Playwright's bundled Chromium gives a far more convincing fingerprint
         * against Imperva/Incapsula bot detection. "msedge" = Microsoft Edge
         * (ships with Windows), "chrome" = Google Chrome. Empty falls back to
         * bundled Chromium.
         */
        private String channel = "msedge";
        private String loginUrl = "https://www.copart.com/login/";
        private String dashboardUrl = "https://www.copart.com/dashboard/";
        /** Per-navigation timeout for the headless browser. */
        private int navigationTimeoutSeconds = 45;
        /** Slow down Playwright actions by N ms (0 = off); useful when debugging. */
        private int slowMoMillis = 0;
    }
}
