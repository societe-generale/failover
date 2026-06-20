// Failover Dashboard — wired to the read-only JSON API.
// Vanilla ES module, no build step, no remote scripts (CSP-friendly, design doc §7).
// Chart.js is a vendored UMD global; the UI degrades gracefully when metrics are absent.
//
// Endpoints (relative to the configured base-path, the page is served from there):
//   api/metrics            → MetricsSummary {overall, perApi, topExceptions, timestamp}
//   api/health             → [ApiHealth {name, status, healthyRate}]
//   api/config             → [ConfigEntry]
//   api/config/settings    → { group: { key: value } }
//   api/failover-health    → FailoverHealth {status, details}
//   api/metrics/series     → [SeriesPoint]   (only when history.enabled=true; 404 otherwise)

const charts = {};
const pct = v => `${(v * 100).toFixed(1)}%`;
const n = v => Number(v).toLocaleString();
const ms = v => `${(Math.round(v * 10) / 10)}ms`;
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const P = () => ({
    accent: css('--accent'), good: css('--good'), warn: css('--warn'), bad: css('--bad'),
    info: css('--info'), violet: css('--violet'), muted: css('--muted'), surface: css('--surface'),
});
const tint = (h, a = '30') => h + a;
const classify = h => h >= 0.99 ? 'HEALTHY' : h >= 0.90 ? 'DEGRADED' : 'UNHEALTHY';

// ── State ─────────────────────────────────────────────────────────────────────
let lastSummary = null;
let statusByName = {};          // name → HEALTHY|DEGRADED|UNHEALTHY (from api/health)
let configRows = [];
let settingsGroups = null;
let bannerDismissed = false;
let activeView = 'overview';

// client-side trend buffers (filled live; the overall line can be pre-seeded from api/metrics/series)
const TREND_MAX = 30;
const timeline = { labels: [], calls: [], succ: [], fail: [], rec: [] };
const apiTrend = { labels: [], series: {} };
let lastTotal = null;
let lastApiFailover = {};
let timelineHydrated = false;

// Effective health for a failover point. With no calls yet there is nothing wrong, so it is HEALTHY
// at 100% — never UNHEALTHY (a 0/0 healthyRate from the server would otherwise classify as UNHEALTHY).
function effRate(k) { return k.totalCalls > 0 ? k.rates.healthyRate : 1; }
function effStatus(k) {
    if (k.totalCalls === 0) return 'HEALTHY';
    return statusByName[k.name] || classify(k.rates.healthyRate);
}

// ── KPI cards (Signals) ────────────────────────────────────────────────────────
function kpiCard(k) {
    const valrow = k.of !== undefined
        ? `<div class="valrow"><span class="val">${k.val}</span><span class="of">${k.of}</span></div>`
        : `<div class="val">${k.val}</div>`;
    return `<div class="kpi" data-c="${k.c}">
        <div class="top"><span class="lbl">${k.lbl}</span><span class="ic">${k.ic}</span></div>
        ${valrow}<div class="sub">${k.sub}</div></div>`;
}

