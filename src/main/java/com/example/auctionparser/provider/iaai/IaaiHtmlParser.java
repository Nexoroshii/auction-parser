package com.example.auctionparser.provider.iaai;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses IAAI's server-rendered search-results HTML into {@link Lot}s.
 *
 * <p>Each result card exposes data three ways, all handled here:
 * <ul>
 *   <li>the {@code ImageModalClicked(...)} handler on the "View All Images"
 *       button — salvage id, inventory id, VIN, branch, year, make, model,
 *       trim, run&amp;drive flag;</li>
 *   <li>the {@code AddDelWatch(...)} handler on the watch button — inventory id,
 *       branch, auction date, sale reference and sale type;</li>
 *   <li>{@code <span class="data-list__value" title="Label: Value">} spans —
 *       Stock #, Title/Sale Doc, Primary/Secondary Damage, Loss, Odometer, etc.</li>
 * </ul>
 * Missing fields are simply left null.
 */
@Component
public class IaaiHtmlParser {

    private static final String BASE = "https://www.iaai.com";
    private static final String IMAGE_RES =
            "https://vis.iaai.com/resizer?imageKeys=%s&width=1024&height=768";

    /** ImageModalClicked('salvageId','inv~US','VIN','branch','year','make','model','trim','runDrive') */
    private static final Pattern IMAGE_MODAL = Pattern.compile(
            "ImageModalClicked\\(([^)]*)\\)");
    /** AddDelWatch(this, 'inv~US', 'branch', 'auctionDate', 'saleRef', 'saleType') */
    private static final Pattern ADD_WATCH = Pattern.compile(
            "AddDelWatch\\(this,\\s*([^)]*)\\)");
    private static final Pattern IMAGE_KEY = Pattern.compile("imageKeys=([^&\"]+)");
    private static final Pattern ARG = Pattern.compile("'([^']*)'");

    private static final DateTimeFormatter OUT_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    /** Parses all result cards found in the given page. */
    public List<Lot> parse(Document doc) {
        List<Lot> lots = new ArrayList<>();
        for (Element card : doc.select("div.table-row-border:has(.js-intro-result-table)")) {
            Lot lot = parseCard(card);
            if (lot != null && lot.getLotId() != null) {
                lots.add(lot);
            }
        }
        return lots;
    }

    private Lot parseCard(Element card) {
        Lot.LotBuilder b = Lot.builder().auction(AuctionType.IAAI);
        String lotId = null;

        String[] modal = extractArgs(card.selectFirst("button.btn-allimages"), IMAGE_MODAL);
        if (modal.length >= 9) {
            lotId = nullIfBlank(modal[0]);                // salvage / stock number
            b.vin(nullIfBlank(modal[2]));
            b.year(parseInt(modal[4]));
            b.make(nullIfBlank(modal[5]));
            b.model(nullIfBlank(modal[6]));
            b.trim(nullIfBlank(modal[7]));
            b.runAndDrive("true".equalsIgnoreCase(modal[8]) ? "Yes" : "No");
        } else {
            // Fall back to heading "YEAR MAKE MODEL TRIM" if the modal was absent.
            applyHeading(b, textOf(card.selectFirst("h4.heading-7 a")));
        }

        String[] watch = extractArgs(card.selectFirst("a.btn-watch"), ADD_WATCH);
        if (watch.length >= 3) {
            b.auctionDate(formatDate(watch[2]));
            b.location(isBlank(watch[1]) ? null : "Branch " + watch[1]);
        }

        Element detail = card.selectFirst("a[href*=/VehicleDetail]");
        if (detail != null) {
            String href = detail.attr("href");
            b.url(href.startsWith("http") ? href : BASE + href);
        }

        // Labelled data spans (title="Label: Value").
        for (Element span : card.select("span.data-list__value[title]")) {
            String title = span.attr("title");
            int sep = title.indexOf(": ");
            if (sep < 0) {
                continue;
            }
            String label = title.substring(0, sep).trim();
            String value = title.substring(sep + 2).trim();
            if (value.isEmpty()) {
                continue;
            }
            switch (label) {
                case "Stock #" -> { if (isBlank(lotId)) lotId = value; }
                case "Title/Sale Doc" -> b.title(value);
                case "Primary Damage" -> b.primaryDamage(value);
                case "Secondary Damage" -> b.secondaryDamage(value);
                case "Loss" -> b.condition(value);
                case "Odometer" -> b.mileage(value);
                default -> { /* ignore unmapped labels */ }
            }
        }

        Element img = card.selectFirst("img[data-src*=vis.iaai.com]");
        if (img != null) {
            Matcher m = IMAGE_KEY.matcher(img.attr("data-src"));
            if (m.find()) {
                b.photoUrls(new ArrayList<>(List.of(String.format(IMAGE_RES, m.group(1)))));
            }
        }

        return b.lotId(lotId).build();
    }

    private void applyHeading(Lot.LotBuilder b, String heading) {
        if (heading == null || heading.isBlank()) {
            return;
        }
        String[] parts = heading.trim().split("\\s+", 4);
        if (parts.length > 0) b.year(parseInt(parts[0]));
        if (parts.length > 1) b.make(parts[1]);
        if (parts.length > 2) b.model(parts[2]);
        if (parts.length > 3) b.trim(parts[3]);
    }

    private static String textOf(Element el) {
        return el == null ? null : el.text();
    }

    /** Extracts the quoted arguments of an onclick handler matching {@code pattern}. */
    private String[] extractArgs(Element el, Pattern pattern) {
        if (el == null) {
            return new String[0];
        }
        Matcher m = pattern.matcher(el.attr("onclick"));
        if (!m.find()) {
            return new String[0];
        }
        Matcher arg = ARG.matcher(m.group(1));
        List<String> args = new ArrayList<>();
        while (arg.find()) {
            args.add(arg.group(1));
        }
        return args.toArray(new String[0]);
    }

    private String formatDate(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        // Example: "7/2/2026 8:30:00 AM +00:00"
        String cleaned = raw.replaceAll("\\s*[+-]\\d{2}:\\d{2}$", "").trim();
        for (String pattern : new String[]{"M/d/yyyy h:mm:ss a", "M/d/yyyy H:mm:ss"}) {
            try {
                LocalDateTime dt = LocalDateTime.parse(cleaned,
                        DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
                return dt.format(OUT_DATE);
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return raw;
    }

    private static Integer parseInt(String s) {
        try {
            return isBlank(s) ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullIfBlank(String s) {
        return isBlank(s) ? null : s;
    }
}
