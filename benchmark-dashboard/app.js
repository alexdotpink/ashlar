const state = {
  data: null,
  comparisonIndex: 0,
  metric: "LATENCY_P50",
  profile: "all",
  temperature: "all",
  search: "",
  showAllRows: false,
};

const metricLabels = {
  LATENCY_MEAN: "Mean latency",
  LATENCY_P50: "p50 latency",
  LATENCY_P95: "p95 latency",
  LATENCY_P99: "p99 latency",
  THROUGHPUT: "Throughput",
  ALLOCATION: "Allocation",
};

const $ = (selector) => document.querySelector(selector);

async function loadDashboard() {
  try {
    const response = await fetch("data.json", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    state.data = await response.json();
    hydrateControls();
    render();
  } catch (error) {
    console.error("Could not load benchmark evidence", error);
    $("#load-error").hidden = false;
    $("#freshness").textContent = "Evidence temporarily unavailable";
  }
}

function currentComparison() {
  return state.data?.comparisons?.[state.comparisonIndex] ?? null;
}

function latestCandidateResult(comparison) {
  if (!comparison) return null;
  return state.data.results.find((result) =>
    result.source.runId === comparison.source.runId
      && result.revision === comparison.candidateRevision,
  ) ?? state.data.results.find((result) => result.source.runId === comparison.source.runId) ?? null;
}

function hydrateControls() {
  const comparisons = state.data.comparisons ?? [];
  const runSelect = $("#run-filter");
  runSelect.replaceChildren(...comparisons.map((comparison, index) => {
    const option = document.createElement("option");
    option.value = String(index);
    option.textContent = `${formatDate(comparison.source.updatedAt)} · ${shortRevision(comparison.candidateRevision)}`;
    return option;
  }));
  runSelect.disabled = comparisons.length === 0;

  const allCases = comparisons.flatMap((comparison) => comparison.cases);
  populateSelect($("#profile-filter"), unique(allCases.map((item) => item.profile)));
  populateSelect($("#temperature-filter"), unique(allCases.map((item) => item.temperature)));

  runSelect.addEventListener("change", (event) => {
    state.comparisonIndex = Number(event.target.value);
    render();
  });
  $("#metric-filter").addEventListener("change", (event) => {
    state.metric = event.target.value;
    renderComparison();
  });
  $("#profile-filter").addEventListener("change", (event) => {
    state.profile = event.target.value;
    renderComparison();
  });
  $("#temperature-filter").addEventListener("change", (event) => {
    state.temperature = event.target.value;
    renderComparison();
  });
  $("#scenario-search").addEventListener("input", (event) => {
    state.search = event.target.value.trim().toLowerCase();
    renderComparison();
  });
  $("#reset-filters").addEventListener("click", () => {
    state.metric = "LATENCY_P50";
    state.profile = "all";
    state.temperature = "all";
    state.search = "";
    $("#metric-filter").value = state.metric;
    $("#profile-filter").value = "all";
    $("#temperature-filter").value = "all";
    $("#scenario-search").value = "";
    renderComparison();
  });
  $("#toggle-rows").addEventListener("click", () => {
    state.showAllRows = !state.showAllRows;
    renderComparison();
  });
}

function populateSelect(select, values) {
  for (const value of values) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = titleCase(value);
    select.append(option);
  }
}

function render() {
  renderSummary();
  renderComparison();
  renderEvidence();
  renderEnvironment();
}

function renderSummary() {
  const comparison = currentComparison();
  const summary = state.data.summary ?? {};
  const generatedAt = state.data.generatedAt;
  $("#freshness").textContent = generatedAt ? `Updated ${relativeTime(generatedAt)}` : "Freshness unknown";
  $("#generated-at").textContent = generatedAt ? `Snapshot ${formatDateTime(generatedAt)}.` : "";
  $("#case-count").textContent = formatInteger(comparison?.cases?.length ?? summary.caseCount ?? 0);
  $("#evidence-count").textContent = formatInteger(state.data.sources?.length ?? 0);

  const median = medianMetricChange(comparison, "LATENCY_P50");
  setDirectionalMetric($("#median-change"), median);

  const allocationChanges = metricChanges(comparison, "ALLOCATION");
  const stable = allocationChanges.length
    ? allocationChanges.filter((value) => Math.abs(value) < 0.001).length / allocationChanges.length
    : null;
  $("#allocation-stability").textContent = stable == null ? "—" : formatPercent(stable, 0, false);

  if (!comparison) {
    $("#verdict-status").textContent = "No evidence yet";
    $("#verdict-copy").textContent = "The first public benchmark artifact has not been collected.";
    return;
  }

  const displayedStatus = comparison.cases.every((item) => item.status === "EXPLORATORY")
    ? "EXPLORATORY"
    : comparison.status;
  const compatible = comparison.environmentCompatible;
  const badge = $("#environment-badge");
  badge.textContent = compatible ? "Environment matched" : "Environment mismatch";
  badge.className = `pill ${compatible ? "good" : "bad"}`;
  $("#verdict-status").textContent = verdictLabel(displayedStatus);
  $("#verdict-copy").textContent = verdictCopy(comparison, displayedStatus);
  $("#baseline-revision").textContent = shortRevision(comparison.baselineRevision);
  $("#candidate-revision").textContent = shortRevision(comparison.candidateRevision);
  $("#latest-run-link").href = comparison.source.url ?? "https://github.com/alexdotpink/ashlar/actions";
}

