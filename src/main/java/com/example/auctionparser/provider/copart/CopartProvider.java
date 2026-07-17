package com.example.auctionparser.provider.copart;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.CopartCredentials;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.AuctionProvider;
import com.example.auctionparser.service.SettingsService;
import com.example.auctionparser.util.AuctionDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Copart provider backed by a headless Chromium session (Playwright).
 *
 * <p>Each search first guarantees a logged-in browser session (passing the
 * Incapsula challenge and signing in with the user's account, reusing a saved
 * session when possible), then drives Copart's own results page and sniffs the
 * internal {@code /public/lots/search-results} JSON — which carries the Incapsula
 * + auth cookies automatically — and maps it into {@link Lot}s. Criteria Copart
 * cannot express in the free-form query (year range, model refinement) are
 * applied as a client-side post-filter.
 */
@Component
public class CopartProvider implements AuctionProvider {

    private static final Logger log = LoggerFactory.getLogger(CopartProvider.class);

    /** Per-search cap on returned lots, and the paging bounds that enforce it. */
    private static final int MAX_LOTS = 200;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 25;
    /** Guards against enumerating an absurd year span into the server filter. */
    private static final int MAX_YEAR_SPAN = 40;
    /** Max photos to attach (Telegram's album limit, kept to one message). */
    private static final int MAX_PHOTOS = 10;

    private final CopartBrowserManager browser;
    private final CopartLotParser parser;
    private final SettingsService settingsService;

    public CopartProvider(CopartBrowserManager browser, CopartLotParser parser,
                          SettingsService settingsService) {
        this.browser = browser;
        this.parser = parser;
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
    public void enrichPhotos(Lot lot) {
        if (lot.getLotId() == null) {
            return;
        }
        List<String> photos = browser.fetchLotImages(lot.getLotId(), MAX_PHOTOS);
        if (!photos.isEmpty()) {
            lot.setPhotoUrls(photos);
        }
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

        String makeQuery = trimmed(filter.getMake());
        String freeQuery = buildQuery(filter);
        if (freeQuery.isBlank()) {
            log.warn("Copart search for '{}' has no make/model query terms; skipping",
                    filter.displayLabel());
            return List.of();
        }

        List<String> yearClauses = buildYearClauses(filter);

        // Translate the requested model into precise server-side model-group
        // clauses (e.g. "3 Series" -> lot_model_group:"3 SERIES" and its trims),
        // discovered from the response's own MODG facet. When we can, we drive the
        // search with make + MODG (exact) instead of the fuzzy free-text query.
        List<String> modelClauses = resolveModelClauses(filter, freeQuery, yearClauses);
        boolean modelServerSide = !modelClauses.isEmpty();
        String query = modelServerSide && !makeQuery.isBlank() ? makeQuery : freeQuery;

        List<Lot> collected = new ArrayList<>();
        int page = 0;
        while (collected.size() < MAX_LOTS && page < MAX_PAGES) {
            String json = browser.searchResultsJson(query, modelClauses, yearClauses, page, PAGE_SIZE);
            if (json == null) {
                break;
            }
            CopartLotParser.ParsedPage parsed = parser.parsePage(json);
            for (Lot lot : parsed.lots()) {
                // Year is a server filter; keep it as a cheap backstop. Model is
                // server-side via MODG, so only fall back to a fuzzy client check
                // when we couldn't resolve model groups.
                if (!matches(lot, filter)) {
                    continue;
                }
                if (!modelServerSide && !modelMatches(lot, filter)) {
                    continue;
                }
                if (!AuctionDates.isTodayOrTomorrow(lot.getAuctionDate(), ZoneId.systemDefault())) {
                    continue; // only imminent (today/tomorrow) auctions
                }
                collected.add(lot);
                if (collected.size() >= MAX_LOTS) {
                    break;
                }
            }
            page++;
            // Stop once we've walked past the last page of server-side matches.
            if (parsed.lots().isEmpty() || (long) page * PAGE_SIZE >= parsed.totalElements()) {
                break;
            }
        }
        log.debug("Copart search '{}' (query='{}', modelServerSide={}, years {}): "
                        + "collected {} lots over {} page(s)",
                filter.displayLabel(), query, modelServerSide, yearClauses, collected.size(), page);
        return collected;
    }

    /**
     * Resolves the filter's model to Copart Model Group facet clauses. Does one
     * lightweight probe request (size 1) to read the live MODG facet, then keeps
     * every group whose name equals or starts with the requested model — so "X3"
     * also picks up "X3 M"/"X3 M40i", and "3 Series" spans 330i/328i/… Returns an
     * empty list (fall back to free-text + client model check) when the model is
     * blank or no group matches.
     */
    private List<String> resolveModelClauses(SearchFilter filter, String freeQuery,
                                             List<String> yearClauses) {
        String model = trimmed(filter.getModel());
        if (model.isBlank()) {
            return List.of();
        }
        String probe = browser.searchResultsJson(freeQuery, List.of(), yearClauses, 0, 1);
        if (probe == null) {
            return List.of();
        }
        String target = normalize(model);
        List<String> clauses = new ArrayList<>();
        for (CopartLotParser.ModelGroup group : parser.modelGroups(probe)) {
            String name = normalize(group.displayName());
            if (name.equals(target) || name.startsWith(target + " ")) {
                clauses.add(group.query());
            }
        }
        if (clauses.isEmpty()) {
            log.debug("Copart: no MODG facet matched model '{}'; using free-text query", model);
        }
        return clauses;
    }

    /**
     * Copart's server-side year facet: one Solr clause per year in the requested
     * range, e.g. {@code lot_year:"2021"}. Empty when the filter has no year bound.
     */
    private List<String> buildYearClauses(SearchFilter filter) {
        Integer from = filter.getYearFrom();
        Integer to = filter.getYearTo();
        if (from == null && to == null) {
            return List.of();
        }
        int lo = from != null ? from : to;
        int hi = to != null ? to : from;
        if (lo > hi || hi - lo > MAX_YEAR_SPAN) {
            return List.of(); // implausible range — fall back to client-side year filter
        }
        List<String> clauses = new ArrayList<>();
        for (int y = lo; y <= hi; y++) {
            clauses.add("lot_year:\"" + y + "\"");
        }
        return clauses;
    }

    /** Free-form query Copart's search box understands, e.g. "Toyota Camry". */
    private String buildQuery(SearchFilter filter) {
        StringBuilder sb = new StringBuilder();
        if (filter.getMake() != null && !filter.getMake().isBlank()) {
            sb.append(filter.getMake().trim());
        }
        if (filter.getModel() != null && !filter.getModel().isBlank()) {
            sb.append(sb.isEmpty() ? "" : " ").append(filter.getModel().trim());
        }
        return sb.toString();
    }

    /**
     * Fallback client model check used only when Model Group facets couldn't be
     * resolved. Matches the requested model against the lot's model OR trim, since
     * Copart splits a series inconsistently between the two (e.g. "330I").
     */
    private boolean modelMatches(Lot lot, SearchFilter filter) {
        String model = trimmed(filter.getModel());
        if (model.isBlank()) {
            return true;
        }
        String needle = model.toLowerCase();
        return (lot.getModel() != null && lot.getModel().toLowerCase().contains(needle))
                || (lot.getTrim() != null && lot.getTrim().toLowerCase().contains(needle));
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    /** Upper-cased, whitespace-collapsed model text for facet name comparison. */
    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase().replaceAll("\\s+", " ");
    }
}
