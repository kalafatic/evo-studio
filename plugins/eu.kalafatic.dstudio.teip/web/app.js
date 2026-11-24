/* DStudio Web — inference workspace arranged like the DCode web app:
 * topbar (center status + right controls), left views, tabbed work area,
 * right Context panel and bottom Console/Events/Problems. DStudio's own objects. */
(function () {
  "use strict";

  const { $, detect, providers, models, allModels, projects, harnesses, json, sse,
          askToken, token, setToken, esc, formatBytes, fillSelect, toast,
          applyTheme, renderMarkdown, copyToClipboard, wireCodeCopy,
          FRONTEND_BUILD } = window.Inference;

  const STORE_KEY = "dstudio.conversations.v1";

  const state = {
    connected: false,
    view: "inference",
    providerId: "",
    modelId: "",
    projectId: "",
    harnessId: "",
    allProviders: [],
    allModels: [],
    allProjects: [],
    allHarnesses: [],
    conversations: [],
    currentId: null,
    sessionFilter: "",
    context: { type: "", item: null },
    busy: false,
    stream: null,
    tabOpen: true,
    consoleEntries: [],
    consoleAutoscroll: true,
    eventStream: null,
    lastEventId: 0,
    filesPath: ""
  };

  // ---------------- persistence / conversations ----------------
  // Conversations are backend sessions (/api/inference/sessions — persisted by
  // DStudio's InferenceService). localStorage keeps only unsent drafts and UI
  // preferences; it is never a second session manager.

  function loadStore() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      state.conversations = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(state.conversations)) { state.conversations = []; }
    } catch (e) { state.conversations = []; }
  }

  function persist() {
    try {
      const drafts = state.conversations.filter((c) => !c.backend && !c.studioSessionId);
      localStorage.setItem(STORE_KEY, JSON.stringify(drafts));
    } catch (e) { /* storage unavailable */ }
  }

  function newConversation() {
    return {
      id: "c-" + Date.now().toString(36) + "-" + Math.floor(Math.random() * 1e6).toString(36),
      title: "New conversation",
      messages: [],
      providerId: state.providerId || "",
      modelId: state.modelId || "",
      studioSessionId: null,
      backend: false,
      status: "IDLE",
      createdAt: new Date().toISOString()
    };
  }

  function current() {
    return state.conversations.find((c) => c.id === state.currentId) || null;
  }

  /** Merges backend sessions into the conversation list (drafts stay on top). */
  async function loadBackendSessions() {
    try {
      const body = await json("/api/inference/sessions");
      const sessions = Array.isArray(body.sessions) ? body.sessions : [];
      const drafts = state.conversations.filter((c) => !c.backend && !c.studioSessionId);
      const merged = sessions.map((s) => ({
        id: String(s.id),
        studioSessionId: String(s.id),
        providerId: s.engineId || "",
        modelId: s.model || "",
        title: s.title || "Session",
        messages: null,
        backend: true,
        status: s.status || "IDLE",
        statusMessage: s.statusMessage || "",
        createdAt: s.createdAt || "",
        updatedAt: s.updatedAt || ""
      }));
      const selected = state.currentId;
      state.conversations = drafts.concat(merged);
      if (selected && state.conversations.some((c) => c.id === selected)) {
        state.currentId = selected;
      } else {
        state.currentId = state.conversations[0] ? state.conversations[0].id : null;
      }
    } catch (e) {
      log("error", "Sessions unavailable: " + e.message);
      if (!state.conversations.length) { state.conversations.push(newConversation()); }
      if (!state.currentId) { state.currentId = state.conversations[0].id; }
    }
  }

  function rebindConversation(oldId, backendId) {
    const conversation = state.conversations.find((c) => c.id === oldId);
    if (!conversation) { return; }
    conversation.id = backendId;
    conversation.studioSessionId = backendId;
    conversation.backend = true;
    if (state.currentId === oldId) { state.currentId = backendId; }
    persist();
  }

  // ---------------- theme / toast / connection ----------------
  // applyTheme/toast come from common.js (single implementation, both pages).

  function setConnection(kind, text) {
    state.connected = kind === "connected";
    const el = $("connection-status");
    el.className = "conn conn-" + kind;
    el.textContent = "\u25CF " + text;
    const sb = $("sb-status");
    if (sb) { sb.textContent = state.connected ? "connected" : text; }
  }

  function updateStatusbar() {
    const engine = state.allProviders.find((p) => p.id === state.providerId);
    const model = state.allModels.find((m) => m.id === state.modelId);
    if ($("sb-engine")) {
      $("sb-engine").textContent = "engine " + (engine ? engine.name : "—");
    }
    if ($("sb-model")) {
      $("sb-model").textContent = "model " + (model ? model.name : "—");
    }
    if ($("sb-build")) { $("sb-build").textContent = FRONTEND_BUILD; }
  }

  // ---------------- console / events / problems ----------------

  function stamp() { return new Date().toISOString().slice(11, 19); }

  function log(kind, message) {
    const category = String(kind || "info").toUpperCase();
    const line = stamp() + " " + category + " " + message;
    state.consoleEntries.push(line);
    const node = document.createElement("div");
    node.className = "log-line log-" + category.toLowerCase();
    const ts = document.createElement("span");
    ts.className = "ts";
    ts.textContent = stamp() + " ";
    const tag = document.createElement("span");
    tag.className = "console-tag console-tag-" + category.toLowerCase();
    tag.textContent = category + " ";
    node.appendChild(ts);
    node.appendChild(tag);
    node.appendChild(document.createTextNode(message));
    const consolePane = $("panel-console");
    consolePane.appendChild(node);
    if (state.consoleAutoscroll) { consolePane.scrollTop = consolePane.scrollHeight; }
    if (category === "ERROR") { problem(message); }
  }

  function event(message) {
    const node = document.createElement("div");
    node.className = "ev";
    node.textContent = stamp() + " " + message;
    const pane = $("panel-events");
    pane.appendChild(node);
    if (state.consoleAutoscroll) { pane.scrollTop = pane.scrollHeight; }
  }

  function problem(message) {
    const node = document.createElement("div");
    node.className = "problem";
    node.textContent = stamp() + " " + message;
    const pane = $("panel-problems");
    pane.appendChild(node);
    if (state.consoleAutoscroll) { pane.scrollTop = pane.scrollHeight; }
  }

  // ---------------- live event stream (shared backend event bus) ----------------

  function startEventStream() {
    if (state.eventStream || !state.connected) { return; }
    state.eventStream = true;
    const connect = async () => {
      try {
        await sse("/api/events?afterSequence=" + state.lastEventId,
            { method: "GET" }, (type, data) => {
              if (data && data.__id) {
                const seq = Number(data.__id);
                if (!isNaN(seq)) {
                  if (seq <= state.lastEventId) { return; }
                  state.lastEventId = seq;
                }
              }
              const kind = type || (data && data.type) || "event";
              const message = (data && data.message != null) ? String(data.message)
                  : (data && data.type ? String(data.type) : "");
              event(kind + (message ? " " + message : ""));
            });
      } catch (e) { /* connection lost — reconnect below */ }
      state.eventStream = false;
      if (state.connected) { setTimeout(startEventStream, 3000); }
    };
    connect();
  }

  // ---------------- selects / data ----------------
  // fillSelect comes from common.js.

  function providerLabel(provider) {
    return provider.name + (provider.isDefault ? " \u2605" : "")
        + (provider.status ? " · " + provider.status : "");
  }

  async function refreshProviders() {
    state.allProviders = (await providers()).filter((p) => p.enabled !== false);
    const keep = state.providerId
        || (state.allProviders.find((p) => p.isDefault) || {}).id || "";
    fillSelect($("providerSelect"), state.allProviders.map((p) => ({
      id: p.id, label: providerLabel(p)
    })), keep, "no provider");
    state.providerId = $("providerSelect").value || "";
    await refreshModels();
  }

  async function refreshModels(preferredModelId) {
    const providerId = $("providerSelect").value;
    state.providerId = providerId;
    const select = $("modelSelect");
    if (!providerId) {
      state.allModels = [];
      fillSelect(select, [], "", "no model");
      return;
    }
    select.innerHTML = "<option value=\"\">loading…</option>";
    try {
      state.allModels = await models(providerId);
    } catch (e) {
      state.allModels = [];
      log("error", "Models unavailable for " + providerId + ": " + e.message);
    }
    if (!state.allModels.length) {
      try {
        const aggregate = await allModels(state.allProviders);
        state.allModels = aggregate.models;
      } catch (e) { /* keep empty */ }
    }
    const keep = preferredModelId || state.modelId;
    fillSelect(select, state.allModels.map((m) => ({
      id: m.id,
      label: (m.providerName ? m.providerName + " :: " : "") + m.name
    })), keep, "no model");
    state.modelId = select.value || "";
  }

  async function refreshProjects() {
    try { state.allProjects = await projects(); }
    catch (e) { state.allProjects = []; log("error", "Projects unavailable: " + e.message); }
    if (state.projectId && !state.allProjects.some((p) => p.id === state.projectId)) {
      state.projectId = "";
    }
  }

  async function refreshHarnesses() {
    try { state.allHarnesses = await harnesses(); }
    catch (e) { state.allHarnesses = []; log("error", "Harnesses unavailable: " + e.message); }
    if (state.harnessId && !state.allHarnesses.some((h) => h.id === state.harnessId)) {
      state.harnessId = "";
    }
  }

  // ---------------- render: topbar / tabs / context ----------------
  // formatBytes comes from common.js.

  function updateTopbar() {
    const project = state.allProjects.find((p) => p.id === state.projectId);
    const engine = state.allProviders.find((p) => p.id === state.providerId);
    const model = state.allModels.find((m) => m.id === state.modelId);
    $("top-project").textContent = project ? "project: " + project.name : "no project";
    $("top-engine").textContent = engine ? "engine: " + engine.name
        + (engine.isDefault ? " \u2605" : "") : "no engine";
    $("top-model").textContent = model ? "model: " + model.name : "no model";
    $("modelMeta").textContent = [engine ? engine.name : "",
      model ? model.name : "",
      model && model.sizeBytes ? formatBytes(model.sizeBytes) : ""]
        .filter(Boolean).join(" · ");
    updateStatusbar();
  }

  function renderTab() {
    const pane = $("inference-pane");
    const noTab = $("no-tab");
    if (!state.tabOpen) {
      pane.classList.remove("active");
      noTab.hidden = false;
      return;
    }
    pane.classList.add("active");
    noTab.hidden = true;
    const conversation = current();
    $("tabTitle").textContent = conversation && conversation.title
        ? conversation.title : "Inference";
  }

  function renderContext() {
    const box = $("right-content");
    if (!state.context.type || !state.context.item) {
      box.innerHTML = "<div class=\"empty-note\">Select a session, project, engine, "
          + "model or harness for details.</div>";
      return;
    }
    const item = state.context.item;
    const rows = [];
    const add = (k, v) => rows.push("<div class=\"ctx-kv\"><span class=\"k\">"
        + esc(k) + "</span><span class=\"v\">" + esc(v == null ? "—" : v)
        + "</span></div>");
    if (state.context.type === "session") {
      add("Title", item.title);
      add("Provider", item.providerId);
      add("Model", item.modelId);
      add("Status", item.backend ? (item.status || "SESSION") : "LOCAL DRAFT");
    } else if (state.context.type === "project") {
      add("Name", item.name);
      add("Root", item.rootPath);
    } else if (state.context.type === "engine") {      add("Name", item.name);
      add("Type", item.type);
      add("Status", item.status);
      add("Endpoint", item.endpoint);
    } else if (state.context.type === "model") {
      add("Name", item.name);
      add("Provider", item.providerName || item.providerId);
      add("Size", formatBytes(item.sizeBytes));
      add("Available", item.available === false ? "no" : "yes");
    } else if (state.context.type === "harness") {
      add("Name", item.name);
      add("Type", item.type);
      add("Status", item.status);
      add("Default", item.isDefault === true ? "yes ★" : "no");
      add("Enabled", item.enabled === false ? "no" : "yes");
    }
    box.innerHTML = "<div class=\"ctx-title\">" + esc(item.name || item.title
        || state.context.type) + "</div>" + rows.join("");
    if (state.context.type === "project") { renderProjectFiles(box, item); }
  }

  /**
   * Real project file browser (GET /api/workspace/files — server-contained path
   * resolution). Directory browsing, file preview and "Use in prompt" (inserts
   * the file path plus its content so the model actually receives it).
   */
  async function renderProjectFiles(box, project) {
    const section = document.createElement("div");
    section.className = "ctx-files";
    const head = document.createElement("div");
    head.className = "ctx-files-head";
    const label = document.createElement("span");
    label.className = "ctx-sub";
    label.textContent = "Project files" + (state.filesPath ? " · " + state.filesPath : "");
    head.appendChild(label);
    const up = document.createElement("button");
    up.className = "btn btn-small";
    up.textContent = "root";
    up.title = "Back to the project root";
    up.onclick = () => { state.filesPath = ""; renderProjectFiles(box, project); };
    head.appendChild(up);
    section.appendChild(head);
    box.appendChild(section);
    const loading = document.createElement("div");
    loading.className = "empty-note";
    loading.textContent = "loading…";
    section.appendChild(loading);
    try {
      const body = await json("/api/workspace/files?project="
          + encodeURIComponent(project.id)
          + "&path=" + encodeURIComponent(state.filesPath || ""));
      section.innerHTML = "";
      section.appendChild(head);
      if (body.directory === true) {
        const children = Array.isArray(body.children) ? body.children : [];
        if (!children.length) {
          const empty = document.createElement("div");
          empty.className = "empty-note";
          empty.textContent = "empty directory";
          section.appendChild(empty);
        }
        for (const child of children) {
          const line = document.createElement("div");
          line.className = "file-row";
          const name = document.createElement("span");
          name.className = "file-name" + (child.directory ? " dir" : "");
          name.textContent = (child.directory ? "▸ " : "· ") + child.name;
          name.title = child.path || child.name;
          line.appendChild(name);
          const action = document.createElement("button");
          action.className = "btn btn-small";
          action.textContent = child.directory ? "open" : "preview";
          action.onclick = (event) => {
            event.stopPropagation();
            state.filesPath = child.path || child.name;
            renderProjectFiles(box, project);
          };
          line.onclick = () => action.click();
          line.appendChild(action);
          section.appendChild(line);
        }
      } else if (body.content != null) {
        const meta = document.createElement("div");
        meta.className = "ctx-sub";
        meta.textContent = "File: " + (body.path || "");
        section.appendChild(meta);
        const insert = document.createElement("button");
        insert.className = "btn btn-small";
        insert.textContent = "Use in prompt";
        insert.title = "Insert the file path and content into the prompt";
        insert.onclick = () => {
          const path = body.path || "";
          const content = String(body.content || "");
          const input = $("promptInput");
          input.value = (input.value ? input.value.replace(/\s+$/, "") + "\n" : "")
              + "File: " + path + "\n```\n" + content.slice(0, 30000) + "\n```\n";
          input.focus();
          toast("File " + path + " added to the prompt.");
        };
        section.appendChild(insert);
        const pre = document.createElement("pre");
        pre.className = "file-preview";
        pre.textContent = String(body.content).slice(0, 20000);
        section.appendChild(pre);
      }
    } catch (e) {
      section.innerHTML = "";
      section.appendChild(head);
      const error = document.createElement("div");
      error.className = "empty-note";
      error.textContent = "Files unavailable: " + e.message;
      section.appendChild(error);
    }
  }

  // ---------------- render: left views ----------------
  // esc comes from common.js.

  function item(node, title, sub, onClick, actions) {
    const row = document.createElement("div");
    row.className = "item";
    const body = document.createElement("div");
    body.className = "item-body";
    const titleNode = document.createElement("div");
    titleNode.className = "item-title";
    titleNode.textContent = title;
    body.appendChild(titleNode);
    if (sub) {
      const subNode = document.createElement("div");
      subNode.className = "item-sub";
      subNode.textContent = sub;
      body.appendChild(subNode);
    }
    row.appendChild(body);
    if (onClick) { row.onclick = onClick; }
    if (actions) {
      const bar = document.createElement("div");
      bar.className = "item-actions";
      actions.forEach((a) => { bar.appendChild(a); });
      bar.onclick = (e) => e.stopPropagation();
      row.appendChild(bar);
    }
    node.appendChild(row);
    return row;
  }

  function renderLeft() {
    const content = $("left-content");
    content.innerHTML = "";
    document.querySelectorAll("#left-nav .nav-btn").forEach((b) =>
        b.classList.toggle("active", b.dataset.view === state.view));

    if (state.view === "tasks") {
      window.DStudioTasks.activate(content, $("right-content"));
      return;
    }

    if (state.view === "inference" || state.view === "sessions") {
      const head = document.createElement("div");
      head.className = "section-head";
      const label = document.createElement("span");
      label.textContent = "Conversations";
      const add = document.createElement("button");
      add.className = "btn btn-small";
      add.textContent = "New";
      add.onclick = () => { startNewConversation(); renderLeft(); };
      head.appendChild(label);
      head.appendChild(add);
      content.appendChild(head);
      const search = document.createElement("input");
      search.type = "search";
      search.className = "list-search";
      search.placeholder = "Filter sessions…";
      search.value = state.sessionFilter || "";
      search.oninput = () => { state.sessionFilter = search.value; renderLeft(); };
      content.appendChild(search);
      const list = document.createElement("div");
      list.className = "item-list";
      content.appendChild(list);
      const filter = (state.sessionFilter || "").toLowerCase();
      const visible = state.conversations.filter((conversation) =>
          !filter || (conversation.title || "").toLowerCase().includes(filter));
      if (!state.conversations.length) {
        const empty = document.createElement("div");
        empty.className = "empty-note";
        empty.style.padding = "10px";
        empty.textContent = "No conversations yet.";
        list.appendChild(empty);
      } else if (!visible.length) {
        const empty = document.createElement("div");
        empty.className = "empty-note";
        empty.style.padding = "10px";
        empty.textContent = "No sessions match the filter.";
        list.appendChild(empty);
      }
      for (const conversation of visible.slice().sort((a, b) =>
          (b.updatedAt || b.createdAt || "").localeCompare(a.updatedAt || a.createdAt || ""))) {
        const count = conversation.messages === null ? "·" : conversation.messages.length;
        const row = item(list, conversation.title || "New conversation",
            (conversation.modelId || "no model") + " · " + count + " msg"
            + (conversation.backend ? " · server" : ""),
            () => selectConversation(conversation.id));
        if (conversation.id === state.currentId) { row.classList.add("active"); }
        const rename = document.createElement("button");
        rename.className = "btn btn-small";
        rename.textContent = "✎";
        rename.title = "Rename session";
        rename.onclick = async (event) => {
          event.stopPropagation();
          const value = await window.Inference.askText("Rename session",
              "session title", conversation.title || "", false);
          if (value === null || !value) { return; }
          conversation.title = value;
          if (conversation.backend && conversation.studioSessionId) {
            try {
              await json("/api/inference/sessions/"
                  + encodeURIComponent(conversation.studioSessionId) + "/rename",
                  { method: "POST", body: { title: value } });
            } catch (e) {
              toast("Rename failed: " + e.message, true);
            }
          }
          persist();
          renderLeft();
          renderTab();
        };
        const del = document.createElement("button");
        del.className = "btn btn-small";
        del.textContent = "\u00D7";
        del.title = "Delete conversation";
        del.onclick = (event) => {
          event.stopPropagation();
          deleteConversation(conversation.id);
          renderLeft();
        };
        const bar = document.createElement("div");
        bar.className = "item-actions";
        bar.appendChild(rename);
        bar.appendChild(del);
        row.appendChild(bar);
      }
      return;
    }

    const head = document.createElement("div");
    head.className = "section-head";
    const label = document.createElement("span");
    const refresh = document.createElement("button");
    refresh.className = "btn btn-small";
    refresh.textContent = "Refresh";
    refresh.onclick = () => bootData(false);
    head.appendChild(label);
    head.appendChild(refresh);
    content.appendChild(head);
    const list = document.createElement("div");
    list.className = "item-list";
    content.appendChild(list);

    if (state.view === "projects") {
      label.textContent = "Projects";
      if (!state.allProjects.length) {
        list.innerHTML = "<div class=\"empty-note\" style=\"padding:10px\">"
            + "No projects. Open them from the Settings tab.</div>";
      }
      for (const project of state.allProjects) {
        const row = item(list, project.name, project.rootPath, () => {
          state.projectId = project.id;
          state.filesPath = "";
          state.context = { type: "project", item: project };
          updateTopbar(); renderContext(); renderLeft();
        });
        if (project.id === state.projectId) { row.classList.add("active"); }
      }
    } else if (state.view === "engines") {
      label.textContent = "Inference engines";
      for (const engine of state.allProviders) {
        const row = item(list, engine.name + (engine.isDefault ? " \u2605" : ""),
            (engine.type || "") + (engine.status ? " · " + engine.status : ""),
            async () => {
              state.providerId = engine.id;
              state.modelId = "";
              state.context = { type: "engine", item: engine };
              $("providerSelect").value = engine.id;
              await refreshModels("");
              updateTopbar(); renderContext(); renderLeft(); renderChat();
            });
        if (engine.id === state.providerId) { row.classList.add("active"); }
      }
    } else if (state.view === "models") {
      label.textContent = "Models";
      if (!state.allModels.length) {
        list.innerHTML = "<div class=\"empty-note\" style=\"padding:10px\">"
            + "No models reported. Use Settings → Browse with a running engine.</div>";
      }
      for (const model of state.allModels) {
        const row = item(list, model.name,
            (model.providerName || model.providerId || "")
            + (model.sizeBytes ? " · " + formatBytes(model.sizeBytes) : ""),
            () => {
              state.modelId = model.id;
              if (model.providerId) {
                state.providerId = model.providerId;
                $("providerSelect").value = model.providerId;
              }
              $("modelSelect").value = model.id;
              state.context = { type: "model", item: model };
              updateTopbar(); renderContext(); renderLeft(); renderChat();
            });
        if (model.id === state.modelId) { row.classList.add("active"); }
      }
    } else if (state.view === "harnesses") {
      label.textContent = "Harnesses";
      if (!state.allHarnesses.length) {
        list.innerHTML = "<div class=\"empty-note\" style=\"padding:10px\">"
            + "No harnesses reported.</div>";
      }
      for (const harness of state.allHarnesses) {
        const row = item(list, harness.name + (harness.isDefault ? " \u2605" : ""),
            (harness.type || "") + (harness.status ? " · " + harness.status : "")
            + (harness.enabled === false ? " · disabled" : ""),
            () => {
              state.harnessId = harness.id;
              state.context = { type: "harness", item: harness };
              renderContext(); renderLeft();
            });
        if (harness.id === state.harnessId) { row.classList.add("active"); }
      }
    }
  }

  // ---------------- chat ----------------

  function selectConversation(id) {
    state.currentId = id;
    const conversation = current();
    if (conversation) {
      if (conversation.providerId) {
        state.providerId = conversation.providerId;
        $("providerSelect").value = conversation.providerId;
        refreshModels(conversation.modelId).then(() => {
          updateTopbar(); renderContext(); renderChat(); renderTab();
        }).catch(() => {});
      }
      state.context = { type: "session", item: conversation };
    }
    openTab();
    renderLeft();
    renderChat();
    renderTab();
    updateTopbar();
    renderContext();
    if (conversation && conversation.backend && conversation.messages === null) {
      loadBackendMessages(conversation);
    }
  }

  async function loadBackendMessages(conversation) {
    if (!conversation || !conversation.studioSessionId) { return; }
    try {
      const wire = await json("/api/inference/sessions/"
          + encodeURIComponent(conversation.studioSessionId));
      conversation.messages = (Array.isArray(wire.messages) ? wire.messages : [])
          .map((m) => ({ role: m.role || "assistant", content: m.content || "" }));
      if (wire.title) { conversation.title = wire.title; }
      if (conversation.id === state.currentId) {
        renderChat(); renderTab(); renderLeft(); renderContext();
      }
    } catch (e) {
      log("error", "Session " + conversation.studioSessionId
          + " unavailable: " + e.message);
      conversation.messages = [];
      if (conversation.id === state.currentId) { renderChat(); }
    }
  }

  async function deleteConversation(id) {
    const conversation = state.conversations.find((c) => c.id === id);
    if (conversation && conversation.backend) {
      try {
        await json("/api/inference/sessions/" + encodeURIComponent(id),
            { method: "DELETE" });
        log("info", "session " + id.slice(0, 12) + " deleted");
      } catch (e) {
        toast("Delete failed: " + e.message, true);
        return;
      }
    }
    state.conversations = state.conversations.filter((c) => c.id !== id);
    if (!state.conversations.length) { state.conversations.push(newConversation()); }
    if (state.currentId === id) { state.currentId = state.conversations[0].id; }
    persist();
    renderLeft();
    renderChat();
    renderTab();
    updateTopbar();
    renderContext();
  }

  function startNewConversation() {
    const conversation = newConversation();
    conversation.providerId = state.providerId;
    conversation.modelId = state.modelId;
    state.conversations.unshift(conversation);
    state.currentId = conversation.id;
    state.context = { type: "session", item: conversation };
    persist();
    openTab();
    renderLeft();
    renderChat();
    renderTab();
    updateTopbar();
    renderContext();
    $("promptInput").focus();
  }

  function touchConversation() {
    const conversation = current();
    if (!conversation) { return; }
    conversation.providerId = state.providerId;
    conversation.modelId = state.modelId;
    conversation.updatedAt = new Date().toISOString();
    const firstUser = conversation.messages.find((m) => m.role === "user");
    if (firstUser) {
      const text = firstUser.content.replace(/\s+/g, " ").trim();
      conversation.title = text.length > 48 ? text.slice(0, 48) + "…" : text;
      if (state.context.type === "session") {
        state.context = { type: "session", item: conversation };
      }
      if (conversation.backend && conversation.studioSessionId) {
        json("/api/inference/sessions/"
            + encodeURIComponent(conversation.studioSessionId) + "/rename",
            { method: "POST", body: { title: conversation.title } }).catch(() => {});
      }
    }
    persist();
  }

  function renderChat() {
    const logBox = $("chatLog");
    logBox.innerHTML = "";
    wireCodeCopy(logBox);
    const conversation = current();
    const messages = conversation ? conversation.messages : [];
    if (!messages.length) {
      const placeholder = document.createElement("div");
      placeholder.className = "placeholder";
      const title = document.createElement("div");
      title.className = "placeholder-title";
      title.textContent = "Start a conversation";
      const text = document.createElement("div");
      text.className = "placeholder-text";
      text.textContent = "Choose a provider and model above, then send a prompt. "
          + "Engines, models, projects and harnesses are configured in the Settings tab.";
      placeholder.appendChild(title);
      placeholder.appendChild(text);
      logBox.appendChild(placeholder);
      return;
    }
    for (const message of messages) {
      const node = document.createElement("div");
      node.className = "msg " + message.role;
      const head = document.createElement("div");
      head.className = "msg-head";
      const who = document.createElement("span");
      who.className = "who";
      who.textContent = message.role;
      head.appendChild(who);
      if (message.createdAt) {
        const time = document.createElement("span");
        time.className = "msg-time";
        time.textContent = String(message.createdAt).slice(11, 19);
        head.appendChild(time);
      }
      const spacer = document.createElement("span");
      spacer.className = "spacer";
      head.appendChild(spacer);
      if (message.role === "assistant") {
        const copy = document.createElement("button");
        copy.className = "msg-action";
        copy.textContent = "copy";
        copy.title = "Copy message";
        copy.onclick = () => {
          copyToClipboard(message.content || "").then(() => {
            copy.textContent = "copied";
            setTimeout(() => { copy.textContent = "copy"; }, 1400);
          }).catch(() => toast("Clipboard unavailable.", true));
        };
        head.appendChild(copy);
      }
      const text = document.createElement("div");
      text.className = "text";
      text.innerHTML = renderMarkdown(message.content);
      node.appendChild(head);
      node.appendChild(text);
      logBox.appendChild(node);
    }
    logBox.scrollTop = logBox.scrollHeight;
  }

  function appendToken(value) {
    const conversation = current();
    if (!conversation) { return; }
    const message = conversation.messages[conversation.messages.length - 1];
    if (!message || message.role !== "assistant") { return; }
    message.content += value;
    const nodes = $("chatLog").querySelectorAll(".msg.assistant .text");
    const last = nodes[nodes.length - 1];
    if (last) { last.innerHTML = renderMarkdown(message.content); }
    const logBox = $("chatLog");
    logBox.scrollTop = logBox.scrollHeight;
  }

  // ---------------- send / stream ----------------

  function setBusy(busy) {
    state.busy = busy;
    $("sendBtn").disabled = busy;
    $("cancelBtn").hidden = !busy;
    $("sendStatus").textContent = busy ? "Streaming…" : "";
  }

  async function ensureSession(conversation) {
    if (conversation.studioSessionId) { return conversation.studioSessionId; }
    const created = await json("/api/inference/sessions", {
      method: "POST",
      body: {
        engine: state.providerId,
        model: state.modelId,
        systemPrompt: "",
        title: conversation.title
      }
    });
    conversation.studioSessionId = created.id;
    persist();
    return created.id;
  }
  async function send() {
    if (state.busy) { return; }
    const input = $("promptInput");
    const text = input.value.trim();
    if (!text) { return; }
    if (!state.providerId || !state.modelId) {
      toast("Select an engine and model first (Settings scans engines and browses models).", true);
      return;
    }
    if (!current()) { startNewConversation(); }
    const active = current();
    if (active.messages === null) { await loadBackendMessages(active); }
    if (active.studioSessionId && (active.providerId !== state.providerId
        || active.modelId !== state.modelId)) {
      // A server session pins engine/model; switching requires a fresh one.
      if (active.backend) {
        try {
          await json("/api/inference/sessions/"
              + encodeURIComponent(active.studioSessionId), { method: "DELETE" });
        } catch (e) { /* best effort */ }
        active.backend = false;
      }
      active.studioSessionId = null;
    }
    active.providerId = state.providerId;
    active.modelId = state.modelId;
    const assistantMessage = { role: "assistant", content: "" };
    active.messages.push({ role: "user", content: text });
    active.messages.push(assistantMessage);
    input.value = "";
    renderChat();
    touchConversation();
    renderLeft();
    renderTab();
    renderContext();
    log("inference", "send → " + state.providerId + " / " + state.modelId);

    const controller = new AbortController();
    state.stream = { controller: controller, cancelled: false };
    setBusy(true);
    let streamError = null;

    try {
      const sessionId = await ensureSession(active);
      if (active.id !== sessionId) { rebindConversation(active.id, sessionId); }
      event("session " + sessionId.slice(0, 12) + " streaming");
      await sse("/api/inference/sessions/" + encodeURIComponent(sessionId)
          + "/messages/stream", {
        method: "POST", body: { content: text }, signal: controller.signal
      }, (type, data) => {
        if (type === "chunk") {
          if (data && data.text != null) { appendToken(data.text); }
        } else if (type === "done") {
          return false;
        } else if (type === "error") {
          streamError = new Error(data.message || "Inference failed");
          return false;
        }
      });
    } catch (e) {
      if (!state.stream.cancelled && e.name !== "AbortError") { streamError = e; }
    }

    const wasCancelled = state.stream.cancelled;
    state.stream = null;
    setBusy(false);
    if (streamError) {
      if (!assistantMessage.content) {
        const index = active.messages.indexOf(assistantMessage);
        if (index >= 0) { active.messages.splice(index, 1); }
      }
      log("error", streamError.message);
      $("sendStatus").textContent = "Failed: " + streamError.message;
      renderChat();
    } else if (wasCancelled) {
      if (!assistantMessage.content) { assistantMessage.content = "[cancelled]"; }
      // The cancelled backend session cannot accept further messages (409), so it
      // is removed and the conversation becomes a local draft again — the next
      // send creates a fresh server session.
      if (active.studioSessionId) {
        try {
          await json("/api/inference/sessions/"
              + encodeURIComponent(active.studioSessionId), { method: "DELETE" });
        } catch (e) { /* best effort */ }
      }
      active.studioSessionId = null;
      active.backend = false;
      persist();
      log("warning", "generation cancelled");
      renderChat();
    } else {
      log("success", "generation complete");
    }
    touchConversation();
    renderLeft();
    renderTab();
    renderContext();
  }

  async function cancel() {
    if (!state.stream) { return; }
    state.stream.cancelled = true;
    try {
      const conversation = current();
      if (conversation && conversation.studioSessionId) {
        await json("/api/inference/sessions/"
            + encodeURIComponent(conversation.studioSessionId) + "/cancel",
            { method: "POST", body: {} });
      }
    } catch (e) { /* best effort */ }
    try { state.stream.controller.abort(); } catch (e) { /* ignore */ }
    $("sendStatus").textContent = "Cancelling…";
  }

  function openTab() {
    state.tabOpen = true;
    const pane = $("inference-pane");
    const noTab = $("no-tab");
    pane.classList.add("active");
    noTab.hidden = true;
    renderTab();
  }

  function closeTab() {
    state.tabOpen = false;
    $("inference-pane").classList.remove("active");
    $("no-tab").hidden = false;
  }

  // ---------------- wiring ----------------

  function wire() {
    document.querySelectorAll("#left-nav .nav-btn").forEach((button) => {
      button.onclick = () => {
        if (state.view === "tasks" && button.dataset.view !== "tasks") {
          window.DStudioTasks.deactivate();
        }
        state.view = button.dataset.view;
        openTab();
        renderLeft();
        renderChat();
        renderTab();
        renderContext();
      };
    });
    document.querySelectorAll(".bottom-tab").forEach((tab) => {
      tab.onclick = () => {
        document.querySelectorAll(".bottom-tab").forEach((t) =>
            t.classList.remove("active"));
        document.querySelectorAll(".bottom-pane").forEach((p) =>
            p.classList.remove("active"));
        tab.classList.add("active");
        $("panel-" + tab.dataset.panel).classList.add("active");
      };
    });
    $("chip-autoscroll").onclick = () => {
      state.consoleAutoscroll = !state.consoleAutoscroll;
      $("chip-autoscroll").classList.toggle("active", state.consoleAutoscroll);
    };
    $("chip-autoscroll").classList.toggle("active", state.consoleAutoscroll);
    $("chip-clear").onclick = () => { $("panel-console").innerHTML = ""; };
    $("chip-copy").onclick = () => {
      const text = state.consoleEntries.join("\n");
      if (navigator.clipboard) { navigator.clipboard.writeText(text); }
      log("info", "console copied to clipboard");
    };
    $("chip-export").onclick = () => {
      const blob = new Blob([state.consoleEntries.join("\n")], { type: "text/plain" });
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = "dstudio-console.txt";
      link.click();
    };
    $("tabClose").onclick = (event) => { event.stopPropagation(); closeTab(); };
    document.querySelector(".tab[data-tab=\"inference\"]").onclick = openTab;
    $("openInferenceBtn").onclick = () => { openTab(); $("promptInput").focus(); };
    $("sendBtn").onclick = send;
    $("cancelBtn").onclick = cancel;
    $("refreshBtn").onclick = () => bootData(true);
    $("btn-auth").onclick = async () => {
      const value = await askToken(token());
      if (value === null) { return; }
      setToken(value);
      bootData(true);
    };
    $("btn-conn").onclick = () => {
      log("info", "connection: " + (state.connected ? "connected" : "offline")
          + " · " + window.location.origin);
      toast((state.connected ? "Connected: " : "Offline: ") + window.location.origin,
          !state.connected);
    };
    $("providerSelect").onchange = async () => {
      state.modelId = "";
      await refreshModels("");
      updateTopbar(); renderContext(); renderLeft(); renderChat();
    };
    $("modelSelect").onchange = () => {
      state.modelId = $("modelSelect").value;
      const model = state.allModels.find((m) => m.id === state.modelId);
      if (model && model.providerId && model.providerId !== state.providerId) {
        state.providerId = model.providerId;
        $("providerSelect").value = model.providerId;
      }
      updateTopbar(); renderContext(); renderLeft(); renderChat();
    };
    $("promptInput").addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        send();
      }
    });

    // Global web-UI shortcuts (documented in help.html#shortcuts).
    document.addEventListener("keydown", (event) => {
      const modifier = event.ctrlKey || event.metaKey;
      const key = event.key.toLowerCase();
      if (modifier && key === "k") {
        event.preventDefault();
        $("promptInput").focus();
      } else if (modifier && key === "n") {
        event.preventDefault();
        startNewConversation();
      } else if (event.key === "Escape" && state.busy) {
        event.preventDefault();
        cancel();
      }
    });
  }

  async function bootData(showToast) {
    setConnection("reconnecting", "connecting");
    try {
      await detect();
    } catch (e) {
      setConnection("disconnected", "offline");
      log("error", "Backend unreachable: " + e.message);
      toast(e.message, true);
      return;
    }
    try {
      await refreshProviders();
      await refreshProjects();
      await refreshHarnesses();
      await loadBackendSessions();
      setConnection("connected", "connected");
      startEventStream();
      updateTopbar();
      renderLeft();
      renderChat();
      renderTab();
      renderContext();
      if (showToast) { toast("Providers, models, projects, harnesses and sessions reloaded."); }
      log("info", "connected to EVO-Studio at " + window.location.origin);
    } catch (e) {
      setConnection("disconnected", "error");
      log("error", "Configuration load failed: " + e.message);
      toast(e.message, true);
    }
  }

  async function boot() {
    applyTheme("btn-theme");
    loadStore();
    if (window.DStudioTasks) {
      window.DStudioTasks.boot({ $, esc, toast, log, json, projects, harnesses });
    }
    const build = $("frontend-build");
    if (build) { build.textContent = FRONTEND_BUILD; }
    console.log("EVO-Studio frontend build " + FRONTEND_BUILD
        + " (" + window.location.origin + ")");
    const conversation = current();
    if (conversation) { state.context = { type: "session", item: conversation }; }
    wire();
    renderLeft();
    renderChat();
    renderTab();
    updateTopbar();
    renderContext();
    await bootData(false);
    // Select the newest backend session once the list has loaded; drafts stay on top.
    if (!current() && state.conversations.length) {
      state.currentId = state.conversations[0].id;
      selectConversation(state.currentId);
    } else if (current() && current().backend && current().messages === null) {
      loadBackendMessages(current());
    }
  }

  boot();
})();
