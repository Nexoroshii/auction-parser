package com.example.auctionparser.ui;

import com.example.auctionparser.model.AppSettings;
import com.example.auctionparser.model.CopartCredentials;
import com.example.auctionparser.model.TelegramSettings;
import com.example.auctionparser.provider.copart.CopartBrowserManager;
import com.example.auctionparser.service.SettingsService;
import com.example.auctionparser.service.TelegramNotifier;
import com.example.auctionparser.telegram.TelegramClient;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Settings dialog: Telegram credentials, monitoring interval, media options and
 * OS-integration toggles. Persists via {@link SettingsService} on OK and offers
 * a non-blocking "test connection" against the Telegram Bot API.
 */
public class SettingsDialog extends Dialog<Boolean> {

    private final SettingsService settingsService;
    private final TelegramNotifier notifier;
    private final CopartBrowserManager copartBrowser;

    private final PasswordField botToken = new PasswordField();
    private final TextField chatId = new TextField();
    private final TextField interval = new TextField();
    private final CheckBox launchOnStartup = new CheckBox("Запуск вместе с Windows");
    private final CheckBox minimizeToTray = new CheckBox("Сворачивать в трей при закрытии");
    private final CheckBox sendPhotos = new CheckBox("Отправлять фото");
    private final CheckBox sendVideo = new CheckBox("Отправлять видео");
    private final CheckBox topicsEnabled = new CheckBox("Разделять лоты по темам (по маркам)");
    private final Label testResult = new Label();

    private final TextField copartUsername = new TextField();
    private final PasswordField copartPassword = new PasswordField();
    private final Label copartTestResult = new Label();

    public SettingsDialog(SettingsService settingsService, TelegramNotifier notifier,
                          CopartBrowserManager copartBrowser) {
        this.settingsService = settingsService;
        this.notifier = notifier;
        this.copartBrowser = copartBrowser;

        setTitle("Настройки");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TelegramSettings tg = settingsService.getTelegramSettings();
        AppSettings app = settingsService.getAppSettings();
        botToken.setText(tg.getBotToken());
        chatId.setText(tg.getChatId());
        interval.setText(String.valueOf(app.getIntervalMinutes()));
        launchOnStartup.setSelected(app.isLaunchOnStartup());
        minimizeToTray.setSelected(app.isMinimizeToTray());
        sendPhotos.setSelected(app.isSendPhotos());
        sendVideo.setSelected(app.isSendVideo());
        topicsEnabled.setSelected(app.isTopicsEnabled());

        CopartCredentials copart = settingsService.getCopartCredentials();
        copartUsername.setText(copart.getUsername());
        copartPassword.setText(copart.getPassword());

        Button testButton = new Button("Проверить подключение");
        testButton.setOnAction(e -> runConnectionTest());
        Button copartTestButton = new Button("Проверить вход в Copart");
        copartTestButton.setOnAction(e -> runCopartLoginTest());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        int r = 0;
        grid.add(new Label("Bot Token"), 0, r);
        grid.add(botToken, 1, r++);
        grid.add(new Label("Chat ID"), 0, r);
        grid.add(chatId, 1, r++);
        grid.add(testButton, 1, r++);
        grid.add(testResult, 1, r++);
        grid.add(new Label("Интервал (мин)"), 0, r);
        grid.add(interval, 1, r++);
        grid.add(launchOnStartup, 1, r++);
        grid.add(minimizeToTray, 1, r++);
        grid.add(sendPhotos, 1, r++);
        grid.add(sendVideo, 1, r++);
        grid.add(topicsEnabled, 1, r++);
        grid.add(new Separator(), 0, r++, 2, 1);
        grid.add(new Label("Copart логин"), 0, r);
        grid.add(copartUsername, 1, r++);
        grid.add(new Label("Copart пароль"), 0, r);
        grid.add(copartPassword, 1, r++);
        grid.add(copartTestButton, 1, r++);
        grid.add(copartTestResult, 1, r);

        getDialogPane().setContent(grid);
        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                save();
                return true;
            }
            return false;
        });
    }

    private void runConnectionTest() {
        testResult.setText("Проверка…");
        String token = botToken.getText();
        Task<TelegramClient.ApiResult> task = new Task<>() {
            @Override
            protected TelegramClient.ApiResult call() throws Exception {
                return notifier.testConnection(token);
            }
        };
        task.setOnSucceeded(e -> {
            TelegramClient.ApiResult result = task.getValue();
            testResult.setText(result.ok()
                    ? "✅ Подключение успешно"
                    : "❌ Ошибка: " + (result.description() != null ? result.description() : result.statusCode()));
        });
        task.setOnFailed(e -> testResult.setText("❌ " + task.getException().getMessage()));
        Thread t = new Thread(task, "telegram-test");
        t.setDaemon(true);
        t.start();
    }

    private void runCopartLoginTest() {
        copartTestResult.setText("Запуск браузера и вход… (может занять минуту)");
        CopartCredentials credentials = CopartCredentials.builder()
                .username(copartUsername.getText())
                .password(copartPassword.getText())
                .build();
        // Persist first so the browser session is associated with these creds.
        settingsService.saveCopartCredentials(credentials);
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return copartBrowser.ensureLoggedIn(credentials);
            }
        };
        task.setOnSucceeded(e -> copartTestResult.setText(
                Boolean.TRUE.equals(task.getValue()) ? "✅ Вход в Copart выполнен" : "❌ Не удалось войти"));
        task.setOnFailed(e -> copartTestResult.setText("❌ " + task.getException().getMessage()));
        Thread t = new Thread(task, "copart-login-test");
        t.setDaemon(true);
        t.start();
    }

    private void save() {
        settingsService.saveTelegramSettings(TelegramSettings.builder()
                .botToken(botToken.getText())
                .chatId(chatId.getText())
                .build());
        settingsService.saveCopartCredentials(CopartCredentials.builder()
                .username(copartUsername.getText())
                .password(copartPassword.getText())
                .build());
        settingsService.saveAppSettings(AppSettings.builder()
                .intervalMinutes(parseInterval())
                .launchOnStartup(launchOnStartup.isSelected())
                .minimizeToTray(minimizeToTray.isSelected())
                .sendPhotos(sendPhotos.isSelected())
                .sendVideo(sendVideo.isSelected())
                .topicsEnabled(topicsEnabled.isSelected())
                .build());
    }

    private int parseInterval() {
        try {
            return Math.max(1, Integer.parseInt(interval.getText().trim()));
        } catch (NumberFormatException e) {
            return 5;
        }
    }
}
