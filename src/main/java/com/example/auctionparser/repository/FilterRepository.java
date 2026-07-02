package com.example.auctionparser.repository;

import com.example.auctionparser.model.SearchFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/** CRUD persistence for user search filters. */
@Repository
public class FilterRepository {

    private final JdbcTemplate jdbc;

    public FilterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchFilter> findAll() {
        return jdbc.query("SELECT * FROM filters ORDER BY id", MAPPER);
    }

    public List<SearchFilter> findEnabled() {
        return jdbc.query("SELECT * FROM filters WHERE enabled = 1 ORDER BY id", MAPPER);
    }

    /** Inserts a new filter or updates an existing one; returns it with its id. */
    public SearchFilter save(SearchFilter f) {
        return f.getId() == null ? insert(f) : update(f);
    }

    private SearchFilter insert(SearchFilter f) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO filters (name, brand, model, year_from, year_to, vin, "
                            + "damage_type, title_type, max_mileage, bid_min, bid_max, "
                            + "retail_min, retail_max, location, telegram_chat_id, enabled) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            bind(ps, f);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            f.setId(key.longValue());
        }
        return f;
    }

    private SearchFilter update(SearchFilter f) {
        jdbc.update("UPDATE filters SET name=?, brand=?, model=?, year_from=?, year_to=?, "
                        + "vin=?, damage_type=?, title_type=?, max_mileage=?, bid_min=?, bid_max=?, "
                        + "retail_min=?, retail_max=?, location=?, telegram_chat_id=?, enabled=? "
                        + "WHERE id=?",
                f.getName(), f.getMake(), f.getModel(), f.getYearFrom(), f.getYearTo(),
                f.getVin(), f.getDamageType(), f.getTitleType(), f.getMaxMileage(),
                f.getBidMin(), f.getBidMax(), f.getRetailMin(), f.getRetailMax(),
                f.getLocation(), f.getTelegramChatId(), f.isEnabled() ? 1 : 0, f.getId());
        return f;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM filters WHERE id = ?", id);
    }

    private static void bind(PreparedStatement ps, SearchFilter f) throws java.sql.SQLException {
        ps.setString(1, f.getName());
        ps.setString(2, f.getMake());
        ps.setString(3, f.getModel());
        setNullableInt(ps, 4, f.getYearFrom());
        setNullableInt(ps, 5, f.getYearTo());
        ps.setString(6, f.getVin());
        ps.setString(7, f.getDamageType());
        ps.setString(8, f.getTitleType());
        setNullableInt(ps, 9, f.getMaxMileage());
        setNullableDouble(ps, 10, f.getBidMin());
        setNullableDouble(ps, 11, f.getBidMax());
        setNullableDouble(ps, 12, f.getRetailMin());
        setNullableDouble(ps, 13, f.getRetailMax());
        ps.setString(14, f.getLocation());
        ps.setString(15, f.getTelegramChatId());
        ps.setInt(16, f.isEnabled() ? 1 : 0);
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.INTEGER); else ps.setInt(i, v);
    }

    private static void setNullableDouble(PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.DOUBLE); else ps.setDouble(i, v);
    }

    private static final RowMapper<SearchFilter> MAPPER = (rs, i) -> SearchFilter.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .make(rs.getString("brand"))
            .model(rs.getString("model"))
            .yearFrom(getNullableInt(rs, "year_from"))
            .yearTo(getNullableInt(rs, "year_to"))
            .vin(rs.getString("vin"))
            .damageType(rs.getString("damage_type"))
            .titleType(rs.getString("title_type"))
            .maxMileage(getNullableInt(rs, "max_mileage"))
            .bidMin(getNullableDouble(rs, "bid_min"))
            .bidMax(getNullableDouble(rs, "bid_max"))
            .retailMin(getNullableDouble(rs, "retail_min"))
            .retailMax(getNullableDouble(rs, "retail_max"))
            .location(rs.getString("location"))
            .telegramChatId(rs.getString("telegram_chat_id"))
            .enabled(rs.getInt("enabled") == 1)
            .build();

    private static Integer getNullableInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double getNullableDouble(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
