const queryForm = document.querySelector("#query-form");
const questionField = document.querySelector("#question");
const queryStatus = document.querySelector("#query-status");
const queryTrace = document.querySelector("#query-trace");
const traceOutput = document.querySelector("#trace-output");
const querySql = document.querySelector("#query-sql");
const sqlOutput = document.querySelector("#sql-output");
const queryResult = document.querySelector("#query-result");
const resultTable = document.querySelector("#result-table");
const submitButton = queryForm.querySelector("button");
const sessionId = crypto.randomUUID();

function renderTable(table) {
    queryResult.hidden = table.rows.length === 0;
    resultTable.replaceChildren();
    if (queryResult.hidden) {
        return;
    }
    const heading = resultTable.createTHead().insertRow();
    for (const column of table.columns) {
        const cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = column;
        heading.append(cell);
    }
    const body = resultTable.createTBody();
    for (const row of table.rows) {
        const target = body.insertRow();
        for (const value of row) {
            target.insertCell().textContent = value ?? "—";
        }
    }
    resultTable.createCaption().textContent = table.truncated
        ? "查询结果（仅展示前 200 行）"
        : "查询结果";
}

queryForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const question = questionField.value.trim();
    if (!question) {
        queryStatus.textContent = "请输入经营数据问题。";
        return;
    }
    queryStatus.textContent = "正在查询…";
    queryTrace.hidden = true;
    querySql.hidden = true;
    queryResult.hidden = true;
    submitButton.disabled = true;

    try {
        const response = await fetch("/ask", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ session_id: sessionId, question }),
        });
        if (!response.ok) {
            queryStatus.textContent = "请求失败（HTTP " + response.status + "）。";
            return;
        }
        const result = await response.json();
        queryStatus.textContent = result.metric_id
            ? result.metric_id + " · " + result.answer
            : result.answer;
        renderTable(result.table);
        sqlOutput.textContent = result.sql.join("\n\n");
        querySql.hidden = result.sql.length === 0;
        traceOutput.textContent = JSON.stringify(result.trace, null, 2);
        queryTrace.hidden = result.trace.length === 0;
    } catch {
        queryStatus.textContent = "连接失败。请从 Java 启动日志里的网址打开页面。";
    } finally {
        submitButton.disabled = false;
    }
});
