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

    @Data
    public static class Monitoring {
        private int defaultIntervalMinutes = 5;
        private boolean demoProviderEnabled = false;
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
        private String loginUrl = "https://www.copart.com/login/";
        private String dashboardUrl = "https://www.copart.com/dashboard/";
        /** Per-navigation timeout for the headless browser. */
        private int navigationTimeoutSeconds = 45;
        /** Slow down Playwright actions by N ms (0 = off); useful when debugging. */
        private int slowMoMillis = 0;
    }
}
