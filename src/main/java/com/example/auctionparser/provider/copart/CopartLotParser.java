package com.example.auctionparser.provider.copart;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps Copart's internal search-results JSON into {@link Lot}s.
 *
 * <p>The payload shape is
 * {@code {returnCode:1, data:{results:{totalElements, content:[ lot... ]}}}} and
 * each lot uses abbreviated Solr keys. The key map was reverse-engineered from
 * the live endpoint (see the {@code site-investigation} memory); unknown/missing
 * keys are simply left null.
 */
@Component
public class CopartLotParser {

    private static final Logger log = LoggerFactory.getLogger(CopartLotParser.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper mapper;

    public CopartLotParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** One page of parsed results plus the server's total match count. */
    public record ParsedPage(List<Lot> lots, int totalElements) {
        public static final ParsedPage EMPTY = new ParsedPage(List.of(), 0);
    }

    /** Parses the raw JSON body; returns an empty list on any structural problem. */
    public List<Lot> parse(String json) {
        return parsePage(json).lots();
    }

    /**
     * Extracts the Model Group (quickPickCode {@code MODG}) facet values from a
     * search response: each entry's {@code displayName} (e.g. "3 SERIES", "X3 M")
     * paired with the Solr {@code query} clause used to filter by it
     * (e.g. {@code lot_model_group:"3 SERIES"}). Used to translate a user's model
     * into a precise server-side filter that spans all trims of that model.
     */
    public List<ModelGroup> modelGroups(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode facets;
        try {
            facets = mapper.readTree(json).path("data").path("results").path("facetFields");
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ModelGroup> groups = new ArrayList<>();
        for (JsonNode facet : facets) {
            if (!"MODG".equals(facet.path("quickPickCode").asText(""))) {
                continue;
            }
            for (JsonNode c : facet.path("facetCounts")) {
                String name = c.path("displayName").asText("");
                String query = c.path("query").asText("");
                if (!name.isBlank() && !query.isBlank()) {
                    groups.add(new ModelGroup(name, query));
                }
            }
        }
        return groups;
    }

    /** A Copart Model Group facet: its display name and the Solr filter clause. */
    public record ModelGroup(String displayName, String query) {
    }

    /** Parses one search-results page, exposing {@code totalElements} for paging. */
    public ParsedPage parsePage(String json) {
        if (json == null || json.isBlank()) {
            return ParsedPage.EMPTY;
        }
        JsonNode results;
        try {
            results = mapper.readTree(json).path("data").path("results");
        } catch (RuntimeException e) {
            log.warn("Could not parse Copart search JSON: {}", e.toString());
            return ParsedPage.EMPTY;
        }
        JsonNode content = results.path("content");
        if (!content.isArray()) {
            log.warn("Copart search JSON has no results.content array");
            return ParsedPage.EMPTY;
        }
        int total = results.path("totalElements").asInt(content.size());
        List<Lot> lots = new ArrayList<>(content.size());
        for (JsonNode node : content) {
            Lot lot = toLot(node);
            if (lot != null) {
                lots.add(lot);
            }
        }
        log.debug("Parsed {} Copart lots (totalElements={})", lots.size(), total);
        return new ParsedPage(lots, total);
    }

    private Lot toLot(JsonNode n) {
        String lotId = text(n, "lotNumberStr", "ln");
        if (lotId == null) {
            return null;
        }
        Lot.LotBuilder b = Lot.builder()
                .lotId(lotId)
                .auction(AuctionType.COPART)
                .url(buildUrl(lotId, text(n, "ldu")))
                .vin(text(n, "fv"))
                .make(text(n, "mkn", "lmc"))
                .model(text(n, "lm", "lmg"))
                .trim(text(n, "ltd"))
                .year(intVal(n, "lcy"))
                .color(text(n, "clr"))
                .mileage(odometer(n))
                .engine(text(n, "egn"))
                .fuelType(text(n, "ft"))
                .transmission(text(n, "tmtp"))
                .drive(text(n, "drv"))
                .keys(yesNo(text(n, "hk")))
                .primaryDamage(text(n, "dd"))
                .runAndDrive(text(n, "lcd"))
                .title(text(n, "tgd"))
                .location(text(n, "yn", "syn"))
                .auctionDate(auctionDate(n))
                .estimatedRetailValue(money(n, "lotPlugAcv"))
                .repairCost(money(n, "rc"))
                .buyNow(money(n, "bnp"))
                .currentBid(money(n.path("dynamicLotDetails"), "currentBid"));

        List<String> photos = new ArrayList<>();
        String thumb = text(n, "tims");
        if (thumb != null) {
            photos.add(thumb);
        }
        b.photoUrls(photos);
        return b.build();
    }

    private static String buildUrl(String lotId, String slug) {
        String base = "https://www.copart.com/lot/" + lotId;
        return slug != null ? base + "/" + slug : base;
    }

    private String auctionDate(JsonNode n) {
        // `ad` is the UPCOMING auction date (epoch millis); `lad` is the last/prior
        // auction date and must not be used here.
        JsonNode ad = n.get("ad");
        if (ad == null || !ad.isNumber() || ad.asLong() <= 0) {
            return null;
        }
        String date = Instant.ofEpochMilli(ad.asLong())
                .atZone(ZoneId.systemDefault()).toLocalDate().format(DATE);
        String time = text(n, "at");
        return time != null ? date + " " + time : date;
    }

    // --- small helpers over the abbreviated keys ---

    /** First non-blank textual value among the given keys, else null. */
    private static String text(JsonNode n, String... keys) {
        for (String key : keys) {
            JsonNode v = n.get(key);
            if (v != null && !v.isNull()) {
                String s = v.asText().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Odometer as a whole-number string (the {@code orr} key is numeric). */
    private static String odometer(JsonNode n) {
        JsonNode v = n.get("orr");
        if (v == null || !v.isNumber()) {
            return null;
        }
        return String.valueOf(v.asLong());
    }

    private static Integer intVal(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && v.isNumber() ? v.asInt() : null;
    }

    /** Formats a numeric currency key as "$N", treating 0/absent as "no value". */
    private static String money(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isNumber()) {
            return null;
        }
        double d = v.asDouble();
        if (d <= 0) {
            return null;
        }
        return d == Math.floor(d) ? "$" + (long) d : "$" + d;
    }

    private static String yesNo(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase()) {
            case "YES", "Y", "TRUE" -> "Yes";
            case "NO", "N", "FALSE" -> "No";
            default -> raw;
        };
    }
}
