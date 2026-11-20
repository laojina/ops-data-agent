# 经营数据问答 Agent

用自然语言查询仓库出库、运输异常和履约指标。模型通过原生 Tool Calling 读取指标字典和表结构，再生成 SQLite 查询。页面展示本轮结果、口径 ID、原始 SQL 和每步调用记录，便于复核。

技术栈为 Java 17、Spring Boot 3.2、Maven、SQLite 和原生 JavaScript。应用以单进程运行，默认端口为 8000。仓库中的业务数据均为可重建的样例数据。

## 快速启动

准备 JDK 17、Maven 3.6.3 或更新版本，以及支持原生 Tool Calling 的 OpenAI 兼容接口。首次构建需要下载 Maven 依赖。使用完整交付分支：

```powershell
git clone --branch feature/bi-agent https://github.com/laojina/ops-data-agent.git
cd ops-data-agent
```

在项目根目录新建被 Git 忽略的 `.env.local`，填入使用方提供的模型配置。也可以用同名环境变量注入。下面的地址和模型名是本项目的默认配置。

```properties
LLM_API_KEY=在本机填入模型凭据
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-flash
```

在项目根目录依次执行：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--seed"
mvn spring-boot:run
```

第一条根据 [seed.sql](seed.sql) 生成 `data/ops.db` 后退出。第二条启动服务，打开日志中 `Open in browser:` 后面的网址，默认是 [http://localhost:8000/](http://localhost:8000/)。如果使用 IDEA，运行 `OpsDataApplication`，将工作目录设为仓库根目录。

跑自动化测试：

```powershell
mvn test
```

预期共 16 项通过。测试自建临时 SQLite 数据库，并使用本地模型替身验证协议、日期边界、工具权限和重写流程，无需模型凭据。真实模型调用可用下面的请求单独验证。

## 调用接口

在运行服务的同时，在另一个 PowerShell 窗口直接复制下面的请求：

```powershell
$body = @{
    session_id = [guid]::NewGuid().ToString()
    question = '10 月华东区出库及时率是多少'
} | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri 'http://localhost:8000/ask' `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) |
    ConvertTo-Json -Depth 20
```

`POST /ask` 接收非空字符串 `session_id` 和 `question`。非法输入返回 HTTP 400。有效请求返回 HTTP 200，`status` 区分 `answered`、`need_clarify`、`insufficient_evidence` 和 `error`。响应还包含 `answer`、`metric_id`、`sql`、`table` 和 `trace`。其中 `sql` 按提交给 SQLite 的顺序保存原文，`table` 包含 `columns`、`rows` 和 `truncated`。

上述请求的真实 DeepSeek 响应节选如下。口径为 M01，华东 10 月及时出库 136 单、已出库 160 单，**136 ÷ 160 = 85.00%**。节选保留了执行的 SQL、结果表和关键调用。模型用量来自接口响应的 `usage`，耗时由服务计量，复跑时数值会变化。

```json
{
  "status": "answered",
  "metric_id": "M01",
  "sql": ["SELECT COUNT(*) AS \"已出库订单数\",\n       SUM(CASE WHEN o.shipped_at <= o.promised_at THEN 1 ELSE 0 END) AS \"及时出库订单数\",\n       ROUND(SUM(CASE WHEN o.shipped_at <= o.promised_at THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS \"出库及时率(%)\"\nFROM orders o\nJOIN warehouses w ON w.id = o.warehouse_id\nWHERE w.region = '华东'\n  AND o.shipped_at IS NOT NULL\n  AND o.shipped_at >= '2026-10-01' AND o.shipped_at < '2026-11-01'\nLIMIT 10"],
  "table": {
    "columns": ["已出库订单数", "及时出库订单数", "出库及时率(%)"],
    "rows": [[160, 136, 85.0]],
    "truncated": false
  },
  "trace": [
    {
      "type": "model",
      "name": "deepseek-flash",
      "tokens": {"prompt_tokens": 1237, "completion_tokens": 62, "total_tokens": 1299, "prompt_tokens_details": {"cached_tokens": 1024}, "prompt_cache_hit_tokens": 1024, "prompt_cache_miss_tokens": 213},
      "latency_ms": 1066
    },
    {
      "type": "tool",
      "name": "lookup_metric",
      "outcome": {"metric": {"id": "M01"}},
      "latency_ms": 1
    },
    {
      "type": "model",
      "name": "deepseek-flash",
      "tokens": {"prompt_tokens": 1531, "completion_tokens": 66, "total_tokens": 1597, "prompt_tokens_details": {"cached_tokens": 1280}, "prompt_cache_hit_tokens": 1280, "prompt_cache_miss_tokens": 251},
      "latency_ms": 619
    },
    {
      "type": "model",
      "name": "deepseek-flash",
      "tokens": {"prompt_tokens": 1871, "completion_tokens": 205, "total_tokens": 2076, "prompt_tokens_details": {"cached_tokens": 1536}, "prompt_cache_hit_tokens": 1536, "prompt_cache_miss_tokens": 335},
      "latency_ms": 1536
    },
    {
      "type": "tool",
      "name": "run_sql",
      "latency_ms": 80
    },
    {
      "type": "model",
      "name": "deepseek-flash",
      "tokens": {"prompt_tokens": 2134, "completion_tokens": 171, "total_tokens": 2305, "prompt_tokens_details": {"cached_tokens": 1920}, "prompt_cache_hit_tokens": 1920, "prompt_cache_miss_tokens": 214},
      "latency_ms": 2050
    }
  ]
}
```