function kpiCards(o, perApi) {
    const r = o.rates;
    const hasCalls = o.totalCalls > 0;
    const oRate = hasCalls ? r.healthyRate : 1;     // no calls yet ⇒ nothing wrong ⇒ 100% healthy
    const status = classify(oRate);
    const hardFail = o.notRecovered + o.errors;
    const usable = o.upstreamSuccess + o.recovered;

    // Row 1 — overall health + a per-API health card per failover point, worst-first.
    const apis = [...perApi].sort((a, b) => effRate(a) - effRate(b));
    const worst = apis.filter(k => effStatus(k) !== 'HEALTHY').length;
    const overall = `<div class="health-overall">
        <div class="top"><span class="lbl">Overall API Health</span>
            <span class="badge ${status.toLowerCase()}">${status}</span></div>
        <div class="gauge-lg"><canvas id="g_health"></canvas><div class="gv">${hasCalls ? pct(oRate) : '—'}</div></div>
        <div class="note">${hasCalls ? `${n(usable)} of ${n(o.totalCalls)} calls usable` : 'no calls yet'}<br>${
            worst > 0 ? `<b style="color:var(--bad)">${worst} need attention</b>` : 'all APIs healthy'}</div>
    </div>`;
    const apiCards = apis.map(k => {
        const st = effStatus(k), h = (effRate(k) * 100).toFixed(1);
        return `<div class="api-hcard ${st.toLowerCase()}" title="${k.name} · ${k.domain}">
            <div class="nm">${k.name}</div><div class="dm">${k.domain}</div>
            <div class="mid"><span class="hpct">${h}%</span><span class="st">${st}</span></div>
            <div class="hbar"><i style="width:${h}%"></i></div>
            <div class="meta"><span>calls <b>${n(k.totalCalls)}</b></span><span>fail <b>${pct(k.rates.failoverRate)}</b></span></div>
        </div>`;
    }).join('');
    const healthStrip = `<div class="health-strip">${overall}<div class="api-health-grid">${apiCards}</div></div>`;

    // Row 2 — the metric strip: rate + actual count.
    const row2 = [
        { c: 'calls', lbl: 'Overall calls', ic: '∑', val: n(o.totalCalls), sub: 'every intercepted @Failover call' },
        { c: 'success', lbl: 'Success rate', ic: '✓', val: pct(r.successRate), of: n(o.upstreamSuccess), sub: 'upstream healthy → stored' },
        { c: 'failover', lbl: 'Failover rate', ic: '⚡', val: pct(r.failoverRate), of: n(o.failoverInvoked), sub: 'upstream failed → failover ran' },
        { c: 'recovery', lbl: 'Recovery rate', ic: '↺', val: pct(r.recoveryRate), of: n(o.recovered), sub: `failover served a value · ${n(o.partial)} partial` },
        { c: 'nonrec', lbl: 'Non-recovery rate', ic: '✕', val: pct(r.nonRecoveryRate), of: n(hardFail), sub: 'failover found nothing usable' },
        { c: 'async', lbl: 'Persistence failures', ic: '!', val: n(o.asyncFailed),
            sub: o.asyncFailed > 0 ? '⚠ async writes lost — data NOT saved' : 'all store writes persisted' },
        { c: 'latency', lbl: 'Recover latency', ic: '◷', val: ms(o.latency.recoverMeanMs), sub: 'mean recover-path time' },
    ];

    // Rebuilding #kpis replaces the <canvas id="g_health"> node, orphaning the old chart instance —
    // destroy it first so the gauge re-attaches to the fresh canvas (otherwise the ring vanishes).
    if (charts.g_health) { charts.g_health.destroy(); delete charts.g_health; }
    document.getElementById('kpis').innerHTML =
        healthStrip + `<div class="kpis row2">${row2.map(kpiCard).join('')}</div>`;
    gauge(oRate);
}

function gauge(v) {
    const p = P(), col = v >= 0.99 ? p.good : v >= 0.90 ? p.warn : p.bad;
    up('g_health', 'doughnut', {
        labels: ['', ''],
        datasets: [{ data: [v, 1 - v], backgroundColor: [col, css('--surface-2')], borderWidth: 0 }],
    }, { cutout: '76%', plugins: { legend: { display: false }, tooltip: { enabled: false } } });
}

