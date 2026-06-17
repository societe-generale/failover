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
        loadSettings();
        markUpdated();
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

// ── Global settings (Config view) ─────────────────────────────────────────────

// Prefix stripped from each key to produce a short, readable label within its group card.
const SETTINGS_PREFIX = {
    Core: "failover.", Store: "failover.store.", Scheduler: "failover.scheduler.",
    Scatter: "failover.scatter.", Dashboard: "failover.dashboard.",
};

async function loadSettings() {
    const errorEl = document.getElementById("settings-error");
    try {
        const groups = await fetchJson("api/config/settings");
        errorEl.hidden = true;
        document.getElementById("settings-groups").replaceChildren(
            ...Object.entries(groups).map(([title, entries]) => settingsCard(title, entries)));
    } catch (e) {
        showError(errorEl, `Could not load settings: ${e.message}`);
    }
}

function settingsCard(title, entries) {
    const card = document.createElement("div");
    card.className = "settings-card";

    const head = document.createElement("div");
    head.className = "settings-card-head";
    const dot = document.createElement("span");
    dot.className = "settings-dot";
    dot.dataset.group = title;
    const h = document.createElement("span");
    h.className = "settings-card-title";
    h.textContent = title;
    head.append(dot, h);
    card.appendChild(head);

    const prefix = SETTINGS_PREFIX[title] ?? "failover.";
    for (const [key, value] of Object.entries(entries)) {
        const row = document.createElement("div");
        row.className = "settings-row";
        row.title = key; // full property key on hover

        const label = document.createElement("span");
        label.className = "settings-key";
        label.textContent = key.startsWith(prefix) ? key.slice(prefix.length) : key.replace(/^failover\./, "");

        row.append(label, settingsValue(value));
        card.appendChild(row);
    }
    return card;
}

function settingsValue(value) {
    const v = document.createElement("span");
    const empty = value === "" || value == null;
    const text = empty ? "—" : String(value);
    let kind = "neutral";
    if (text === "true") kind = "on";
    else if (text === "false") kind = "off";
    else if (empty) kind = "empty";
    v.className = `settings-val ${kind}`;
    v.textContent = text;
    return v;
}

// ── Metrics view ─────────────────────────────────────────────────────────────

const TREND_MAX = 60;
const charts = {};
// Timeline buffers (client-side, per poll tick): overall calls per tick + the three rates (%).
const timeline = { labels: [], calls: [], failoverRate: [], recoveryRate: [], nonRecoveryRate: [] };
// Per-API actual failures (failover invocations) per tick — one series per failover name.
const apiFailures = { labels: [], series: {} };
let lastOverall = null;
let lastApiFailover = {};
let lastSummary = null;
let timelineHydrated = false;

// Pull live values from the active theme so charts match the console palette.
function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function palette() {
    return {
        accent: cssVar("--accent"), good: cssVar("--good"), warn: cssVar("--warn"),
        bad: cssVar("--bad"), info: cssVar("--info"), violet: cssVar("--violet"),
    };
}

function applyChartTheme() {
    if (!hasCharts) return;
    const C = window.Chart;
    C.defaults.color = cssVar("--muted");
    C.defaults.borderColor = cssVar("--chart-grid");
    C.defaults.font.family = cssVar("--font-mono") || "monospace";
    C.defaults.font.size = 11;
}

