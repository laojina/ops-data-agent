package com.opsdata.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.init.ScriptException;

class SeedDataTest {

    private static final Path SEED_SCRIPT = Path.of("seed.sql");

    @TempDir
    Path tempDirectory;

    @Test
    void buildsAndReplacesDatabaseWithExpectedCoverage() throws Exception {
        Path database = tempDirectory.resolve("ops.db");
        SeedDatabase.create(SEED_SCRIPT, database);
        SeedDatabase.create(SEED_SCRIPT, database);

        try (Connection connection = open(database)) {
            assertEquals("3", scalar(connection, "SELECT count(*) FROM warehouses"));
            assertEquals("1200", scalar(connection, "SELECT count(*) FROM orders"));
            assertEquals("180", scalar(connection, "SELECT count(*) FROM exceptions"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            assertEquals("2026-09-01", scalar(connection, "SELECT date(min(created_at)) FROM orders"));
            assertEquals("2026-10-31", scalar(connection, "SELECT date(max(created_at)) FROM orders"));
            assertEquals("2026-09,2026-10", scalar(connection, """
                    SELECT strftime('%Y-%m', created_at) || ',' || strftime('%Y-%m', shipped_at)
                    FROM orders WHERE order_id = 'FIX-CROSS'
                    """));
        }
        System.out.println("PASS | Sample database: 3 warehouses, 1200 orders, 180 exceptions. Foreign keys and dates verified.");
    }

    @Test
    void fixedOrdersDistinguishExceptionRowsFromOrdersAndShipmentFromDelivery() throws Exception {
        Path database = tempDirectory.resolve("ops.db");
        SeedDatabase.create(SEED_SCRIPT, database);

        try (Connection connection = open(database)) {
            // 三个数字依次是异常记录数、去重后的异常订单数、总订单数。M02 应为 1/3。
            assertEquals("2,1,3", scalar(connection, """
                    SELECT count(e.order_id) || ',' || count(DISTINCT e.order_id) || ',' ||
                           count(DISTINCT o.order_id)
                    FROM orders o LEFT JOIN exceptions e ON e.order_id = o.order_id
                    WHERE o.order_id LIKE 'FIX-SEP-%'
                    """));
            // 先验出库及时率 1/2，再验履约达成率 1/3。签收按日期比较，当天签收算达成。
            assertEquals("1,2,1,3", scalar(connection, """
                    SELECT count(CASE WHEN shipped_at <= promised_at THEN 1 END) || ',' ||
                           count(shipped_at) || ',' ||
                           count(CASE WHEN date(signed_at) <= date(promised_delivery_at) THEN 1 END) ||
                           ',' || count(*)
                    FROM orders WHERE order_id LIKE 'FIX-SEP-%'
                    """));
            assertEquals("1", scalar(connection, """
                    SELECT sum(damaged_pieces) FROM exceptions WHERE order_id LIKE 'FIX-SEP-%'
                    """));
            assertEquals("10", scalar(connection, """
                    SELECT sum(pieces) FROM orders
                    WHERE order_id LIKE 'FIX-SEP-%' AND shipped_at IS NOT NULL
                    """));
        }
        System.out.println("PASS | Three-order fixture: M01 = 1/2, M02 = 1/3, M03 = 1/10, M04 = 1/3.");
    }

    @Test
    void failedRebuildLeavesExistingDatabaseInPlace() throws Exception {
        Path database = tempDirectory.resolve("ops.db");
        SeedDatabase.create(SEED_SCRIPT, database);
        Path invalidScript = tempDirectory.resolve("invalid.sql");
        Files.writeString(invalidScript, "CREATE TABLE partial(id INTEGER); INSERT INTO missing VALUES (1);");

        assertThrows(ScriptException.class, () -> SeedDatabase.create(invalidScript, database));

        try (Connection connection = open(database)) {
            assertEquals("1200", scalar(connection, "SELECT count(*) FROM orders"));
        }
        System.out.println("PASS | A failed rebuild preserves the existing database.");
    }

    @Test
    void baseDictionaryContainsEightUniqueMetricIdsAndExactNames() throws Exception {
        JsonNode metrics = new ObjectMapper().readTree(Path.of("data", "metrics.json").toFile());
        assertEquals(List.of("M01", "M02", "M03", "M04", "M05", "M06", "M07", "M08"),
                metrics.findValuesAsText("id"));
        assertEquals(List.of("出库及时率", "运输异常率", "破损率", "履约达成率",
                        "出库票量", "延误订单率", "出库件量", "出库金额"),
                metrics.findValuesAsText("name"));
        System.out.println("PASS | Metric dictionary: all 8 metric IDs and names match.");
    }

    private static Connection open(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