function renderComparison() {
  const comparison = currentComparison();
  if (!comparison) return;
  const cases = filteredCases(comparison);
  const rows = cases.map((item) => ({ case: item, metric: findMetric(item, state.metric) }))
    .filter((item) => item.metric);
  renderMovement(rows);
  renderSignals(comparison, rows);
  renderTable(rows);
}

function filteredCases(comparison) {
  return comparison.cases.filter((item) => {
    if (state.profile !== "all" && item.profile !== state.profile) return false;
    if (state.temperature !== "all" && item.temperature !== state.temperature) return false;
    if (state.search && !item.scenario.toLowerCase().includes(state.search)) return false;
    return true;
  });
}

function renderMovement(rows) {
  const selected = [...rows]
    .sort((left, right) => Math.abs(right.metric.regressionFraction) - Math.abs(left.metric.regressionFraction))
    .slice(0, 12);
  const maximum = Math.max(0.01, ...selected.map((item) => Math.abs(item.metric.regressionFraction)));
  const chart = $("#movement-chart");
  chart.replaceChildren(...selected.map(({ case: benchmarkCase, metric }) => {
    const row = element("div", "movement-row");
    const label = element("div", "movement-label");
    label.append(
      textElement("strong", displayScenario(benchmarkCase.scenario)),
      textElement("small", `${benchmarkCase.profile} · ${benchmarkCase.temperature}`),
    );
    const track = element("div", "movement-track");
    const direction = movementClass(metric.regressionFraction);
    const bar = element("span", `movement-bar ${direction}`);
    bar.style.width = `${Math.min(50, Math.abs(metric.regressionFraction) / maximum * 49)}%`;
    track.append(bar);
    const value = textElement("span", formatPercent(metric.regressionFraction, 1), `movement-value ${direction}`);
    row.append(label, track, value);
    return row;
  }));
  $("#movement-subtitle").textContent = `${metricLabels[state.metric]} change against baseline`;
  if (selected.length === 0) chart.append(textElement("p", "No measured cases match these filters."));
}

function renderSignals(comparison, rows) {
  const list = $("#signal-list");
  const changes = rows.map((item) => item.metric.regressionFraction);
  const confident = rows.filter((item) => confidenceExcludesZero(item.metric)).length;
  const improvements = changes.filter((value) => value < -0.05).length;
  const regressions = changes.filter((value) => value > 0.05).length;
  const displayedStatus = comparison.cases.every((item) => item.status === "EXPLORATORY")
    ? "EXPLORATORY"
    : comparison.status;
  const items = [
    {
      tone: comparison.environmentCompatible ? "good" : "bad",
      title: comparison.environmentCompatible ? "Environment is comparable" : "Environment mismatch",
      body: comparison.environmentCompatible
        ? "Hardware, JVM, toolchain, and runtime identity passed compatibility checks."
        : "Treat deltas as informational until both sides use the same environment.",
    },
    {
      tone: confident ? "warn" : "good",
      title: `${confident} of ${rows.length} confidence ranges exclude zero`,
      body: confident
        ? "These movements are less likely to be random measurement noise."
        : "No selected movement is statistically separated from zero in this sample.",
    },
    {
      tone: regressions > improvements ? "warn" : "good",
      title: `${improvements} faster · ${regressions} slower`,
      body: "Counts use a ±5% practical movement threshold, independent of contract budgets.",
    },
    {
      tone: displayedStatus === "PASSED" ? "good" : "warn",
      title: verdictLabel(displayedStatus),
      body: displayedStatus === "EXPLORATORY"
        ? "Evidence is recorded, but exploratory contracts do not fail pull requests yet."
        : "Contract evaluations determine the published verdict.",
    },
  ];
  list.replaceChildren(...items.map((item) => {
    const signal = element("div", "signal");
    signal.append(element("span", `signal-dot ${item.tone}`));
    const copy = document.createElement("div");
    copy.append(textElement("strong", item.title), textElement("p", item.body));
    signal.append(copy);
    return signal;
  }));
}

