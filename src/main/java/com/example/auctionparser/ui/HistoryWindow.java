package com.example.auctionparser.ui;

import com.example.auctionparser.repository.LotRepository;
import com.example.auctionparser.service.ExportService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;

/**
 * History of discovered lots. Lets the user reopen a lot's card in the browser
 * and export the history to CSV.
 */
public class HistoryWindow extends Stage {

    private final LotRepository lotRepository;
    private final ExportService exportService;
    private final TableView<LotRepository.HistoryRow> table = new TableView<>();

    public HistoryWindow(LotRepository lotRepository, ExportService exportService) {
        this.lotRepository = lotRepository;
        this.exportService = exportService;
        setTitle("История лотов");

        // HistoryRow is a record, so its accessors are lotId()/dateFound()/url(),
        // not JavaBean getters — PropertyValueFactory can't read those. Use
        // explicit lambdas (as the Auction column already does).
        TableColumn<LotRepository.HistoryRow, String> lotCol = new TableColumn<>("Lot");
        lotCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().lotId()));
        TableColumn<LotRepository.HistoryRow, String> auctionCol = new TableColumn<>("Auction");
        auctionCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().auction().getDisplayName()));
        TableColumn<LotRepository.HistoryRow, String> dateCol = new TableColumn<>("Найден");
        dateCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().dateFound()));
        TableColumn<LotRepository.HistoryRow, String> urlCol = new TableColumn<>("URL");
        urlCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().url()));
        urlCol.setPrefWidth(280);
        table.getColumns().addAll(lotCol, auctionCol, dateCol, urlCol);

        Button openButton = new Button("Открыть карточку");
        openButton.setOnAction(e -> openSelected());
        Button exportButton = new Button("Экспорт CSV");
        exportButton.setOnAction(e -> export());
        Button refreshButton = new Button("Обновить");
        refreshButton.setOnAction(e -> reload());

        BorderPane root = new BorderPane(table);
        root.setTop(new ToolBar(openButton, exportButton, refreshButton));
        BorderPane.setMargin(table, new Insets(6));
        setScene(new Scene(root, 760, 480));

        reload();
    }

    private void reload() {
        table.setItems(FXCollections.observableArrayList(lotRepository.findAllHistory()));
    }

    private void openSelected() {
        LotRepository.HistoryRow row = table.getSelectionModel().getSelectedItem();
        if (row == null || row.url() == null || row.url().isBlank()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(row.url()));
            }
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Не удалось открыть браузер: " + ex.getMessage()).show();
        }
    }

    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("lots.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(this);
        if (file == null) {
            return;
        }
        try {
            int count = exportService.exportHistoryCsv(Path.of(file.getAbsolutePath()));
            new Alert(Alert.AlertType.INFORMATION, "Экспортировано строк: " + count).show();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Ошибка экспорта: " + ex.getMessage()).show();
        }
    }
}
