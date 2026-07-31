package com.example.auctionparser.provider.copart;

import com.example.auctionparser.config.AppProperties;
import com.example.auctionparser.model.CopartCredentials;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Owns the Playwright/Chromium lifecycle for Copart and performs the
 * account login. A real browser is required because Copart fronts everything
 * with an Imperva/Incapsula JavaScript challenge that a plain HTTP client
 * cannot pass; Chromium executes the challenge transparently.
 *
 * <p><b>Thread affinity:</b> Playwright objects must be used from the single
 * thread that created them. All browser work is therefore funnelled through a
 * dedicated single-thread executor, regardless of which caller thread invokes
 * this manager.
 *
 * <p><b>Session reuse:</b> after a successful login the browser storage state
 * (cookies + local storage, including the Incapsula and auth cookies) is written
 * to {@code <dataDir>/copart-storage.json} and reloaded on the next startup, so
 * we avoid logging in every cycle.
 *
 * <p>Selectors are best-effort against the current Copart login page and are
 * centralised as constants; run with {@code app.copart.headless=false} to watch
 * the flow and adjust them if Copart changes the markup. Login failures capture
 * a screenshot to {@code <dataDir>/copart-login-failure.png}.
 */
@Component
public class CopartBrowserManager {

    private static final Logger log = LoggerFactory.getLogger(CopartBrowserManager.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    // Login form selectors, verified against the live Copart /login/ page
    // (2026-07-07). It is now a full page (not a homepage modal); the visible
    // fields carry stable ids. NOTE: old hidden `data-uname='loginPublic...'`
    // duplicates still exist in the DOM, so we must NOT match those.
    private static final String USERNAME_SELECTOR = "#email-member-number";
    private static final String PASSWORD_SELECTOR = "#member-password";
    private static final String SUBMIT_SELECTOR = "button.sign-in-button";

    // Two consent overlays cover the Sign in button and must be dismissed first:
    // a TCF/"funding choices" dialog and the OneTrust cookie banner.
    private static final String CONSENT_ACCEPT_SELECTOR = "button.fc-cta-consent";
    private static final String COOKIE_ACCEPT_SELECTOR = "#onetrust-accept-btn-handler";

    private final AppProperties.Copart config;
    private final Path storageStatePath;
    private final Path failureScreenshotPath;

    private final ExecutorService browserThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "copart-browser");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private volatile boolean initialized;
    private volatile boolean loggedIn;
    private volatile boolean searchPrimed;

    private final tools.jackson.databind.ObjectMapper mapper;

    private final AppProperties.Proxy proxyConfig;

    public CopartBrowserManager(AppProperties properties, tools.jackson.databind.ObjectMapper mapper) {
        this.config = properties.getCopart();
        this.proxyConfig = properties.getProxy();
        this.mapper = mapper;
        Path dataDir = Paths.get(properties.getDataDir());
        this.storageStatePath = dataDir.resolve("copart-storage.json");
        this.failureScreenshotPath = dataDir.resolve("copart-login-failure.png");
    }

    /**
     * Ensures there is a valid, logged-in session, logging in with the given
     * credentials only if the current session is not already authenticated.
     *
     * @return true if the session is authenticated afterwards.
     */
    public boolean ensureLoggedIn(CopartCredentials credentials) {
        if (!credentials.isConfigured()) {
            throw new CopartLoginException("Copart credentials are not configured");
        }
        return onBrowserThread(() -> {
            init();
            // Probe first when we might already be authenticated — either from an
            // earlier login this run, or from a restored storage-state file. A
            // valid session makes Copart redirect away from /login/, so forcing a
            // login there would hang waiting for a form that never renders.
            if ((loggedIn || Files.exists(storageStatePath)) && probeLoggedIn()) {
                loggedIn = true;
                return true;
            }
            loggedIn = performLogin(credentials);
            return loggedIn;
        });
    }

    /** Runs an action against the (initialised) authenticated browser context. */
    public <T> T withContext(Function<BrowserContext, T> action) {
        return onBrowserThread(() -> {
            init();
            return action.apply(context);
        });
    }

