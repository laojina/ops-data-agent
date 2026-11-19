package com.opsdata.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

final class SeedDatabase {

    private SeedDatabase() {
    }

    static void create(Path script, Path destination) throws IOException, SQLException {
        Path database = destination.toAbsolutePath();
        Files.createDirectories(database.getParent());
        Path stagedDatabase = Files.createTempFile(database.getParent(), "ops-seed-", ".db");

        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + stagedDatabase)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
                connection.setAutoCommit(false);
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
                connection.commit();
            }

            try {
                Files.move(stagedDatabase, database,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(stagedDatabase, database, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(stagedDatabase);
        }
    }
}
