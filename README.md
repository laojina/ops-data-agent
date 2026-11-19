# 经营数据问答 Agent

这是 Java 17、Spring Boot 3.2 和 SQLite 的经营数据问答项目。当前可通过模型工具调用查询八条业务指标的口径。

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

基础数据由 `seed.sql` 自建，包含 3 个仓库、1,200 个订单和 180 条运输异常，业务数据覆盖 2026 年 9 月至 10 月。脚本用固定序列生成样例，重建后结果一致。

`data/metrics.json` 是指标口径字典。M01–M04 使用题面定义，并补足以下字段和时间规则：

| 指标 | 基线采用的时间或字段 |
| --- | --- |
| M01 出库及时率 | 按实际出库时间归期，分母为已出库订单。 |
| M02 运输异常率 | 按订单创建时间归期，分子按订单去重，分母为全部订单。 |
| M03 破损率 | 按出库时间归期。异常表的 `damaged_pieces` 记录破损件数，分母为出库件数。 |
| M04 履约达成率 | 用订单的 `promised_delivery_at` 与 `signed_at` 判断。按承诺签收日归期，分母为到期且未取消的订单，包含到期未签收。 |

M05–M08 为自行补充的口径，依次是出库票量、延误订单率、出库件量和出库金额。时间值按同一业务日历解释，周按周一开始。

## 可手算的核对样例

只看 `FIX-SEP-01` 至 `FIX-SEP-03` 这三个订单时，两条异常都属于同一个订单，M02 为 **1/3**。三个订单中两个已出库，其中一个及时，M01 为 **1/2**。同一组订单的 M03 为 **1/10**，M04 为 **1/3**。另有 `FIX-CROSS`，创建于 9 月，实际出库发生在 10 月，可用来检查按不同时间字段归期。