    private static final String SEARCH_RESULTS_URL =
            "https://www.copart.com/public/lots/search-results";

    /**
     * Fetches one page of Copart's internal {@code search-results} JSON via the
     * authenticated browser context (which carries the Incapsula + auth cookies,
     * so VINs come back unmasked). Server-side filters and paging are expressed in
     * the POST body, mirroring what Copart's own Angular app sends.
     *
     * @param query        free-form query, e.g. "BMW X3" or just the make
     * @param modelClauses server-side model-group facet clauses like
     *                     {@code lot_model_group:"3 SERIES"}; empty for none
     * @param yearClauses  server-side year facet clauses like {@code lot_year:"2021"};
     *                     empty for no year constraint
     * @param page         zero-based page index
     * @param size         page size (Copart honours up to ~100)
     * @return the raw JSON body, or {@code null} on a non-200 response
     */
    public String searchResultsJson(String query, List<String> modelClauses,
                                    List<String> yearClauses, int page, int size) {
        return onBrowserThread(() -> {
            init();
            primeSearch();

            java.util.Map<String, Object> filter = new java.util.LinkedHashMap<>();
            if (modelClauses != null && !modelClauses.isEmpty()) {
                filter.put("MODG", modelClauses);
            }
            if (yearClauses != null && !yearClauses.isEmpty()) {
                filter.put("YEAR", yearClauses);
            }
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("query", List.of(query));
            body.put("filter", filter);
            body.put("sort", List.of("relevancy desc", "auction_date_type desc", "auction_date_utc asc"));
            body.put("page", page);
            body.put("size", size);
            body.put("start", page * size);
            body.put("freeFormSearch", true);
            body.put("hideImages", false);
            String json = mapper.writeValueAsString(body);

            com.microsoft.playwright.APIResponse resp = context.request().post(
                    SEARCH_RESULTS_URL,
                    com.microsoft.playwright.options.RequestOptions.create()
                            .setHeader("content-type", "application/json")
                            .setHeader("x-requested-with", "XMLHttpRequest")
                            .setHeader("accept", "application/json, text/plain, */*")
                            .setData(json));
            if (resp.status() != 200) {
                log.warn("Copart search-results POST returned HTTP {} for '{}' page {}",
                        resp.status(), query, page);
                return null;
            }
            return resp.text();
        });
    }

    /**
     * Fetches the full image gallery for a lot via Copart's authenticated
     * {@code lotImages} JSON endpoint, returning up to {@code max} full-resolution
     * image URLs (empty on any failure).
     */
    public List<String> fetchLotImages(String lotId, int max) {
        return onBrowserThread(() -> {
            init();
            com.microsoft.playwright.APIResponse resp = context.request().get(
                    "https://www.copart.com/public/data/lotdetails/solr/lotImages/" + lotId,
                    com.microsoft.playwright.options.RequestOptions.create()
                            .setHeader("x-requested-with", "XMLHttpRequest")
                            .setHeader("accept", "application/json, text/plain, */*"));
            if (resp.status() != 200) {
                log.debug("Copart lotImages HTTP {} for lot {}", resp.status(), lotId);
                return List.<String>of();
            }
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (tools.jackson.databind.JsonNode img : mapper.readTree(resp.text())
                    .path("data").path("imagesList").path("content")) {
                String url = img.path("fullUrl").asText("");
                if (url.isBlank()) {
                    url = img.path("highResUrl").asText("");
                }
                if (!url.isBlank()) {
                    urls.add(url);
                    if (urls.size() >= max) {
                        break;
                    }
                }
            }
            return urls;
        });
    }