// ── Overview charts ─────────────────────────────────────────────────────────────
function overviewCharts(summary) {
    const o = summary.overall, perApi = summary.perApi, ex = summary.topExceptions || [];
    const p = P();
    const noLeg = { plugins: { legend: { display: false } } };
    const bottomLeg = box => ({ plugins: { legend: { position: 'bottom', labels: { boxWidth: box, padding: 14, font: { size: 11 } } } } });

    const failed = o.notRecovered + o.errors;
    up('c_outcome', 'doughnut', {
        labels: ['Live value', 'Recovered from cache', 'Hard failure'],
        datasets: [{ data: [o.upstreamSuccess, o.recovered, failed], backgroundColor: [p.good, p.info, p.bad], borderColor: css('--surface'), borderWidth: 3 }],
    }, { cutout: '62%', ...bottomLeg(10) });

    const fullRec = Math.max(0, o.recovered - o.partial);
    up('c_recovery', 'doughnut', {
        labels: ['Full recovery', 'Partial recovery', 'Nothing usable'],
        datasets: [{ data: [fullRec, o.partial, failed], backgroundColor: [p.good, p.warn, p.bad], borderColor: css('--surface'), borderWidth: 3 }],
    }, { cutout: '62%', ...bottomLeg(10) });

    up('c_store', 'bar', {
        labels: ['Successful', 'Recovered'],
        datasets: [{ data: [o.upstreamSuccess, o.recovered], backgroundColor: [tint(p.good), tint(p.info)], borderColor: [p.good, p.info], borderWidth: 1.5, borderRadius: 8 }],
    }, { ...noLeg, scales: { y: { beginAtZero: true } } });

    const exShort = t => t.split('.').pop();
    up('c_ex', 'bar', {
        labels: ex.map(e => exShort(e.type)),
        datasets: [{ data: ex.map(e => e.count), backgroundColor: tint(p.bad), borderColor: p.bad, borderWidth: 1.5, borderRadius: 6 }],
    }, { ...noLeg, indexAxis: 'y', plugins: { legend: { display: false }, tooltip: { callbacks: { title: i => ex[i[0].dataIndex].type } } } });

    up('c_latency', 'bar', {
        labels: perApi.map(k => k.name),
        datasets: [
            { label: 'store', data: perApi.map(k => k.latency.storeMeanMs), backgroundColor: tint(p.accent), borderColor: p.accent, borderWidth: 1.5, borderRadius: 6 },
            { label: 'recover', data: perApi.map(k => k.latency.recoverMeanMs), backgroundColor: tint(p.info), borderColor: p.info, borderWidth: 1.5, borderRadius: 6 },
        ],
    }, { ...bottomLeg(10), scales: { y: { beginAtZero: true, title: { display: true, text: 'ms' } } } });

    up('c_timeline', 'line', {
        labels: timeline.labels,
        datasets: [
            { label: 'calls / tick', data: timeline.calls, yAxisID: 'cnt', type: 'bar', backgroundColor: tint(p.accent, '22'), borderColor: p.accent, borderWidth: 0, borderRadius: 3 },
            { label: 'success %', data: timeline.succ, yAxisID: 'rate', borderColor: p.good, backgroundColor: tint(p.good), tension: .4, borderWidth: 2, pointRadius: 0, fill: true },
            { label: 'failover %', data: timeline.fail, yAxisID: 'rate', borderColor: p.warn, tension: .4, borderWidth: 2, pointRadius: 0 },
            { label: 'recovery %', data: timeline.rec, yAxisID: 'rate', borderColor: p.info, tension: .4, borderWidth: 2, pointRadius: 0 },
        ],
    }, {
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: { position: 'bottom', labels: { boxWidth: 14, padding: 16, font: { size: 11 } } } },
        scales: {
            rate: { type: 'linear', position: 'left', min: 0, max: 100, title: { display: true, text: 'rate %' } },
            cnt: { type: 'linear', position: 'right', beginAtZero: true, grid: { drawOnChartArea: false }, title: { display: true, text: 'calls' } },
        },
    });
}

// ── Per-API view ────────────────────────────────────────────────────────────────
function apiTrendChart() {
    const p = P();
    const cycle = [p.accent, p.warn, p.info, p.violet, p.bad, p.good];
    up('c_apitrend', 'line', {
        labels: apiTrend.labels,
        datasets: Object.entries(apiTrend.series).map(([name, data], i) => ({
            label: name, data, borderColor: cycle[i % cycle.length], backgroundColor: tint(cycle[i % cycle.length], '18'),
            tension: .4, borderWidth: 2, pointRadius: 2, pointHoverRadius: 4, fill: false,
        })),
    }, {
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 14, font: { size: 11 } } } },
        scales: { y: { beginAtZero: true, title: { display: true, text: 'failovers / tick' } } },
    });
}

