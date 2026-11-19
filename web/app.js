const queryForm = document.querySelector("#query-form");
const questionField = document.querySelector("#question");
const queryStatus = document.querySelector("#query-status");
const queryTrace = document.querySelector("#query-trace");
const traceOutput = document.querySelector("#trace-output");
const submitButton = queryForm.querySelector("button");
const sessionId = crypto.randomUUID();

queryForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const question = questionField.value.trim();
    if (!question) {
        queryStatus.textContent = "请输入指标问题。";
        return;
    }
    queryStatus.textContent = "正在查询指标口径…";
    queryTrace.hidden = true;
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
        traceOutput.textContent = JSON.stringify(result.trace, null, 2);
        queryTrace.hidden = result.trace.length === 0;
    } catch {
        queryStatus.textContent = "连接失败。请从 Java 启动日志里的网址打开页面。";
    } finally {
        submitButton.disabled = false;
    }
});
