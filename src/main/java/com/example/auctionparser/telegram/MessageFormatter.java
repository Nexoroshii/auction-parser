package com.example.auctionparser.telegram;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import org.springframework.stereotype.Component;

/**
 * Builds the Telegram message text for a lot following the spec's template.
 * Missing fields are simply omitted.
 */
@Component
public class MessageFormatter {

    private static final String BID_CARS_LOT = "https://bid.cars/ru/lot/";

    public String format(Lot lot, boolean relisted) {
        StringBuilder sb = new StringBuilder();
        sb.append(relisted ? "🔁 Повторно выставлен\n\n" : "🚗 Новый лот\n\n");

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

        String link = bidCarsLink(lot);
        if (link != null) {
            sb.append("\nСсылка:\n").append(link);
        }
        return sb.toString().trim();
    }

    /**
     * Bid.cars lot link, e.g. {@code https://bid.cars/ru/lot/1-61349196/2024-BMW-X3-5UX53DP00R9V58285}
     * (prefix 1 = Copart, 0 = IAAI; slug = year-make-model-VIN, each part optional).
     * Falls back to the original auction URL when there is no lot number.
     * {@code Lot.url} itself is left untouched — IAAI uses it to fetch photos.
     */
    private String bidCarsLink(Lot lot) {
        String lotId = lot.getLotId();
        if (lotId == null || lotId.isBlank()) {
            return lot.getUrl() != null && !lot.getUrl().isBlank() ? lot.getUrl() : null;
        }
        String prefix = lot.getAuction() == AuctionType.IAAI ? "0" : "1";
        StringBuilder sb = new StringBuilder(BID_CARS_LOT)
                .append(prefix).append('-').append(lotId.trim().replaceAll("[^A-Za-z0-9]", ""));
        String slug = join("-", slugPart(lot.getYear()), slugPart(lot.getMake()),
                slugPart(lot.getModel()), slugPart(lot.getVin()));
        if (!slug.isBlank()) {
            sb.append('/').append(slug);
        }
        return sb.toString();
    }

    /** Collapses any run of non-alphanumerics into a single hyphen; null-safe. */
    private String slugPart(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().trim().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
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
