package com.example.auctionparser.ui;

import com.example.auctionparser.model.SearchFilter;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.Optional;

/**
 * Modal dialog for creating/editing a {@link SearchFilter}. Returns the built
 * filter (with id preserved when editing) via {@link #showAndWait()}.
 */
public class FilterDialog extends Dialog<SearchFilter> {

    private final TextField name = new TextField();
    private final TextField make = new TextField();
    private final TextField model = new TextField();
    private final TextField yearFrom = new TextField();
    private final TextField yearTo = new TextField();
    private final TextField vin = new TextField();
    private final TextField damageType = new TextField();
    private final TextField titleType = new TextField();
    private final TextField maxMileage = new TextField();
    private final TextField bidMin = new TextField();
    private final TextField bidMax = new TextField();
    private final TextField retailMin = new TextField();
    private final TextField retailMax = new TextField();
    private final TextField location = new TextField();
    private final TextField telegramChatId = new TextField();
    private final CheckBox enabled = new CheckBox("Активен");

    public FilterDialog(SearchFilter existing) {
        setTitle(existing == null ? "Новый фильтр" : "Редактировать фильтр");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));

        int r = 0;
        addRow(grid, r++, "Название", name);
        addRow(grid, r++, "Марка", make);
        addRow(grid, r++, "Модель", model);
        addRow(grid, r++, "Год от", yearFrom);
        addRow(grid, r++, "Год до", yearTo);
        addRow(grid, r++, "VIN (частичный)", vin);
        addRow(grid, r++, "Повреждение", damageType);
        addRow(grid, r++, "Title", titleType);
        addRow(grid, r++, "Макс. пробег", maxMileage);
        addRow(grid, r++, "Ставка от", bidMin);
        addRow(grid, r++, "Ставка до", bidMax);
        addRow(grid, r++, "Retail от", retailMin);
        addRow(grid, r++, "Retail до", retailMax);
        addRow(grid, r++, "Локация", location);
        addRow(grid, r++, "Telegram chat id", telegramChatId);
        grid.add(enabled, 1, r);

        enabled.setSelected(existing == null || existing.isEnabled());
        if (existing != null) {
            populate(existing);
        }
        getDialogPane().setContent(grid);

        setResultConverter(button -> button == ButtonType.OK ? build(existing) : null);
    }

    private void populate(SearchFilter f) {
        name.setText(nz(f.getName()));
        make.setText(nz(f.getMake()));
        model.setText(nz(f.getModel()));
        yearFrom.setText(nz(f.getYearFrom()));
        yearTo.setText(nz(f.getYearTo()));
        vin.setText(nz(f.getVin()));
        damageType.setText(nz(f.getDamageType()));
        titleType.setText(nz(f.getTitleType()));
        maxMileage.setText(nz(f.getMaxMileage()));
        bidMin.setText(nz(f.getBidMin()));
        bidMax.setText(nz(f.getBidMax()));
        retailMin.setText(nz(f.getRetailMin()));
        retailMax.setText(nz(f.getRetailMax()));
        location.setText(nz(f.getLocation()));
        telegramChatId.setText(nz(f.getTelegramChatId()));
    }

    private SearchFilter build(SearchFilter existing) {
        return SearchFilter.builder()
                .id(existing != null ? existing.getId() : null)
                .name(text(name))
                .make(text(make))
                .model(text(model))
                .yearFrom(parseInt(yearFrom))
                .yearTo(parseInt(yearTo))
                .vin(text(vin))
                .damageType(text(damageType))
                .titleType(text(titleType))
                .maxMileage(parseInt(maxMileage))
                .bidMin(parseDouble(bidMin))
                .bidMax(parseDouble(bidMax))
                .retailMin(parseDouble(retailMin))
                .retailMax(parseDouble(retailMax))
                .location(text(location))
                .telegramChatId(text(telegramChatId))
                .enabled(enabled.isSelected())
                .build();
    }

    public Optional<SearchFilter> prompt() {
        return showAndWait();
    }

    private static void addRow(GridPane grid, int row, String label, TextField field) {
        grid.add(new Label(label), 0, row);
        grid.add(field, 1, row);
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String text(TextField f) {
        String t = f.getText();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private static Integer parseInt(TextField f) {
        try {
            String t = text(f);
            return t == null ? null : Integer.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(TextField f) {
        try {
            String t = text(f);
            return t == null ? null : Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
