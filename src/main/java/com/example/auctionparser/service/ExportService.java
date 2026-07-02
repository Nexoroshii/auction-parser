package com.example.auctionparser.service;

import com.example.auctionparser.repository.LotRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports the discovered-lot history to CSV. */
@Service
public class ExportService {

    private final LotRepository lotRepository;

    public ExportService(LotRepository lotRepository) {
        this.lotRepository = lotRepository;
    }

    /** Writes the full history to a UTF-8 CSV file. Returns the row count. */
    public int exportHistoryCsv(Path target) throws IOException {
        List<LotRepository.HistoryRow> rows = lotRepository.findAllHistory();
        try (Writer w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write("lot_id,auction,url,date_found,sent\n");
            for (LotRepository.HistoryRow r : rows) {
                w.write(csv(r.lotId()));
                w.write(',');
                w.write(csv(r.auction().name()));
                w.write(',');
                w.write(csv(r.url()));
                w.write(',');
                w.write(csv(r.dateFound()));
                w.write(',');
                w.write(r.sent() ? "1" : "0");
                w.write('\n');
            }
        }
        return rows.size();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