function renderTable(rows) {
  const ordered = [...rows].sort((left, right) => right.metric.regressionFraction - left.metric.regressionFraction);
  const visible = state.showAllRows ? ordered : ordered.slice(0, 20);
  const body = $("#comparison-rows");
  body.replaceChildren(...visible.map(({ case: benchmarkCase, metric }) => {
    const row = document.createElement("tr");
    const scenario = element("td", "scenario-cell");
    scenario.append(textElement("strong", displayScenario(benchmarkCase.scenario)), textElement("small", benchmarkCase.layer));
    row.append(
      scenario,
      textElement("td", titleCase(benchmarkCase.profile)),
      textElement("td", titleCase(benchmarkCase.temperature)),
      textElement("td", formatMetricValue(state.metric, metric.baseline)),
      textElement("td", formatMetricValue(state.metric, metric.candidate)),
    );
    const deltaCell = document.createElement("td");
    deltaCell.append(textElement("span", formatPercent(metric.regressionFraction, 1), `delta ${movementClass(metric.regressionFraction)}`));
    row.append(deltaCell);
    row.append(textElement(
      "td",
      `${formatPercent(metric.confidenceLower, 1)} to ${formatPercent(metric.confidenceUpper, 1)}`,
      "confidence",
    ));
    return row;
  }));
  $("#table-count").textContent = `${visible.length} of ${ordered.length} measured ${ordered.length === 1 ? "case" : "cases"} · sorted slowest first`;
  const toggle = $("#toggle-rows");
  toggle.hidden = ordered.length <= 20;
  toggle.textContent = state.showAllRows ? "Show first 20" : `Show all ${ordered.length}`;
  $("#empty-state").hidden = ordered.length !== 0;
}

function renderEvidence() {
  const comparisons = state.data.comparisons ?? [];
  $("#evidence-list").replaceChildren(...comparisons.slice(0, 12).map((comparison, index) => {
    const item = document.createElement("a");
    item.className = "evidence-item";
    item.href = comparison.source.url ?? "https://github.com/alexdotpink/ashlar/actions";
    const change = medianMetricChange(comparison, "LATENCY_P50");
    item.append(textElement("span", String(index + 1).padStart(2, "0"), "evidence-index"));
    const copy = element("span", "evidence-copy");
    copy.append(
      textElement("strong", `${shortRevision(comparison.baselineRevision)} → ${shortRevision(comparison.candidateRevision)}`),
      textElement("small", `${formatDateTime(comparison.source.updatedAt)} · ${comparison.cases.length} cases`),
    );
    const result = element("span", "evidence-result");
    result.append(textElement("strong", change == null ? "—" : formatPercent(change, 1)), textElement("small", "Median p50"));
    item.append(copy, result);
    return item;
  }));
}

function renderEnvironment() {
  const result = latestCandidateResult(currentComparison());
  const environment = result?.environment ?? {};
  const configuration = result?.configuration ?? {};
  const values = [
    ["CPU", environment.cpuModel],
    ["Logical CPUs", environment.availableProcessors],
    ["JVM", environment.jvmVersion],
    ["Kotlin", environment.kotlinVersion],
    ["Ashlar", environment.ashlarVersion],
    ["Forks", configuration.forks],
    ["Samples", configuration.measurementIterations],
  ].filter(([, value]) => value != null);
  const list = $("#environment-list");
  list.replaceChildren(...values.flatMap(([label, value]) => [textElement("dt", label), textElement("dd", String(value))]));
}

function findMetric(benchmarkCase, name) {
  return benchmarkCase.metrics.find((metric) => metric.metric === name) ?? null;
}

function metricChanges(comparison, name) {
  if (!comparison) return [];
  return comparison.cases
    .map((benchmarkCase) => findMetric(benchmarkCase, name)?.regressionFraction)
    .filter((value) => Number.isFinite(value));
}

function medianMetricChange(comparison, name) {
  const values = metricChanges(comparison, name).sort((left, right) => left - right);
  if (!values.length) return null;
  const middle = Math.floor(values.length / 2);
  return values.length % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
}

