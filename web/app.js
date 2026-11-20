const queryForm = document.querySelector("#query-form");
const questionField = document.querySelector("#question");
const queryHistory = document.querySelector("#query-history");
const conversationScroll = document.querySelector("#conversation-scroll");
const turnTemplate = document.querySelector("#turn-template");
const submitButton = document.querySelector("#submit-question");
const newChat = document.querySelector("#new-chat");
const welcome = document.querySelector("#welcome");
const historyLinks = document.querySelector("#history-links");
const historyEmpty = document.querySelector("#history-empty");
const historyNotice = document.querySelector("#history-notice");
const TURN_KEY_PREFIX = "ops-data-agent:turn:v1:";
const ACTIVE_CHAT_KEY = "ops-data-agent:active-conversation";
const conversations = new Map();
const pendingTurns = new Set();
let activeSessionId = null;

const number = new Intl.NumberFormat("zh-CN");
const SQL_TOKEN = /--[^\r\n]*|\/\*[\s\S]*?\*\/|'(?:''|[^'])*'|"(?:""|[^"])*"|\x60(?:\x60\x60|[^\x60])*\x60|\[[^\]]*\]|\d+(?:\.\d*)?(?:[eE][+-]?\d+)?|[\p{L}_][\p{L}\p{N}_$]*|<=|>=|<>|!=|==|\s+|./gsu;
const KEYWORDS = new Set(("SELECT FROM JOIN LEFT RIGHT FULL INNER OUTER NATURAL CROSS ON WHERE HAVING "
    + "GROUP ORDER BY LIMIT OFFSET WITH RECURSIVE AS UNION ALL DISTINCT CASE WHEN THEN ELSE END "
    + "AND OR NOT IS NULL IN EXISTS LIKE BETWEEN ASC DESC OVER PARTITION FILTER COLLATE").split(" "));
const JOIN_PREFIX = new Set(["LEFT", "RIGHT", "FULL", "INNER", "OUTER", "NATURAL", "CROSS"]);
const CLAUSE_START = new Set(["FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "UNION", "INTERSECT", "EXCEPT", "WITH"]);

function formatSql(sql) {
    const tokens = sql.match(SQL_TOKEN) ?? [];
    let text = "";
    let space = false;
    let depth = 0;
    let clause = "";
    let last = "";

    function write(token) {
        if (space && text && !/\s$/.test(text)) {
            text += " ";
        }
        text += token;
        space = false;
    }

    function line(indent = 0) {
        text = text.replace(/[ \t]+$/, "");
        if (text && !text.endsWith("\n")) {
            text += "\n";
        }
        text += " ".repeat(indent);
        space = false;
    }

    for (const token of tokens) {
        if (/^\s+$/.test(token)) {
            space = true;
            continue;
        }
        const keyword = token.toUpperCase();
        if (depth === 0 && (CLAUSE_START.has(keyword) || keyword === "GROUP" || keyword === "ORDER")) {
            line();
            clause = keyword;
        } else if (depth === 0 && keyword === "SELECT") {
            line();
            write(token);
            line(4);
            clause = keyword;
            last = keyword;
            continue;
        } else if (depth === 0 && (keyword === "JOIN" || JOIN_PREFIX.has(keyword))) {
            if (!JOIN_PREFIX.has(last)) {
                line();
            }
            clause = "JOIN";
        } else if (depth === 0 && keyword === "ON" && clause === "JOIN") {
            line(4);
            clause = "ON";
        } else if (depth === 0 && ["WHERE", "ON", "HAVING"].includes(clause)
                && (keyword === "AND" || keyword === "OR")) {
            line(4);
        }

        if (/^(=|==|<>|!=|<=|>=|<|>)$/.test(token)) {
            space = true;
            write(token);
            space = true;
        } else {
            write(token);
        }

        if (token === "(") {
            depth++;
        } else if (token === ")") {
            depth = Math.max(0, depth - 1);
        }
        if ((token === "," && depth === 0 && clause === "SELECT") || token.startsWith("--")) {
            line(clause === "SELECT" ? 4 : 0);
        }
        last = keyword;
    }
    return text.trim();
}

function highlightSql(code, sql) {
    const pieces = document.createDocumentFragment();
    for (const token of formatSql(sql).match(SQL_TOKEN) ?? []) {
        let style;
        if (KEYWORDS.has(token.toUpperCase())) {
            style = "sql-keyword";
        } else if (/^['"\x60\[]/.test(token)) {
            style = "sql-string";
        } else if (/^\d/.test(token)) {
            style = "sql-number";
        } else if (token.startsWith("--") || token.startsWith("/*")) {
            style = "sql-comment";
        }
        if (style) {
            const span = document.createElement("span");
            span.className = style;
            span.textContent = token;
            pieces.append(span);
        } else {
            pieces.append(token);
        }
    }
    code.append(pieces);
}

function renderSql(details, statements) {
    details.hidden = statements.length === 0;
    const container = details.querySelector(".sql-statements");
    statements.forEach((sql, index) => {
        const block = document.createElement("section");
        block.className = "sql-block";
        const toolbar = document.createElement("div");
        toolbar.className = "sql-bar";
        const label = document.createElement("span");
        label.textContent = statements.length === 1 ? "SQLite" : "SQL " + (index + 1);
        const copy = document.createElement("button");
        copy.type = "button";
        copy.className = "sql-copy";
        copy.textContent = "复制原始 SQL";
        copy.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(sql);
                copy.textContent = "已复制";
            } catch {
                copy.textContent = "复制失败";
            }
        });
        toolbar.append(label, copy);

        const pre = document.createElement("pre");
        const code = document.createElement("code");
        highlightSql(code, sql);
        pre.append(code);
        block.append(toolbar, pre);
        container.append(block);
    });
}

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

