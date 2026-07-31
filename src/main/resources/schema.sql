-- SQLite schema for AuctionNotifier.
-- Executed on every startup via spring.sql.init (idempotent).

-- Discovered lots. The (lot_id, auction) uniqueness guarantees a lot row is
-- inserted at most once; this prevents duplicate Telegram notifications for
-- an unchanged lot. A lot relisted under a new auction date reuses this same
-- row (see LotRepository.updateRelisted) instead of being skipped.
CREATE TABLE IF NOT EXISTS lots (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    lot_id       TEXT    NOT NULL,
    auction      TEXT    NOT NULL,
    url          TEXT,
    date_found   TEXT    NOT NULL,          -- ISO-8601 timestamp
    sent         INTEGER NOT NULL DEFAULT 0, -- 0/1 boolean: delivered to Telegram
    details_json TEXT,                       -- full serialized Lot for history/export
    UNIQUE (lot_id, auction)
);

CREATE INDEX IF NOT EXISTS idx_lots_date_found ON lots (date_found);

-- Search filters. Basic fields (brand/model/year) plus the optional advanced
-- criteria from the spec. Nulls mean "no constraint".
CREATE TABLE IF NOT EXISTS filters (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT,
    brand            TEXT,
    model            TEXT,
    year_from        INTEGER,
    year_to          INTEGER,
    vin              TEXT,
    damage_type      TEXT,
    title_type       TEXT,
    max_mileage      INTEGER,
    bid_min          REAL,
    bid_max          REAL,
    retail_min       REAL,
    retail_max       REAL,
    location         TEXT,
    telegram_chat_id TEXT,                    -- optional per-filter override
    enabled          INTEGER NOT NULL DEFAULT 1
);

-- Generic key/value store for application + Telegram settings.
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT
);

-- Caches the forum-topic (message_thread_id) created for each make, per chat, so
-- a topic is created once via createForumTopic and reused afterwards.
CREATE TABLE IF NOT EXISTS telegram_topics (
    chat_id   TEXT    NOT NULL,
    make      TEXT    NOT NULL,
    thread_id INTEGER NOT NULL,
    PRIMARY KEY (chat_id, make)
);
