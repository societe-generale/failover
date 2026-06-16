// Failover Dashboard — config (P1) + metrics (P3) views.
// Vanilla ES module, no build step, no remote scripts (CSP-friendly, design doc §7).
// Chart.js is an optional vendored UMD global; the UI degrades gracefully when it is absent.

const hasCharts = typeof window.Chart !== "undefined";

// ── Config view ──────────────────────────────────────────────────────────────

const CONFIG_COLUMNS = [
    "name", "domain", "expiryDuration", "expiryUnit", "storeType",
    "executionType", "recoverAll", "payloadSplitter", "keyGenerator", "expiryPolicy",
];

const configState = { rows: [], filter: "", sortKey: "name", sortDir: 1 };

async function loadConfig() {
    const errorEl = document.getElementById("config-error");
    try {
        configState.rows = await fetchJson("api/config");
        errorEl.hidden = true;
        renderConfig();
    } catch (e) {
        showError(errorEl, `Could not load configuration: ${e.message}`);
    }
}

function visibleConfigRows() {
    const q = configState.filter.trim().toLowerCase();
    const filtered = q
        ? configState.rows.filter(r => CONFIG_COLUMNS.some(c => String(r[c]).toLowerCase().includes(q)))
        : configState.rows.slice();
    const { sortKey, sortDir } = configState;
    return filtered.sort((a, b) => (a[sortKey] < b[sortKey] ? -1 : a[sortKey] > b[sortKey] ? 1 : 0) * sortDir);
}

function renderConfig() {
    const rows = visibleConfigRows();
    document.getElementById("config-body").replaceChildren(...rows.map(configRow));
    document.getElementById("config-count").textContent =
        `${rows.length} of ${configState.rows.length} failover point(s)`;
    document.querySelectorAll("#config-table th").forEach(th => {
        const active = th.dataset.key === configState.sortKey;
        th.setAttribute("aria-sort", active ? (configState.sortDir === 1 ? "ascending" : "descending") : "none");
    });
}

function configRow(r) {
    const tr = document.createElement("tr");
    for (const col of CONFIG_COLUMNS) {
        const td = document.createElement("td");
        td.textContent = col === "recoverAll" ? (r[col] ? "yes" : "—") : r[col];
        if (col === "expiryDuration") td.className = "num";
        tr.appendChild(td);
    }
    return tr;
}

// ── Metrics view ─────────────────────────────────────────────────────────────

const TREND_MAX = 60;
const charts = {};
const trend = { labels: [], calls: [], failover: [], recovered: [], notRecovered: [] };
let lastOverall = null;

async function loadMetrics() {
    const errorEl = document.getElementById("metrics-error");
    try {
        const [summary, health] = await Promise.all([fetchJson("api/metrics"), fetchJson("api/health")]);
        errorEl.hidden = true;
        renderKpis(summary.overall);
        renderHealthTable(summary.perApi, health);
        pushTrend(summary.overall);
        if (hasCharts) renderCharts(summary);
        else document.getElementById("metrics-degraded").hidden = false;
    } catch (e) {
        showError(errorEl, `Could not load metrics: ${e.message}`);
    }
}

function pct(v) { return `${(v * 100).toFixed(1)} %`; }

function renderKpis(overall) {
    const r = overall.rates;
    setKpi("overall", overall.totalCalls.toLocaleString());
    setKpi("success", pct(r.successRate));
    setKpi("failover", pct(r.failoverRate));
    setKpi("recovery", pct(r.recoveryRate));
    setKpi("nonRecovery", pct(r.nonRecoveryRate));
    setKpi("health", pct(r.healthyRate));
}

function setKpi(key, value) {
    document.querySelector(`[data-kpi="${key}"]`).textContent = value;
}

function renderHealthTable(perApi, health) {
    const statusByName = Object.fromEntries(health.map(h => [h.name, h.status]));
    const body = document.getElementById("health-body");
    body.replaceChildren(...perApi.map(k => {
        const tr = document.createElement("tr");
        const status = statusByName[k.name] ?? "UNHEALTHY";
        tr.appendChild(cell(k.name));
        tr.appendChild(cell(k.totalCalls.toLocaleString(), "num"));
        tr.appendChild(cell(pct(k.rates.successRate), "num"));
        tr.appendChild(cell(pct(k.rates.recoveryRate), "num"));
        const badge = document.createElement("td");
        const span = document.createElement("span");
        span.className = `badge ${status.toLowerCase()}`;
        span.textContent = status;
        badge.appendChild(span);
        tr.appendChild(badge);
        return tr;
    }));
}

function cell(text, cls) {
    const td = document.createElement("td");
    td.textContent = text;
    if (cls) td.className = cls;
    return td;
}

