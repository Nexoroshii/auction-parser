package com.example.auctionparser.provider.iaai;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link IaaiHtmlParser} using a card snippet that mirrors IAAI's
 * real search-result markup (handlers, labelled spans, image keys).
 */
class IaaiHtmlParserTest {

    // Faithful reproduction of a single IAAI result card (values from a live page).
    private static final String CARD = """
            <div class="table-body">
             <div class="table-row table-row-border">
              <div class="table-row-inner js-intro-result-table">
               <a href="JavaScript:void(0)" class="btn-watch 45536450 46034719 "
                  onclick="AddDelWatch(this, '46034719~US', '736','7/2/2026 8:30:00 AM +00:00','180981_3','Prebid'); return true;"></a>
               <a href="/VehicleDetail/46034719~US">
                 <img data-src="https://vis.iaai.com/resizer?imageKeys=46034719~SID~I1&amp;width=400&amp;height=300"></a>
               <button class="btn btn-allimages"
                  onclick="ImageModalClicked('45536450', '46034719~US', '1C3BCBFG6EN******', '736', '2014', 'CHRYSLER', '200', 'LIMITED', 'false');">View All Images</button>
               <h4 class="heading-7"><a href="/VehicleDetail/46034719~US">2014 CHRYSLER 200 LIMITED</a></h4>
               <ul class="data-list data-list--search">
                 <li><span class="data-list__value" title="Stock #: 45536450">45536450</span></li>
                 <li><span class="data-list__value" title="Title/Sale Doc: Clear">Clear</span></li>
                 <li><span class="data-list__value" title="Primary Damage: Mechanical">Mechanical</span></li>
                 <li><span class="data-list__value" title="Secondary Damage: Left &amp; Right Side">Left &amp; Right Side</span></li>
                 <li><span class="data-list__value" title="Loss: Other">Other</span></li>
                 <li><span class="data-list__value" title="Odometer: 129,141 mi">129,141 mi</span></li>
               </ul>
              </div>
             </div>
            </div>
            """;

    private final IaaiHtmlParser parser = new IaaiHtmlParser();

    @Test
    void extractsAllCardFields() {
        Document doc = Jsoup.parse(CARD, "https://www.iaai.com/Search");
        List<Lot> lots = parser.parse(doc);

        assertEquals(1, lots.size());
        Lot lot = lots.get(0);

        assertEquals(AuctionType.IAAI, lot.getAuction());
        assertEquals("45536450", lot.getLotId());
        assertEquals("1C3BCBFG6EN******", lot.getVin());
        assertEquals(2014, lot.getYear());
        assertEquals("CHRYSLER", lot.getMake());
        assertEquals("200", lot.getModel());
        assertEquals("LIMITED", lot.getTrim());
        assertEquals("No", lot.getRunAndDrive());
        assertEquals("Clear", lot.getTitle());
        assertEquals("Mechanical", lot.getPrimaryDamage());
        assertEquals("Left & Right Side", lot.getSecondaryDamage());
        assertEquals("Other", lot.getCondition());
        assertEquals("129,141 mi", lot.getMileage());
        assertEquals("02 Jul 2026", lot.getAuctionDate());
        assertEquals("Branch 736", lot.getLocation());
        assertEquals("https://www.iaai.com/VehicleDetail/46034719~US", lot.getUrl());
        assertEquals(1, lot.getPhotoUrls().size());
        assertTrue(lot.getPhotoUrls().get(0).contains("imageKeys=46034719~SID~I1"));
    }

    @Test
    void emptyPageYieldsNoLots() {
        Document doc = Jsoup.parse("<html><body><div>no results</div></body></html>",
                "https://www.iaai.com/Search");
        assertTrue(parser.parse(doc).isEmpty());
    }
}
