const queryForm = document.querySelector("#query-form");
const queryStatus = document.querySelector("#query-status");

queryForm.addEventListener("submit", (event) => {
    // 让 JavaScript 接管表单提交，避免浏览器刷新页面。后续在这里接查数 API。
    event.preventDefault();
    queryStatus.textContent = "接入 Agent 后即可查询。";
});
