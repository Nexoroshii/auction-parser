package com.example.auctionparser.provider.copart;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.CopartCredentials;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.AuctionProvider;
import com.example.auctionparser.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Copart provider backed by a headless Chromium session (Playwright).
 *
 * <p>Current scope: <b>authentication</b>. Each search first guarantees a
 * logged-in browser session (passing the Incapsula challenge and signing in with
 * the user's account, reusing a saved session when possible). Result extraction
 * is the next step and will run Copart's internal JSON API through the
 * authenticated browser context — {@code context.request().post(
 * "https://www.copart.com/public/lots/search-results", ...)} — which carries the
 * Incapsula + auth cookies automatically, then parse the JSON into {@link Lot}s.
 */
@Component
public class CopartProvider implements AuctionProvider {

    private static final Logger log = LoggerFactory.getLogger(CopartProvider.class);

    private final CopartBrowserManager browser;
    private final SettingsService settingsService;

    public CopartProvider(CopartBrowserManager browser, SettingsService settingsService) {
        this.browser = browser;
        this.settingsService = settingsService;
    }

    @Override
    public AuctionType getType() {
        return AuctionType.COPART;
    }

    @Override
    public boolean isReady() {
        return settingsService.getCopartCredentials().isConfigured();
    }

    @Override
    public List<Lot> search(SearchFilter filter) {
        CopartCredentials credentials = settingsService.getCopartCredentials();
        boolean authed = browser.ensureLoggedIn(credentials);
        if (!authed) {
            log.warn("Copart session is not authenticated; skipping search for '{}'",
                    filter.displayLabel());
            return List.of();
        }
        // TODO(next): call the internal search-results JSON via the authenticated
        // browser context and map the response into Lot objects.
        log.debug("Copart authenticated; search extraction not yet implemented "
                + "(filter '{}')", filter.displayLabel());
        return List.of();
    }
}