function perApiChart(perApi) {
    const p = P();
    up('c_perapi', 'bar', {
        labels: perApi.map(k => k.name),
        datasets: [
            { label: 'Overall', data: perApi.map(k => k.totalCalls), backgroundColor: tint(p.accent), borderColor: p.accent, borderWidth: 1, borderRadius: 5 },
            { label: 'Failover', data: perApi.map(k => k.failoverInvoked), backgroundColor: tint(p.warn), borderColor: p.warn, borderWidth: 1, borderRadius: 5 },
            { label: 'Recovered', data: perApi.map(k => k.recovered), backgroundColor: tint(p.good), borderColor: p.good, borderWidth: 1, borderRadius: 5 },
            { label: 'Not recovered', data: perApi.map(k => k.notRecovered + k.errors), backgroundColor: tint(p.bad), borderColor: p.bad, borderWidth: 1, borderRadius: 5 },
        ],
    }, { plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 14, font: { size: 11 } } } }, scales: { y: { beginAtZero: true } } });
}

function sparkSvg(name) {
    const series = apiTrend.series[name] || [];
    const values = series.length >= 2 ? series : [0, 0];
    const w = 90, h = 28, max = Math.max(...values, 1), min = Math.min(...values, 0), rng = max - min || 1;
    const pts = values.map((v, i) => `${(i / (values.length - 1)) * w},${h - ((v - min) / rng) * (h - 4) - 2}`).join(' ');
    return `<svg class="spark" viewBox="0 0 ${w} ${h}"><polyline fill="none" stroke="${css('--warn')}" stroke-width="1.6" points="${pts}"/></svg>`;
}

let apiSort = { key: 'totalCalls', dir: -1 };
function apiTable(perApi) {
    const val = (k, key) => key === 'healthy' ? k.rates.healthyRate
        : (key in k.rates ? k.rates[key] : k[key]);
    const rows = [...perApi].sort((a, b) => {
        const va = val(a, apiSort.key), vb = val(b, apiSort.key);
        return (va < vb ? -1 : va > vb ? 1 : 0) * apiSort.dir;
    });
    const p = P();
    document.getElementById('apibody').innerHTML = rows.map(k => {
        const st = effStatus(k);
        const hp = (effRate(k) * 100).toFixed(1);
        const col = st === 'HEALTHY' ? p.good : st === 'DEGRADED' ? p.warn : p.bad;
        return `<tr>
            <td class="name-cell">${k.name}<small>${k.domain}</small></td>
            <td class="r num">${n(k.totalCalls)}</td>
            <td class="barcell"><div class="barrow">
                <div class="bar"><i style="width:${hp}%;background:${col}"></i></div>
                <span class="pctn">${hp}%</span></div></td>
            <td class="r num">${pct(k.rates.successRate)}</td>
            <td class="r num">${pct(k.rates.failoverRate)}</td>
            <td class="r num">${pct(k.rates.recoveryRate)}</td>
            <td class="r num">${n(k.errors)}</td>
            <td>${sparkSvg(k.name)}</td>
            <td><span class="badge ${st.toLowerCase()}">${st}</span></td>
        </tr>`;
    }).join('');
}

function initApiSort() {
    document.querySelectorAll('#apitable thead th.sortable').forEach(th => {
        th.onclick = () => {
            const k = th.dataset.key;
            if (apiSort.key === k) apiSort.dir *= -1; else { apiSort.key = k; apiSort.dir = -1; }
            if (lastSummary) apiTable(lastSummary.perApi);
        };
    });
}

