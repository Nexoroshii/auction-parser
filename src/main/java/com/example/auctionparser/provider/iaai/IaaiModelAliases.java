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
 * <p>Each trim carries the US model years it was sold in, so a year-bounded search
 * only queries trims that can exist in those years (e.g. no "318i 2022") — every
 * pointless query adds to the burst that trips IAAI's rate limiting. Ranges are
 * deliberately a little generous; {@link #PRESENT} marks a trim still on sale.
 *
 * <p>Keys are normalised (upper-case, single-spaced). Only series that genuinely
 * need expanding live here; models IAAI lists directly (X3, X5, Camry, …) are not
 * present and are searched as-is. Extend this map to add more series.
 */
final class IaaiModelAliases {

    private static final int PRESENT = Integer.MAX_VALUE;

    /** A trim name and the model-year ranges ({@code {from, to}} pairs) it was sold in. */
    private record Trim(String name, int... years) {
        boolean soldIn(int year) {
            for (int i = 0; i + 1 < years.length; i += 2) {
                if (year >= years[i] && year <= years[i + 1]) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<String, List<Trim>> SERIES_TRIMS = Map.of(
            "3 SERIES", List.of(
                    new Trim("318i", 1991, 1999),
                    new Trim("320i", 2012, 2019),
                    new Trim("323i", 1998, 2000),
                    new Trim("325i", 1987, 2006),
                    new Trim("328i", 1996, 2000, 2007, 2016),
                    new Trim("330i", 2001, 2006, 2016, PRESENT),
                    new Trim("330e", 2016, PRESENT),
                    new Trim("335i", 2007, 2015),
                    new Trim("340i", 2016, 2019),
                    new Trim("M340i", 2019, PRESENT),
                    new Trim("M3", 1988, PRESENT)),
            "5 SERIES", List.of(
                    new Trim("525i", 1989, 1995, 2001, 2007),
                    new Trim("528i", 1997, 2000, 2008, 2016),
                    new Trim("530i", 1994, 1995, 2001, 2007, 2017, PRESENT),
                    new Trim("530e", 2018, PRESENT),
                    new Trim("535i", 1988, 1993, 2008, 2016),
                    new Trim("540i", 1994, 2003, 2017, PRESENT),
                    new Trim("545i", 2004, 2005),
                    new Trim("550i", 2006, 2016),
                    new Trim("M550i", 2018, PRESENT),
                    new Trim("M5", 1988, PRESENT)),
            "7 SERIES", List.of(
                    new Trim("740i", 1993, 2001, 2016, PRESENT),
                    new Trim("745e", 2019, 2022),
                    new Trim("750i", 2006, 2022),
                    new Trim("760i", 2003, 2008, 2023, PRESENT),
                    new Trim("M760i", 2017, 2022)),
            "4 SERIES", List.of(
                    new Trim("428i", 2014, 2016),
                    new Trim("430i", 2017, PRESENT),
                    new Trim("435i", 2014, 2016),
                    new Trim("440i", 2017, 2020),
                    new Trim("M440i", 2021, PRESENT),
                    new Trim("M4", 2015, PRESENT)),
            "2 SERIES", List.of(
                    new Trim("228i", 2014, PRESENT),
                    new Trim("230i", 2017, PRESENT),
                    new Trim("M235i", 2014, 2016, 2020, PRESENT),
                    new Trim("M240i", 2017, PRESENT),
                    new Trim("M2", 2016, PRESENT)),
            // Lexus IS: IAAI lists these with a SPACE ("IS 300", not "IS300"), so
            // the trims carry the space; the plain "Lexus IS" keyword matches every
            // Lexus, hence the expansion.
            "IS", List.of(
                    new Trim("IS 250", 2006, 2015),
                    new Trim("IS 300", 2001, 2005, 2016, PRESENT),
                    new Trim("IS 350", 2006, PRESENT),
                    new Trim("IS 200t", 2016, 2017),
                    new Trim("IS 500", 2022, PRESENT),
                    new Trim("IS F", 2008, 2014)));

    private IaaiModelAliases() {
    }

    /**
     * Returns the trim names a series should be expanded into for keyword search,
     * or an empty list when {@code model} is not a known series (search it as-is).
     */
    static List<String> trimsForSeries(String model) {
        return lookup(model).stream().map(Trim::name).toList();
    }

    /**
     * Like {@link #trimsForSeries(String)}, but only the trims sold in model year
     * {@code year}. Empty when {@code model} is not a known series.
     */
    static List<String> trimsForSeries(String model, int year) {
        return lookup(model).stream().filter(t -> t.soldIn(year)).map(Trim::name).toList();
    }

    private static List<Trim> lookup(String model) {
        if (model == null) {
            return List.of();
        }
        String key = model.trim().toUpperCase().replaceAll("\\s+", " ");
        return SERIES_TRIMS.getOrDefault(key, List.of());
    }
}
