/* EVO-Studio Inference workspace — single shared frontend (SWT Browser and external
 * browser). Professional four-area layout: application header, left session explorer,
 * main conversation workspace + bottom composer, and an optional contextual inspector.
 * All sessions, messages and streaming go through the existing backend API. */
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
    consoleEntries: [],
    consoleAutoscroll: true,
    eventStream: null,
    lastEventId: 0,
    filesPath: "",
    attachments: [],
    inspectorTab: "context",
    changesVisible: false
  };

  /** Observes workspace/chat/composer size changes so the layout re-syncs even when the
   *  SWT Browser is resized without a traditional window resize event. */
  let resizeObserver = null;

  // ---------------- persistence / conversations ----------------

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
      title: "New session",
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

  // ---------------- connection / status ----------------

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

  function updateTopbar() {
    const project = state.allProjects.find((p) => p.id === state.projectId);
    $("top-project").textContent = project ? project.name : "no project";
    updateStatusbar();
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
    const activity = $("activity-log");
    if (activity) {
      const line = document.createElement("div");
      line.className = "activity-line";
      line.textContent = stamp() + " " + message;
      activity.appendChild(line);
      activity.scrollTop = activity.scrollHeight;
    }
  }

  function problem(message) {
    const node = document.createElement("div");
    node.className = "problem";
    node.textContent = stamp() + " " + message;
    const pane = $("panel-problems");
    pane.appendChild(node);
    if (state.consoleAutoscroll) { pane.scrollTop = pane.scrollHeight; }
  }

  // ---------------- live event stream ----------------

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
      } catch (e) { /* reconnect below */ }
      state.eventStream = false;
      if (state.connected) { setTimeout(startEventStream, 3000); }
    };
    connect();
  }

  // ---------------- data ----------------

  function providerLabel(provider) {
    return provider.name + (provider.isDefault ? " \u2605" : "")
        + (provider.status ? " · " + provider.status : "");
  }

  // Persisted last-used runtime/model (restored on startup for new sessions).
  const SELECTION_KEY = "dstudio.selection.v1";

  function loadSelection() {
    try {
      const raw = localStorage.getItem(SELECTION_KEY);
      if (!raw) { return; }
      const saved = JSON.parse(raw);
      if (saved && saved.providerId) { state.providerId = saved.providerId; }
      if (saved && saved.modelId) { state.modelId = saved.modelId; }
    } catch (e) { /* storage unavailable */ }
  }

  function saveSelection() {
    try {
      localStorage.setItem(SELECTION_KEY, JSON.stringify({
        providerId: state.providerId || "",
        modelId: state.modelId || ""
      }));
    } catch (e) { /* storage unavailable */ }
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
    const available = keep && state.allModels.some((m) => m.id === keep);
    fillSelect(select, state.allModels.map((m) => ({
      id: m.id,
      label: (m.providerName ? m.providerName + " :: " : "") + m.name
    })), available ? keep : "", "no model");
    if (keep && !available && state.allModels.length) {
      // Never silently select an arbitrary model when the previous one disappeared.
      const placeholder = document.createElement("option");
      placeholder.value = "";
      placeholder.textContent = "(model unavailable — select)";
      select.insertBefore(placeholder, select.firstChild);
      select.value = "";
      log("warning", "Previously selected model '" + keep + "' is not available from "
          + providerId + "; select a model.");
    }
    state.modelId = select.value || "";
    saveSelection();
  }

  async function refreshProjects() {
    try { state.allProjects = await projects(); }
    catch (e) { state.allProjects = []; log("error", "Projects unavailable: " + e.message); }
    if (state.projectId && !state.allProjects.some((p) => p.id === state.projectId)) {
      state.projectId = "";
    }
    renderContextSelect();
  }

  async function refreshHarnesses() {
    try { state.allHarnesses = await harnesses(); }
    catch (e) { state.allHarnesses = []; log("error", "Harnesses unavailable: " + e.message); }
    if (state.harnessId && !state.allHarnesses.some((h) => h.id === state.harnessId)) {
      state.harnessId = "";
    }
  }

  // ---------------- inspector ----------------

  function renderContext() {
    const box = $("right-content");
    if (!box) { return; }
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
    } else if (state.context.type === "engine") {
      add("Name", item.name);
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
  }

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
          const bar = document.createElement("span");
          bar.className = "file-actions";
          if (!child.directory) {
            const attach = document.createElement("button");
            attach.className = "btn btn-small";
            attach.textContent = "attach";
            attach.title = "Add this file as context to the next message";
            attach.onclick = (event) => {
              event.stopPropagation();
              addAttachment({ name: child.name, path: child.path || child.name,
                  projectId: project.id });
              toast("Attached " + (child.path || child.name));
            };
            bar.appendChild(attach);
          }
          const action = document.createElement("button");
          action.className = "btn btn-small";
          action.textContent = child.directory ? "open" : "preview";
          action.onclick = (event) => {
            event.stopPropagation();
            state.filesPath = child.path || child.name;
            renderProjectFiles(box, project);
          };
          bar.appendChild(action);
          line.onclick = () => action.click();
          line.appendChild(bar);
          section.appendChild(line);
        }
      } else if (body.content != null) {
        const meta = document.createElement("div");
        meta.className = "ctx-sub";
        meta.textContent = "File: " + (body.path || "");
        section.appendChild(meta);
        const actions = document.createElement("div");
        actions.className = "ctx-files-head";
        const attach = document.createElement("button");
        attach.className = "btn btn-small";
        attach.textContent = "Attach";
        attach.title = "Add this file as context";
        attach.onclick = () => {
          addAttachment({ name: body.path || "", path: body.path || "",
              projectId: project.id });
          toast("Attached " + (body.path || ""));
        };
        actions.appendChild(attach);
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
        actions.appendChild(insert);
        section.appendChild(actions);
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

  function renderInspector() {
    document.querySelectorAll("#inspector-tabs .insp-tab").forEach((tab) =>
        tab.classList.toggle("active", tab.dataset.tab === state.inspectorTab));
    const context = $("right-content");
    const activity = $("activity-log");
    const details = $("details-content");
    const showContext = state.inspectorTab === "context" || state.inspectorTab === "files";
    context.hidden = !showContext;
    activity.hidden = state.inspectorTab !== "activity";
    details.hidden = state.inspectorTab !== "details";
    if (state.inspectorTab === "context") {
      renderContext();
    } else if (state.inspectorTab === "files") {
      renderFiles();
    } else if (state.inspectorTab === "details") {
      renderDetails();
    }
  }

  function renderFiles() {
    const box = $("right-content");
    box.innerHTML = "";
    const project = state.allProjects.find((p) => p.id === state.projectId);
    if (project) {
      state.context = { type: "project", item: project };
      renderProjectFiles(box, project);
    } else {
      box.innerHTML = "<div class=\"empty-note\">Open a project to browse its files.</div>";
    }
  }

  function renderDetails() {
    const box = $("details-content");
    const conversation = current();
    if (!conversation) {
      box.innerHTML = "<div class=\"empty-note\">No active session.</div>";
      return;
    }
    const rows = [];
    const add = (k, v) => rows.push("<div class=\"ctx-kv\"><span class=\"k\">"
        + esc(k) + "</span><span class=\"v\">" + esc(v == null ? "—" : v)
        + "</span></div>");
    add("Session id", conversation.studioSessionId || conversation.id);
    add("Provider", conversation.providerId || "—");
    add("Model", conversation.modelId || "—");
    add("Status", conversation.backend ? (conversation.status || "SESSION") : "LOCAL DRAFT");
    add("Created", conversation.createdAt);
    add("Updated", conversation.updatedAt || "—");
    add("Messages", conversation.messages === null
        ? "not loaded" : String(conversation.messages.length));
    box.innerHTML = rows.join("");
  }

  // ---------------- left views / session explorer ----------------

  function item(node, title, sub, onClick) {
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
        empty.textContent = "No sessions yet.";
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
        const row = item(list, conversation.title || "New session",
            (conversation.modelId || "no model") + " · " + count + " msg"
            + (conversation.backend ? " · server" : ""),
            () => selectConversation(conversation.id));
        if (conversation.id === state.currentId) { row.classList.add("active"); }
        row.addEventListener("contextmenu", (event) => {
          event.preventDefault();
          openSessionMenu(event.clientX, event.clientY, conversation);
        });
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

    if (state.view === "engines") {
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

  // ---------------- session context menu ----------------

  function openSessionMenu(x, y, conversation) {
    const menu = $("context-menu");
    menu.innerHTML = "";
    menu.hidden = false;
    menu.style.left = x + "px";
    menu.style.top = y + "px";
    const actions = [
      { label: "Open", run: () => selectConversation(conversation.id) },
      { label: "Rename", run: () => renameConversation(conversation) },
      { label: "Delete", run: () => deleteConversation(conversation.id) }
    ];
    for (const action of actions) {
      const button = document.createElement("button");
      button.textContent = action.label;
      if (action.label === "Delete") { button.className = "danger"; }
      button.onclick = () => { closeMenu(); action.run(); };
      menu.appendChild(button);
    }
  }

  function closeMenu() {
    const menu = $("context-menu");
    menu.hidden = true;
    menu.innerHTML = "";
    const more = $("more-menu");
    if (more) { more.hidden = true; }
  }

  function confirmDialog(title, message) {
    return new Promise((resolve) => {
      const overlay = document.createElement("div");
      overlay.className = "modal-overlay";
      const modal = document.createElement("div");
      modal.className = "modal";
      const titleNode = document.createElement("div");
      titleNode.className = "modal-title";
      titleNode.textContent = title;
      const textNode = document.createElement("div");
      textNode.className = "modal-text";
      textNode.textContent = message;
      const actions = document.createElement("div");
      actions.className = "modal-actions";
      const cancel = document.createElement("button");
      cancel.textContent = "Cancel";
      const apply = document.createElement("button");
      apply.className = "danger";
      apply.textContent = "Delete";
      const close = (value) => { overlay.remove(); resolve(value); };
      cancel.onclick = () => close(false);
      apply.onclick = () => close(true);
      overlay.onclick = (event) => { if (event.target === overlay) { close(false); } };
      actions.appendChild(cancel);
      actions.appendChild(apply);
      modal.appendChild(titleNode);
      modal.appendChild(textNode);
      modal.appendChild(actions);
      overlay.appendChild(modal);
      document.body.appendChild(overlay);
      apply.focus();
    });
  }

  async function renameConversation(conversation) {
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
          updateTopbar(); renderContext(); renderChat();
        }).catch(() => {});
      }
      state.context = { type: "session", item: conversation };
    }
    renderLeft();
    renderChat();
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
          .map((m) => ({ role: m.role || "assistant", content: m.content || "",
              createdAt: m.createdAt || "" }));
      if (wire.title) { conversation.title = wire.title; }
      if (conversation.id === state.currentId) {
        renderChat(); renderLeft(); renderContext();
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
    const title = conversation ? conversation.title : "session";
    const confirmed = await confirmDialog("Delete session",
        "Delete \"" + title + "\"? This cannot be undone.");
    if (!confirmed) { return; }
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
    renderLeft();
    renderChat();
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

  function messageNode(message, index, messages) {
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
    const actions = document.createElement("span");
    actions.className = "msg-actions";
    if (message.role === "assistant") {
      const copy = document.createElement("button");
      copy.className = "msg-action";
      copy.textContent = "copy";
      copy.title = "Copy response";
      copy.onclick = () => {
        copyToClipboard(message.content || "").then(() => {
          copy.textContent = "copied";
          setTimeout(() => { copy.textContent = "copy"; }, 1400);
        }).catch(() => toast("Clipboard unavailable.", true));
      };
      actions.appendChild(copy);
      const resend = document.createElement("button");
      resend.className = "msg-action";
      resend.textContent = "retry";
      resend.title = "Re-send the previous prompt";
      resend.onclick = () => resendLastPrompt();
      actions.appendChild(resend);
    } else if (message.role === "user") {
      const edit = document.createElement("button");
      edit.className = "msg-action";
      edit.textContent = "edit";
      edit.title = "Edit and resend this prompt";
      edit.onclick = () => {
        const input = $("promptInput");
        input.value = message.content || "";
        input.focus();
        // remove this message and everything after it so it can be re-sent
        const conversation = current();
        if (conversation) {
          const at = conversation.messages.indexOf(message);
          if (at >= 0) { conversation.messages.splice(at); renderChat(); }
        }
      };
      actions.appendChild(edit);
      const copy = document.createElement("button");
      copy.className = "msg-action";
      copy.textContent = "copy";
      copy.title = "Copy prompt";
      copy.onclick = () => {
        copyToClipboard(message.content || "").then(() => {
          copy.textContent = "copied";
          setTimeout(() => { copy.textContent = "copy"; }, 1400);
        }).catch(() => toast("Clipboard unavailable.", true));
      };
      actions.appendChild(copy);
    }
    head.appendChild(actions);
    const text = document.createElement("div");
    text.className = "text";
    text.innerHTML = renderMarkdown(message.content);
    node.appendChild(head);
    node.appendChild(text);
    return node;
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
          + "Engines, models, projects and harnesses are configured in Settings.";
      placeholder.appendChild(title);
      placeholder.appendChild(text);
      const examples = document.createElement("div");
      examples.className = "placeholder-examples";
      ["Explain this project", "Write a Java method that reverses a string",
        "Suggest a unit test", "Why might my build fail?"].forEach((sample) => {
        const chip = document.createElement("button");
        chip.className = "btn placeholder-chip";
        chip.textContent = sample;
        chip.onclick = () => {
          const input = $("promptInput");
          input.value = sample;
          input.focus();
        };
        examples.appendChild(chip);
      });
      placeholder.appendChild(examples);
      logBox.appendChild(placeholder);
      return;
    }
    for (let i = 0; i < messages.length; i++) {
      logBox.appendChild(messageNode(messages[i], i, messages));
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

  // ---------------- attachments ----------------

  function addAttachment(attachment) {
    if (!attachment || (!attachment.name && !attachment.path)) { return; }
    const existing = state.attachments.find((a) =>
        (a.path && a.path === attachment.path) || (a.name && a.name === attachment.name));
    if (!existing) { state.attachments.push(attachment); }
    renderAttachments();
  }

  function removeAttachment(index) {
    state.attachments.splice(index, 1);
    renderAttachments();
  }

  function renderAttachments() {
    const box = $("attachments");
    box.innerHTML = "";
    box.hidden = state.attachments.length === 0;
    state.attachments.forEach((attachment, index) => {
      const chip = document.createElement("span");
      chip.className = "attachment-chip";
      const name = document.createElement("span");
      name.className = "attachment-name";
      name.textContent = attachment.name || attachment.path || "file";
      name.title = attachment.path || attachment.name || "";
      chip.appendChild(name);
      if (attachment.path) {
        const path = document.createElement("span");
        path.className = "attachment-path";
        path.textContent = attachment.path;
        chip.appendChild(path);
      }
      const remove = document.createElement("button");
      remove.className = "attachment-remove";
      remove.textContent = "×";
      remove.title = "Remove attachment";
      remove.onclick = () => removeAttachment(index);
      chip.appendChild(remove);
      box.appendChild(chip);
    });
  }

  /** Resolves each attachment's content so it can be included in the prompt. */
  async function resolveAttachmentContent(attachment) {
    if (attachment.content != null) { return attachment.content; }
    if (attachment.projectId && attachment.path) {
      try {
        const body = await json("/api/workspace/files?project="
            + encodeURIComponent(attachment.projectId)
            + "&path=" + encodeURIComponent(attachment.path));
        return body && body.content != null ? String(body.content) : "";
      } catch (e) {
        return "";
      }
    }
    return "";
  }

  async function buildPromptText(userText) {
    let text = userText || "";
    const blocks = [];
    for (const attachment of state.attachments) {
      const content = await resolveAttachmentContent(attachment);
      const label = attachment.path || attachment.name || "file";
      blocks.push("File: " + label + "\n```\n" + content.slice(0, 30000) + "\n```");
    }
    if (blocks.length) {
      text = blocks.join("\n\n") + (text ? "\n\n" + text : "");
    }
    return text;
  }

  function handleDroppedFiles(files) {
    const list = Array.from(files || []);
    for (const file of list) {
      if (file.size > 1024 * 1024) {
        toast("Attachment too large: " + file.name, true);
        continue;
      }
      const reader = new FileReader();
      reader.onload = () => {
        addAttachment({ name: file.name, content: String(reader.result || "") });
      };
      reader.onerror = () => toast("Could not read " + file.name, true);
      reader.readAsText(file);
    }
  }

  function renderContextSelect() {
    const select = $("context-select");
    if (!select) { return; }
    select.innerHTML = "<option value=\"\">no project context</option>";
    const project = state.allProjects.find((p) => p.id === state.projectId);
    if (!project) { return; }
    const option = document.createElement("option");
    option.value = project.id;
    option.textContent = "project: " + project.name;
    select.appendChild(option);
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

  function resendLastPrompt() {
    const conversation = current();
    if (!conversation || !conversation.messages.length) { return; }
    // find the last user message (excluding a trailing empty assistant message)
    let lastUserIndex = -1;
    for (let i = conversation.messages.length - 1; i >= 0; i--) {
      if (conversation.messages[i].role === "user") { lastUserIndex = i; break; }
    }
    if (lastUserIndex < 0) { return; }
    const prompt = conversation.messages[lastUserIndex].content;
    // remove the trailing assistant response so it can be regenerated
    if (lastUserIndex + 1 < conversation.messages.length
        && conversation.messages[conversation.messages.length - 1].role === "assistant") {
      conversation.messages.splice(lastUserIndex + 1);
    }
    $("promptInput").value = prompt;
    renderChat();
    send();
  }

  async function send() {
    if (state.busy) { return; }
    const input = $("promptInput");
    const text = input.value.trim();
    if (!text && !state.attachments.length) { return; }
    if (!state.providerId || !state.modelId) {
      toast("Select an engine and model first (Settings scans engines and browses models).", true);
      return;
    }
    if (!current()) { startNewConversation(); }
    const active = current();
    if (active.messages === null) { await loadBackendMessages(active); }
    if (active.studioSessionId && (active.providerId !== state.providerId
        || active.modelId !== state.modelId)) {
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

    // Fold attachment contents into the user message (existing file-content capability).
    let content;
    try {
      content = await buildPromptText(text);
    } catch (e) {
      toast("Attachment resolution failed: " + e.message, true);
      return;
    }

    const assistantMessage = { role: "assistant", content: "" };
    active.messages.push({ role: "user", content: content });
    active.messages.push(assistantMessage);
    state.attachments = [];
    renderAttachments();
    input.value = "";
    renderChat();
    touchConversation();
    renderLeft();
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
        method: "POST", body: { content: content }, signal: controller.signal
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

  // ---------------- panels (sidebar / inspector / changes) ----------------

  function toggleSidebar() {
    document.body.classList.toggle("sidebar-collapsed");
  }

  /**
   * Re-syncs the conversation viewport after a resize. CSS flexbox re-flows
   * intrinsically; this only keeps the chat scrolled to the newest message when the
   * user was already following it, so nothing ends up clipped after a resize. Uses
   * ResizeObserver (the SWT Browser may change size without a window resize) with a
   * window.resize fallback. No polling.
   */
  function wireResizeHandling() {
    const chat = $("chatLog");
    if (!chat) { return; }
    const atBottom = () => chat.scrollHeight - chat.scrollTop - chat.clientHeight < 48;
    const sync = () => { if (atBottom()) { chat.scrollTop = chat.scrollHeight; } };
    if (typeof ResizeObserver !== "undefined") {
      resizeObserver = new ResizeObserver(sync);
      resizeObserver.observe(chat);
      const row = $("workspace-row");
      if (row) { resizeObserver.observe(row); }
      const composer = $("composer");
      if (composer) { resizeObserver.observe(composer); }
    }
    window.addEventListener("resize", sync);
  }

  function toggleInspector() {
    const inspector = $("inspector");
    inspector.hidden = !inspector.hidden;
  }

  function renderChanges() {
    const panel = $("changes-panel");
    const content = $("changes-content");
    if (!state.changesVisible) {
      panel.hidden = true;
      return;
    }
    panel.hidden = false;
    content.innerHTML = "<div class=\"empty-note\">No file changes for this session. "
        + "File-change review is available in the desktop Change Review view after a "
        + "coding task.</div>";
  }

  // ---------------- wiring ----------------

  function wire() {
    $("btn-sidebar").onclick = toggleSidebar;
    $("btn-inspector").onclick = toggleInspector;
    $("new-session-btn").onclick = () => { startNewConversation(); };
    $("session-search").oninput = (event) => {
      state.sessionFilter = event.target.value;
      renderLeft();
    };

    document.querySelectorAll("#left-nav .nav-btn").forEach((button) => {
      button.onclick = () => {
        if (state.view === "tasks" && button.dataset.view !== "tasks") {
          window.DStudioTasks.deactivate();
        }
        state.view = button.dataset.view;
        if (button.dataset.view === "tasks") {
          // task details render into right-content; ensure it is visible
          state.inspectorTab = "context";
          $("right-content").hidden = false;
          const activity = $("activity-log");
          if (activity) { activity.hidden = true; }
          const details = $("details-content");
          if (details) { details.hidden = true; }
        }
        renderLeft();
        renderChat();
        renderContext();
      };
    });

    document.querySelectorAll("#inspector-tabs .insp-tab").forEach((tab) => {
      tab.onclick = () => {
        state.inspectorTab = tab.dataset.tab;
        renderInspector();
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
    $("chip-export").onclick = exportConsole;

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
      const previousModel = state.modelId;
      await refreshModels(previousModel);
      saveSelection();
      updateTopbar(); renderContext(); renderLeft(); renderChat();
    };
    $("modelSelect").onchange = () => {
      state.modelId = $("modelSelect").value;
      const model = state.allModels.find((m) => m.id === state.modelId);
      if (model && model.providerId && model.providerId !== state.providerId) {
        state.providerId = model.providerId;
        $("providerSelect").value = model.providerId;
      }
      saveSelection();
      updateTopbar(); renderContext(); renderLeft(); renderChat();
    };

    // Composer keyboard behavior: Enter sends, Shift+Enter adds a newline.
    $("promptInput").addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        send();
      }
    });

    // Attachments: file picker + drag-and-drop.
    $("attach-btn").onclick = () => $("file-input").click();
    $("file-input").onchange = () => {
      handleDroppedFiles($("file-input").files);
      $("file-input").value = "";
    };
    $("context-select").onchange = () => {
      const projectId = $("context-select").value;
      if (!projectId) { return; }
      const project = state.allProjects.find((p) => p.id === projectId);
      if (project) {
        state.projectId = project.id;
        state.context = { type: "project", item: project };
        renderContext();
        // open the Files inspector so the user can pick a file to attach
        state.inspectorTab = "files";
        renderInspector();
      }
      $("context-select").value = "";
    };

    const composer = $("composer");
    composer.addEventListener("dragover", (event) => { event.preventDefault(); });
    composer.addEventListener("drop", (event) => {
      event.preventDefault();
      if (event.dataTransfer && event.dataTransfer.files
          && event.dataTransfer.files.length) {
        handleDroppedFiles(event.dataTransfer.files);
      }
    });

    // "More" menu.
    $("btn-more").onclick = (event) => {
      event.stopPropagation();
      const menu = $("more-menu");
      menu.hidden = !menu.hidden;
      if (!menu.hidden) {
        menu.style.right = "8px";
        menu.style.top = "48px";
        menu.style.left = "auto";
      }
    };
    $("more-menu").querySelectorAll("button[data-action]").forEach((button) => {
      button.onclick = () => {
        const action = button.dataset.action;
        closeMenu();
        if (action === "new-session") { startNewConversation(); }
        else if (action === "export-console") { exportConsole(); }
        else if (action === "clear-console") { $("panel-console").innerHTML = ""; }
      };
    });

    // Changes panel toggle (hidden by default).
    $("changes-close").onclick = () => { state.changesVisible = false; renderChanges(); };

    // Dismiss menus on outside click.
    document.addEventListener("click", (event) => {
      const menu = $("context-menu");
      if (!menu.hidden && !menu.contains(event.target)) { closeMenu(); }
      const more = $("more-menu");
      if (more && !more.hidden && event.target !== $("btn-more")
          && !more.contains(event.target)) { more.hidden = true; }
    });

    // Global shortcuts.
    document.addEventListener("keydown", (event) => {
      const modifier = event.ctrlKey || event.metaKey;
      const key = event.key.toLowerCase();
      if (modifier && key === "k") {
        event.preventDefault();
        $("promptInput").focus();
      } else if (modifier && key === "n") {
        event.preventDefault();
        startNewConversation();
      } else if (modifier && key === "b") {
        event.preventDefault();
        toggleSidebar();
      } else if (modifier && key === ".") {
        event.preventDefault();
        toggleInspector();
      } else if (event.key === "Escape" && state.busy) {
        event.preventDefault();
        cancel();
      }
    });
  }

  function exportConsole() {
    const blob = new Blob([state.consoleEntries.join("\n")], { type: "text/plain" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "evostudio-console.txt";
    link.click();
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
      renderContext();
      renderInspector();
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
    loadSelection();
    if (window.DStudioTasks) {
      window.DStudioTasks.boot({ $, esc, toast, log, json, projects, harnesses });
    }
    const build = $("frontend-build") || $("sb-build");
    if (build) { build.textContent = FRONTEND_BUILD; }
    console.log("EVO-Studio frontend build " + FRONTEND_BUILD
        + " (" + window.location.origin + ")");
    const conversation = current();
    if (conversation) { state.context = { type: "session", item: conversation }; }
    wire();
    wireResizeHandling();
    renderLeft();
    renderChat();
    renderInspector();
    updateTopbar();
    renderContext();
    await bootData(false);
    if (!current() && state.conversations.length) {
      state.currentId = state.conversations[0].id;
      selectConversation(state.currentId);
    } else if (current() && current().backend && current().messages === null) {
      loadBackendMessages(current());
    }
  }

  boot();
})();
