package com.example.auctionparser.repository;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Persistence for discovered lots. The {@code (lot_id, auction)} unique
 * constraint is the mechanism that guarantees a lot is never sent twice.
 */
@Repository
public class LotRepository {

    private static final Logger log = LoggerFactory.getLogger(LotRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** @return true if this lot has already been recorded. */
    public boolean exists(AuctionType auction, String lotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lots WHERE auction = ? AND lot_id = ?",
                Integer.class, auction.name(), lotId);
        return count != null && count > 0;
    }

    /**
     * Inserts a newly discovered lot. Uses {@code INSERT OR IGNORE} so a race
     * between concurrent cycles cannot create duplicates.
     *
     * @return true if a new row was inserted, false if it already existed.
     */
    public boolean insertIfNew(Lot lot, String dateFoundIso, boolean sent) {
        int rows = jdbc.update(
                "INSERT OR IGNORE INTO lots (lot_id, auction, url, date_found, sent, details_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                lot.getLotId(),
                lot.getAuction().name(),
                lot.getUrl(),
                dateFoundIso,
                sent ? 1 : 0,
                toJson(lot));
        return rows > 0;
    }

    public void markSent(AuctionType auction, String lotId) {
        jdbc.update("UPDATE lots SET sent = 1 WHERE auction = ? AND lot_id = ?",
                auction.name(), lotId);
    }

    /** Count of lots first found at or after the given ISO timestamp. */
    public int countFoundSince(String isoTimestamp) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lots WHERE date_found >= ?",
                Integer.class, isoTimestamp);
        return count == null ? 0 : count;
    }

    /** Full history (newest first) for the history view / export. */
    public List<HistoryRow> findAllHistory() {
        return jdbc.query(
                "SELECT lot_id, auction, url, date_found, sent, details_json "
                        + "FROM lots ORDER BY date_found DESC",
                HISTORY_MAPPER);
    }

    public Lot findDetails(AuctionType auction, String lotId) {
        try {
            String json = jdbc.queryForObject(
                    "SELECT details_json FROM lots WHERE auction = ? AND lot_id = ?",
                    String.class, auction.name(), lotId);
            return fromJson(json);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static final RowMapper<HistoryRow> HISTORY_MAPPER = (rs, i) -> new HistoryRow(
            rs.getString("lot_id"),
            AuctionType.valueOf(rs.getString("auction")),
            rs.getString("url"),
            rs.getString("date_found"),
            rs.getInt("sent") == 1,
            rs.getString("details_json"));

    private String toJson(Lot lot) {
        try {
            return objectMapper.writeValueAsString(lot);
        } catch (JacksonException e) {
            log.warn("Failed to serialize lot {} details", lot.getLotId(), e);
            return null;
        }
    }

    private Lot fromJson(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Lot.class);
        } catch (JacksonException e) {
            log.warn("Failed to deserialize lot details", e);
            return null;
        }
    }

    /** Flat projection of a history row (details kept as raw JSON). */
    public record HistoryRow(String lotId, AuctionType auction, String url,
                             String dateFound, boolean sent, String detailsJson) {
    }
}
