/* DStudio Settings tab — DStudio's own engines, models, projects and harnesses. */
(function () {
  "use strict";

  const { $, detect, productName, providers, models, allModels, projects, openProject,
          harnesses, scanProviders, testProvider, applyDefaults, json,
          bridgeAvailable, bridgeCall, esc, formatBytes, fillSelect, toast,
          applyTheme } = window.Inference;

  const state = {
    backend: null,
    media: {},
    providers: [],
    models: [],
    projects: [],
    harnesses: [],
    agents: [],
    selection: { providerId: "", modelId: "", harnessId: "", agentProfileId: "" }
  };

  // ---- helpers (esc/toast/formatBytes/fillSelect/applyTheme come from common.js) ----

  function banner(text, kind) {
    const el = $("banner");
    if (!text) { el.hidden = true; el.textContent = ""; return; }
    el.hidden = false;
    el.className = "banner " + (kind || "warn");
    el.textContent = text;
  }

  function setConnection(kind, text) {
    const dot = $("dot");
    dot.className = "dot" + (kind === "online" ? " ok" : kind === "offline" ? " err" : "");
    $("connText").textContent = text;
  }

  function badge(status) {
    const value = String(status || "").toUpperCase();
    const ok = ["CONNECTED", "AVAILABLE", "UP", "READY", "RUNNING", "OK", "HEALTHY"];
    const err = ["DISCONNECTED", "ERROR", "UNAVAILABLE", "DOWN", "FAILED", "DEGRADED"];
    let cls = "";
    if (ok.some((s) => value.includes(s))) { cls = " ok"; }
    else if (err.some((s) => value.includes(s))) { cls = " err"; }
    return "<span class=\"badge" + cls + "\">" + esc(value || "—") + "</span>";
  }

  // ---- selects ----

  function renderProviderSelect() {
    const items = state.providers.map((p) => ({
      id: p.id,
      label: p.name + (p.isDefault ? " \u2605" : "")
          + (p.status ? " · " + p.status : "")
    }));
    fillSelect($("providerSelect"), items, state.selection.providerId, "no provider");
    state.selection.providerId = $("providerSelect").value || "";
  }

  function renderModelSelect(modelList) {
    const items = (modelList || state.models).map((m) => ({
      id: m.id,
      providerId: m.providerId,
      label: (m.providerName ? m.providerName + " :: " : "") + m.name
    }));
    fillSelect($("modelSelect"), items, state.selection.modelId, "no model");
    state.selection.modelId = $("modelSelect").value || "";
    const option = $("modelSelect").selectedOptions[0];
    if (option && option.dataset.providerId) {
      state.selection.providerId = option.dataset.providerId;
      $("providerSelect").value = option.dataset.providerId;
    }
  }

  function renderHarnessSelect() {
    const row = $("harnessRow");
    const card = $("harnessCard");
    if (!state.harnesses.length) {
      row.hidden = true;
      card.hidden = true;
      state.selection.harnessId = "";
      return;
    }
    row.hidden = false;
    card.hidden = false;
    const items = state.harnesses.map((h) => ({
      id: h.id,
      label: h.name + (h.status ? " · " + h.status : "")
    }));
    fillSelect($("harnessSelect"), items, state.selection.harnessId, "—");
    state.selection.harnessId = $("harnessSelect").value || "";
  }

  function renderAgentSelect() {
    const row = $("agentRow");
    if (!state.agents.length) {
      row.hidden = true;
      state.selection.agentProfileId = "";
      return;
    }
    row.hidden = false;
    const items = state.agents.map((a) => ({
      id: a.id,
      label: a.name + (a.default ? " \u2605" : "") + (a.role ? " · " + a.role : "")
    }));
    fillSelect($("agentSelect"), items, state.selection.agentProfileId, "—");
    state.selection.agentProfileId = $("agentSelect").value || "";
  }

  // ---- tables ----

  function renderProvidersTable() {
    if (!state.providers.length) {
      $("providerTable").innerHTML = "<tbody><tr><td class=\"muted\">none</td></tr></tbody>";
      return;
    }
    const rows = state.providers.map((p) => "<tr><td>" + esc(p.name)
        + (p.isDefault ? " \u2605" : "") + "</td><td>" + esc(p.type || "—")
        + "</td><td class=\"ellipsis\">" + esc(p.endpoint || "—")
        + "</td><td>" + badge(p.status) + "</td>"
        + "<td style=\"text-align:right\"><button class=\"small\" data-test=\""
        + esc(p.id) + "\">Test</button></td></tr>").join("");
    $("providerTable").innerHTML = "<tbody>" + rows + "</tbody>";
    $("providerTable").querySelectorAll("[data-test]").forEach((button) => {
      button.onclick = async () => {
        button.disabled = true;
        try {
          const result = await testProvider(button.dataset.test);
          const success = result && (result.success === true || result.success === undefined);
          toast((success ? "OK — " : "Failed — ") + ((result && result.message) || ""),
              !success);
        } catch (e) {
          toast("Test failed: " + e.message, true);
        } finally {
          button.disabled = false;
        }
      };
    });
  }

  function renderModelsTable() {
    if (!state.models.length) {
      $("modelTable").innerHTML = "<tbody><tr><td class=\"muted\">none</td></tr></tbody>";
      return;
    }
    const rows = state.models.map((m) => "<tr><td>" + esc(m.name)
        + "</td><td>" + esc(m.providerName || m.providerId || "—")
        + "</td><td>" + esc(formatBytes(m.sizeBytes))
        + "</td><td>" + badge(m.available === false ? "UNAVAILABLE" : "AVAILABLE")
        + "</td></tr>").join("");
    $("modelTable").innerHTML = "<tbody>" + rows + "</tbody>";
  }

  function renderProjectsTable() {
    if (!state.projects.length) {
      $("projectTable").innerHTML = "<tbody><tr><td class=\"muted\">none</td></tr></tbody>";
      return;
    }
    const rows = state.projects.map((p) => "<tr><td>" + esc(p.name)
        + "</td><td class=\"ellipsis\">" + esc(p.rootPath || "") + "</td></tr>").join("");
    $("projectTable").innerHTML = "<tbody>" + rows + "</tbody>";
  }

  function renderHarnessesTable() {
    if (!state.harnesses.length) {
      $("harnessTable").innerHTML = "<tbody><tr><td class=\"muted\">none</td></tr></tbody>";
      return;
    }
    const rows = state.harnesses.map((h) => "<tr><td>" + esc(h.name)
        + (h.isDefault ? " \u2605" : "") + "</td><td>" + esc(h.type || "—")
        + "</td><td>" + badge(h.status) + "</td></tr>").join("");
    $("harnessTable").innerHTML = "<tbody>" + rows + "</tbody>";
  }

  // ---- data loading ----

  async function loadProviders(scan) {
    const previous = state.selection.providerId;
    state.providers = await (scan ? scanProviders() : providers());
    state.providers = state.providers.filter((p) => p.enabled !== false);
    if (!state.selection.providerId) {
      const preferred = state.providers.find((p) => p.isDefault) || state.providers[0];
      state.selection.providerId = preferred ? preferred.id : "";
    } else if (!state.providers.some((p) => p.id === state.selection.providerId)) {
      state.selection.providerId = previous && state.providers.some((p) => p.id === previous)
          ? previous : (state.providers[0] ? state.providers[0].id : "");
    }
    renderProviderSelect();
    renderProvidersTable();
  }

  async function loadModelsForProvider(providerId, preferredModelId) {
    if (!providerId) {
      state.models = [];
      renderModelSelect([]);
      renderModelsTable();
      return;
    }
    try {
      const found = await models(providerId);
      const provider = state.providers.find((p) => p.id === providerId);
      for (const model of found) { model.providerName = provider ? provider.name : providerId; }
      state.models = found;
    } catch (e) {
      state.models = [];
      toast("Models unavailable for " + providerId + ": " + e.message, true);
    }
    if (preferredModelId) { state.selection.modelId = preferredModelId; }
    renderModelSelect();
    renderModelsTable();
  }

  async function browseAllModels() {
    $("modelHint").textContent = "loading…";
    try {
      const result = await allModels(state.providers);
      state.models = result.models;
      renderModelSelect();
      renderModelsTable();
      $("modelHint").textContent = result.models.length + " model(s)"
          + (result.failures.length ? " · " + result.failures.length + " provider(s) unavailable" : "");
      if (!result.models.length) {
        toast("No models reported by the available providers.", true);
      }
    } catch (e) {
      $("modelHint").textContent = "failed: " + e.message;
      toast("Browse failed: " + e.message, true);
    }
  }

  async function loadProjects() {
    try { state.projects = await projects(); }
    catch (e) { state.projects = []; }
    renderProjectsTable();
  }

  async function loadHarnesses() {
    try { state.harnesses = await harnesses(); }
    catch (e) { state.harnesses = []; }
    renderHarnessSelect();
    renderHarnessesTable();
  }

  // ---- desktop bridge (DStudio settings tab) ----

  async function bootBridge() {
    if (!bridgeAvailable()) { return; }
    try {
      const payload = await bridgeCall("boot", {});
      if (payload) {
        const defaults = payload.defaults || {};
        const global = payload.globalConfig || {};
        if (!state.selection.harnessId) {
          state.selection.harnessId = defaults.defaultHarnessId
              || global.harnessId || "";
        }
        if (!state.selection.providerId) {
          state.selection.providerId = defaults.defaultEngineId
              || global.providerId || "";
        }
        if (!state.selection.modelId) {
          state.selection.modelId = global.modelId || "";
        }
        if (Array.isArray(payload.agents)) {
          state.agents = payload.agents.map((a) => ({
            id: String(a.id), name: a.name || a.id, role: a.role || "",
            default: a.default === true
          }));
          if (!state.selection.agentProfileId) {
            state.selection.agentProfileId = defaults.defaultProfileId
                || (state.agents.find((a) => a.default) || {}).id || "";
          }
          renderAgentSelect();
        }
        if (payload.connection) {
          $("cState").textContent = String(payload.stage || payload.connection.stage || "UP");
        }
      }
    } catch (e) { /* bridge optional */ }
    // The desktop "ready" acknowledgement is fire-and-forget (the Java side does not
    // send a result); awaiting it would block the whole settings boot.
    bridgeCall("ready", {}).catch(() => {});
  }

  // ---- actions ----

  async function apply() {
    try {
      await applyDefaults({
        providerId: $("providerSelect").value || state.selection.providerId,
        modelId: $("modelSelect").value || state.selection.modelId,
        harnessId: $("harnessSelect").value || state.selection.harnessId,
        agentProfileId: state.agents.length
            ? ($("agentSelect").value || state.selection.agentProfileId) : ""
      });
      toast("Defaults applied and persisted by the backend.");
    } catch (e) {
      toast("Failed: " + e.message, true);
    }
  }

  async function doOpenProject() {
    const path = $("projectPath").value.trim();
    if (!path) { toast("Project path is required.", true); return; }
    try {
      await openProject(path);
      $("projectPath").value = "";
      await loadProjects();
      toast("Project opened.");
    } catch (e) {
      toast("Open failed: " + e.message, true);
    }
  }

  function wire() {
    $("retryBtn").onclick = () => boot(true);
    $("tokenBtn").onclick = async () => {
      const value = await window.Inference.askToken(window.Inference.token());
      if (value === null) { return; }
      window.Inference.setToken(value);
      boot(true);
    };
    $("providerSelect").onchange = () => {
      state.selection.providerId = $("providerSelect").value;
      state.selection.modelId = "";
      loadModelsForProvider(state.selection.providerId);
    };
    const themeSelect = $("themeSelect");
    if (themeSelect) {
      let currentTheme = "light";
      try { currentTheme = localStorage.getItem(window.Inference.THEME_KEY) || "light"; }
      catch (e) { /* ignore */ }
      themeSelect.value = currentTheme === "light" ? "light" : "dark";
      themeSelect.onchange = () => {
        document.documentElement.dataset.theme = themeSelect.value;
        try { localStorage.setItem(window.Inference.THEME_KEY, themeSelect.value); }
        catch (e) { /* ignore */ }
      };
    }
    $("modelSelect").onchange = () => {
      state.selection.modelId = $("modelSelect").value;
      const option = $("modelSelect").selectedOptions[0];
      if (option && option.dataset.providerId) {
        state.selection.providerId = option.dataset.providerId;
        $("providerSelect").value = option.dataset.providerId;
      }
    };
    $("harnessSelect").onchange = () => {
      state.selection.harnessId = $("harnessSelect").value;
    };
    $("agentSelect").onchange = () => {
      state.selection.agentProfileId = $("agentSelect").value;
    };
    $("scanProvidersBtn").onclick = () => scan(true);
    $("scanProvidersBtn2").onclick = () => scan(true);
    $("browseModelsBtn").onclick = browseAllModels;
    $("browseModelsBtn2").onclick = browseAllModels;
    $("applyDefaultsBtn").onclick = apply;
    $("openProjectBtn").onclick = doOpenProject;
    $("refreshProjectsBtn").onclick = loadProjects;
  }

  async function scan(verbose) {
    try {
      await loadProviders(true);
      await loadModelsForProvider(state.selection.providerId, state.selection.modelId);
      if (verbose) { toast("Providers scanned and models refreshed."); }
    } catch (e) {
      toast("Scan failed: " + e.message, true);
    }
  }

  async function boot(verbose) {
    applyTheme("btn-theme");
    wire();
    setConnection("reconnecting", "connecting…");
    banner("");
    try {
      state.backend = await detect();
    } catch (e) {
      setConnection("offline", e.message);
      $("cState").textContent = "unreachable";
      banner("Cannot reach the backend (" + e.message + "). Use Retry to reload.", "err");
      return;
    }
    const product = productName(state.backend);
    $("brandName").textContent = product;
    document.title = product + " Settings";
    $("cState").textContent = "UP";
    $("cEndpoint").textContent = window.location.origin;
    $("cBackend").textContent = product;
    setConnection("online", "READY · " + window.location.origin);
    try {
      const health = await json("/api/status");
      state.media = health || {};
      $("cVersion").textContent = (health && health.version) || "?";
      $("cBackend").textContent = product;
      $("cState").textContent = (health && health.status)
          || (health && health.ready ? "READY" : "UP");
    } catch (e) { /* health optional */ }

    try {
      await loadProviders(false);
      await bootBridge();
      renderProviderSelect();
      await loadModelsForProvider(state.selection.providerId, state.selection.modelId);
      if (!state.models.length) {
        // Default engine offline: aggregate every reachable engine's models.
        await browseAllModels();
      }
      await loadProjects();
      await loadHarnesses();
      if (!bridgeAvailable()) {
        banner("Applying defaults needs the EVO-Studio desktop bridge; engine, model, "
            + "project and harness data are live over HTTP.", "warn");
      }
      if (verbose) { toast("Settings reloaded."); }
    } catch (e) {
      setConnection("offline", "error");
      banner("Cannot load configuration: " + e.message, "err");
      toast(e.message, true);
    }
  }

  boot(false);
})();
