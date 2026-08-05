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
 * against the local database, sends genuinely new or relisted lots to
 * Telegram and records them. A lot whose auction date has changed since it
 * was last seen is treated as relisted (see {@link #isRelisted}) and is
 * delivered again rather than being silently dropped.
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
            Lot existing = lotRepository.findDetails(lot.getAuction(), lot.getLotId());
            boolean relisted = existing != null && isRelisted(existing, lot);
            if (existing != null && !relisted) {
                continue; // already seen previously with the same auction date — never resend
            }
            // Fetch the full photo gallery only now that we know the lot is new or
            // relisted, so we don't pay the per-lot image request for lots we skip.
            enrichPhotos(provider, lot);
            if (relisted) {
                lotRepository.updateRelisted(lot, Instant.now().toString());
                uiLog.info("Лот выставлен повторно: " + lot.getAuction().getDisplayName()
                        + " Lot " + lot.getLotId() + " (" + lot.getAuctionDate() + ")");
            } else {
                boolean inserted = lotRepository.insertIfNew(lot, Instant.now().toString(), false);
                if (!inserted) {
                    continue; // lost a race with another cycle
                }
            }
            // Count the lot as found regardless of whether delivery succeeds; a
            // failed Telegram send leaves sent=0 so a later cycle retries.
            newlyFound++;
            deliver(lot, filter, relisted);
        }
        return newlyFound;
    }

    /**
     * A lot is relisted (as opposed to a plain duplicate) when it was seen
     * before but now carries a different, non-null auction date — e.g. a
     * "no sale" lot re-offered at a later auction under the same lot number.
     */
    private boolean isRelisted(Lot existing, Lot incoming) {
        String oldDate = existing.getAuctionDate();
        String newDate = incoming.getAuctionDate();
        return oldDate != null && newDate != null && !oldDate.equals(newDate);
    }

    /** Enriches a lot's photos, isolating any provider failure. */
    private void enrichPhotos(AuctionProvider provider, Lot lot) {
        try {
            provider.enrichPhotos(lot);
        } catch (RuntimeException e) {
            uiLog.error("Не удалось загрузить фото лота " + lot.getLotId(), e);
        }
    }

    private boolean deliver(Lot lot, SearchFilter filter, boolean relisted) {
        try {
            boolean ok = notifier.sendLot(lot, filter.getTelegramChatId(), relisted);
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
