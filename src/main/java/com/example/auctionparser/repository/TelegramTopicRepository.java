package com.example.auctionparser.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Caches the forum-topic id created for each (chat, make) pair. */
@Repository
public class TelegramTopicRepository {

    private final JdbcTemplate jdbc;

    public TelegramTopicRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The cached {@code message_thread_id} for this chat+make, or null if none yet. */
    public Integer find(String chatId, String make) {
        try {
            return jdbc.queryForObject(
                    "SELECT thread_id FROM telegram_topics WHERE chat_id = ? AND make = ?",
                    Integer.class, chatId, make);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** Upsert. */
    public void save(String chatId, String make, int threadId) {
        jdbc.update(
                "INSERT INTO telegram_topics (chat_id, make, thread_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT(chat_id, make) DO UPDATE SET thread_id = excluded.thread_id",
                chatId, make, threadId);
    }
}
