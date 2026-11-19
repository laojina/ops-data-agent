# 经营数据问答 Agent

这是 Java 17、Spring Boot 3.2 和 SQLite 的经营数据问答项目。支持自然语言查数、九条指标的口径查询和多轮对话。

## 运行

在项目根目录使用 Java 17 和 Maven 3.6.3 或以上版本。首次构建需要下载 Maven 依赖。项目的 .mvn 设置使用 Maven Central，可通过用户级 Maven 设置配置网络代理。

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--seed"
mvn spring-boot:run
mvn test
```

- 第一条从 `seed.sql` 重建 `data/ops.db`。脚本执行成功后才替换现有数据库。
- 第二条启动网页。在控制台找到 `Open in browser:`，点击后面的网址。默认是 `http://localhost:8000/`。
- 第三条运行基线测试。每项通过后会输出一行 `PASS`，Maven 最后汇总运行数、失败数、错误数和跳过数。测试使用临时数据库，可以独立运行。

## 查询指标口径

在项目根目录的本机配置文件 `.env.local` 中设置 `LLM_API_KEY=你的模型凭据`，也可以使用同名环境变量。默认连接 DeepSeek 官方 API 和 `deepseek-flash`。其他 OpenAI 兼容平台可另设 `LLM_BASE_URL` 与 `LLM_MODEL`。本机配置由 Git 忽略，换凭据后重启服务。

启动网页并输入「运输异常率怎么算？」，也可以用 PowerShell 调用接口。