    /**
     * Loads a Copart page once per session so Imperva has issued the cookies the
     * subsequent API POSTs need. The dashboard probe during login usually covers
     * this, but a home-page visit here makes the search path self-sufficient.
     *
     * <p>Waits for {@code NETWORKIDLE}, not just {@code DOMCONTENTLOADED} — the
     * Incapsula JS challenge runs and sets its clearance cookie asynchronously
     * after the DOM is ready, so closing the page any earlier races it: the very
     * first search POST would fire before the cookie exists and come back as an
     * unparseable challenge page. Same reasoning as the login flow below.
     */
    private void primeSearch() {
        if (searchPrimed) {
            return;
        }
        Page page = context.newPage();
        try {
            page.navigate("https://www.copart.com/");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            searchPrimed = true;
        } catch (RuntimeException e) {
            log.debug("Copart search prime navigation failed: {}", e.toString());
        } finally {
            page.close();
        }
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // --- browser-thread internals (only called on the copart-browser thread) ---

    private void init() {
        if (initialized) {
            return;
        }
        log.info("Launching Chromium for Copart (headless={}, channel={})",
                config.isHeadless(), config.getChannel());
        playwright = Playwright.create();
        browser = launchBrowser();
        context = newContext();
        applyStealth(context);
        context.setDefaultNavigationTimeout(config.getNavigationTimeoutSeconds() * 1000.0);
        initialized = true;
    }

    /**
     * Launches the browser with anti-automation tweaks so Imperva/Incapsula is
     * less likely to serve an "Access denied" (Error 15) page. Prefers the real
     * installed Google Chrome; if that channel is unavailable, falls back to the
     * bundled Chromium so the app still starts.
     */
    private Browser launchBrowser() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(config.isHeadless())
                .setSlowMo(config.getSlowMoMillis())
                // Removes the navigator.webdriver=true signal and the automation banner.
                .setArgs(java.util.List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-features=IsolateOrigins,site-per-process"))
                .setIgnoreDefaultArgs(java.util.List.of("--enable-automation"));
        if (proxyConfig != null && proxyConfig.isUsable()) {
            com.microsoft.playwright.options.Proxy proxy =
                    new com.microsoft.playwright.options.Proxy(proxyConfig.serverUrl());
            if (proxyConfig.getUsername() != null && !proxyConfig.getUsername().isBlank()) {
                proxy.setUsername(proxyConfig.getUsername()).setPassword(proxyConfig.getPassword());
            }
            options.setProxy(proxy);
            log.info("Copart browser routing through proxy {}", proxyConfig.serverUrl());
        }
        String channel = config.getChannel();
        if (channel != null && !channel.isBlank()) {
            try {
                options.setChannel((String) channel);
                return playwright.chromium().launch(options);
            } catch (RuntimeException e) {
                log.warn("Browser channel '{}' unavailable ({}); falling back to bundled Chromium",
                        channel, e.getMessage());
                options.setChannel((String) null);
            }
        }
        return playwright.chromium().launch(options);
    }

    /** Masks the remaining headless/automation fingerprints on every new page. */
    private void applyStealth(BrowserContext ctx) {
        ctx.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
                        + "window.chrome = window.chrome || { runtime: {} };"
                        + "Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});"
                        + "Object.defineProperty(navigator, 'languages', {get: () => ['en-US','en']});");
    }

