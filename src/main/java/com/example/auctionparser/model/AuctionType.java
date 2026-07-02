package com.example.auctionparser.model;

/**
 * Supported auction sources. Adding a new auction means adding an enum constant
 * here and a matching {@link com.example.auctionparser.provider.AuctionProvider}
 * implementation &mdash; no other code needs to change.
 */
public enum AuctionType {
    COPART("Copart"),
    IAAI("IAAI");

    private final String displayName;

    AuctionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
