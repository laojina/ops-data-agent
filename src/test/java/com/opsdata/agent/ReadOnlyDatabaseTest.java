package com.opsdata.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlyDatabaseTest {

    @TempDir
    Path directory;
    private ReadOnlyDatabase database;

    @BeforeEach
    void createFixture() throws Exception {
        Path fixture = directory.resolve("ops.db");
        SeedDatabase.create(Path.of("seed.sql"), fixture);
        database = new ReadOnlyDatabase(fixture.toString());
    }

    @Test
    void describesTheActualWarehouseSchemaAndCapsTheResult() throws Exception {
        assertEquals(List.of(3L, 1200L, 180L),
                database.listTables().stream().map(ReadOnlyDatabase.TableInfo::rows).toList());
        assertTrue(database.describe("warehouses").columns().stream().anyMatch(column ->
                column.name().equals("region") && column.values().contains("华东")));
        assertThrows(IllegalArgumentException.class, () -> database.describe("customers"));

        var result = database.run("SELECT order_id FROM orders ORDER BY order_id LIMIT 201;");
        assertEquals(200, result.rows().size());
        assertTrue(result.truncated());
        assertFalse(database.run("SELECT order_id FROM orders LIMIT 200").truncated());
        System.out.println("PASS | SQLite metadata is current and query results are capped at 200 rows.");
    }

    @Test
    void rejectsWritesMultipleStatementsAndQueriesWithoutAnOuterLimit() throws Exception {
        for (String sql : List.of(
                "DELETE FROM orders",
                "DROP TABLE orders",
                "SELECT order_id FROM orders LIMIT 1; SELECT name FROM warehouses LIMIT 1",
                "SELECT * FROM orders",
                "SELECT * FROM (SELECT * FROM orders LIMIT 1)",
                "SELECT name FROM sqlite_schema LIMIT 1")) {
            assertThrows(IllegalArgumentException.class, () -> database.run(sql), sql);
        }
        var result = database.run("""
                WITH shipped AS (SELECT shipped_at FROM orders WHERE shipped_at IS NOT NULL)
                SELECT count(*) AS shipped_orders FROM shipped LIMIT 1
                """);
        assertEquals(List.of("shipped_orders"), result.columns());
        assertFalse(result.rows().isEmpty());
        System.out.println("PASS | Only single SELECT statements with an outer LIMIT can query business tables.");
    }
}