    private BrowserContext newContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setLocale("en-US");
        // Only spoof the UA for bundled Chromium; the real Chrome channel already
        // sends a genuine, self-consistent UA and overriding it just creates a
        // version mismatch that Imperva can flag.
        String channel = config.getChannel();
        if (channel == null || channel.isBlank()) {
            options.setUserAgent(USER_AGENT);
        }
        if (Files.exists(storageStatePath)) {
            log.info("Reusing saved Copart session from {}", storageStatePath);
            options.setStorageStatePath(storageStatePath);
        }
        return browser.newContext(options);
    }

    private boolean performLogin(CopartCredentials credentials) {
        Page page = context.newPage();
        try {
            log.info("Navigating to Copart login page");
            page.navigate(config.getLoginUrl());
            // Chromium transparently solves the Incapsula JS challenge here. We used to
            // block on NETWORKIDLE for this, but Copart's login page keeps background
            // XHRs (Incapsula heartbeat, analytics, chat widget) running indefinitely,
            // so network never truly goes idle and the wait would time out even though
            // the page had rendered fine. The waitForSelector below is the real gate.
            // Both consent overlays sit on top of the Sign in button.
            dismissOverlay(page, CONSENT_ACCEPT_SELECTOR, "consent dialog");
            dismissOverlay(page, COOKIE_ACCEPT_SELECTOR, "cookie banner");

            // Wait for the (visible) username field on the full login page.
            page.waitForSelector(USERNAME_SELECTOR, new Page.WaitForSelectorOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                    .setTimeout(navTimeoutMs()));

            page.locator(USERNAME_SELECTOR).first().fill(credentials.getUsername());
            page.locator(PASSWORD_SELECTOR).first().fill(credentials.getPassword());
            log.info("Submitting Copart login form");
            page.locator(SUBMIT_SELECTOR).first().click();

            waitForPostLogin(page);

            boolean success = probeLoggedIn();
            if (success) {
                saveStorageState();
                log.info("Copart login successful; session saved");
            } else {
                captureFailure(page);
                log.warn("Copart login did not reach an authenticated state; "
                        + "screenshot at {}", failureScreenshotPath);
            }
            return success;
        } catch (TimeoutError e) {
            captureFailure(page);
            throw new CopartLoginException(
                    "Timed out during Copart login (challenge/captcha or changed selectors?)", e);
        } finally {
            page.close();
        }
    }

    /** Best-effort click on a consent/cookie overlay if it is present. */
    private void dismissOverlay(Page page, String selector, String description) {
        Locator button = page.locator(selector);
        if (button.count() > 0) {
            try {
                button.first().click(new Locator.ClickOptions().setTimeout(5000));
                log.debug("Dismissed Copart {}", description);
                page.waitForTimeout(500);
            } catch (RuntimeException e) {
                log.debug("Copart {} present but not clickable: {}", description, e.toString());
            }
        }
    }

    private void waitForPostLogin(Page page) {
        try {
            page.waitForURL(url -> !url.contains("/login"),
                    new Page.WaitForURLOptions().setTimeout(navTimeoutMs()));
        } catch (TimeoutError e) {
            // URL may not change on some flows; fall through to an explicit probe.
            log.debug("URL did not leave /login within timeout; will probe dashboard");
        }
    }

    /** Confirms authentication by loading the dashboard and checking we stay there. */
    private boolean probeLoggedIn() {
        Page page = context.newPage();
        try {
            page.navigate(config.getDashboardUrl());
            page.waitForLoadState();
            boolean authed = !page.url().contains("/login");
            log.debug("Copart login probe: url={} authed={}", page.url(), authed);
            return authed;
        } catch (RuntimeException e) {
            log.debug("Copart login probe failed: {}", e.toString());
            return false;
        } finally {
            page.close();
        }
    }

    private void saveStorageState() {
        context.storageState(new BrowserContext.StorageStateOptions().setPath(storageStatePath));
    }

    private void captureFailure(Page page) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(failureScreenshotPath).setFullPage(true));
        } catch (RuntimeException e) {
            log.debug("Could not capture login failure screenshot: {}", e.toString());
        }
    }

    private double navTimeoutMs() {
        return config.getNavigationTimeoutSeconds() * 1000.0;
    }

    // --- executor plumbing ---

    private <T> T onBrowserThread(Callable<T> task) {
        try {
            return browserThread.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CopartLoginException("Interrupted while running browser task", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CopartLoginException cle) {
                throw cle;
            }
            throw new CopartLoginException("Copart browser task failed: " + cause.getMessage(), cause);
        }
    }

    @PreDestroy
    public void shutdown() {
        browserThread.submit(() -> {
            try {
                if (context != null) context.close();
                if (browser != null) browser.close();
                if (playwright != null) playwright.close();
            } catch (RuntimeException e) {
                log.debug("Error closing Playwright: {}", e.toString());
            }
        });
        browserThread.shutdown();
        try {
            if (!browserThread.awaitTermination(10, TimeUnit.SECONDS)) {
                browserThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            browserThread.shutdownNow();
        }
    }
}
