package com.example.auctionparser.provider.iaai;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.AuctionProvider;
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
import java.util.List;

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
    public List<Lot> search(SearchFilter filter) throws IOException {
        String url = buildSearchUrl(filter);
        HttpRequest request = http.requestBuilder(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = http.sendForString(request);
        String body = response.body();

        if (looksLikeChallenge(body)) {
            log.warn("IAAI returned an anti-bot challenge instead of results "
                    + "(HTTP {}). A logged-in browser session may be required.", response.statusCode());
            return List.of();
        }

        Document doc = Jsoup.parse(body, SEARCH_URL);
        List<Lot> lots = parser.parse(doc);
        return lots.stream()
                .filter(lot -> matches(lot, filter))
                .filter(lot -> modelMatches(lot, filter))
                .toList();
    }

    private String buildSearchUrl(SearchFilter filter) {
        StringBuilder url = new StringBuilder(SEARCH_URL);
        if (filter.getMake() != null && !filter.getMake().isBlank()) {
            url.append("?queryFilterValue=").append(enc(filter.getMake()))
               .append("&queryFilterGroup=Make");
        }
        return url.toString();
    }

    private boolean modelMatches(Lot lot, SearchFilter filter) {
        if (filter.getModel() == null || filter.getModel().isBlank()) {
            return true;
        }
        return lot.getModel() != null
                && lot.getModel().toLowerCase().contains(filter.getModel().toLowerCase());
    }

    private boolean looksLikeChallenge(String body) {
        return body == null
                || body.contains("_Incapsula_Resource")
                || body.length() < 2000;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
