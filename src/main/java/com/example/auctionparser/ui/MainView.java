package com.example.auctionparser.ui;

import com.example.auctionparser.model.AuctionType;
import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.provider.copart.CopartBrowserManager;
import com.example.auctionparser.repository.LotRepository;
import com.example.auctionparser.scheduler.MonitoringScheduler;
import com.example.auctionparser.service.ExportService;
import com.example.auctionparser.service.FilterService;
import com.example.auctionparser.service.MonitoringStatus;
import com.example.auctionparser.service.SettingsService;
import com.example.auctionparser.service.TelegramNotifier;
import com.example.auctionparser.service.UiLogService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Main application window: filter list + CRUD, monitoring controls, live status
 * panel and access to logs/history/settings. Built programmatically so all
 * wiring is compile-checked. Spring beans are pulled from the context; the view
 * itself is intentionally not a Spring bean (keeps JavaFX out of the bean graph).
 */
public class MainView {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ConfigurableApplicationContext context;
    private final Stage stage;

    private final FilterService filterService;
    private final MonitoringScheduler scheduler;
    private final MonitoringStatus status;
    private final UiLogService uiLog;
    private final SettingsService settingsService;
    private final TelegramNotifier telegramNotifier;
    private final LotRepository lotRepository;
    private final ExportService exportService;
    private final CopartBrowserManager copartBrowser;

    private final ObservableList<SearchFilter> filters = FXCollections.observableArrayList();
    private final ListView<SearchFilter> filterList = new ListView<>(filters);

    private final Label lastCheckLabel = new Label("—");
    private final Label nextCheckLabel = new Label("—");
    private final Label checkedLabel = new Label("—");
    private final Label foundTodayLabel = new Label("0");
    private final Button monitorButton = new Button("Начать мониторинг");

    private final Runnable statusListener = () -> Platform.runLater(this::refreshStatus);
    private TrayIcon trayIcon;

    public MainView(ConfigurableApplicationContext context, Stage stage) {
        this.context = context;
        this.stage = stage;
        this.filterService = context.getBean(FilterService.class);
        this.scheduler = context.getBean(MonitoringScheduler.class);
        this.status = context.getBean(MonitoringStatus.class);
        this.uiLog = context.getBean(UiLogService.class);
        this.settingsService = context.getBean(SettingsService.class);
        this.telegramNotifier = context.getBean(TelegramNotifier.class);
        this.lotRepository = context.getBean(LotRepository.class);
        this.exportService = context.getBean(ExportService.class);
        this.copartBrowser = context.getBean(CopartBrowserManager.class);
    }

    public void show() {
        stage.setTitle("AuctionNotifier");
        stage.setScene(new Scene(buildRoot(), 820, 560));

        loadFilters();
        status.addListener(statusListener);
        startCountdown();
        installTray();
        handleWindowClose();
        refreshStatus();

        // Start monitoring automatically on launch (spec: runs continuously).
        scheduler.start();

        stage.show();
    }

    private BorderPane buildRoot() {
        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        root.setLeft(buildFilterPane());
        root.setCenter(buildStatusPane());
        return root;
    }

    private ToolBar buildToolbar() {
        Button settingsButton = new Button("Настройки Telegram");
        settingsButton.setOnAction(e -> openSettings());
        Button logsButton = new Button("Логи");
        logsButton.setOnAction(e -> new LogWindow(uiLog).show());
        Button historyButton = new Button("История");
        historyButton.setOnAction(e -> new HistoryWindow(lotRepository, exportService).show());
        return new ToolBar(settingsButton, logsButton, historyButton);
    }