// ── Config view ─────────────────────────────────────────────────────────────────
let cfgFilter = '';
function configTable() {
    const q = cfgFilter.toLowerCase();
    const rows = configRows.filter(r => !q ||
        [r.name, r.domain, r.storeType, r.executionType].some(v => String(v).toLowerCase().includes(q)));
    const dash = '<span style="color:var(--faint)">—</span>';
    const norm = v => (!v || v === '') ? dash : v === 'default' ? '<span style="color:var(--faint)">default</span>' : v;
    document.getElementById('cfgbody').innerHTML = rows.map(r => `<tr>
        <td class="name-cell">${r.name}</td>
        <td>${r.domain}</td>
        <td class="r num">${r.expiryDuration} <span style="color:var(--faint)">${String(r.expiryUnit).toLowerCase()}</span></td>
        <td><span class="pill-tag">${r.storeType}</span></td>
        <td><span class="pill-tag">${r.executionType}</span></td>
        <td>${r.recoverAll ? '<span class="badge healthy" style="padding:2px 8px">yes</span>' : dash}</td>
        <td>${norm(r.payloadSplitter)}</td>
        <td>${norm(r.keyGenerator)}</td>
        <td>${norm(r.expiryPolicy)}</td>
    </tr>`).join('');
    document.getElementById('cfgcount').textContent = `${rows.length} of ${configRows.length} failover point(s)`;
}

function renderSettings() {
    if (!settingsGroups) return;
    document.getElementById('settings').innerHTML = Object.entries(settingsGroups).map(([g, kv]) => {
        const rows = Object.entries(kv).map(([k, v]) => {
            const key = k.replace(/^failover\.[a-z]*\.?/, '') || k;
            let cls = '', txt = String(v);
            if (v === 'true' || v === true) { cls = 'on'; txt = 'true'; }
            else if (v === 'false' || v === false) { cls = 'off'; txt = 'false'; }
            else if (v === '' || v == null) { cls = 'empty'; txt = '—'; }
            return `<div class="kvrow"><span class="k" title="${k}">${key}</span><span class="v ${cls}">${txt}</span></div>`;
        }).join('');
        return `<div class="kvcard"><div class="g">${g}</div>${rows}</div>`;
    }).join('');
}

// ── Health view (actuator-style) ─────────────────────────────────────────────────
function renderFailoverHealth(h) {
    const up = h.status === 'UP';
    const hero = document.getElementById('health-hero');
    hero.className = 'health-hero ' + (up ? 'up' : 'down');
    document.getElementById('health-hero-ic').textContent = up ? '✓' : '✕';
    document.getElementById('health-hero-status').textContent = h.status;
    document.getElementById('health-hero-note').textContent = up
        ? 'At least one @Failover point is registered and serving.'
        : 'No @Failover points discovered — likely a misconfiguration.';
    const details = h.details || {};
    document.getElementById('health-details').innerHTML =
        Object.entries(details).map(([k, v]) => {
            const empty = v === '' || v == null;
            const cls = v === 'true' ? 'on' : v === 'false' ? 'off' : empty ? 'empty' : '';
            const label = k.replace(/[-.]/g, ' ');
            return `<div class="stat-card">
                <span class="stat-k">${label}</span>
                <span class="stat-v ${cls}">${empty ? '—' : v}</span>
            </div>`;
        }).join('');
}

// ── Trend buffers ─────────────────────────────────────────────────────────────────
function trim(buf, keys) { keys.forEach(k => { if (buf[k].length > TREND_MAX) buf[k].shift(); }); }

