package com.example.auctionparser.provider;

import java.util.Map;
import java.util.Set;

/**
 * Guards the providers' "family prefix" model matching (e.g. a filter for "X3"
 * also matching "X3 M") against umbrella model names whose prefix is shared by
 * otherwise distinct nameplates. Land Rover's "Range Rover" is the motivating
 * case: "Range Rover Sport", "Range Rover Velar" and "Range Rover Evoque" are
 * separate model lines, not trims of the base Range Rover, even though their
 * names all start with "Range Rover ".
 */
public final class ModelLines {

    private static final Map<String, Set<String>> DISTINCT_SUBLINES = Map.of(
            "RANGE ROVER", Set.of("SPORT", "VELAR", "EVOQUE"));

    private ModelLines() {
    }

    /**
     * True when {@code candidate} (a lot's normalised model/trim/facet text)
     * should count as a match for the normalised {@code model} filter: an exact
     * match, or a family-prefix match that isn't one of {@code model}'s known
     * distinct sub-lines. Both arguments must already be upper-cased and
     * whitespace-collapsed.
     */
    public static boolean isFamilyMatch(String model, String candidate) {
        if (candidate.equals(model)) {
            return true;
        }
        if (!candidate.startsWith(model + " ")) {
            return false;
        }
        Set<String> exclusions = DISTINCT_SUBLINES.get(model);
        if (exclusions == null || exclusions.isEmpty()) {
            return true;
        }
        String rest = candidate.substring(model.length() + 1);
        int sp = rest.indexOf(' ');
        String nextWord = sp < 0 ? rest : rest.substring(0, sp);
        return !exclusions.contains(nextWord);
    }
}