async function loadMetrics() {
    const errorEl = document.getElementById("metrics-error");
    try {
        const [summary, health] = await Promise.all([fetchJson("api/metrics"), fetchJson("api/health")]);
        errorEl.hidden = true;
        lastSummary = summary;
        renderKpis(summary.overall);
        renderAsyncBanner(summary.overall);
        renderHealthTable(summary.perApi, health);
        await hydrateTimeline();
        pushTimeline(summary);
        if (hasCharts) renderCharts(summary);
        else document.getElementById("metrics-degraded").hidden = false;
        markUpdated();
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
    setKpi("async", overall.asyncFailed.toLocaleString());
    setKpi("latency", `${overall.latency.recoverMeanMs} ms`);
}

function setKpi(key, value) {
    document.querySelector(`[data-kpi="${key}"]`).textContent = value;
}

/** Loud banner when any async store write has failed — that data was silently not persisted. */
function renderAsyncBanner(overall) {
    const el = document.getElementById("async-banner");
    if (overall.asyncFailed > 0) {
        el.textContent = `⚠ ${overall.asyncFailed.toLocaleString()} async store write(s) failed inside the executor — `
            + `failover data was NOT persisted. Inspect logs and the failover.store.async.failed counter.`;
        el.hidden = false;
    } else {
        el.hidden = true;
    }
}

function renderHealthTable(perApi, health) {
    const statusByName = Object.fromEntries(health.map(h => [h.name, h.status]));
    const body = document.getElementById("health-body");
    body.replaceChildren(...perApi.map(k => {
        const tr = document.createElement("tr");
        const status = statusByName[k.name] ?? "UNHEALTHY";
        tr.appendChild(cell(k.name));
        tr.appendChild(cell(k.domain));
        tr.appendChild(cell(k.totalCalls.toLocaleString(), "num"));
        tr.appendChild(cell(pct(k.rates.successRate), "num"));
        tr.appendChild(cell(pct(k.rates.failoverRate), "num"));
        tr.appendChild(cell(pct(k.rates.recoveryRate), "num"));
        tr.appendChild(cell(pct(k.rates.nonRecoveryRate), "num"));
        tr.appendChild(cell(pct(k.rates.healthyRate), "num"));
        tr.appendChild(cell(k.recovered.toLocaleString(), "num"));
        tr.appendChild(cell(k.notRecovered.toLocaleString(), "num"));
        tr.appendChild(cell(k.errors.toLocaleString(), "num"));
        tr.appendChild(cell(k.asyncFailed.toLocaleString(), k.asyncFailed > 0 ? "num bad" : "num"));
        tr.appendChild(cell(`${k.latency.recoverMeanMs}`, "num"));
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

function rateP(v) { return Number((v * 100).toFixed(2)); }

function trim(obj, keys) {
    for (const k of keys) {
        if (obj[k].length > TREND_MAX) obj[k].shift();
    }
}

// Pre-fill the timeline chart from the server-side ring buffer (api/metrics/series) so a browser reload
// keeps its trend instead of starting blank. Runs at most once; a no-op (silently) when history is
// disabled (the /series endpoint is absent → fetch fails) or fewer than two samples are retained.
async function hydrateTimeline() {
    if (timelineHydrated) return;
    timelineHydrated = true; // attempt only once, regardless of outcome
    try {
        const points = await fetchJson("api/metrics/series?windowSec=0"); // 0 = all retained
        if (!Array.isArray(points) || points.length < 2) return;
        for (let i = 1; i < points.length; i++) {
            const p = points[i];
            timeline.labels.push(new Date(p.timestamp).toLocaleTimeString());
            timeline.calls.push(Math.max(0, p.calls - points[i - 1].calls));
            timeline.failoverRate.push(p.calls ? rateP(p.failover / p.calls) : 0);
            timeline.recoveryRate.push(p.failover ? rateP(p.recovered / p.failover) : 0);
            timeline.nonRecoveryRate.push(p.failover ? rateP((p.failover - p.recovered) / p.failover) : 0);
        }
        trim(timeline, ["labels", "calls", "failoverRate", "recoveryRate", "nonRecoveryRate"]);
        // Seed the delta baseline from the last sample so the first live tick continues seamlessly.
        lastOverall = { totalCalls: points[points.length - 1].calls };
    } catch {
        // history disabled or unavailable — the client-side buffer fills in from live polls instead
    }
}

function pushTimeline(summary) {
    const o = summary.overall;
    if (lastOverall) {
        const label = new Date().toLocaleTimeString();

        // overall calls (delta this tick) + cumulative rates
        timeline.labels.push(label);
        timeline.calls.push(Math.max(0, o.totalCalls - lastOverall.totalCalls));
        timeline.failoverRate.push(rateP(o.rates.failoverRate));
        timeline.recoveryRate.push(rateP(o.rates.recoveryRate));
        timeline.nonRecoveryRate.push(rateP(o.rates.nonRecoveryRate));
        trim(timeline, ["labels", "calls", "failoverRate", "recoveryRate", "nonRecoveryRate"]);

        // per-API actual failures (failover invocations) this tick
        apiFailures.labels.push(label);
        for (const k of summary.perApi) {
            const prev = lastApiFailover[k.name] ?? k.failoverInvoked;
            const arr = (apiFailures.series[k.name] ??= []);
            while (arr.length < apiFailures.labels.length - 1) arr.unshift(0); // pad new APIs
            arr.push(Math.max(0, k.failoverInvoked - prev));
        }
        trim(apiFailures, ["labels"]);
        for (const name of Object.keys(apiFailures.series)) trim(apiFailures.series, [name]);
    }
    lastOverall = o;
    lastApiFailover = Object.fromEntries(summary.perApi.map(k => [k.name, k.failoverInvoked]));
}

function renderCharts(summary) {
    applyChartTheme();
    const o = summary.overall;
    const p = palette();
    const fill = hex => hex + "30";           // ~19% alpha tint from a #rrggbb value
    const line = { tension: 0.35, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4 };
    const cycle = [p.info, p.violet, p.warn, p.good, p.bad, p.accent];

    upsertChart("chart-recovery", "doughnut", {
        labels: ["Recovered", "Not recovered + error"],
        datasets: [{
            data: [o.recovered, o.notRecovered + o.errors],
            backgroundColor: [p.good, p.bad],
            borderColor: cssVar("--surface"),
            borderWidth: 2,
        }],
    }, { cutout: "64%" });

    const noLegend = { plugins: { legend: { display: false } } };
    upsertChart("chart-perapi", "bar", {
        labels: summary.perApi.map(k => k.name),
        datasets: [
            { label: "Overall", data: summary.perApi.map(k => k.totalCalls), backgroundColor: fill(p.info), borderColor: p.info, borderWidth: 1 },
            { label: "Failover", data: summary.perApi.map(k => k.failoverInvoked), backgroundColor: fill(p.warn), borderColor: p.warn, borderWidth: 1 },
            { label: "Recovered", data: summary.perApi.map(k => k.recovered), backgroundColor: fill(p.good), borderColor: p.good, borderWidth: 1 },
            { label: "Not recovered", data: summary.perApi.map(k => k.notRecovered + k.errors), backgroundColor: fill(p.bad), borderColor: p.bad, borderWidth: 1 },
        ],
    }, { borderRadius: 4 });

    upsertChart("chart-store", "bar", {
        labels: ["Store (success)", "Recover (failover)"],
        datasets: [{ data: [o.upstreamSuccess, o.failoverInvoked], backgroundColor: [fill(p.accent), fill(p.warn)], borderColor: [p.accent, p.warn], borderWidth: 1 }],
    }, { ...noLegend, borderRadius: 4 });

    // Latency — per-API store vs recover mean (ms)
    upsertChart("chart-latency", "bar", {
        labels: summary.perApi.map(k => k.name),
        datasets: [
            { label: "store mean", data: summary.perApi.map(k => k.latency.storeMeanMs), backgroundColor: fill(p.accent), borderColor: p.accent, borderWidth: 1 },
            { label: "recover mean", data: summary.perApi.map(k => k.latency.recoverMeanMs), backgroundColor: fill(p.info), borderColor: p.info, borderWidth: 1 },
        ],
    }, { borderRadius: 4, scales: { y: { beginAtZero: true, title: { display: true, text: "ms" } } } });

    // Failover triggers — top exception types (horizontal bar; class simple-name on the axis, FQN in tooltip)
    const exShort = t => t.split(".").pop();
    upsertChart("chart-exceptions", "bar", {
        labels: summary.topExceptions.map(e => exShort(e.type)),
        datasets: [{ label: "occurrences", data: summary.topExceptions.map(e => e.count), backgroundColor: fill(p.bad), borderColor: p.bad, borderWidth: 1 }],
    }, {
        ...noLegend, indexAxis: "y", borderRadius: 4,
        plugins: { legend: { display: false }, tooltip: { callbacks: { title: items => summary.topExceptions[items[0].dataIndex].type } } },
    });

    // Success / Full recovery / Partial recovery. "recovered" includes partials, so full = recovered − partial.
    const fullRecovery = Math.max(0, o.recovered - o.partial);
    upsertChart("chart-outcome-mix", "bar", {
        labels: ["Success", "Full recovery", "Partial recovery"],
        datasets: [{
            data: [o.upstreamSuccess, fullRecovery, o.partial],
            backgroundColor: [fill(p.good), fill(p.info), fill(p.warn)],
            borderColor: [p.good, p.info, p.warn], borderWidth: 1,
        }],
    }, { ...noLegend, borderRadius: 4 });

    // Timeline: overall calls (count axis, right) + failover/recovery/non-recovery rates (% axis, left)
    upsertChart("chart-timeline", "line", {
        labels: timeline.labels,
        datasets: [
            { label: "overall calls", data: timeline.calls, yAxisID: "count", type: "bar", backgroundColor: fill(p.accent), borderColor: p.accent, borderWidth: 1 },
            { label: "failover rate %", data: timeline.failoverRate, yAxisID: "rate", borderColor: p.warn, backgroundColor: fill(p.warn), ...line },
            { label: "recovery rate %", data: timeline.recoveryRate, yAxisID: "rate", borderColor: p.good, backgroundColor: fill(p.good), ...line },
            { label: "non-recovery rate %", data: timeline.nonRecoveryRate, yAxisID: "rate", borderColor: p.bad, backgroundColor: fill(p.bad), ...line },
        ],
    }, {
        interaction: { mode: "index", intersect: false },
        scales: {
            rate: { type: "linear", position: "left", min: 0, max: 100, title: { display: true, text: "rate %" } },
            count: { type: "linear", position: "right", beginAtZero: true, grid: { drawOnChartArea: false }, title: { display: true, text: "calls / tick" } },
        },
    });

    // Per-API actual failures (failover invocations) over time — one line per failover
    upsertChart("chart-api-failures", "line", {
        labels: apiFailures.labels,
        datasets: Object.entries(apiFailures.series).map(([name, data], i) => ({
            label: name, data, borderColor: cycle[i % cycle.length], backgroundColor: fill(cycle[i % cycle.length]), ...line,
        })),
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

// ── Health view (actuator-style failover health) ─────────────────────────────

async function loadFailoverHealth() {
    const errorEl = document.getElementById("health-error");
    try {
        const health = await fetchJson("api/failover-health");
        errorEl.hidden = true;
        const up = health.status === "UP";

        const pill = document.getElementById("health-status");
        pill.textContent = health.status;
        pill.className = "status-pill " + (up ? "up" : "down");

        const hero = document.getElementById("health-hero");
        hero.classList.toggle("up", up);
        hero.classList.toggle("down", !up);
        document.getElementById("health-hero-icon").textContent = up ? "✓" : "✕";

        const grid = document.getElementById("health-details");
        grid.replaceChildren(...Object.entries(health.details).map(([k, v]) => healthCard(k, v === "" ? "—" : v)));
        markUpdated();
    } catch (e) {
        showError(errorEl, `Could not load failover health: ${e.message}`);
    }
}

/** One config detail rendered as a labelled card for the Health view. */
function healthCard(key, value) {
    const card = document.createElement("div");
    card.className = "health-card";
    const k = document.createElement("span");
    k.className = "health-card-key";
    k.textContent = key;
    const v = document.createElement("span");
    v.className = "health-card-val";
    v.textContent = value;
    card.append(k, v);
    return card;
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

/** Stamps the "last updated" indicator with the current time; called after every successful load. */
function markUpdated() {
    const el = document.getElementById("last-updated");
    if (el) el.textContent = new Date().toLocaleTimeString();
}

let refreshTimer = null;
let activeTab = "metrics";

function refreshActive() {
    if (activeTab === "config") loadConfig();
    else if (activeTab === "health") loadFailoverHealth();
    else loadMetrics();
}

function applyRefreshInterval() {
    if (refreshTimer) clearInterval(refreshTimer);
    const seconds = Number(document.getElementById("refresh-interval").value);
    document.getElementById("live-dot").classList.toggle("paused", seconds <= 0);
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

// Rebuild charts so they repaint with the active theme's palette (default config persists in buffers).
function repaintCharts() {
    if (!hasCharts) return;
    for (const id of Object.keys(charts)) {
        charts[id].destroy();
        delete charts[id];
    }
    if (activeTab === "metrics" && lastSummary) renderCharts(lastSummary);
}

function initTheme() {
    document.getElementById("theme-toggle").addEventListener("click", () => {
        const current = document.documentElement.dataset.theme || "dark";
        document.documentElement.dataset.theme = current === "dark" ? "light" : "dark";
        repaintCharts();
    });
}

initTabs();
initConfigControls();
initTheme();
document.getElementById("refresh-interval").addEventListener("change", applyRefreshInterval);
document.getElementById("refresh-now").addEventListener("click", refreshActive);
refreshActive();
applyRefreshInterval();

// Deep-link: ?theme=dark|light forces the theme; #metrics opens the metrics view directly.
const theme = new URLSearchParams(location.search).get("theme");
if (theme === "dark" || theme === "light") {
    document.documentElement.dataset.theme = theme;
}
const initialTab = document.querySelector(`.tab[data-tab="${location.hash.slice(1)}"]`);
if (initialTab) {
    initialTab.click();
}
