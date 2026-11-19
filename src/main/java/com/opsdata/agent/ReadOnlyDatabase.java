package com.opsdata.agent;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteLimits;

@Component
final class ReadOnlyDatabase {

    private static final int MAX_ROWS = 200;
    private static final List<String> TABLES = List.of("warehouses", "orders", "exceptions");
    private static final Map<String, String> PURPOSES = Map.of(
            "warehouses", "仓库名称和所属区域",
            "orders", "订单、金额和件数。shipped_at 非空表示已经出库，已签收订单也可以有出库时间",
            "exceptions", "订单关联的运输异常记录，一个订单可以有多条异常");
    private static final Map<String, List<String>> ENUMS = Map.of(
            "warehouses", List.of("region"),
            "orders", List.of("status"),
            "exceptions", List.of("type", "code"));
    private static final Map<String, List<String>> DIMENSIONS = Map.of(
            "warehouses", List.of("仓库", "区域"),
            "orders", List.of("仓库", "区域（关联 warehouses）", "周", "月"),
            "exceptions", List.of("异常类型", "仓库、区域（经 orders 关联）", "周", "月"));

    private final Path database;

    ReadOnlyDatabase(@Value("${ops.database.path:data/ops.db}") String path) {
        this.database = Path.of(path).toAbsolutePath();
    }

    List<TableInfo> listTables() throws SQLException {
        List<TableInfo> result = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    if (count.next()) {
                        result.add(new TableInfo(table, PURPOSES.get(table), count.getLong(1)));
                    }
                }
            }
        }
        return result;
    }

    TableDetails describe(String name) throws SQLException {
        String table = name.strip().toLowerCase(Locale.ROOT);
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("未知表名：" + name + "。请先调用 list_tables。");
        }
        List<Column> columns = new ArrayList<>();
        try (Connection connection = connect();
             Statement metadata = connection.createStatement();
             ResultSet fields = metadata.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (fields.next()) {
                String column = fields.getString("name");
                List<String> values = ENUMS.get(table).contains(column)
                        ? enumValues(connection, table, column)
                        : List.of();
                columns.add(new Column(column, fields.getString("type"), values));
            }
        }
        return new TableDetails(table, columns, DIMENSIONS.get(table));
    }

    // 先用解析器判断整条语句和最外层 LIMIT，再交给只能读的 SQLite 连接。
    private void validate(String sql) {
        if (sql.isBlank() || sql.length() > 12_000) {
            throw new IllegalArgumentException("SQL 为空或超过长度限制。");
        }
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.size() != 1 || !(statements.get(0) instanceof Select select)) {
                throw new IllegalArgumentException("只支持一条只读 SELECT 查询。");
            }
            if (select.getLimit() == null || !(select.getLimit().getRowCount() instanceof LongValue count)
                    || count.getValue() < 0) {
                throw new IllegalArgumentException("查询最外层须带非负整数 LIMIT。");
            }
            Set<String> tables = new TablesNamesFinder().getTables(statements.get(0));
            if (tables.isEmpty() || tables.stream().anyMatch(table -> !TABLES.contains(normalizeTable(table)))) {
                throw new IllegalArgumentException("查询只允许读取 warehouses、orders、exceptions。");
            }
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("SQL 解析失败。请检查语法和最外层 LIMIT。");
        }
    }

    QueryResult run(String sql) throws SQLException {
        validate(sql);
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler() {
                @Override
                protected int progress() {
                    return System.nanoTime() >= deadline ? 1 : 0;
                }
            });
            try (ResultSet result = statement.executeQuery(sql)) {
                var metadata = result.getMetaData();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    columns.add(metadata.getColumnLabel(column));
                }
                while (rows.size() < MAX_ROWS && result.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int column = 1; column <= columns.size(); column++) {
                        row.add(result.getObject(column));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows, rows.size() == MAX_ROWS && result.next());
            }
        }
    }

    private Connection connect() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enableLoadExtension(false);
        SQLiteConnection connection = (SQLiteConnection)
                DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            connection.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private List<String> enumValues(Connection connection, String table, String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DISTINCT " + column + " FROM " + table
                     + " ORDER BY " + column + " LIMIT 20")) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static String normalizeTable(String table) {
        return table.toLowerCase(Locale.ROOT).replaceAll("[\"\\x60\\[\\]]", "").replaceFirst("^main\\.", "");
    }

    record TableInfo(String name, String purpose, long rows) {}

    record Column(String name, String type, List<String> values) {}

    record TableDetails(String name, List<Column> columns, List<String> dimensions) {}

    record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {}
}