    private VBox buildFilterPane() {
        filterList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SearchFilter item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayLabel());
            }
        });
        filterList.setPrefWidth(280);

        Button add = new Button("Добавить");
        add.setOnAction(e -> addFilter());
        Button edit = new Button("Редактировать");
        edit.setOnAction(e -> editFilter());
        Button delete = new Button("Удалить");
        delete.setOnAction(e -> deleteFilter());
        HBox crud = new HBox(6, add, edit, delete);

        monitorButton.setMaxWidth(Double.MAX_VALUE);
        monitorButton.setOnAction(e -> toggleMonitoring());
        Button checkNow = new Button("Проверить сейчас");
        checkNow.setMaxWidth(Double.MAX_VALUE);
        checkNow.setOnAction(e -> scheduler.triggerNow());

        VBox box = new VBox(8,
                new Label("Фильтры"), filterList, crud,
                new Label(), monitorButton, checkNow);
        box.setPadding(new Insets(10));
        return box;
    }

    private GridPane buildStatusPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int r = 0;
        grid.add(new Label("Последняя проверка:"), 0, r);
        grid.add(lastCheckLabel, 1, r++);
        grid.add(new Label("Следующая через:"), 0, r);
        grid.add(nextCheckLabel, 1, r++);
        grid.add(new Label("Проверено:"), 0, r);
        grid.add(checkedLabel, 1, r++);
        grid.add(new Label("Найдено сегодня:"), 0, r);
        grid.add(foundTodayLabel, 1, r);
        return grid;
    }

    // --- filter CRUD ---

    private void loadFilters() {
        filters.setAll(filterService.findAll());
    }

    private void addFilter() {
        new FilterDialog(null).prompt().ifPresent(f -> {
            filterService.save(f);
            loadFilters();
        });
    }

    private void editFilter() {
        SearchFilter selected = filterList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        new FilterDialog(selected).prompt().ifPresent(f -> {
            filterService.save(f);
            loadFilters();
        });
    }

    private void deleteFilter() {
        SearchFilter selected = filterList.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getId() != null) {
            filterService.delete(selected.getId());
            loadFilters();
        }
    }

    // --- monitoring controls ---

    private void toggleMonitoring() {
        if (scheduler.isRunning()) {
            scheduler.stop();
        } else {
            scheduler.start();
        }
        refreshStatus();
    }

    private void openSettings() {
        new SettingsDialog(settingsService, telegramNotifier, copartBrowser).showAndWait();
        // Apply a possibly-changed interval to a running scheduler.
        scheduler.updateInterval(settingsService.getAppSettings().getIntervalMinutes());
    }

    // --- status rendering ---

    private void startCountdown() {
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateCountdown()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void refreshStatus() {
        MonitoringStatus.Snapshot s = status.snapshot();
        lastCheckLabel.setText(s.lastCheck() == null ? "—" : TIME.format(s.lastCheck()));
        foundTodayLabel.setText(String.valueOf(s.foundToday()));
        checkedLabel.setText(s.checkedAuctions().isEmpty() ? "—"
                : s.checkedAuctions().stream().map(AuctionType::getDisplayName)
                    .collect(Collectors.joining(", ")));
        monitorButton.setText(s.running() ? "Остановить мониторинг" : "Начать мониторинг");
        updateCountdown();
    }

    private void updateCountdown() {
        MonitoringStatus.Snapshot s = status.snapshot();
        if (!s.running() || s.nextCheck() == null) {
            nextCheckLabel.setText("—");
            return;
        }
        long seconds = Math.max(0, s.nextCheck().getEpochSecond() - Instant.now().getEpochSecond());
        nextCheckLabel.setText(String.format("%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60));
    }

    // --- tray & shutdown ---

    private void handleWindowClose() {
        stage.setOnCloseRequest(e -> {
            if (settingsService.getAppSettings().isMinimizeToTray() && trayIcon != null) {
                e.consume();
                stage.hide();
            } else {
                shutdown();
            }
        });
    }

    private void installTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            java.awt.MenuItem open = new java.awt.MenuItem("Открыть");
            open.addActionListener(a -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));
            java.awt.MenuItem exit = new java.awt.MenuItem("Выход");
            exit.addActionListener(a -> Platform.runLater(this::shutdown));
            menu.add(open);
            menu.add(exit);

            trayIcon = new TrayIcon(trayImage(), "AuctionNotifier", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(a -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ex) {
            uiLog.error("Не удалось создать иконку в трее", ex);
            trayIcon = null;
        }
    }

    private BufferedImage trayImage() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x2E7D32));
        g.fillRoundRect(0, 0, 16, 16, 4, 4);
        g.dispose();
        return img;
    }

    private void shutdown() {
        status.removeListener(statusListener);
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        Platform.setImplicitExit(true);
        stage.close();
        context.close();
        Platform.exit();
    }
}
