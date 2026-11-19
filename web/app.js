const queryForm = document.querySelector("#query-form");
const questionField = document.querySelector("#question");
const queryStatus = document.querySelector("#query-status");
const queryHistory = document.querySelector("#query-history");
const turnTemplate = document.querySelector("#turn-template");
const submitButton = document.querySelector("#submit-question");
const newChat = document.querySelector("#new-chat");
let sessionId = crypto.randomUUID();

function renderTable(container, data) {
    if (data.rows.length === 0) {
        return;
    }
    const table = container.querySelector("table");
    table.createCaption().textContent = data.truncated ? "查询结果（仅展示前 200 行）" : "查询结果";
    const heading = table.createTHead().insertRow();
    for (const column of data.columns) {
        const cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = column;
        heading.append(cell);
    }
    const body = table.createTBody();
    for (const row of data.rows) {
        const target = body.insertRow();
        for (const value of row) {
            target.insertCell().textContent = value ?? "—";
        }
    }
    container.hidden = false;
}

function addTurn(question, result) {
    const turn = turnTemplate.content.cloneNode(true);
    turn.querySelector(".turn").dataset.status = result.status;
    turn.querySelector(".turn-question").textContent = question;
    turn.querySelector(".turn-answer").textContent = result.metric_id
        ? result.metric_id + " · " + result.answer
        : result.answer;
    renderTable(turn.querySelector(".turn-result"), result.table);
    const sql = turn.querySelector(".turn-sql");
    sql.hidden = result.sql.length === 0;
    sql.querySelector("pre").textContent = result.sql.join("\n\n");
    const trace = turn.querySelector(".turn-trace");
    trace.hidden = result.trace.length === 0;
    trace.querySelector("pre").textContent = JSON.stringify(result.trace, null, 2);
    queryHistory.append(turn);
}

newChat.addEventListener("click", () => {
    sessionId = crypto.randomUUID();
    queryHistory.replaceChildren();
    queryStatus.textContent = "";
    queryForm.reset();
    questionField.focus();
});

queryForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const question = questionField.value.trim();
    if (!question) {
        queryStatus.textContent = "请输入经营数据问题。";
        return;
    }
    queryStatus.textContent = "正在查询…";
    submitButton.disabled = true;
    newChat.disabled = true;
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
        addTurn(question, await response.json());
        queryForm.reset();
        queryStatus.textContent = "";
        questionField.focus();
    } catch {
        queryStatus.textContent = "连接失败。请从 Java 启动日志里的网址打开页面。";
    } finally {
        submitButton.disabled = false;
        newChat.disabled = false;
    }
});
