package com.example.auctionparser.ui;

import com.example.auctionparser.AuctionParserApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX {@link Application} that hosts the Spring context. Spring boots in
 * {@link #init()} (off the FX thread), the UI is built in {@link #start(Stage)},
 * and the context is closed cleanly on {@link #stop()}.
 *
 * <p>Launched indirectly from {@link AuctionParserApplication#main} via
 * {@code Application.launch(...)} so the main class does not itself extend
 * {@code Application} (avoids the "JavaFX runtime components are missing" error
 * when running from a plain classpath/fat jar).
 */
public class JavaFxApplication extends Application {

    private ConfigurableApplicationContext context;

    @Override
    public void init() {
        String[] args = getParameters().getRaw().toArray(new String[0]);
        this.context = new SpringApplicationBuilder(AuctionParserApplication.class)
                .headless(false) // required for JavaFX + system tray
                .run(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Let the app keep running in the tray when the last window closes.
        Platform.setImplicitExit(false);
        MainView mainView = new MainView(context, primaryStage);
        mainView.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
        Platform.exit();
    }
}