完整 trace 的每一步包含 `type`、`name`、`input`、`outcome`、`tokens` 和 `latency_ms`。网页可折叠查看 SQL 和调用记录，也可以复制原始 SQL。左栏历史保存在当前浏览器。后端按 `session_id` 保留最近六轮提问、已选口径和澄清信息，旧查询的数值不会交给下一轮模型。服务重启后，模型上下文重新开始。

## 模型、口径和执行边界

1. [AskService.java](src/main/java/com/opsdata/agent/AskService.java) 注册 `list_tables`、`describe_table`、`lookup_metric` 和 `run_sql` 四个工具。系统提示词和工具描述负责指导工具选择、澄清和上下文继承。
2. [ModelClient.java](src/main/java/com/opsdata/agent/ModelClient.java) 发送带工具定义的模型请求。Java 执行工具后通过原 `tool_call_id` 回传结果。模型根据口径和结构编写 SQL，并在看到执行结果或报错后决定是否继续。每轮最多六次模型决策，SQL 最多尝试三次。
3. [MetricDictionary.java](src/main/java/com/opsdata/agent/MetricDictionary.java) 在查口径时读取 [data/metrics.json](data/metrics.json)，按 ID、名称或别名精确查找。指标的分子、分母、时间字段和可用维度以字典为准。
4. [ReadOnlyDatabase.java](src/main/java/com/opsdata/agent/ReadOnlyDatabase.java) 检查单条 `SELECT`、业务表白名单和最外层整数 `LIMIT`，用只读 SQLite 连接执行，每次最多返回 200 行。DDL、DML、多语句和缺最外层 `LIMIT` 的查询会被拒绝。超出数据范围或要求导出不存在的客户手机号时，服务明确说明原因；改订单状态不受支持。
5. Java 用本轮 SQLite 结果生成查数答复和表格。口径解释从字典生成。缺少模型 Key 时，`/ask` 返回 `error`，查询结果为空。

样例库含 4 个仓库、1,203 笔订单、182 条异常，覆盖 2026 年 9 月 1 日至 10 月 31 日。可用 Navicat 打开 `data/ops.db`，查看 `warehouses`、`orders` 和 `exceptions`。

| 口径 | 时间字段与计算要点 |
| --- | --- |
| M01 出库及时率 | 按实际出库时间归期。分子为及时出库订单数，分母为已出库订单数。 |
| M02 运输异常率 | 按订单创建时间归期。有异常记录的订单按订单 ID 去重，分母为全部订单。 |
| M03 破损率 | 按实际出库时间归期。破损件数除以出库件数，分母中的每笔订单只计一次。 |
| M04 履约达成率 | 按承诺签收日归期。及时签收订单数除以应签收订单数，分母含到期未签收订单，排除已取消订单和观测截止日后才到期的订单。 |
| M09 破损金额率 | 按实际出库时间归期。破损金额除以出库金额。 |

M05 至 M08 依次为出库票量、延误订单率、出库件量和出库金额。前三个 `FIX-SEP` 订单可手算核对 M01、M02、M03、M04，依次为 1/2、1/3、1/10、1/3。`FIX-CROSS` 创建于 9 月、出库于 10 月，可检查两个时间字段的归期差异。

## 公开用例

