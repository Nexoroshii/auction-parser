package com.example.auctionparser.provider;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Synthetic provider used only to exercise the end-to-end pipeline
 * (dedup &rarr; persist &rarr; Telegram) while the real parsers are stubs.
 * Enabled via {@code app.monitoring.demo-provider-enabled=true}.
 *
 * <p>Emits one lot per cycle whose id is the current epoch-minute so each cycle
 * yields a genuinely "new" lot the first time and a duplicate if a cycle repeats
 * within the same minute &mdash; enough to observe dedup and delivery working.
 */
@Component
@ConditionalOnProperty(prefix = "app.monitoring", name = "demo-provider-enabled", havingValue = "true")
public class DemoAuctionProvider implements AuctionProvider {

    @Override
    public AuctionType getType() {
        return AuctionType.COPART; // masquerades as Copart for realism
    }

    @Override
    public List<Lot> search(SearchFilter filter) {
        long minute = System.currentTimeMillis() / 60_000L;
        String lotId = "DEMO-" + minute;
        Lot lot = Lot.builder()
                .lotId(lotId)
                .auction(AuctionType.COPART)
                .url("https://www.copart.com/lot/" + lotId)
                .make(filter.getMake() != null ? filter.getMake() : "BMW")
                .model(filter.getModel() != null ? filter.getModel() : "X3")
                .trim("xDrive30i")
                .year(2022)
                .vin("5UX" + minute)
                .mileage("24,112 mi")
                .mileageType("Actual")
                .engine("2.0L Turbo")
                .transmission("Automatic")
                .fuelType("Gasoline")
                .drive("AWD")
                .primaryDamage("Front End")
                .secondaryDamage("Minor Dent")
                .runAndDrive("Yes")
                .auctionDate("12 Jul 2026")
                .location("Houston TX")
                .estimatedRetailValue("$31,500")
                .currentBid("$12,700")
                .title("Clean")
                .seller("GEICO")
                .photoUrls(List.of(
                        "https://picsum.photos/seed/" + minute + "a/800/600",
                        "https://picsum.photos/seed/" + minute + "b/800/600"))
                .build();
        return List.of(lot);
    }
}
