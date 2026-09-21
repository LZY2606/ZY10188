"use strict";

const RUN_ID = "synthetic-crank-v1";
let state = null;
let raw = null;
let focusedCycle = null;

const $ = (id) => document.getElementById(id);

async function api(path, options) {
  const res = await fetch(path, options || {});
  if (!res.ok) {
    const text = await res.text();
    throw new Error(res.status + " " + text);
  }
  return res.json();
}

async function loadAll() {
  [state, raw] = await Promise.all([
    api(`/api/runs/${RUN_ID}/analysis`),
    api(`/api/runs/${RUN_ID}/raw`),
  ]);
  render();
}

async function postJson(path, body) {
  state = await api(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  render();
}

function fmt(v, digits) {
  if (v === null || v === undefined || Number.isNaN(v)) return "—";
  return Number(v).toFixed(digits === undefined ? 2 : digits);
}

function render() {
  renderHeader();
  renderControls();
  drawRaw();
  drawGaps();
  drawAngle();
  drawEnvelope();
  renderMetrics();
  renderAudit();
}

function renderHeader() {
  const r = state.run;
  $("runMeta").textContent =
    `${r.id} · ${r.sampleCount} 点 @ ${r.sampleRateHz.toFixed(0)} Hz · ` +
    `${(r.durationMs).toFixed(1)} ms · 转速 ${r.fixtureParams.rpmStart}→${r.fixtureParams.rpmEnd} rpm`;
  const ambiguous = !state.patternUnique;
  const phaseConfirmed = state.state.trustedTdcIndex !== null &&
    (!ambiguous || state.state.patternConfirmed);
  const badge = $("phaseBadge");
  badge.textContent = phaseConfirmed ? "相位已确认"
    : (ambiguous && !state.state.patternConfirmed
        ? "缺齿模式未唯一（保留候选）"
        : "待置信 TDC 锦标");
  badge.classList.toggle("ok", phaseConfirmed);
}

function renderControls() {
  const cand = $("candidateList");
  cand.innerHTML = "";
  if (state.patternUnique && state.candidates.length === 1) {
    cand.innerHTML = `<span class="tag">${state.candidates[0].label}</span>`;
  } else {
    for (const c of state.candidates) {
      const b = document.createElement("button");
      const active = state.state.patternCandidateId === c.id;
      b.textContent = (active ? "✓ " : "") + c.label + `（边界 ${c.firingTdcMs.map(x => x.toFixed(2)).join(", ")} ms）`;
      if (active) b.classList.add("primary");
      b.onclick = () => postJson(`/api/runs/${RUN_ID}/confirm-pattern`, { candidateId: c.id });
      cand.appendChild(b);
    }
  }

  const tdc = $("tdcList");
  tdc.innerHTML = "";
  raw.tdcMarkersMs.forEach((t, i) => {
    const b = document.createElement("button");
    const active = state.state.trustedTdcIndex === i;
    b.textContent = (active ? "✓ " : "置信 ") + `TDC #${i} @${t.toFixed(2)}ms`;
    if (active) b.classList.add("primary");
    b.onclick = () => postJson(`/api/runs/${RUN_ID}/trust-tdc`, { tdcIndex: i });
    tdc.appendChild(b);
  });

  const sat = $("satInfo");
  sat.innerHTML = "";
  const satCycles = state.cycles.filter((c) => c.saturationSegments.length > 0);
  if (satCycles.length === 0) {
    sat.textContent = "无饱和段";
  } else {
    for (const c of satCycles) {
      const seg = c.saturationSegments[0];
      const b = document.createElement("button");
      const excluded = state.state.excludedCycleIndexes.includes(c.cycleIndex);
      b.textContent = `循环 ${c.cycleIndex} 饱和 ${seg.startAngleDeg.toFixed(0)}–${seg.endAngleDeg.toFixed(0)}°` +
        (excluded ? "（已排除）" : "（排除）");
      if (excluded) b.classList.add("danger");
      b.onclick = () => postJson(`/api/runs/${RUN_ID}/exclude-saturation`,
        { cycleIndex: c.cycleIndex, excluded: !excluded });
      sat.appendChild(b);
    }
  }
}

function setup(canvas) {
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = 300 * dpr;
  const ctx = canvas.getContext("2d");
  ctx.scale(dpr, dpr);
  const W = rect.width, H = 300;
  const pad = { l: 52, r: 52, t: 14, b: 30 };
  return { ctx, W, H, pad,
    iw: W - pad.l - pad.r, ih: H - pad.t - pad.b };
}

function axes(g, xLabel, yLabel, y2Label) {
  const { ctx, pad, iw, ih } = g;
  ctx.strokeStyle = "#2a323c";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(pad.l, pad.t);
  ctx.lineTo(pad.l, pad.t + ih);
  ctx.lineTo(pad.l + iw, pad.t + ih);
  ctx.stroke();
  ctx.fillStyle = "#8b98a5";
  ctx.font = "11px sans-serif";
  ctx.fillText(xLabel, pad.l + iw - 10, pad.t + ih + 20);
  ctx.fillText(yLabel, 6, pad.t + 10);
  if (y2Label) {
    ctx.fillText(y2Label, pad.l + iw + 10, pad.t + 10);
  }
}

function scale(v, lo, hi, a, b) {
  if (hi === lo) return (a + b) / 2;
  return a + (v - lo) / (hi - lo) * (b - a);
}

function drawRaw() {
  const g = setup($("rawChart"));
  const { ctx, pad, iw, ih } = g;
  ctx.clearRect(0, 0, g.W, g.H);
  const pts = raw.pressure;
  const t0 = pts[0][0], t1 = pts[pts.length - 1][0];
  const pMax = 105;
  axes(g, "时间 ms", "bar", "rpm");

  // rpm 右轴
  const rpms = raw.condition.map((p) => p[1]);
  const rpmLo = Math.min(...rpms) - 50, rpmHi = Math.max(...rpms) + 50;
  ctx.strokeStyle = "#82aaff";
  ctx.beginPath();
  raw.condition.forEach((p, i) => {
    const x = scale(p[0], t0, t1, pad.l, pad.l + iw);
    const y = scale(p[1], rpmLo, rpmHi, pad.t + ih, pad.t);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
  ctx.fillStyle = "#82aaff";
  ctx.fillText(rpmHi.toFixed(0), pad.l + iw + 6, pad.t + 4);
  ctx.fillText(rpmLo.toFixed(0), pad.l + iw + 6, pad.t + ih);

  // pressure
  ctx.lineWidth = 1;
  ctx.beginPath();
  pts.forEach((p, i) => {
    const x = scale(p[0], t0, t1, pad.l, pad.l + iw);
    const y = scale(p[1], 0, pMax, pad.t + ih, pad.t);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = "#4db6ff";
  ctx.stroke();

  // saturated emphasis
  ctx.fillStyle = "rgba(255,107,107,0.55)";
  let inSat = false, sx = 0;
  pts.forEach((p) => {
    const x = scale(p[0], t0, t1, pad.l, pad.l + iw);
    if (p[2] === 1 && !inSat) { inSat = true; sx = x; }
    if (p[2] !== 1 && inSat) {
      inSat = false;
      ctx.fillRect(sx, pad.t, x - sx, ih);
    }
  });
  if (inSat) ctx.fillRect(sx, pad.t, pad.l + iw - sx, ih);

  vLineSet(raw.referenceMarkersMs, "#c792ea", 1.2, [6, 4]);
  vLineSet(raw.tdcMarkersMs, "#42d68a", 1.6, []);
  vLineSet(raw.uncertainGapMs, "#ffb454", 1.4, [3, 3]);

  function vLineSet(arr, color, w, dash) {
    ctx.strokeStyle = color;
    ctx.lineWidth = w;
    ctx.setLineDash(dash);
    for (const t of arr) {
      const x = scale(t, t0, t1, pad.l, pad.l + iw);
      ctx.beginPath();
      ctx.moveTo(x, pad.t);
      ctx.lineTo(x, pad.t + ih);
      ctx.stroke();
    }
    ctx.setLineDash([]);
  }
}

function drawGaps() {
  const g = setup($("gapChart"));
  const { ctx, pad, iw, ih } = g;
  ctx.clearRect(0, 0, g.W, g.H);
  const ivs = state.toothIntervals;
  const t0 = ivs[0].tMs, t1 = ivs[ivs.length - 1].tMs;
  const rpms = ivs.map((v) => v.rpmInstant).filter((x) => x > 0);
  const lo = Math.min(...rpms) - 60, hi = Math.max(...rpms) + 60;
  axes(g, "时间 ms", "rpm");
  for (const v of ivs) {
    const x = scale(v.tMs, t0, t1, pad.l, pad.l + iw);
    const y = scale(v.rpmInstant, lo, hi, pad.t + ih, pad.t);
    if (v.gap) {
      ctx.fillStyle = v.span >= 4 ? "#ff6b6b" : "#ffb454";
      ctx.beginPath();
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#ffb454";
      ctx.fillText("S=" + v.span, x + 4, y - 6);
    } else {
      ctx.fillStyle = "#5a6a78";
      ctx.fillRect(x - 0.8, y - 0.8, 1.6, 1.6);
    }
  }
}

function drawAngle() {
  const g = setup($("angleChart"));
  const { ctx, pad, iw, ih } = g;
  ctx.clearRect(0, 0, g.W, g.H);
  const cycles = state.cycles;
  let pMax = 0;
  for (const c of cycles) for (const p of c.trace) if (p[1] > pMax) pMax = p[1];
  pMax = Math.ceil(pMax / 20) * 20;
  axes(g, "° 循环内（发火 TDC=0）", "bar");

  // peg window
  const px0 = scale(390, 0, 720, pad.l, pad.l + iw);
  const px1 = scale(450, 0, 720, pad.l, pad.l + iw);
  ctx.fillStyle = "rgba(69,104,138,0.25)";
  ctx.fillRect(px0, pad.t, px1 - px0, ih);

  const palette = ["#4db6ff", "#ff9f43", "#42d68a", "#c792ea"];
  cycles.forEach((c, idx) => {
    if (c.excluded) return;
    if (focusedCycle !== null && focusedCycle !== idx) return;
    ctx.strokeStyle = palette[idx % palette.length];
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    c.trace.forEach((p, i) => {
      const x = scale(p[0], 0, 720, pad.l, pad.l + iw);
      const y = scale(p[1], 0, pMax, pad.t + ih, pad.t);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
    // saturation segments overlay
    ctx.fillStyle = "rgba(255,107,107,0.5)";
    for (const seg of c.saturationSegments) {
      const x0 = scale(seg.startAngleDeg, 0, 720, pad.l, pad.l + iw);
      const x1 = scale(seg.endAngleDeg, 0, 720, pad.l, pad.l + iw);
      ctx.fillRect(x0, pad.t, Math.max(2, x1 - x0), ih);
    }
    // label
    const peak = c.trace.reduce((a, b) => (b[1] > a[1] ? b : a));
    ctx.fillStyle = palette[idx % palette.length];
    ctx.fillText("C" + idx,
      scale(peak[0], 0, 720, pad.l, pad.l + iw),
      scale(peak[1], 0, pMax, pad.t + ih, pad.t) - 6);
  });
}

function drawEnvelope() {
  const g = setup($("envChart"));
  const { ctx, pad, iw, ih } = g;
  ctx.clearRect(0, 0, g.W, g.H);
  const e = state.envelope;
  let pMax = 0;
  for (let i = 0; i < e.angle.length; i++) {
    if (e.max[i] && e.max[i] > pMax) pMax = e.max[i];
  }
  pMax = Math.ceil(pMax / 20) * 20;
  axes(g, "° 循环内", "bar");

  const xs = e.angle.map((a) => scale(a, 0, 720, pad.l, pad.l + iw));
  const yMax = e.max.map((v) => scale(Number.isNaN(v) ? 0 : v, 0, pMax, pad.t + ih, pad.t));
  const yMin = e.min.map((v) => scale(Number.isNaN(v) ? 0 : v, 0, pMax, pad.t + ih, pad.t));
  ctx.fillStyle = "rgba(90,106,120,0.25)";
  ctx.beginPath();
  xs.forEach((x, i) => i === 0 ? ctx.moveTo(x, yMax[i]) : ctx.lineTo(x, yMax[i]));
  for (let i = xs.length - 1; i >= 0; i--) ctx.lineTo(xs[i], yMin[i]);
  ctx.closePath();
  ctx.fill();

  strokeLine(xs, yMax, "#ffb454");
  strokeLine(xs, e.mean.map((v) => scale(Number.isNaN(v) ? 0 : v, 0, pMax, pad.t + ih, pad.t)), "#4db6ff");
  strokeLine(xs, yMin, "#5a6a78");

  ctx.fillStyle = "rgba(255,107,107,0.45)";
  for (let i = 0; i < e.angle.length; i++) {
    if (e.saturated[i]) ctx.fillRect(xs[i] - 0.5, pad.t, 1.5, ih);
  }

  function strokeLine(x, y, color) {
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.3;
    ctx.beginPath();
    x.forEach((xx, i) => i === 0 ? ctx.moveTo(xx, y[i]) : ctx.lineTo(xx, y[i]));
    ctx.stroke();
  }
}

function metricOf(c, key) {
  return c.metrics.find((m) => m.key === key);
}

function metricCell(m, digits) {
  if (m.value === null || m.value === undefined) {
    return `<td class="num null">不输出</td>`;
  }
  const cls = m.lowerBoundOnly ? "num lb" : "num";
  const prefix = m.lowerBoundOnly ? "≥" : "";
  return `<td class="${cls}" title="${m.note}&#10;算法 ${m.algorithmVersion} / 标定 ${m.calibrationVersion}">` +
    `${prefix}${fmt(m.value, digits)}</td>`;
}

function renderMetrics() {
  const tb = $("metricTable").querySelector("tbody");
  tb.innerHTML = "";
  for (const c of state.cycles) {
    const tr = document.createElement("tr");
    const pmax = metricOf(c, "pmax");
    const pa = metricOf(c, "pmaxAngle");
    const rpr = metricOf(c, "maxPressureRise");
    const ca = metricOf(c, "ca50");
    const notes = [pmax, pa, rpr, ca].filter((m) => m.value === null || m.lowerBoundOnly)
      .map((m) => m.note).join("；");
    const flags = [];
    if (c.longSpanInterpolation) flags.push("长跨距线性拉伸");
    if (c.saturationSegments.length) flags.push("含饱和段");
    if (c.excluded) flags.push("已排除");
    if (c.userOffsetDeg !== 0) flags.push("手动偏移 " + c.userOffsetDeg + "°");
    tr.innerHTML =
      `<td><a href="#" data-cycle="${c.cycleIndex}" class="cycleLink">C${c.cycleIndex}</a></td>` +
      `<td>${c.candidateId}</td>` +
      `<td>${flags.map((f) => `<span class="tag ${f === "已排除" ? "excluded" : ""}">${f}</span>`).join(" ")}</td>` +
      metricCell(pmax, 1) +
      metricCell(pa, 1) +
      metricCell(rpr, 2) +
      metricCell(ca, 1) +
      `<td class="num">${fmt(c.peggingOffsetBar, 3)}</td>` +
      `<td>${notes || "—"}</td>`;
    tr.querySelector(".cycleLink").onclick = (ev) => {
      ev.preventDefault();
      focusedCycle = focusedCycle === c.cycleIndex ? null : c.cycleIndex;
      drawAngle();
    };
    tb.appendChild(tr);
  }
  const v = state.versions;
  $("versionBar").innerHTML = Object.entries(v).map(([k, ver]) =>
    `<span>${k}: <code>${ver}</code></span>`).join("");
}

async function renderAudit() {
  const actions = await api(`/api/runs/${RUN_ID}/actions`);
  const tb = $("auditTable").querySelector("tbody");
  tb.innerHTML = "";
  for (const a of actions.slice().reverse()) {
    const tr = document.createElement("tr");
    const d = new Date(a.atMs);
    tr.innerHTML = `<td>${a.id}</td><td>${d.toLocaleTimeString()}</td>` +
      `<td>${a.type}</td><td>${a.payload}</td>`;
    tb.appendChild(tr);
  }
}

$("btnReload").onclick = () => loadAll().catch(alertErr);
$("btnImport").onclick = async () => {
  await api(`/api/runs/import-fixture`, { method: "POST" });
  await loadAll();
};
$("btnClear").onclick = async () => {
  if (!confirm("清空全部 runs/state/actions？清空后可重新导入 fixture 复核。")) return;
  await api(`/api/admin/clear`, { method: "POST" });
  await api(`/api/runs/import-fixture`, { method: "POST" });
  await loadAll();
};
$("btnExport").onclick = async () => {
  const res = await fetch(`/api/runs/${RUN_ID}/export`);
  const blob = await res.blob();
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "run-synthetic-crank-v1-export.json";
  a.click();
};
$("btnReplay").onclick = () => $("fileReplay").click();
$("fileReplay").onchange = async (ev) => {
  const file = ev.target.files[0];
  if (!file) return;
  const text = await file.text();
  JSON.parse(text);
  await api(`/api/runs/replay`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: text,
  });
  await loadAll();
};
$("btnOffset").onclick = async () => {
  const cycleIndex = parseInt($("offsetCycle").value, 10);
  const offsetDeg = parseFloat($("offsetDeg").value);
  if (Number.isNaN(cycleIndex) || Number.isNaN(offsetDeg)) {
    alert("请输入循环编号与偏移角度");
    return;
  }
  await postJson(`/api/runs/${RUN_ID}/cycle-offset`, { cycleIndex, offsetDeg });
};

function alertErr(e) { console.error(e); alert(e.message); }

loadAll().catch(alertErr);
