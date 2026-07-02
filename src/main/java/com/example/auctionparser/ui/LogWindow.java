package com.example.auctionparser.ui;

import com.example.auctionparser.service.UiLogService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.util.function.Consumer;

/**
 * Separate window showing the live monitoring log. Subscribes to
 * {@link UiLogService} and appends entries on the FX thread.
 */
public class LogWindow extends Stage {

    private final UiLogService uiLog;
    private final ObservableList<String> lines = FXCollections.observableArrayList();
    private final Consumer<String> listener;

    public LogWindow(UiLogService uiLog) {
        this.uiLog = uiLog;
        setTitle("Логи");

        ListView<String> list = new ListView<>(lines);
        lines.addAll(uiLog.backlog());
        scrollToEnd(list);

        this.listener = entry -> Platform.runLater(() -> {
            lines.add(entry);
            if (lines.size() > 2000) {
                lines.remove(0);
            }
            scrollToEnd(list);
        });
        uiLog.addListener(listener);

        // Detach the listener when the window is closed to avoid leaks.
        setOnHidden(e -> uiLog.removeListener(listener));

        setScene(new Scene(list, 640, 480));
    }

    private void scrollToEnd(ListView<String> list) {
        if (!lines.isEmpty()) {
            list.scrollTo(lines.size() - 1);
        }
    }
}