// Pre-fill the overall timeline from the server ring buffer (api/metrics/series) so a reload keeps
// its trend. No-op when history is disabled (endpoint 404) or fewer than two samples are retained.
async function hydrateTimeline() {
    if (timelineHydrated) return;
    timelineHydrated = true;
    try {
        const pts = await fetchJson('api/metrics/series?windowSec=0');
        if (!Array.isArray(pts) || pts.length < 2) return;
        for (let i = 1; i < pts.length; i++) {
            const a = pts[i - 1], b = pts[i];
            const label = new Date(b.timestamp).toLocaleTimeString();
            timeline.labels.push(label);
            timeline.calls.push(Math.max(0, b.calls - a.calls));
            timeline.succ.push(b.calls ? +((b.store - a.store) / Math.max(1, b.calls - a.calls) * 100).toFixed(1) : 0);
            timeline.fail.push(b.calls ? +(b.failover / b.calls * 100).toFixed(1) : 0);
            timeline.rec.push(b.failover ? +(b.recovered / b.failover * 100).toFixed(1) : 0);

            // per-API failover-trend, rebuilt server-side so it survives a reload
            const fa = b.failoverByApi || {}, fp = a.failoverByApi || {};
            apiTrend.labels.push(label);
            for (const name of Object.keys(fa)) {
                const arr = (apiTrend.series[name] ??= []);
                while (arr.length < apiTrend.labels.length - 1) arr.unshift(0);
                arr.push(Math.max(0, (fa[name] ?? 0) - (fp[name] ?? 0)));
            }
        }
        trim(timeline, ['labels', 'calls', 'succ', 'fail', 'rec']);
        trim(apiTrend, ['labels']);
        Object.keys(apiTrend.series).forEach(name => { if (apiTrend.series[name].length > TREND_MAX) apiTrend.series[name].shift(); });
        const last = pts[pts.length - 1];
        lastTotal = last.calls;
        lastApiFailover = { ...(last.failoverByApi || {}) };  // continue live deltas seamlessly
    } catch { /* history disabled — live polls fill the buffer instead */ }
}

function pushTick(o, perApi) {
    if (lastTotal != null) {
        const label = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        timeline.labels.push(label);
        timeline.calls.push(Math.max(0, o.totalCalls - lastTotal));
        timeline.succ.push(+(o.rates.successRate * 100).toFixed(1));
        timeline.fail.push(+(o.rates.failoverRate * 100).toFixed(1));
        timeline.rec.push(+(o.rates.recoveryRate * 100).toFixed(1));
        trim(timeline, ['labels', 'calls', 'succ', 'fail', 'rec']);

        // Seed one leading zero baseline so the line is visible from the very first sample
        // (there is no per-API history endpoint to hydrate from, unlike the overall timeline).
        if (apiTrend.labels.length === 0) {
            apiTrend.labels.push('');
            for (const k of perApi) (apiTrend.series[k.name] ??= []).push(0);
        }
        apiTrend.labels.push(label);
        for (const k of perApi) {
            const prev = lastApiFailover[k.name] ?? k.failoverInvoked;
            const arr = (apiTrend.series[k.name] ??= []);
            while (arr.length < apiTrend.labels.length - 1) arr.unshift(0);
            arr.push(Math.max(0, k.failoverInvoked - prev));
        }
        trim(apiTrend, ['labels']);
        Object.keys(apiTrend.series).forEach(name => { if (apiTrend.series[name].length > TREND_MAX) apiTrend.series[name].shift(); });
    }
    lastTotal = o.totalCalls;
    lastApiFailover = Object.fromEntries(perApi.map(k => [k.name, k.failoverInvoked]));
}

// ── Status chip + banner ──────────────────────────────────────────────────────────
function statusBar(perApi) {
    const sts = perApi.map(effStatus);
    const st = sts.includes('UNHEALTHY') ? 'down' : sts.includes('DEGRADED') ? 'degraded' : 'up';
    const chip = document.getElementById('statuschip');
    chip.className = 'statuschip' + (st === 'up' ? '' : ' ' + st);
    document.getElementById('statustext').textContent =
        st === 'up' ? 'Healthy' : st === 'degraded' ? 'Degraded' : 'Unhealthy';
}

