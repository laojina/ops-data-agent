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
        // 先在正式库旁边建临时库。整个脚本成功后才替换原库，失败时原库仍可用。
        Path stagedDatabase = Files.createTempFile(database.getParent(), "ops-seed-", ".db");

        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + stagedDatabase)) {
                // SQLite 的外键检查按连接设置，在开启事务前启用。
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
                // 建表和填数据放在同一个事务里，所有语句成功后再提交。
                connection.setAutoCommit(false);
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
                connection.commit();
            }

            try {
                // SQLite 连接已经关闭，避免 Windows 在数据库文件仍打开时阻止移动。
                Files.move(stagedDatabase, database,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(stagedDatabase, database, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 脚本或移动失败时清掉临时库，成功移动后这里无需再删文件。
            Files.deleteIfExists(stagedDatabase);
        }
    }
}