function pushTrend(overall) {
    if (lastOverall) {
        trend.labels.push(new Date().toLocaleTimeString());
        trend.calls.push(Math.max(0, overall.totalCalls - lastOverall.totalCalls));
        trend.failover.push(Math.max(0, overall.failoverInvoked - lastOverall.failoverInvoked));
        trend.recovered.push(Math.max(0, overall.recovered - lastOverall.recovered));
        trend.notRecovered.push(Math.max(0, overall.notRecovered - lastOverall.notRecovered));
        for (const k of ["labels", "calls", "failover", "recovered", "notRecovered"]) {
            if (trend[k].length > TREND_MAX) trend[k].shift();
        }
    }
    lastOverall = overall;
}

function renderCharts(summary) {
    const o = summary.overall;
    upsertChart("chart-recovery", "doughnut", {
        labels: ["Recovered", "Not recovered + error"],
        datasets: [{ data: [o.recovered, o.notRecovered + o.errors] }],
    });
    // single-series bars: no dataset label, so suppress the "undefined" legend entry
    const noLegend = { plugins: { legend: { display: false } } };
    upsertChart("chart-perapi", "bar", {
        labels: summary.perApi.map(k => k.name),
        datasets: [
            { label: "Overall", data: summary.perApi.map(k => k.totalCalls) },
            { label: "Failover", data: summary.perApi.map(k => k.failoverInvoked) },
            { label: "Recovered", data: summary.perApi.map(k => k.recovered) },
            { label: "Not recovered", data: summary.perApi.map(k => k.notRecovered + k.errors) },
        ],
    });
    upsertChart("chart-store", "bar", {
        labels: ["Store (success)", "Recover (failover)"],
        datasets: [{ data: [o.upstreamSuccess, o.failoverInvoked] }],
    }, noLegend);
    upsertChart("chart-trend", "line", {
        labels: trend.labels,
        datasets: [
            { label: "calls", data: trend.calls },
            { label: "failover", data: trend.failover },
            { label: "recovered", data: trend.recovered },
            { label: "not-recovered", data: trend.notRecovered },
        ],
    });
}

function upsertChart(canvasId, type, data, extraOptions) {
    if (charts[canvasId]) {
        charts[canvasId].data = data;
        charts[canvasId].update();
        return;
    }
    charts[canvasId] = new window.Chart(document.getElementById(canvasId), {
        type, data,
        options: { responsive: true, maintainAspectRatio: false, ...extraOptions },
    });
}

// ── Shared / shell ───────────────────────────────────────────────────────────

async function fetchJson(path) {
    const res = await fetch(path, { headers: { Accept: "application/json" } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

function showError(el, message) {
    el.textContent = message;
    el.hidden = false;
}

let refreshTimer = null;
let activeTab = "config";

function refreshActive() {
    if (activeTab === "config") loadConfig();
    else loadMetrics();
}

function applyRefreshInterval() {
    if (refreshTimer) clearInterval(refreshTimer);
    const seconds = Number(document.getElementById("refresh-interval").value);
    if (seconds > 0) refreshTimer = setInterval(refreshActive, seconds * 1000);
}

function initTabs() {
    document.querySelectorAll(".tab").forEach(tab => tab.addEventListener("click", () => {
        activeTab = tab.dataset.tab;
        document.querySelectorAll(".tab").forEach(t => {
            const on = t === tab;
            t.classList.toggle("active", on);
            t.setAttribute("aria-selected", String(on));
        });
        document.querySelectorAll(".panel").forEach(p => { p.hidden = p.id !== activeTab; });
        refreshActive();
    }));
}

function initConfigControls() {
    document.querySelectorAll("#config-table th").forEach(th => th.addEventListener("click", () => {
        const key = th.dataset.key;
        if (configState.sortKey === key) configState.sortDir *= -1;
        else { configState.sortKey = key; configState.sortDir = 1; }
        renderConfig();
    }));
    document.getElementById("config-filter").addEventListener("input", e => {
        configState.filter = e.target.value;
        renderConfig();
    });
}

function initTheme() {
    document.getElementById("theme-toggle").addEventListener("click", () => {
        document.documentElement.dataset.theme =
            document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    });
}

initTabs();
initConfigControls();
initTheme();
document.getElementById("refresh-interval").addEventListener("change", applyRefreshInterval);
loadConfig();
applyRefreshInterval();

// Deep-link: ?theme=dark|light forces the theme; #metrics opens the metrics view directly.
const theme = new URLSearchParams(location.search).get("theme");
if (theme === "dark" || theme === "light") {
    document.documentElement.dataset.theme = theme;
}
if (location.hash === "#metrics") {
    document.querySelector('.tab[data-tab="metrics"]').click();
}