// Health summary banner — re-rendered on every load (so it tracks live health), user-closable.
function showBanner(perApi) {
    const bad = perApi.filter(k => effStatus(k) === 'UNHEALTHY');
    const deg = perApi.filter(k => effStatus(k) === 'DEGRADED');
    let kind, icon, title, sub;
    if (bad.length) {
        kind = 'bad'; icon = '✕'; title = `${bad.length} API${bad.length > 1 ? 's' : ''} unhealthy — action needed`;
        sub = `Failover frequently cannot recover: <b>${bad.map(k => k.name).join(', ')}</b>`;
    } else if (deg.length) {
        kind = 'warn'; icon = '!'; title = `${deg.length} API${deg.length > 1 ? 's' : ''} need attention`;
        sub = `Failover working but firing often: <b>${deg.map(k => k.name).join(', ')}</b>`;
    } else {
        kind = 'ok'; icon = '✓'; title = 'All APIs healthy';
        sub = `<b>${perApi.length}</b> failover point(s) serving usable results`;
    }
    document.getElementById('banner-slot').innerHTML =
        `<div class="infobanner ${kind}" role="status">
            <div class="ib-ic">${icon}</div>
            <div class="ib-main"><div class="ib-title">${title}</div><div class="ib-sub">${sub}</div></div>
            <span class="ib-time">${new Date().toLocaleTimeString()}</span>
            <button class="ib-x" aria-label="Dismiss">×</button>
        </div>`;
    document.querySelector('.ib-x').onclick = () => {
        bannerDismissed = true;
        document.getElementById('banner-slot').innerHTML = '';
    };
}

// ── Loaders ─────────────────────────────────────────────────────────────────────
async function loadMetrics() {
    try {
        const [summary, health] = await Promise.all([fetchJson('api/metrics'), fetchJson('api/health')]);
        lastSummary = summary;
        statusByName = Object.fromEntries((health || []).map(h => [h.name, h.status]));
        clearNotice();
        statusBar(summary.perApi);
        await hydrateTimeline();
        pushTick(summary.overall, summary.perApi);
        kpiCards(summary.overall, summary.perApi);
        if (activeView === 'overview') overviewCharts(summary);
        if (activeView === 'apis') { apiTable(summary.perApi); apiTrendChart(); perApiChart(summary.perApi); }
        if (!bannerDismissed) showBanner(summary.perApi);
        markUpdated();
        fetchJson('api/metrics/source').then(renderSourceBadge).catch(() => {}); // non-fatal
    } catch (e) {
        showNotice(`Metrics unavailable — ${e.message}. The Config and Health views still work without Micrometer.`);
    }
}

// Metrics-provenance badge so single-instance figures are never misread as a cluster aggregate.
function renderSourceBadge(info) {
    const el = document.getElementById('src-badge');
    if (!info || !info.mode) { el.hidden = true; return; }
    el.hidden = false;
    if (info.mode === 'local') {
        el.className = 'src-badge tip local';
        el.textContent = 'This instance only';
        el.dataset.tip = "Metrics from this instance's in-process registry only — not a cluster aggregate";
    } else {
        const partial = !!info.partial;
        el.className = 'src-badge tip cluster' + (partial ? ' partial' : '');
        const count = info.instancesExpected > 0
            ? `${info.instancesReporting}/${info.instancesExpected}` : `${info.instancesReporting}`;
        el.textContent = `Cluster · ${count}`;
        el.dataset.tip = `Cluster aggregate (${info.mode}) · ${info.instancesReporting} instance(s) reporting`
            + (partial ? ' · partial data' : '');
    }
}

async function loadConfig() {
    try {
        [configRows, settingsGroups] = await Promise.all([fetchJson('api/config'), fetchJson('api/config/settings')]);
        clearNotice();
        configTable();
        renderSettings();
        markUpdated();
    } catch (e) {
        showNotice(`Could not load configuration — ${e.message}`);
    }
}

async function loadFailoverHealth() {
    try {
        renderFailoverHealth(await fetchJson('api/failover-health'));
        clearNotice();
        markUpdated();
    } catch (e) {
        showNotice(`Could not load failover health — ${e.message}`);
    }
}

