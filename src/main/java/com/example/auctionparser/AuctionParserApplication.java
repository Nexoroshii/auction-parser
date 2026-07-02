package com.example.auctionparser;

import com.example.auctionparser.ui.JavaFxApplication;
import javafx.application.Application;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration root and process entry point. The Spring context is
 * actually started from {@link JavaFxApplication#init()}; {@code main} only hands
 * off to the JavaFX launcher.
 */
@SpringBootApplication
public class AuctionParserApplication {

    public static void main(String[] args) {
        Application.launch(JavaFxApplication.class, args);
    }

}
