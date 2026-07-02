package com.example.auctionparser.provider;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;

import java.util.List;

/**
 * A source of auction lots. This is the sole extension point for supporting a
 * new auction (Copart, IAAI, and in future Manheim/ACV/Impact/CrashedToys):
 * implement this interface as a Spring {@code @Component} and it is picked up
 * automatically &mdash; the monitoring pipeline collects all beans of this type
 * and requires no other change.
 */
public interface AuctionProvider {

    /** Which auction this provider serves. Must be unique across providers. */
    AuctionType getType();

    /**
     * Executes a search for the given filter and returns the matching lots.
     * Implementations should express as much of the filter as possible in the
     * site's own query and may apply the remainder as a client-side filter via
     * {@link #matches(Lot, SearchFilter)}.
     *
     * <p>Should return an empty list rather than throwing for a "no results"
     * outcome. Transient/network failures may throw &mdash; the caller isolates
     * and logs per-provider failures so one bad source cannot stop the cycle.
     */
    List<Lot> search(SearchFilter filter) throws Exception;

    /**
     * Whether the provider is currently usable (e.g. credentials present, parser
     * implemented). Unready providers are skipped with a log note.
     */
    default boolean isReady() {
        return true;
    }

    /**
     * Client-side post-filter for criteria a provider cannot express in its
     * server query. Default keeps everything; providers/pipeline may reuse it.
     */
    default boolean matches(Lot lot, SearchFilter filter) {
        if (filter.getYearFrom() != null && lot.getYear() != null
                && lot.getYear() < filter.getYearFrom()) {
            return false;
        }
        if (filter.getYearTo() != null && lot.getYear() != null
                && lot.getYear() > filter.getYearTo()) {
            return false;
        }
        return true;
    }
}
