package com.example.auctionparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user-defined search filter. Basic criteria (make/model/year range) plus the
 * optional advanced criteria from the spec. A {@code null} field means "no
 * constraint on this attribute".
 *
 * <p>Providers translate whichever fields they support into their site's query;
 * fields a provider cannot express server-side may be applied as a client-side
 * post-filter (see {@link com.example.auctionparser.provider.AuctionProvider}).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SearchFilter {

    private Long id;

    /** Optional human-friendly label; falls back to "make model" for display. */
    private String name;

    private String make;
    private String model;
    private Integer yearFrom;
    private Integer yearTo;

    // --- optional advanced criteria ---
    private String vin;
    private String damageType;
    private String titleType;
    private Integer maxMileage;
    private Double bidMin;
    private Double bidMax;
    private Double retailMin;
    private Double retailMax;
    private String location;

    /** Optional per-filter Telegram chat override; null => default chat. */
    private String telegramChatId;

    @Builder.Default
    private boolean enabled = true;

    /** Label used in the UI list. */
    public String displayLabel() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        if (make != null) sb.append(make);
        if (model != null) sb.append(sb.isEmpty() ? "" : " ").append(model);
        if (yearFrom != null || yearTo != null) {
            sb.append(" (")
              .append(yearFrom != null ? yearFrom : "…")
              .append("–")
              .append(yearTo != null ? yearTo : "…")
              .append(")");
        }
        return sb.isEmpty() ? "Filter #" + id : sb.toString();
    }
}
