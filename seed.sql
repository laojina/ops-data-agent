-- 创建顺序是仓库、订单、异常。订单通过仓库 ID 找区域，异常通过订单 ID 找原订单。
CREATE TABLE warehouses (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    region TEXT NOT NULL
);

CREATE TABLE orders (
    order_id TEXT PRIMARY KEY,
    warehouse_id TEXT NOT NULL REFERENCES warehouses(id),
    created_at TEXT NOT NULL,
    -- promised_at 是承诺出库时间，promised_delivery_at 是承诺签收时间。
    -- 实际出库、签收时间允许为空，表示截至样例数据截止日尚未发生。
    promised_at TEXT NOT NULL,
    shipped_at TEXT,
    signed_at TEXT,
    promised_delivery_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('待出库', '已出库', '已签收', '已取消')),
    amount NUMERIC NOT NULL CHECK (amount >= 0),
    pieces INTEGER NOT NULL CHECK (pieces > 0)
);

-- 同一个订单可以关联多条异常。按订单统计异常率时要按 order_id 去重。
CREATE TABLE exceptions (
    order_id TEXT NOT NULL REFERENCES orders(order_id),
    event_time TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('破损', '延误', '丢失')),
    code TEXT NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount >= 0),
    -- amount 用于金额口径，damaged_pieces 用于件数口径。只有破损异常才记录破损件数。
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
    ('WH03', '西安仓', '西北'),
    ('WH04', '成都二号仓', '西南');

-- 前三单用于手算。第一单出库及时但晚签收，第二单晚出库但在承诺日签收，第三单还未出库。
-- FIX-CROSS 在 9 月下单、10 月出库，用于区分创建月和出库月。
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
     '2026-10-01 09:00:00', '2026-10-03 17:00:00', '2026-10-03 18:00:00', '已签收', 500, 5),
    ('MAIN-CD-01', 'WH04', '2026-10-22 08:00:00', '2026-10-22 12:00:00',
     '2026-10-22 11:00:00', '2026-10-24 10:00:00', '2026-10-25 18:00:00', '已签收', 1000, 10),
    ('MAIN-CD-02', 'WH04', '2026-10-27 08:00:00', '2026-10-27 12:00:00',
     '2026-10-27 15:00:00', '2026-10-31 10:00:00', '2026-10-30 18:00:00', '已签收', 2000, 20),
    ('MAIN-CD-03', 'WH04', '2026-10-29 08:00:00', '2026-10-29 12:00:00',
     NULL, NULL, '2026-10-31 18:00:00', '待出库', 500, 5);

-- 用固定序列生成其余订单，重建后数据完全一致。
-- 递归查询从 1 逐行加到 1196，加上前面的 4 条固定样例，共 1200 单。
WITH RECURSIVE sequence(n) AS (
    VALUES (1)
    UNION ALL
    SELECT n + 1 FROM sequence WHERE n < 1196
),
-- 9 月 1 日到 10 月 31 日共 61 天，用取余把订单均匀分布到这些日期。
dated_orders AS (
    SELECT n, datetime('2026-09-01 08:00:00', '+' || ((n - 1) % 61) || ' days') AS created_at
    FROM sequence
),
-- 按固定规则构造未出库、按时出库和迟出库订单，签收同样保留不同结果供核对。
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
-- 样例数据截至 10 月 31 日，晚于截止日的实际签收时间留空。
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
    -- 两条异常都属于 FIX-SEP-01。用前三单计算 M02 时，分子是 1 单，分母是 3 单。
    ('FIX-SEP-01', '2026-09-02 09:00:00', '破损', 'DAMAGE', 40, 1),
    ('FIX-SEP-01', '2026-09-02 15:00:00', '延误', 'DELAY', 0, 0);

-- 按订单 ID 固定选取 178 条已出库的生成订单，再按编号分配异常类型。
-- 加上前面的 2 条固定异常，共 180 条。
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

INSERT INTO exceptions (order_id, event_time, type, code, amount, damaged_pieces) VALUES
    ('MAIN-CD-01', '2026-10-23 09:00:00', '破损', 'DAMAGE', 25, 1),
    ('MAIN-CD-02', '2026-10-28 09:00:00', '延误', 'DELAY', 0, 0);