演示相对时间时，将业务日期固定为 2026 年 11 月 19 日。可向 IDEA 的 Program arguments 添加 `--agent.today=2026-11-19`，或用下面的命令启动：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.today=2026-11-19"
```

每组从「新对话」开始。箭头后面的追问在同一会话里发送。

| 输入 | 核对结果 |
| --- | --- |
| 10 月华东区出库及时率是多少 | M01，及时 136 单、已出库 160 单，85.00%。 |
| 上个月出库怎么样 → 看出库及时率 | 首轮 `need_clarify`，复述 10 月并询问口径。第二轮沿用 10 月，按仓返回 M01。 |
| 9 月和 10 月运输异常率对比 → 华南呢 | M02。全部仓库 16.06%、14.02%。追问改为华南后，两个月分别为 15.92%、14.14%。 |
| 11 月的及时率呢 | `insufficient_evidence`，解释数据只到 10 月 31 日。 |
| 查 2026-11-02 或 11/2 这天的履约达成率 | `insufficient_evidence`，明确 2026 年 11 月 2 日没有观测数据，SQL 为空。月/日简写采用样例年份。 |
| 对比 9 月和 11 月的履约达成率 | `insufficient_evidence`，明确 11 月缺数据，并说明 9 月在覆盖范围内、可单独查询。 |
| 把客户的手机号导出来 | 说明样例库没有该字段。 |
| 帮我把 10 月延误订单改成已处理 | 说明只支持查询。 |
| 上个月出库怎么样 → 我也不确定，你选一个 | 澄清一次后默认 M01，并说明选择原因，沿用 10 月。 |
| 10 月成都二号仓出库票量 / 10 月成都二号仓破损金额率 | M05 为 2 单。M09 为 25/3000，约 0.8333%。 |

### 四个走查变异

在独立副本中复测。每次使用新会话提交同一道题。

| 改动 | 复测依据 |
| --- | --- |
| 去掉模型 Key 并重启 | 重问 10 月华东及时率。`/ask` 返回 `error`，SQL 与表格为空。 |
| 仅将字典里 M02 的 `denominator` 改成统计范围内关联的异常记录条数 | 重问 9 月和 10 月运输异常率。保留按订单去重的分子，结果变为 97/98 和 84/84，约 98.98% 和 100.00%。展开 trace 核对新口径。 |
| 将 `run_sql` 的描述改为「仅用于统计表行数」 | 使用下方的文案实验命令。重问及时率，检查 `trace` 中模型实际请求的工具。 |
| 新增一笔 10 月华东的迟出库订单，重新建库 | 用 `--seed` 命令重建数据库。重问 10 月华东及时率，分子仍为 136，分母变为 161，结果约 84.47%。 |

文案实验只改模型收到的描述。同一份描述同时写入工具声明和系统提示。分别用下面两种描述启动服务，对同一道题检查 trace。本次使用 DeepSeek 实测，两种描述都在首轮返回能力不足，未请求 `run_sql`。

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.tools.run-sql-description=仅用于统计表行数"
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.tools.run-sql-description=只允许统计表的总行数。不得查询经营指标。"
```

执行权限由独立的配置项控制。启用行数模式后，`run_sql` 的工具参数不再提供 SQL，Java 也会拒绝模型发来的指标查询，表行数可通过 `list_tables` 查到。下方同时用同义描述验证文案可以自由修改。

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.tools.table-counts-only=true --agent.tools.run-sql-description=只允许统计表的总行数。不得查询经营指标。"
```

在这个模式下，问「orders 表有多少行」返回 `answered` 和 `list_tables` 查出的 1,203 行，`sql` 为空。另开会话问「10 月华东区出库及时率是多少」，仍返回 `insufficient_evidence`。

### SQL 重写与代码复核

[SQL 重写测试](src/test/java/com/opsdata/agent/ModelToolCallingTest.java) 让模型替身首次提交 `SELECT missing_column FROM orders LIMIT 1`。SQLite 执行后返回 `no such column: missing_column`，Java 按原工具调用 ID 回传错误。替身重写后查到 2 笔已出库订单，最终答复取自该次查询。若重写仍然失败，服务返回错误。

```powershell
mvn "-Dtest=ModelToolCallingTest#passesSqliteErrorBackAndDisplaysOnlyTheActualQueryValue" test
```

复核 AI 生成的 SQL 时发现，`status = '已出库'` 会漏掉已经出库、后来签收的订单。M01 按 `shipped_at` 识别已出库订单，并先乘 `100.0` 再除，避免 SQLite 的整数除法截断。华东 10 月的 136/160，以及成都二号仓分别按件数和金额计算的 1/30、25/3000，可作为对账样例。

## Git 交付

在确认未提供起始仓库后，自建 `exam-base` 基线并从该处开发 `feature/bi-agent`。`main` 上的模拟数据更新 `753033a` 通过 `eaee6af` 合入功能分支。`seed.sql` 与 `data/metrics.json` 的冲突保留了两边的数据和口径改动。合并后使用成都二号仓、M09 和原华东 M01 用例复测。

```powershell
git log --graph --oneline --decorate --all
git show -s --format="%h %p %s" eaee6af
```