```powershell
$body = @{ session_id = 'stage2-demo'; question = '运输异常率怎么算？' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri 'http://localhost:8000/ask' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

展开「查看调用记录」，可以看到模型生成的工具请求、Java 从 `data/metrics.json` 命中的 M02、模型最终答复。两次模型调用的 token 来自接口实际返回的 `usage`。工具记录包含输入、命中口径和耗时。删除所使用的 Key 并重启后，`/ask` 会返回 `status: error`。


### 真实调用验收

实测问题为「运输异常率怎么算？」。模型发起 `lookup_metric`，Java 按原工具调用 ID 回传 M02，模型给出运输异常率的分子和分母。以下为实测响应节选，token 取自 DeepSeek 返回的用量。

```json
{
  "status": "answered",
  "metric_id": "M02",
  "trace": [
    {
      "type": "model",
      "name": "deepseek-flash",
      "tokens": {"prompt_tokens": 372, "completion_tokens": 41, "total_tokens": 413},
      "latency_ms": 1124
    },
    {
      "type": "tool",
      "name": "lookup_metric",
      "outcome": {"metric": {"id": "M02"}},
      "tokens": null,
      "latency_ms": 9
    },
    {
      "type": "model",
      "name": "deepseek-flash",
      "tokens": {"prompt_tokens": 533, "completion_tokens": 108, "total_tokens": 641},
      "latency_ms": 953
    }
  ]
}
```


## 查看 SQLite 数据

第一条命令将数据库文件生成在项目根目录的 `data/ops.db`。在支持 SQLite 的 Navicat 中，新建 SQLite 连接，类型选择「现有数据库文件」，选中该文件并连接。展开左侧「表」，双击 `warehouses`、`orders` 或 `exceptions` 查看数据。在 Windows 上重新建库前可以先关闭该连接，避免文件被占用。

## 样例数据与口径

基础数据由 `seed.sql` 自建，包含 4 个仓库、1,203 个订单和 182 条运输异常，业务数据覆盖 2026 年 9 月至 10 月。脚本用固定序列生成样例，重建后结果一致。

`data/metrics.json` 是指标口径字典。M01–M04 使用题面定义，并补足以下字段和时间规则：

| 指标 | 基线采用的时间或字段 |
| --- | --- |
| M01 出库及时率 | 按实际出库时间归期，分母为已出库订单。 |
| M02 运输异常率 | 按订单创建时间归期，分子按订单去重，分母为全部订单。 |
| M03 破损率 | 按出库时间归期。异常表的 `damaged_pieces` 记录破损件数，分母为出库件数。 |
| M04 履约达成率 | 用订单的 `promised_delivery_at` 与 `signed_at` 判断。按承诺签收日归期，分母为到期且未取消的订单，包含到期未签收。 |

M05–M08 为自行补充的口径，依次是出库票量、延误订单率、出库件量和出库金额。M09 破损金额率随模拟的 main 数据更新加入。时间值按同一业务日历解释，周按周一开始。

## 可手算的核对样例

只看 `FIX-SEP-01` 至 `FIX-SEP-03` 这三个订单时，两条异常都属于同一个订单，M02 为 **1/3**。三个订单中两个已出库，其中一个及时，M01 为 **1/2**。同一组订单的 M03 为 **1/10**，M04 为 **1/3**。另有 `FIX-CROSS`，创建于 9 月，实际出库发生在 10 月，可用来检查按不同时间字段归期。

## 查数链路和提示词

[AskService.java](src/main/java/com/opsdata/agent/AskService.java) 中的系统提示词负责告诉模型何时查口径、如何承接时间和维度、怎样使用工具。每个工具另外有自己的 description。模型发出的原生工具调用和真实 token 都在 trace 里，一步是一次模型决策及其返回的工具调用。

- 模型负责选择工具、从口径和表结构生成 SQL、根据错误决定是否重写。
- Java 负责执行边界。歧义提问先澄清，空范围和缺失敏感字段会在执行 SQL 之前返回明确的原因。
- [ReadOnlyDatabase.java](src/main/java/com/opsdata/agent/ReadOnlyDatabase.java) 用解析器检查单条 SELECT、业务表白名单和最外层 LIMIT，再用 SQLite 只读连接执行。结果最多 200 行，禁用附件数据库和加载扩展，并设置查询时间上限。
- 查数答案和表格由本轮 SQLite 结果生成。会话只给模型提供最近六轮提问、口径标识和澄清信息。SQL 和旧结果不进入后续轮次的历史。

## 公开用例与合并验证

以下用本机真实 DeepSeek 接口复核，数字另用独立 SQLite 查询比对。验收日期固定为 2026 年 11 月 19 日。演示「上个月」时，可先在 PowerShell 设置 `$env:AGENT_TODAY = '2026-11-19'` 再启动。

| 提问或场景 | 预期及实测结果 |
| --- | --- |
| 10 月华东出库及时率 | M01，及时 136 单、已出库 160 单，85.00%。 |
| 上个月出库怎么样 → 看出库及时率 | 先带 10 月和三个口径选项澄清，随后按四个仓库查询 M01。 |
| 9 月和 10 月运输异常率对比 | M02，16.06% 和 14.02%，异常按订单去重。 |
| 继续问华南呢 | 沿用 M02 和两个月，返回 15.92% 和 14.14%。 |
| 11 月及时率、导出手机号、修改订单状态 | 返回 insufficient_evidence，分别说明覆盖范围、字段缺失和只读能力。 |
| 用户澄清后仍未选口径 | 说明默认用出库及时率衡量时效，并按原时间范围分仓查询。 |
| 合并后的 10 月成都二号仓 | 出库票量 2 单，破损金额率 0.8333%。 |

10 月华东问法的真实响应节选

~~~json
{
  "status": "answered",
  "metric_id": "M01",
  "sql": [
    "SELECT ROUND(SUM(CASE WHEN o.shipped_at IS NOT NULL AND o.shipped_at <= o.promised_at THEN 1 ELSE 0 END) * 100.0 / NULLIF(SUM(CASE WHEN o.shipped_at IS NOT NULL THEN 1 ELSE 0 END), 0), 2) AS \"出库及时率(%)\", SUM(CASE WHEN o.shipped_at IS NOT NULL THEN 1 ELSE 0 END) AS \"出库票量\", SUM(CASE WHEN o.shipped_at IS NOT NULL AND o.shipped_at <= o.promised_at THEN 1 ELSE 0 END) AS \"及时出库票量\" FROM orders o JOIN warehouses w ON o.warehouse_id = w.id WHERE w.region = '华东' AND o.shipped_at >= '2026-10-01' AND o.shipped_at < '2026-11-01' LIMIT 200"
  ],
  "table": {
    "columns": [
      "出库及时率(%)",
      "出库票量",
      "及时出库票量"
    ],
    "rows": [
      [
        85.0,
        160,
        136
      ]
    ],
    "truncated": false
  },
  "model_calls": [
    {
      "name": "deepseek-flash",
      "total_tokens": 1156,
      "latency_ms": 932
    },
    {
      "name": "deepseek-flash",
      "total_tokens": 1454,
      "latency_ms": 1231
    },
    {
      "name": "deepseek-flash",
      "total_tokens": 1968,
      "latency_ms": 1425
    },
    {
      "name": "deepseek-flash",
      "total_tokens": 2192,
      "latency_ms": 1244
    }
  ]
}
~~~

## 四个走查变异

在独立副本中复测，测试后的业务文件仍以 Git 里的合并版本为准。

| 改动 | 实测行为 |
| --- | --- |
| 清空模型 Key 后重启 | `/ask` 返回 error，提示缺少 LLM_API_KEY，SQL 和结果表为空。 |
| 将 M02 的 denominator 改为对应订单的异常记录条数 | 同一问题的计算随字典改变。9 月约 98.98%，10 月 100.00%。 |
| 将 run_sql 工具描述改成「仅用于统计表行数」 | 模型收到新描述。指标 SQL 受限，Java 反馈当前能力不足。 |
| 在 seed.sql 加一条 10 月华东迟出库订单后重建库 | 同一问法从 85.00% 变为 84.47%。 |

第三项可通过启动参数复现。

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.tools.run-sql-description=仅用于统计表行数"
```

## SQL 重写与代码复核

[ModelToolCallingTest.java](src/test/java/com/opsdata/agent/ModelToolCallingTest.java) 使用本地模型替身，让第一次工具调用提交 `SELECT missing_column FROM orders LIMIT 1`。SQLite 真正执行并返回 `no such column: missing_column`。服务按原工具调用 ID 把错误交还给模型，替身随后改成查固定样例的已出库订单数。测试确认本轮答复使用实际的 2 单，并忽略替身文本里另写的数字。这条测试可随 `mvn test` 重现。

复核 AI 生成的查询时发现，`status = '已出库'` 会漏掉状态已变为「已签收」的订单，直接用两个整数相除也会把小比例截成零。工具描述、指标字典和系统提示词明确用 `shipped_at` 识别已出库订单，先用浮点数再做除法。成都二号仓的件数破损率 `1/30` 与金额破损率 `25/3000` 可现场手算对照。

## Git 交付

`feature/bi-agent` 从 `exam-base` 开始，合并提交 `eaee6af` 纳入了 `main` 的模拟更新 `753033a`。解决 `seed.sql` 和 `data/metrics.json` 的冲突后，保留了月末样例、成都二号仓、10 月下旬订单和 M09。提交或演示时使用 `feature/bi-agent`。
