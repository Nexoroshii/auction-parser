package com.example.auctionparser.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Builds the SQLite {@link DataSource} programmatically so the parent directory
 * exists before the database file is opened (SQLite will not create it).
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    public DataSource dataSource(AppProperties properties) {
        Path dir = Paths.get(properties.getDataDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory: " + dir, e);
        }
        Path dbFile = dir.resolve("auctionnotifier.db");
        log.info("Using SQLite database at {}", dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        ds.setEnforceForeignKeys(true);
        return ds;
    }
}
