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

    // Login form selectors, verified against the live Copart page. The login is
    // a modal on the homepage; its fields carry only data-uname attributes.
    private static final String USERNAME_SELECTOR = "input[data-uname='loginPublicloginmodalusername']";
    private static final String PASSWORD_SELECTOR = "input[data-uname='loginPublicloginmodalpassword']";
    private static final String SUBMIT_SELECTOR = "button[data-uname='loginSigninmemberbutton']";
    private static final String COOKIE_ACCEPT_SELECTOR = "#onetrust-accept-btn-handler";

    // The desktop "Sign In" trigger is a hidden responsive duplicate, so instead
    // of clicking it we reveal the already-rendered modal directly: walk up from
    // the username field, un-hide any display:none ancestor and mark the Bootstrap
    // modal shown. Verified to make the fields visible and fillable.
    private static final String OPEN_MODAL_JS = """
            () => {
              let e = document.querySelector("[data-uname='loginPublicloginmodalusername']");
              if (!e) return false;
              while (e && e.tagName !== 'BODY') {
                if (getComputedStyle(e).display === 'none') {
                  e.style.setProperty('display', 'block', 'important');
                }
                if (e.classList && (e.classList.contains('modal') || e.classList.contains('fade'))) {
                  e.classList.add('show', 'in');
                  e.style.setProperty('display', 'block', 'important');
                  e.removeAttribute('aria-hidden');
                }
                e = e.parentElement;
              }
              return true;
            }
            """;

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

    public CopartBrowserManager(AppProperties properties) {
        this.config = properties.getCopart();
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
            if (loggedIn && probeLoggedIn()) {
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

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // --- browser-thread internals (only called on the copart-browser thread) ---

    private void init() {
        if (initialized) {
            return;
        }
        log.info("Launching Chromium for Copart (headless={})", config.isHeadless());
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(config.isHeadless())
                .setSlowMo(config.getSlowMoMillis()));
        context = newContext();
        context.setDefaultNavigationTimeout(config.getNavigationTimeoutSeconds() * 1000.0);
        initialized = true;
    }

    private BrowserContext newContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setUserAgent(USER_AGENT)
                .setViewportSize(1366, 768)
                .setLocale("en-US");
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
            // Chromium transparently solves the Incapsula JS challenge here.
            page.waitForLoadState(LoadState.NETWORKIDLE);
            dismissCookieBanner(page);

            // Reveal the login modal, then wait for the field to be visible.
            page.evaluate(OPEN_MODAL_JS);
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

    private void dismissCookieBanner(Page page) {
        Locator cookie = page.locator(COOKIE_ACCEPT_SELECTOR);
        if (cookie.count() > 0) {
            try {
                cookie.first().click(new Locator.ClickOptions().setTimeout(5000));
                log.debug("Dismissed Copart cookie consent banner");
            } catch (RuntimeException e) {
                log.debug("Cookie banner present but not clickable: {}", e.toString());
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
