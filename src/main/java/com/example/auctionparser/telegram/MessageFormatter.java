package com.example.auctionparser.telegram;

import com.example.auctionparser.model.Lot;
import org.springframework.stereotype.Component;

/**
 * Builds the Telegram message text for a lot following the spec's template.
 * Missing fields are simply omitted.
 */
@Component
public class MessageFormatter {

    public String format(Lot lot) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚗 Новый лот\n\n");

        String title = join(" ", lot.getMake(), lot.getModel(), lot.getTrim());
        if (!title.isBlank()) {
            sb.append(title).append('\n');
        }
        if (lot.getYear() != null) {
            sb.append(lot.getYear()).append('\n');
        }
        sb.append('\n');

        line(sb, "Auction", lot.getAuction() != null ? lot.getAuction().getDisplayName() : null);
        line(sb, "Lot", lot.getLotId());
        line(sb, "VIN", lot.getVin());
        line(sb, "Mileage", lot.getMileage());
        line(sb, "Engine", lot.getEngine());
        line(sb, "Transmission", lot.getTransmission());
        line(sb, "Fuel", lot.getFuelType());
        line(sb, "Drive", lot.getDrive());
        line(sb, "Primary damage", lot.getPrimaryDamage());
        line(sb, "Secondary damage", lot.getSecondaryDamage());
        line(sb, "Run & Drive", lot.getRunAndDrive());
        line(sb, "Auction date", lot.getAuctionDate());
        line(sb, "Location", lot.getLocation());
        line(sb, "Retail", lot.getEstimatedRetailValue());
        line(sb, "Current bid", lot.getCurrentBid());
        line(sb, "Buy Now", lot.getBuyNow());
        line(sb, "Title", lot.getTitle());
        line(sb, "Seller", lot.getSeller());

        if (lot.getUrl() != null && !lot.getUrl().isBlank()) {
            sb.append("\nСсылка:\n").append(lot.getUrl());
        }
        return sb.toString().trim();
    }

    private void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(":\n").append(value).append('\n');
        }
    }

    private String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(sep);
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
