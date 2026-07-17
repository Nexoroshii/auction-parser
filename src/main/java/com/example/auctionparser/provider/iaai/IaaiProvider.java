package com.example.auctionparser.provider.iaai;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.AuctionProvider;
import com.example.auctionparser.util.AuctionDates;
import com.example.auctionparser.util.RetryableHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IAAI provider backed by the site's server-rendered search results (no browser
 * required). Filtering by make is expressed as an IAAI facet query; model and
 * year range are applied client-side for robustness.
 *
 * <p>Full lot detail (unmasked VIN, values, complete photo gallery) requires an
 * authenticated session and is a later enhancement; the public listing already
 * yields most of the fields the spec asks for.
 */
@Component
public class IaaiProvider implements AuctionProvider {

    private static final Logger log = LoggerFactory.getLogger(IaaiProvider.class);
    private static final String SEARCH_URL = "https://www.iaai.com/Search";
    /** Per-search cap on returned lots, and the widest year span we enumerate. */
    private static final int MAX_LOTS = 200;
    private static final int MAX_YEAR_SPAN = 40;
    /** Upper bound on keyword requests per search (series expansion × years). */
    private static final int MAX_QUERIES = 40;
    /** Delay between consecutive keyword requests to avoid Incapsula rate limits. */
    private static final long REQUEST_DELAY_MS = 500;
    /** Max photos to attach (Telegram's album limit, kept to one message). */
    private static final int MAX_PHOTOS = 10;
    /** Display-sized resizer URL template; {@code %s} is an image key. */
    private static final String IMAGE_RES =
            "https://vis.iaai.com/resizer?imageKeys=%s&width=1024&height=768";
    /** Image key in a VehicleDetail page: capture (1) inventory id and (2) image index. */
    private static final Pattern IMAGE_KEY_IN_PAGE =
            Pattern.compile("(\\d+)~SID~B\\d+~S\\d+~I(\\d+)~");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final RetryableHttpClient http;
    private final IaaiHtmlParser parser;

    public IaaiProvider(RetryableHttpClient http, IaaiHtmlParser parser) {
        this.http = http;
        this.parser = parser;
    }

    @Override
    public AuctionType getType() {
        return AuctionType.IAAI;
    }

    @Override
    public void enrichPhotos(Lot lot) {
        if (lot.getUrl() == null || lot.getUrl().isBlank()) {
            return;
        }
        try {
            HttpRequest request = http.requestBuilder(lot.getUrl())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build();
            List<String> photos = extractImageUrls(http.sendForString(request).body());
            if (!photos.isEmpty()) {
                lot.setPhotoUrls(photos);
            }
        } catch (IOException e) {
            log.debug("IAAI enrichPhotos failed for {}: {}", lot.getLotId(), e.toString());
        }
    }

    /**
     * Extracts the lot's image list from a VehicleDetail page. Each embedded image
     * key looks like {@code 46040754~SID~B612~S0~I3~RW2576~H1932~TH0}; we take the
     * inventory id and image index and rebuild a display-sized resizer URL, ordered
     * by index and capped at {@link #MAX_PHOTOS}.
     */
    private List<String> extractImageUrls(String html) {
        Matcher m = IMAGE_KEY_IN_PAGE.matcher(html);
        java.util.TreeMap<Integer, String> byIndex = new java.util.TreeMap<>();
        while (m.find()) {
            int index = Integer.parseInt(m.group(2));
            byIndex.putIfAbsent(index, String.format(IMAGE_RES, m.group(1) + "~SID~I" + index));
        }
        return byIndex.values().stream().limit(MAX_PHOTOS).toList();
    }

    @Override
    public List<Lot> search(SearchFilter filter) throws IOException {
        // IAAI's server-rendered results page holds ~100 lots and its facet/paging
        // is gated behind an encrypted token + JS API, so we can't reliably page or
        // apply Make/Model/Year facets by URL. Its keyword search DOES filter
        // server-side, however, so we run one keyword query per year in the range
        // ("make model year") — each such result set is usually small enough to fit
        // one page — and merge, deduplicating by lot id and capping the total.
        java.util.LinkedHashMap<String, Lot> byId = new java.util.LinkedHashMap<>();
        boolean first = true;
        for (String keyword : buildKeywords(filter)) {
            if (byId.size() >= MAX_LOTS) {
                break;
            }
            // Space requests out: a rapid burst of many keyword queries trips
            // IAAI's Incapsula rate limiting (every response comes back as a
            // challenge). The persistent cookie jar plus this delay keeps us under
            // the threshold.
            if (!first) {
                throttle();
            }
            first = false;
            for (Lot lot : fetchKeyword(keyword)) {
                if (lot.getLotId() == null || byId.containsKey(lot.getLotId())) {
                    continue;
                }
                // IAAI's keyword match is fuzzy (e.g. "Lexus IS 300" also returns
                // NX 300/GS 300), so we re-check the model client-side, then keep
                // only imminent (today/tomorrow) auctions within the year range.
                if (modelMatches(lot, filter)
                        && matches(lot, filter)
                        && AuctionDates.isTodayOrTomorrow(lot.getAuctionDate(), ZoneId.systemDefault())) {
                    byId.put(lot.getLotId(), lot);
                    if (byId.size() >= MAX_LOTS) {
                        break;
                    }
                }
            }
        }
        log.debug("IAAI search '{}': collected {} lots", filter.displayLabel(), byId.size());
        return List.copyOf(byId.values());
    }

