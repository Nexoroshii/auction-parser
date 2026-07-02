-- SQLite schema for AuctionNotifier.
-- Executed on every startup via spring.sql.init (idempotent).

-- Discovered lots. The (lot_id, auction) uniqueness guarantees a lot is never
-- processed twice, which is what prevents duplicate Telegram notifications.
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
