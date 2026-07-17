package com.example.auctionparser.provider.iaai;

import java.util.List;
import java.util.Map;

/**
 * Maps an "umbrella" model name (a vehicle <em>series</em>) to the concrete trim
 * names the site actually lists individual lots under.
 *
 * <p>IAAI has no series concept: unlike Copart's Model Group facet, it records a
 * lot's model as its trim ("330I", "328I", …) and its keyword search matches that
 * text literally — so a search for "BMW 3 Series" finds only the handful of lots
 * whose text literally says "3 Series" and misses every 330i/328i. To cover a
 * series we therefore fan the query out across its trims.
 *
 * <p>Keys are normalised (upper-case, single-spaced). Only series that genuinely
 * need expanding live here; models IAAI lists directly (X3, X5, Camry, …) are not
 * present and are searched as-is. Extend this map to add more series.
 */
final class IaaiModelAliases {

    private static final Map<String, List<String>> SERIES_TRIMS = Map.of(
            "3 SERIES", List.of("318i", "320i", "323i", "325i", "328i", "330i",
                    "330e", "335i", "340i", "M340i", "M3"),
            "5 SERIES", List.of("525i", "528i", "530i", "530e", "535i", "540i",
                    "545i", "550i", "M550i", "M5"),
            "7 SERIES", List.of("740i", "745e", "750i", "760i", "M760i"),
            "4 SERIES", List.of("428i", "430i", "435i", "440i", "M440i", "M4"),
            "2 SERIES", List.of("228i", "230i", "M235i", "M240i", "M2"),
            // Lexus IS: IAAI lists these with a SPACE ("IS 300", not "IS300"), so
            // the trims carry the space; the plain "Lexus IS" keyword matches every
            // Lexus, hence the expansion.
            "IS", List.of("IS 250", "IS 300", "IS 350", "IS 200t", "IS 500", "IS F"));

    private IaaiModelAliases() {
    }

    /**
     * Returns the trim names a series should be expanded into for keyword search,
     * or an empty list when {@code model} is not a known series (search it as-is).
     */
    static List<String> trimsForSeries(String model) {
        if (model == null) {
            return List.of();
        }
        String key = model.trim().toUpperCase().replaceAll("\\s+", " ");
        return SERIES_TRIMS.getOrDefault(key, List.of());
    }
}