function toolLabel(step) {
    switch (step.name) {
        case "lookup_metric": {
            const metric = step.outcome?.metric;
            return metric ? "读取口径 " + metric.id + " · " + metric.name : "查找指标 " + (step.input?.term ?? "");
        }
        case "list_tables":
            return "列出可用数据表";
        case "describe_table":
            return "查看 " + (step.input?.name ?? "") + " 表结构";
        case "run_sql":
            return step.outcome?.error ? "SQL 执行受限或失败" : "执行只读 SQL";
        default:
            return step.name;
    }
}

function renderTrace(details, trace, elapsed) {
    details.hidden = trace.length === 0;
    if (details.hidden) {
        return;
    }
    const completed = trace.filter(step => step.type === "model" && Number.isFinite(step.tokens?.total_tokens));
    const tokens = completed.reduce((sum, step) => sum + step.tokens.total_tokens, 0);
    details.querySelector(".trace-summary").textContent = [
        completed.length + " 次模型调用",
        number.format(tokens) + " tokens",
        (elapsed / 1000).toFixed(1) + " 秒",
    ].join(" · ");

    const list = details.querySelector(".trace-steps");
    let round = 0;
    for (const step of trace) {
        const item = document.createElement("li");
        const title = document.createElement("span");
        const meta = document.createElement("span");
        meta.className = "step-meta";
        const data = [number.format(step.latency_ms) + " ms"];
        if (step.type === "model") {
            round++;
            title.textContent = "第 " + round + " 轮模型 · " + step.name;
            if (Number.isFinite(step.tokens?.total_tokens)) {
                data.unshift(number.format(step.tokens.total_tokens) + " tokens");
            } else {
                data.unshift("调用未完成");
            }
        } else {
            title.textContent = toolLabel(step);
            if (step.outcome?.error) {
                data.unshift(step.outcome.error);
            } else if (step.name === "run_sql" && Array.isArray(step.outcome?.rows)) {
                data.unshift("返回 " + step.outcome.rows.length + " 行");
            }
        }
        meta.textContent = data.join(" · ");
        item.append(title, meta);
        list.append(item);
    }

    const raw = details.querySelector(".raw-trace");
    raw.addEventListener("toggle", () => {
        if (raw.open) {
            raw.querySelector("pre").textContent = JSON.stringify(trace, null, 2);
        }
    });
}

function readHistory() {
    const entries = [];
    try {
        for (const key of Object.keys(localStorage)) {
            if (!key.startsWith(TURN_KEY_PREFIX)) {
                continue;
            }
            let entry;
            try {
                entry = JSON.parse(localStorage.getItem(key));
            } catch {
                continue;
            }
            if (entry?.id && entry.sessionId && typeof entry.question === "string" && Number.isFinite(entry.sentAt)) {
                entries.push(entry);
            }
        }
    } catch {
        historyNotice.hidden = false;
    }
    entries.sort((first, second) => first.sentAt - second.sentAt).forEach(rememberTurn);
}

function rememberTurn(entry) {
    if (!conversations.has(entry.sessionId)) {
        conversations.set(entry.sessionId, []);
    }
    conversations.get(entry.sessionId).push(entry);
}

function saveTurn(entry) {
    // 每条问答单独保存，其他标签页写入自己的问答时不会覆盖整段历史。
    try {
        localStorage.setItem(TURN_KEY_PREFIX + entry.id, JSON.stringify(entry));
    } catch {
        historyNotice.hidden = false;
    }
}

function renderHistory() {
    const items = document.createDocumentFragment();
    const newestFirst = [...conversations.entries()].sort(([, first], [, second]) =>
        second.at(-1).sentAt - first.at(-1).sentAt);
    for (const [sessionId, turns] of newestFirst) {
        const label = document.createElement("button");
        label.type = "button";
        label.textContent = turns[0].question;
        label.title = turns[0].question;
        if (sessionId === activeSessionId) {
            label.setAttribute("aria-current", "true");
        }
        label.addEventListener("click", () => {
            if (sessionId !== activeSessionId) {
                openConversation(sessionId);
                historyLinks.querySelector('[aria-current="true"]').focus();
            }
        });
        const item = document.createElement("li");
        item.append(label);
        items.append(item);
    }
    historyLinks.replaceChildren(items);
    historyEmpty.hidden = conversations.size > 0;
}

