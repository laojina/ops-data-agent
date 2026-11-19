const queryForm = document.querySelector("#query-form");
const queryStatus = document.querySelector("#query-status");

queryForm.addEventListener("submit", (event) => {
    event.preventDefault();
    queryStatus.textContent = "接入 Agent 后即可查询。";
});