    private void throttle() {
        try {
            Thread.sleep(REQUEST_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs one keyword search and returns its parsed lots (empty on challenge). */
    private List<Lot> fetchKeyword(String keyword) throws IOException {
        String url = SEARCH_URL + "?Keyword=" + enc(keyword);
        HttpRequest request = http.requestBuilder(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = http.sendForString(request);
        String body = response.body();
        if (looksLikeChallenge(body)) {
            log.warn("IAAI returned an anti-bot challenge for '{}' (HTTP {}).",
                    keyword, response.statusCode());
            return List.of();
        }
        return parser.parse(Jsoup.parse(body, SEARCH_URL));
    }

    /**
     * Keyword queries to run so IAAI filters make+model+year server-side. Each
     * query is "make model year". A model that is a known series (e.g. "3 Series")
     * is expanded into its trims (330i, 328i, …) — see {@link IaaiModelAliases} —
     * because IAAI lists lots under the trim, not the series. Falls back to a
     * single unyeared query when no year range is set. Bounded by {@link #MAX_QUERIES}.
     */
    private List<String> buildKeywords(SearchFilter filter) {
        String make = filter.getMake() == null ? "" : filter.getMake().trim();
        String model = filter.getModel() == null ? "" : filter.getModel().trim();
        if (make.isEmpty() && model.isEmpty()) {
            return List.of();
        }

        // Model variants to search: the series' trims, or the model itself.
        List<String> variants;
        List<String> trims = IaaiModelAliases.trimsForSeries(model);
        if (!trims.isEmpty()) {
            variants = trims;
        } else if (!model.isEmpty()) {
            variants = List.of(model);
        } else {
            variants = List.of(""); // make-only search
        }

        List<String> bases = new ArrayList<>();
        for (String variant : variants) {
            String base = (make + " " + variant).trim();
            if (!base.isEmpty()) {
                bases.add(base);
            }
        }

        List<Integer> years = yearsInRange(filter);
        List<String> keywords = new ArrayList<>();
        if (years.isEmpty()) {
            for (String base : bases) {
                if (keywords.size() >= MAX_QUERIES) {
                    break;
                }
                keywords.add(base);
            }
            return keywords;
        }
        outer:
        for (String base : bases) {
            for (int y : years) {
                if (keywords.size() >= MAX_QUERIES) {
                    break outer;
                }
                keywords.add(base + " " + y);
            }
        }
        return keywords;
    }

    /**
     * Client-side model check that tolerates IAAI's inconsistent naming. A lot
     * matches when its model or trim:
     * <ul>
     *   <li>equals the requested model or begins with it as a family prefix
     *       ("IS 300"←"IS", "4 SERIES"←"4 Series", "X3 M"←"X3"), or</li>
     *   <li>matches one of the series' alias trims exactly, by prefix, or by its
     *       numeric core so drivetrain suffixes are tolerated
     *       ("330I"/"330XI"/"330E" all match the "330i" alias).</li>
     * </ul>
     */
    private boolean modelMatches(Lot lot, SearchFilter filter) {
        String model = normalize(filter.getModel());
        if (model.isBlank()) {
            return true;
        }
        List<String> trims = new ArrayList<>();
        for (String t : IaaiModelAliases.trimsForSeries(filter.getModel())) {
            trims.add(normalize(t));
        }
        for (String raw : new String[]{lot.getModel(), lot.getTrim()}) {
            String f = normalize(raw);
            if (f.isBlank()) {
                continue;
            }
            if (f.equals(model) || f.startsWith(model + " ")) {
                return true;
            }
            for (String t : trims) {
                if (f.equals(t) || f.startsWith(t + " ")) {
                    return true;
                }
                String core = numericCore(t);
                if (!core.isEmpty() && numericCore(f).equals(core)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /** Leading run of digits (or a leading 'M'), e.g. "330XI"→"330", "M340I"→"M340". */
    private static String numericCore(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || (i == 0 && c == 'M')) {
                b.append(c);
            } else {
                break;
            }
        }
        return b.toString();
    }

    /** The years to search, or empty for "no year constraint" / implausible range. */
    private List<Integer> yearsInRange(SearchFilter filter) {
        Integer from = filter.getYearFrom();
        Integer to = filter.getYearTo();
        if (from == null && to == null) {
            return List.of();
        }
        int lo = from != null ? from : to;
        int hi = to != null ? to : from;
        if (lo > hi || hi - lo > MAX_YEAR_SPAN) {
            return List.of();
        }
        List<Integer> years = new ArrayList<>();
        for (int y = lo; y <= hi; y++) {
            years.add(y);
        }
        return years;
    }

    /**
     * Detects Imperva/Incapsula's anti-bot interstitial. That page is a tiny
     * ({@literal ~}900 B) iframe carrying an {@code "Incapsula incident ID"} and
     * {@code incident_id=} query param. A fully rendered results page ALSO embeds
     * a {@code _Incapsula_Resource} telemetry script, so keying off that substring
     * flags real pages as challenges — we key off the incident marker instead,
     * with a body-size backstop for any other truncated/empty error page.
     */
    private boolean looksLikeChallenge(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        if (body.contains("Incapsula incident ID") || body.contains("incident_id=")) {
            return true;
        }
        return body.length() < 2000;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
