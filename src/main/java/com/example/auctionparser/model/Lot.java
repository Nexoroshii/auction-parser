package com.example.auctionparser.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A single auction lot with every field the spec asks us to extract. All fields
 * are optional: a parser fills in whatever the source exposes and leaves the
 * rest {@code null}/empty. The message formatter simply omits missing fields.
 *
 * <p>The identity of a lot is {@code (auction, lotId)} &mdash; see
 * {@link com.example.auctionparser.repository.LotRepository} for deduplication.
 *
 * <p>{@code @NoArgsConstructor} is required alongside {@code @Builder} so
 * Jackson has a default constructor to deserialize {@code details_json} back
 * into a {@code Lot} (Lombok does not emit one implicitly once a builder
 * constructor exists).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Lot {

    /** Auction-native lot identifier (e.g. Copart lot number). Required. */
    private String lotId;

    /** Source auction. Required. */
    private AuctionType auction;

    private String url;
    private String vin;
    private String make;
    private String model;
    private String trim;
    private Integer year;
    private String color;
    private String mileage;
    private String mileageType;      // Actual / Not Actual
    private String engine;
    private String engineSize;
    private String fuelType;
    private String transmission;
    private String drive;
    private String bodyStyle;
    private String keys;             // Yes / No
    private String primaryDamage;
    private String secondaryDamage;
    private String condition;
    private String runAndDrive;      // Yes / No / Enhanced ...
    private String auctionDate;
    private String location;
    private String seller;
    private String estimatedRetailValue;
    private String repairCost;
    private String currentBid;
    private String buyNow;
    private String title;
    private String ownersCount;

    @Builder.Default
    private List<String> photoUrls = new ArrayList<>();

    private String videoUrl;
}
