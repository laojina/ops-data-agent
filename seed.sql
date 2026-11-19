CREATE TABLE warehouses (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    region TEXT NOT NULL
);

CREATE TABLE orders (
    order_id TEXT PRIMARY KEY,
    warehouse_id TEXT NOT NULL REFERENCES warehouses(id),
    created_at TEXT NOT NULL,
    promised_at TEXT NOT NULL,
    shipped_at TEXT,
    signed_at TEXT,
    promised_delivery_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('待出库', '已出库', '已签收', '已取消')),
    amount NUMERIC NOT NULL CHECK (amount >= 0),
    pieces INTEGER NOT NULL CHECK (pieces > 0)
);

CREATE TABLE exceptions (
    order_id TEXT NOT NULL REFERENCES orders(order_id),
    event_time TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('破损', '延误', '丢失')),
    code TEXT NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount >= 0),
    damaged_pieces INTEGER NOT NULL DEFAULT 0 CHECK (
        (type = '破损' AND damaged_pieces > 0)
        OR (type <> '破损' AND damaged_pieces = 0)
    )
);

CREATE INDEX idx_orders_warehouse_created ON orders(warehouse_id, created_at);
CREATE INDEX idx_exceptions_order ON exceptions(order_id);

INSERT INTO warehouses (id, name, region) VALUES
    ('WH01', '上海一号仓', '华东'),
    ('WH02', '广州仓', '华南'),
    ('WH03', '西安仓', '西北');

-- 固定样例用于独立核对去重、出库与签收的区别、跨月归属。
INSERT INTO orders (
    order_id, warehouse_id, created_at, promised_at, shipped_at, signed_at,
    promised_delivery_at, status, amount, pieces
) VALUES
    ('FIX-SEP-01', 'WH01', '2026-09-01 08:00:00', '2026-09-01 12:00:00',
     '2026-09-01 10:00:00', '2026-09-04 09:00:00', '2026-09-03 18:00:00', '已签收', 400, 4),
    ('FIX-SEP-02', 'WH01', '2026-09-01 08:00:00', '2026-09-01 12:00:00',
     '2026-09-01 14:00:00', '2026-09-03 20:00:00', '2026-09-03 18:00:00', '已签收', 600, 6),
    ('FIX-SEP-03', 'WH01', '2026-09-01 08:00:00', '2026-09-01 12:00:00',
     NULL, NULL, '2026-09-03 18:00:00', '待出库', 300, 3),
    ('FIX-CROSS', 'WH02', '2026-09-30 22:00:00', '2026-10-01 10:00:00',
     '2026-10-01 09:00:00', '2026-10-03 17:00:00', '2026-10-03 18:00:00', '已签收', 500, 5);

-- 用固定序列生成其余订单，重建后数据完全一致。
WITH RECURSIVE sequence(n) AS (
    VALUES (1)
    UNION ALL
    SELECT n + 1 FROM sequence WHERE n < 1196
),
dated_orders AS (
    SELECT n, datetime('2026-09-01 08:00:00', '+' || ((n - 1) % 61) || ' days') AS created_at
    FROM sequence
),
timed_orders AS (
    SELECT n, created_at,
           datetime(created_at, '+4 hours') AS promised_at,
           CASE
               WHEN n % 10 IN (0, 9) THEN NULL
               WHEN n % 7 = 0 THEN datetime(created_at, '+6 hours')
               ELSE datetime(created_at, '+2 hours')
           END AS shipped_at,
           CASE WHEN n % 10 NOT IN (0, 9) AND n % 4 <> 0
               THEN datetime(created_at, CASE WHEN n % 6 = 0 THEN '+4 days' ELSE '+2 days' END)
           END AS planned_signed_at,
           datetime(created_at, '+3 days', '+10 hours') AS promised_delivery_at
    FROM dated_orders
),
observed_orders AS (
    SELECT *, CASE WHEN planned_signed_at < '2026-11-01' THEN planned_signed_at END AS signed_at
    FROM timed_orders
)
INSERT INTO orders (
    order_id, warehouse_id, created_at, promised_at, shipped_at, signed_at,
    promised_delivery_at, status, amount, pieces
)
SELECT printf('ORD-%04d', n), printf('WH%02d', 1 + (n - 1) % 3),
       created_at, promised_at, shipped_at, signed_at, promised_delivery_at,
       CASE
           WHEN n % 10 = 0 THEN '已取消'
           WHEN shipped_at IS NULL THEN '待出库'
           WHEN signed_at IS NULL THEN '已出库'
           ELSE '已签收'
       END,
       100 + (n * 137) % 900, 1 + (n * 7) % 20
FROM observed_orders;

INSERT INTO exceptions (order_id, event_time, type, code, amount, damaged_pieces) VALUES
    ('FIX-SEP-01', '2026-09-02 09:00:00', '破损', 'DAMAGE', 40, 1),
    ('FIX-SEP-01', '2026-09-02 15:00:00', '延误', 'DELAY', 0, 0);

WITH sample_orders AS (
    SELECT order_id, shipped_at, CAST(substr(order_id, 5) AS INTEGER) AS n
    FROM orders
    WHERE order_id LIKE 'ORD-%' AND shipped_at IS NOT NULL
    ORDER BY order_id
    LIMIT 178
)
INSERT INTO exceptions (order_id, event_time, type, code, amount, damaged_pieces)
SELECT order_id, datetime(shipped_at, '+1 hour'),
       CASE n % 5 WHEN 0 THEN '破损' WHEN 1 THEN '丢失' ELSE '延误' END,
       CASE n % 5 WHEN 0 THEN 'DAMAGE' WHEN 1 THEN 'LOST' ELSE 'DELAY' END,
       CASE n % 5 WHEN 0 THEN 10 + n % 40 ELSE 0 END,
       CASE n % 5 WHEN 0 THEN 1 ELSE 0 END
FROM sample_orders;