function refreshActive() {
    if (activeView === 'config') loadConfig();
    else if (activeView === 'health') loadFailoverHealth();
    else loadMetrics();
}

// ── Shared / shell ────────────────────────────────────────────────────────────────
async function fetchJson(path) {
    const res = await fetch(path, { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}
function showNotice(msg) {
    document.getElementById('notice-slot').innerHTML = `<div class="notice">${msg}</div>`;
}
function clearNotice() { document.getElementById('notice-slot').innerHTML = ''; }
function markUpdated() {
    document.getElementById('last-updated').textContent = 'updated ' + new Date().toLocaleTimeString();
}

function up(id, type, data, options) {
    const el = document.getElementById(id);
    if (!el) return;
    if (charts[id]) { charts[id].data = data; if (options) charts[id].options = { responsive: true, maintainAspectRatio: false, ...options }; charts[id].update(); return; }
    charts[id] = new Chart(el, { type, data, options: { responsive: true, maintainAspectRatio: false, ...options } });
}

function chartTheme() {
    if (typeof Chart === 'undefined') return;
    Chart.defaults.color = css('--muted');
    Chart.defaults.borderColor = css('--chart-grid');
    Chart.defaults.font.family = css('--font') || 'sans-serif';
    Chart.defaults.font.size = 11;
    // Live-polling dashboard: re-animating every poll is distracting and leaves charts
    // mid-transition. Render instantly and update in place.
    Chart.defaults.animation = false;
}
function rebuildCharts() { for (const id in charts) { charts[id].destroy(); delete charts[id]; } }

let timer = null;
function applyRefresh() {
    if (timer) clearInterval(timer);
    const s = Number(document.getElementById('refresh').value);
    document.getElementById('live').classList.toggle('paused', s <= 0);
    if (s > 0) timer = setInterval(refreshActive, s * 1000);
}

// tabs
function openTab(name) {
    const tab = document.querySelector(`.tab[data-tab="${name}"]`);
    if (!tab) return;
    activeView = name;
    document.querySelectorAll('.tab').forEach(x => {
        const on = x === tab;
        x.classList.toggle('active', on);
        x.setAttribute('aria-selected', String(on));
    });
    document.querySelectorAll('.view').forEach(v => { v.hidden = v.dataset.view !== name; });
    try { history.replaceState(null, '', '#' + name); } catch { /* file:// */ }
    refreshActive();
}
document.querySelectorAll('.tab').forEach(t => t.onclick = () => openTab(t.dataset.tab));
// theme
document.getElementById('theme').onclick = () => {
    const cur = document.documentElement.dataset.theme || 'dark';
    document.documentElement.dataset.theme = cur === 'dark' ? 'light' : 'dark';
    rebuildCharts();
    chartTheme();
    refreshActive();
};
// refresh + filter
document.getElementById('refresh').onchange = applyRefresh;
document.getElementById('refresh-now').onclick = () => {
    const rot = document.querySelector('#refresh-now .rot');
    rot.classList.add('spin');
    setTimeout(() => rot.classList.remove('spin'), 600);
    refreshActive();
};
document.getElementById('cfgfilter').oninput = e => { cfgFilter = e.target.value; configTable(); };

// boot
const params = new URLSearchParams(location.search);
const themeParam = params.get('theme');
if (themeParam === 'dark' || themeParam === 'light') document.documentElement.dataset.theme = themeParam;
const refreshParam = params.get('refresh');
if (refreshParam !== null && [...document.getElementById('refresh').options].some(o => o.value === refreshParam)) {
    document.getElementById('refresh').value = refreshParam;
}
chartTheme();
initApiSort();
const hashTab = location.hash.slice(1);
if (document.querySelector(`.tab[data-tab="${hashTab}"]`)) openTab(hashTab);
else refreshActive();
applyRefresh();
