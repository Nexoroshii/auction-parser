package com.example.auctionparser.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Parses the free-form {@code auctionDate} strings the parsers produce and tells
 * whether a lot's auction is imminent (today or tomorrow).
 *
 * <p>Copart renders {@code yyyy-MM-dd[ HH:mm:ss]} and IAAI {@code dd MMM yyyy};
 * both are handled. An unparseable or missing date is treated as "not imminent"
 * so the today/tomorrow constraint is applied strictly.
 */
public final class AuctionDates {

    private static final DateTimeFormatter IAAI_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private AuctionDates() {
    }

    /** True if the given auction-date string is today or tomorrow in {@code zone}. */
    public static boolean isTodayOrTomorrow(String auctionDate, ZoneId zone) {
        LocalDate date = parse(auctionDate);
        if (date == null) {
            return false;
        }
        LocalDate today = LocalDate.now(zone);
        return date.equals(today) || date.equals(today.plusDays(1));
    }

    /** Best-effort parse of the date portion; null when unrecognised. */
    static LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        // Copart: ISO date, optionally followed by a time -> take the date part.
        if (s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (RuntimeException ignored) {
                // not ISO — fall through
            }
        }
        // IAAI: "07 Jul 2026".
        try {
            return LocalDate.parse(s, IAAI_DATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
