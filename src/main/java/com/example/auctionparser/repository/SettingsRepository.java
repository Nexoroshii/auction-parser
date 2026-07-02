package com.example.auctionparser.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Generic key/value persistence used for app + Telegram settings. */
@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key, String defaultValue) {
        try {
            String value = jdbc.queryForObject(
                    "SELECT value FROM settings WHERE key = ?", String.class, key);
            return value != null ? value : defaultValue;
        } catch (EmptyResultDataAccessException e) {
            return defaultValue;
        }
    }

    /** Upsert. */
    public void put(String key, String value) {
        jdbc.update(
                "INSERT INTO settings (key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, value);
    }
}
