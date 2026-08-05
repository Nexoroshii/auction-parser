package com.example.auctionparser.provider;

import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.copart.CopartBrowserManager;
import com.example.auctionparser.provider.copart.CopartLotParser;
import com.example.auctionparser.provider.copart.CopartProvider;
import com.example.auctionparser.provider.iaai.IaaiProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for {@link ModelLines}: a "Range Rover" search filter must
 * match the base Range Rover but not the distinct Sport/Velar/Evoque
 * nameplates, on both providers' model-matching paths. Exercises the real
 * private matching methods via reflection since the providers otherwise
 * require a live network/browser session.
 */
class RangeRoverModelMatchTest {

    private static final SearchFilter RANGE_ROVER_FILTER =
            SearchFilter.builder().make("Land Rover").model("Range Rover").build();

    @Test
    void iaaiModelMatchesExcludesDistinctSublines() throws Exception {
        IaaiProvider provider = new IaaiProvider(null, null, null);
        Method modelMatches = IaaiProvider.class.getDeclaredMethod("modelMatches", Lot.class, SearchFilter.class);
        modelMatches.setAccessible(true);

        assertTrue((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER", "SE"), RANGE_ROVER_FILTER),
                "plain Range Rover should match");
        assertTrue((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER", "AUTOBIOGRAPHY"), RANGE_ROVER_FILTER),
                "Range Rover trim should match");
        assertFalse((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER SPORT", "HSE"), RANGE_ROVER_FILTER),
                "Range Rover Sport must NOT match");
        assertFalse((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER VELAR", "R-DYNAMIC"), RANGE_ROVER_FILTER),
                "Range Rover Velar must NOT match");
        assertFalse((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER EVOQUE", "SE"), RANGE_ROVER_FILTER),
                "Range Rover Evoque must NOT match");
    }

    @Test
    void copartResolveModelClausesExcludesDistinctSublines() throws Exception {
        String facetJson = """
                {
                  "data": {
                    "results": {
                      "facetFields": [
                        {
                          "quickPickCode": "MODG",
                          "facetCounts": [
                            {"displayName": "RANGE ROVER", "query": "lot_model_group:\\"RANGE ROVER\\""},
                            {"displayName": "RANGE ROVER SPORT", "query": "lot_model_group:\\"RANGE ROVER SPORT\\""},
                            {"displayName": "RANGE ROVER VELAR", "query": "lot_model_group:\\"RANGE ROVER VELAR\\""},
                            {"displayName": "RANGE ROVER EVOQUE", "query": "lot_model_group:\\"RANGE ROVER EVOQUE\\""}
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        CopartBrowserManager browser = mock(CopartBrowserManager.class);
        when(browser.searchResultsJson(any(), eq(List.of()), any(), eq(0), eq(1))).thenReturn(facetJson);
        CopartLotParser parser = new CopartLotParser(JsonMapper.builder().build());
        CopartProvider provider = new CopartProvider(browser, parser, null);

        Method resolveModelClauses = CopartProvider.class.getDeclaredMethod(
                "resolveModelClauses", SearchFilter.class, String.class, List.class);
        resolveModelClauses.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> clauses = (List<String>) resolveModelClauses.invoke(
                provider, RANGE_ROVER_FILTER, "Land Rover Range Rover", List.of());

        assertTrue(clauses.contains("lot_model_group:\"RANGE ROVER\""), "base Range Rover group must be included");
        assertFalse(clauses.stream().anyMatch(c -> c.contains("SPORT")), "Sport group must be excluded");
        assertFalse(clauses.stream().anyMatch(c -> c.contains("VELAR")), "Velar group must be excluded");
        assertFalse(clauses.stream().anyMatch(c -> c.contains("EVOQUE")), "Evoque group must be excluded");
    }

    @Test
    void copartFallbackModelMatchesExcludesDistinctSublines() throws Exception {
        CopartProvider provider = new CopartProvider(mock(CopartBrowserManager.class),
                new CopartLotParser(JsonMapper.builder().build()), null);
        Method modelMatches = CopartProvider.class.getDeclaredMethod("modelMatches", Lot.class, SearchFilter.class);
        modelMatches.setAccessible(true);

        assertTrue((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER", "SE"), RANGE_ROVER_FILTER));
        assertFalse((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER SPORT", "HSE"), RANGE_ROVER_FILTER));
        assertFalse((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER VELAR", null), RANGE_ROVER_FILTER));
        assertFalse((Boolean) modelMatches.invoke(provider, lot("RANGE ROVER EVOQUE", null), RANGE_ROVER_FILTER));
    }

    private static Lot lot(String model, String trim) {
        return Lot.builder().lotId("1").make("LAND ROVER").model(model).trim(trim).build();
    }
}
