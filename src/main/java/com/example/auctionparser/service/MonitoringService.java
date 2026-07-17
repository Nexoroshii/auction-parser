package com.example.auctionparser.service;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.Lot;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.AuctionProvider;
import com.example.auctionparser.repository.LotRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The core monitoring pipeline. One {@link #runCycle()} invocation:
 * for every enabled filter and every ready provider, searches, deduplicates
 * against the local database, sends genuinely new lots to Telegram and records
 * them so they are never sent again.
 *
 * <p>Per-provider and per-lot failures are isolated and logged so a single bad
 * source or a single Telegram error cannot abort the whole cycle.
 */
@Service
public class MonitoringService {

    private final List<AuctionProvider> providers;
    private final FilterService filterService;
    private final LotRepository lotRepository;
    private final TelegramNotifier notifier;
    private final UiLogService uiLog;
    private final MonitoringStatus status;

    public MonitoringService(List<AuctionProvider> providers,
                             FilterService filterService,
                             LotRepository lotRepository,
                             TelegramNotifier notifier,
                             UiLogService uiLog,
                             MonitoringStatus status) {
        this.providers = providers;
        this.filterService = filterService;
        this.lotRepository = lotRepository;
        this.notifier = notifier;
        this.uiLog = uiLog;
        this.status = status;
    }

    /** Runs a single monitoring cycle across all providers and filters. */
    public synchronized void runCycle() {
        List<SearchFilter> filters = filterService.findEnabled();
        if (filters.isEmpty()) {
            uiLog.info("Нет активных фильтров — проверка пропущена");
        }

        for (AuctionProvider provider : providers) {
            AuctionType type = provider.getType();
            if (!provider.isReady()) {
                uiLog.info("Проверка " + type.getDisplayName() + " пропущена (парсер не готов)");
                continue;
            }
            uiLog.info("Проверка " + type.getDisplayName());
            int newForAuction = 0;
            for (SearchFilter filter : filters) {
                newForAuction += processFilter(provider, filter);
            }
            uiLog.info("Найдено " + newForAuction + " новых лотов ("
                    + type.getDisplayName() + ")");
            status.markAuctionChecked(type);
        }

        refreshFoundToday();
    }

    /** @return the number of newly discovered (first-seen) lots for this filter. */
    private int processFilter(AuctionProvider provider, SearchFilter filter) {
        int newlyFound = 0;
        List<Lot> lots;
        try {
            lots = provider.search(filter);
        } catch (Exception e) {
            uiLog.error("Ошибка поиска в " + provider.getType().getDisplayName()
                    + " по фильтру '" + filter.displayLabel() + "'", e);
            return 0;
        }

        for (Lot lot : lots) {
            if (lot.getLotId() == null || lot.getAuction() == null) {
                continue;
            }
            if (!provider.matches(lot, filter)) {
                continue;
            }
            if (lotRepository.exists(lot.getAuction(), lot.getLotId())) {
                continue; // already seen previously — never resend
            }
            // Fetch the full photo gallery only now that we know the lot is new, so
            // we don't pay the per-lot image request for lots we've already sent.
            enrichPhotos(provider, lot);
            boolean inserted = lotRepository.insertIfNew(lot, Instant.now().toString(), false);
            if (!inserted) {
                continue; // lost a race with another cycle
            }
            // Count the lot as found regardless of whether delivery succeeds; a
            // failed Telegram send leaves sent=0 so a later cycle retries.
            newlyFound++;
            deliver(lot, filter);
        }
        return newlyFound;
    }

    /** Enriches a lot's photos, isolating any provider failure. */
    private void enrichPhotos(AuctionProvider provider, Lot lot) {
        try {
            provider.enrichPhotos(lot);
        } catch (RuntimeException e) {
            uiLog.error("Не удалось загрузить фото лота " + lot.getLotId(), e);
        }
    }

    private boolean deliver(Lot lot, SearchFilter filter) {
        try {
            boolean ok = notifier.sendLot(lot, filter.getTelegramChatId());
            if (ok) {
                lotRepository.markSent(lot.getAuction(), lot.getLotId());
                uiLog.info("Отправлено в Telegram: " + lot.getAuction().getDisplayName()
                        + " Lot " + lot.getLotId());
                return true;
            }
            uiLog.info("Не удалось отправить лот " + lot.getLotId() + " (будет повторно отправлен)");
            return false;
        } catch (Exception e) {
            // Left with sent=0 so a future cycle can retry delivery.
            uiLog.error("Ошибка отправки лота " + lot.getLotId() + " в Telegram", e);
            return false;
        }
    }

    private void refreshFoundToday() {
        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        status.setFoundToday(lotRepository.countFoundSince(startOfDay.toString()));
    }
}