function updateSubmitButton() {
    submitButton.disabled = pendingTurns.has(conversations.get(activeSessionId)?.at(-1));
}

function openConversation(sessionId) {
    activeSessionId = conversations.has(sessionId) ? sessionId : null;
    try {
        // 每个标签页独立记住自己正在看的对话，历史内容仍由同一浏览器共享。
        sessionStorage.setItem(ACTIVE_CHAT_KEY, activeSessionId ?? "");
    } catch {
        // 会话存储不可用时，仍允许在当前页面切换对话。
    }
    queryHistory.replaceChildren();
    queryForm.reset();
    const turns = conversations.get(activeSessionId) ?? [];
    welcome.hidden = turns.length > 0;
    turns.forEach(renderTurn);
    conversationScroll.scrollTop = conversationScroll.scrollHeight;
    renderHistory();
    updateSubmitButton();
}

function renderTurn(entry) {
    const turn = turnTemplate.content.firstElementChild.cloneNode(true);
    turn.querySelector(".turn-question").textContent = entry.question;
    queryHistory.append(turn);
    if (!pendingTurns.has(entry)) {
        const result = entry.result ?? errorReply("这条问题的答复未保存，可以重新发送。");
        showReply(turn, result, entry.elapsed ?? 0);
    }
    return turn;
}

function showReply(turn, result, elapsed, scrollToReply = false) {
    const wasAtBottom = scrollToReply
        && conversationScroll.scrollHeight - conversationScroll.scrollTop - conversationScroll.clientHeight < 120;
    turn.dataset.status = result.status;
    turn.querySelector(".thinking").hidden = true;
    turn.querySelector(".turn-answer").textContent = result.answer;
    const metric = turn.querySelector(".metric-tag");
    if (result.metric_id) {
        metric.textContent = result.metric_id;
        metric.hidden = false;
    }
    renderTable(turn.querySelector(".turn-result"), result.table);
    renderSql(turn.querySelector(".turn-sql"), result.sql);
    renderTrace(turn.querySelector(".turn-trace"), result.trace, elapsed);
    turn.querySelector(".reply-content").hidden = false;
    if (wasAtBottom) {
        turn.querySelector(".reply-header").scrollIntoView({ block: "start" });
    }
}

function errorReply(message) {
    return { status: "error", answer: message, table: { columns: [], rows: [] }, sql: [], trace: [] };
}

for (const example of welcome.querySelectorAll("[data-question]")) {
    example.addEventListener("click", () => {
        questionField.value = example.dataset.question;
        questionField.focus();
    });
}

questionField.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        if (!submitButton.disabled && questionField.value.trim()) {
            queryForm.requestSubmit();
        }
    }
});

newChat.addEventListener("click", () => {
    openConversation(null);
    questionField.focus({ preventScroll: true });
});

queryForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const question = questionField.value.trim();
    if (!question || submitButton.disabled) {
        return;
    }
    const sessionId = activeSessionId ?? crypto.randomUUID();
    const entry = { id: crypto.randomUUID(), sessionId, question, sentAt: Date.now(), result: null };
    pendingTurns.add(entry);
    rememberTurn(entry);
    saveTurn(entry);
    if (activeSessionId === null) {
        openConversation(sessionId);
    } else {
        renderTurn(entry);
        renderHistory();
        updateSubmitButton();
    }
    queryHistory.lastElementChild.scrollIntoView({ block: "end" });
    const started = performance.now();
    const clock = window.setInterval(() => {
        if (activeSessionId === sessionId) {
            const timer = queryHistory.lastElementChild.querySelector(".thinking-time");
            timer.textContent = Math.floor((performance.now() - started) / 1000) + " 秒";
        }
    }, 250);
    queryForm.reset();
    questionField.focus({ preventScroll: true });

    let result;
    try {
        const response = await fetch("/ask", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ session_id: sessionId, question }),
        });
        result = response.ok ? await response.json() : errorReply("请求失败（HTTP " + response.status + "）。");
    } catch {
        result = errorReply("连接失败。请从 Java 启动日志里的网址打开页面。");
    } finally {
        window.clearInterval(clock);
    }
    entry.result = result;
    entry.elapsed = performance.now() - started;
    saveTurn(entry);
    pendingTurns.delete(entry);
    if (activeSessionId === sessionId) {
        showReply(queryHistory.lastElementChild, result, entry.elapsed, true);
        updateSubmitButton();
    }
});

readHistory();
let previousSessionId;
try {
    previousSessionId = sessionStorage.getItem(ACTIVE_CHAT_KEY);
} catch {
    // 会话存储不可用时，从空白对话开始。
}
openConversation(previousSessionId);