function confidenceExcludesZero(metric) {
  return metric.confidenceLower > 0 || metric.confidenceUpper < 0;
}

function setDirectionalMetric(target, value) {
  target.className = "";
  if (value == null) {
    target.textContent = "—";
    return;
  }
  target.textContent = formatPercent(value, 1);
  if (value < -0.005) target.classList.add("good");
  if (value > 0.005) target.classList.add("bad");
}

function movementClass(value) {
  if (Math.abs(value) < 0.005) return "flat";
  return value < 0 ? "better" : "worse";
}

function formatMetricValue(metric, value) {
  if (!Number.isFinite(value)) return "—";
  if (metric.startsWith("LATENCY")) return formatDuration(value);
  if (metric === "ALLOCATION") return formatBytes(value);
  if (metric === "THROUGHPUT") return `${compactNumber(value)} ops/s`;
  return compactNumber(value);
}

function formatDuration(nanos) {
  if (nanos < 1_000) return `${compactNumber(nanos)} ns`;
  if (nanos < 1_000_000) return `${compactNumber(nanos / 1_000)} µs`;
  if (nanos < 1_000_000_000) return `${compactNumber(nanos / 1_000_000)} ms`;
  return `${compactNumber(nanos / 1_000_000_000)} s`;
}

function formatBytes(bytes) {
  if (Math.abs(bytes) < 1024) return `${compactNumber(bytes)} B`;
  if (Math.abs(bytes) < 1024 * 1024) return `${compactNumber(bytes / 1024)} KiB`;
  return `${compactNumber(bytes / 1024 / 1024)} MiB`;
}

function compactNumber(value) {
  return new Intl.NumberFormat("en", { maximumFractionDigits: 2, notation: Math.abs(value) >= 10_000 ? "compact" : "standard" }).format(value);
}

function formatInteger(value) {
  return new Intl.NumberFormat("en", { maximumFractionDigits: 0 }).format(value);
}

function formatPercent(value, digits = 1, signed = true) {
  if (!Number.isFinite(value)) return "—";
  const percentage = value * 100;
  const sign = signed && percentage > 0 ? "+" : "";
  return `${sign}${percentage.toFixed(digits)}%`;
}

function shortRevision(revision) {
  if (!revision) return "unknown";
  return revision === "main" ? revision : revision.slice(0, 8);
}

function displayScenario(value) {
  return value.split(".").map((part) => part.replaceAll("-", " ")).map(titleCase).join(" · ");
}

function titleCase(value) {
  const text = String(value ?? "").toLowerCase().replaceAll("_", " ");
  return text.replace(/(^|\s)\S/g, (match) => match.toUpperCase());
}

function unique(values) {
  return [...new Set(values.filter(Boolean))].sort();
}

function formatDate(value) {
  if (!value) return "Unknown date";
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric" }).format(new Date(value));
}

function formatDateTime(value) {
  if (!value) return "Unknown time";
  return new Intl.DateTimeFormat("en", {
    month: "short", day: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "short",
  }).format(new Date(value));
}

function relativeTime(value) {
  const seconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  if (Math.abs(seconds) < 60) return formatter.format(seconds, "second");
  if (Math.abs(seconds) < 3600) return formatter.format(Math.round(seconds / 60), "minute");
  if (Math.abs(seconds) < 86400) return formatter.format(Math.round(seconds / 3600), "hour");
  return formatter.format(Math.round(seconds / 86400), "day");
}

function verdictLabel(status) {
  return ({
    PASSED: "Within budget",
    FAILED: "Regression found",
    INCONCLUSIVE: "Needs another run",
    EXPLORATORY: "Exploratory evidence",
  })[status] ?? titleCase(status);
}

function verdictCopy(comparison, displayedStatus = comparison.status) {
  if (!comparison.environmentCompatible) return "The machines or toolchains differ, so this comparison is informational only.";
  if (displayedStatus === "PASSED") return "Every contractual budget passed for this paired measurement.";
  if (displayedStatus === "FAILED") return "At least one contractual budget was exceeded with sufficient confidence.";
  if (displayedStatus === "INCONCLUSIVE") return "The point estimate moved, but the evidence is not decisive yet.";
  return "All cases were measured and compared. Contracts are still exploratory, so this run records evidence without blocking the pull request.";
}

function element(tag, className) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  return node;
}

function textElement(tag, text, className) {
  const node = element(tag, className);
  node.textContent = text;
  return node;
}

loadDashboard();
