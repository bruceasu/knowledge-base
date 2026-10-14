const api = {
  health: "/api/v2/health",
  search: "/api/v2/knowledge/search",
  knowledgeObject: (id) => `/api/v2/knowledge/objects/${encodeURIComponent(id)}`,
  ask: "/api/v2/ask",
  documents: "/api/v2/documents",
  document: (id) => `/api/v2/documents/${encodeURIComponent(id)}`,
  documentRevisions: (id) => `/api/v2/documents/${encodeURIComponent(id)}/revisions`,
  restoreRevision: (id, revision) => `/api/v2/documents/${encodeURIComponent(id)}/revisions/${encodeURIComponent(revision)}/restore`,
  trash: "/api/v2/trash",
  restoreTrash: (id) => `/api/v2/trash/${encodeURIComponent(id)}/restore`,
  sessions: "/api/v2/sessions",
  sessionImport: "/api/v2/sessions/import",
  sessionById: (id) => `/api/v2/sessions/${encodeURIComponent(id)}`,
  sessionExport: (id) => `/api/v2/sessions/${encodeURIComponent(id)}/export`,
};

const storageKeys = {
  sessions: "asu-kb.sessions.v1",
  activeSession: "asu-kb.active-session.v1",
  sidebarOpen: "asu-kb.sidebar-open.v1",
  mode: "asu-kb.mode.v1",
};

const $ = (id) => document.getElementById(id);

const dom = {
  body: document.body,
  sidebar: $("sidebar"),
  sidebarToggle: $("panelToggle"),
  sidebarRememberOpen: $("sidebarRememberOpen"),
  searchModeBtn: $("searchModeBtn"),
  askModeBtn: $("askModeBtn"),
  workspaceForm: $("workspaceForm"),
  promptLabel: $("promptLabel"),
  promptInput: $("promptInput"),
  primaryActionBtn: $("primaryActionBtn"),
  limitInput: $("limitInput"),
  topKInput: $("topKInput"),
  thread: $("thread"),
  threadSummary: $("threadSummary"),
  sessionList: $("sessionList"),
  newSessionBtn: $("newSessionBtn"),
  renameSessionBtn: $("renameSessionBtn"),
  deleteSessionBtn: $("deleteSessionBtn"),
  sessionExportBtn: $("sessionExportBtn"),
  sessionImportInput: $("sessionImportInput"),
  clearThreadBtn: $("clearThreadBtn"),
  newArticleBtn: $("newArticleBtn"),
  trashBtn: $("trashBtn"),
  clearActivityBtn: $("clearActivityBtn"),
  refreshHealthBtn: $("refreshHealthBtn"),
  healthDot: $("healthDot"),
  healthText: $("healthText"),
  healthDetail: $("healthDetail"),
  modeDetail: $("modeDetail"),
  sessionDetail: $("sessionDetail"),
  messageCountDetail: $("messageCountDetail"),
  storageDetail: $("storageDetail"),
  sidebarDetail: $("sidebarDetail"),
  activityFeed: $("activityFeed"),
};

const state = {
  mode: "search",
  sidebarOpen: false,
  sessions: [],
  activeSessionId: null,
  activity: [],
  health: { ok: false, text: "Checking..." },
  lastAskResponse: null,
  operation: { status: "idle", message: "" },
  requests: { health: 0, sessionList: 0, sessionDetail: 0, prompt: 0 },
};

function operationStatusElement() {
  let element = document.getElementById("operationStatus");
  if (!element) {
    element = document.createElement("p");
    element.id = "operationStatus";
    element.className = "operation-status";
    element.setAttribute("aria-live", "polite");
    dom.workspaceForm.appendChild(element);
  }
  return element;
}

function setOperationStatus(status, message = "") {
  state.operation = { status, message };
  const element = operationStatusElement();
  element.textContent = message;
  element.dataset.status = status;
}

function setPrimaryBusy(busy) {
  dom.primaryActionBtn.disabled = busy;
  dom.primaryActionBtn.setAttribute("aria-busy", busy ? "true" : "false");
  if (busy) {
    dom.primaryActionBtn.dataset.idleLabel = dom.primaryActionBtn.textContent;
    dom.primaryActionBtn.textContent = state.mode === "search" ? "检索中…" : "回答中…";
  } else if (dom.primaryActionBtn.dataset.idleLabel) {
    dom.primaryActionBtn.textContent = dom.primaryActionBtn.dataset.idleLabel;
  }
}

function createSession(title = "Session") {
  const now = new Date().toISOString();
  return {
    id: crypto.randomUUID(),
    title,
    name: title,
    createdAt: now,
    updatedAt: now,
    mode: "search",
    messages: [],
    summaries: [],
  };
}

function safeParse(value, fallback) {
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function sessionDisplayName(session) {
  return session?.title || session?.name || "Session";
}

function activeSession() {
  return state.sessions.find((session) => session.id === state.activeSessionId) || state.sessions[0];
}

function ensureActiveSession() {
  if (!state.sessions.length) {
    state.sessions = [createSession("Starter")];
  }
  if (!state.activeSessionId || !state.sessions.some((session) => session.id === state.activeSessionId)) {
    state.activeSessionId = state.sessions[0].id;
  }
  const session = activeSession();
  state.mode = session?.mode || state.mode || "search";
  setMode(state.mode, false);
  return session;
}

function loadCachedSessions() {
  const raw = localStorage.getItem(storageKeys.sessions);
  const parsed = raw ? safeParse(raw, null) : null;
  if (!parsed) {
    return [createSession("Starter")];
  }
  if (Array.isArray(parsed)) {
    return parsed.length ? parsed : [createSession("Starter")];
  }
  if (Array.isArray(parsed.sessions)) {
    return parsed.sessions.length ? parsed.sessions : [createSession("Starter")];
  }
  return [createSession("Starter")];
}

function saveCache() {
  localStorage.setItem(storageKeys.sessions, JSON.stringify(state.sessions));
  localStorage.setItem(storageKeys.activeSession, state.activeSessionId || "");
  localStorage.setItem(storageKeys.mode, state.mode);
  localStorage.setItem(storageKeys.sidebarOpen, state.sidebarOpen ? "1" : "0");
}

function normalizeSession(session) {
  if (!session) {
    return session;
  }
  return {
    ...session,
    title: session.title || session.name || "Session",
    name: session.title || session.name || "Session",
    messages: Array.isArray(session.messages) ? session.messages : [],
    summaries: Array.isArray(session.summaries) ? session.summaries : [],
  };
}

function normalizeServerMessage(message) {
  if (message.kind) {
    return message;
  }
  if (message.role === "assistant") {
    return {
      ...message,
      kind: "answer",
      answer: message.content || "",
      sources: message.sources || [],
    };
  }
  if (message.role === "user") {
    return {
      ...message,
      kind: "user",
      text: message.content || "",
    };
  }
  return {
    ...message,
    kind: message.role || "system",
    text: message.content || "",
  };
}

function setSidebarOpen(open, persist = true) {
  state.sidebarOpen = open;
  dom.body.classList.toggle("sidebar-collapsed", !open);
  dom.sidebarToggle.textContent = open ? "Hide sidebar" : "Open sidebar";
  dom.sidebarDetail.textContent = open ? "Visible" : "Hidden by default";
  dom.sidebarRememberOpen.checked = open;
  if (persist) {
    localStorage.setItem(storageKeys.sidebarOpen, open ? "1" : "0");
  }
}

function setMode(mode, persist = true) {
  state.mode = mode;
  const isSearch = mode === "search";
  dom.searchModeBtn.classList.toggle("active", isSearch);
  dom.askModeBtn.classList.toggle("active", !isSearch);
  dom.promptLabel.textContent = isSearch ? "Query" : "Question";
  dom.promptInput.placeholder = isSearch ? "Type a search query" : "Type a question";
  dom.primaryActionBtn.textContent = isSearch ? "Search" : "Ask";
  for (const panel of document.querySelectorAll("[data-mode-panel]")) {
    panel.hidden = panel.getAttribute("data-mode-panel") !== mode;
  }
  dom.modeDetail.textContent = isSearch ? "Search" : "Ask";
  const session = activeSession();
  if (session) {
    session.mode = mode;
  }
  if (persist) {
    localStorage.setItem(storageKeys.mode, mode);
  }
  saveCache();
}

function formatTime(iso) {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso || "";
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function getMessageCount(session) {
  return session?.messages?.length || 0;
}

function pushActivity(text, tone = "info") {
  state.activity.unshift({
    id: crypto.randomUUID(),
    text,
    tone,
    at: new Date().toISOString(),
  });
  state.activity = state.activity.slice(0, 12);
  renderActivity();
}

function addMessage(session, message) {
  session.messages.push({
    id: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
    ...message,
  });
  saveCache();
  renderAll();
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.text();
  let json = {};
  if (body) {
    try {
      json = JSON.parse(body);
    } catch {
      throw new Error(body || response.statusText);
    }
  }
  if (!response.ok || json.ok === false) {
    const diagnostic = Array.isArray(json.diagnostics) && json.diagnostics[0];
    const detail = diagnostic?.detail ? `：${diagnostic.detail}` : "";
    const error = new Error(`${json.message || json.error || response.statusText}${detail}`);
    error.status = response.status;
    error.code = json.code || diagnostic?.code;
    error.payload = json;
    throw error;
  }
  return json;
}

async function loadSessionDetail(sessionId) {
  if (!sessionId) {
    return null;
  }
  const request = ++state.requests.sessionDetail;
  const json = await requestJson(api.sessionExport(sessionId), { method: "GET" });
  if (request !== state.requests.sessionDetail || state.activeSessionId !== sessionId) return null;
  const session = normalizeSession(json.session);
  session.messages = (json.messages || []).map(normalizeServerMessage);
  session.summaries = json.summaries || [];
  const index = state.sessions.findIndex((item) => item.id === sessionId);
  if (index >= 0) {
    state.sessions[index] = { ...state.sessions[index], ...session };
  } else {
    state.sessions.unshift(session);
  }
  return session;
}

async function refreshSessionsFromServer() {
  const request = ++state.requests.sessionList;
  const json = await requestJson(api.sessions, { method: "GET" });
  if (request !== state.requests.sessionList) return;
  const sessions = (json.sessions || []).map((session) => normalizeSession(session));
  if (!sessions.length) {
    const created = await requestJson(api.sessions, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: "Starter" }),
    });
    if (request !== state.requests.sessionList) return;
    sessions.push(normalizeSession(created.session));
  }
  state.sessions = sessions;
  if (!state.activeSessionId || !state.sessions.some((session) => session.id === state.activeSessionId)) {
    state.activeSessionId = state.sessions[0].id;
  }
  await loadSessionDetail(state.activeSessionId);
  saveCache();
  renderAll();
}

async function selectSession(sessionId) {
  state.activeSessionId = sessionId;
  const session = await loadSessionDetail(sessionId);
  if (session) {
    state.mode = session.mode || state.mode || "search";
    state.lastAskResponse = null;
    setMode(state.mode, false);
    saveCache();
    renderAll();
    pushActivity(`Switched to session ${sessionDisplayName(session)}`);
  }
}

function renderSessions() {
  dom.sessionList.innerHTML = state.sessions
    .map((session) => {
      const active = session.id === state.activeSessionId;
      return `
        <button class="session-item ${active ? "active" : ""}" type="button" data-session-id="${session.id}">
          <span class="session-name">${escapeHtml(sessionDisplayName(session))}</span>
          <span class="session-meta">${getMessageCount(session)} msgs</span>
        </button>
      `;
    })
    .join("");

  dom.sessionList.querySelectorAll("[data-session-id]").forEach((button) => {
    button.addEventListener("click", () => {
      const nextSessionId = button.getAttribute("data-session-id");
      selectSession(nextSessionId).catch((err) => pushActivity(`Switch failed: ${err.message}`, "error"));
    });
  });
}

function renderSources(sources) {
  if (!sources.length) {
    return "";
  }
  return `
    <div class="source-list">
      <div class="source-title">Sources</div>
      ${sources
        .map(
          (source) => `
            <div class="source-item source-link" data-document-id="${escapeHtml(source.documentId || "")}" data-object-id="${escapeHtml(source.objectId || "")}" role="button" tabindex="0">
              <div class="source-path">${escapeHtml(source.path || "暂无来源路径")} ${source.chunkNo == null ? "" : `· chunk ${escapeHtml(source.chunkNo)}`}</div>
              <div class="hit-meta">${escapeHtml(source.freshness || "freshness unavailable")} · evidence ${source.evidenceCount ?? "—"}</div>
              <div class="source-snippet">${escapeHtml(source.snippet || "")}</div>
            </div>
          `,
        )
        .join("")}
    </div>
  `;
}

function renderThread() {
  const session = ensureActiveSession();
  const threadItems = session.messages.slice();
  if (state.lastAskResponse && state.lastAskResponse.sessionId === state.activeSessionId) {
    threadItems.push({
      kind: "ask-sources",
      createdAt: new Date().toISOString(),
      sources: state.lastAskResponse.sources || [],
    });
  }

  dom.threadSummary.textContent = `${threadItems.length} messages`;
  dom.thread.innerHTML = threadItems.length
    ? threadItems
        .map((message) => {
          if (message.kind === "search-results") {
            const diagnostics = (message.diagnostics || [])
              .map((diagnostic) => `${diagnostic.code || "诊断"}：${diagnostic.detail || ""}`)
              .join("；");
            return `
              <article class="message assistant">
                <div class="message-meta">Search ${formatTime(message.createdAt)}</div>
                <div class="message-body">
                  <div class="search-query">Query: ${escapeHtml(message.query || "")}</div>
                  ${message.complete === false || diagnostics ? `<div class="result-notice warning-text">${escapeHtml(message.complete === false ? "结果不完整。" : "")} ${escapeHtml(diagnostics)}</div>` : ""}
                  <div class="search-hits">
                    ${(message.hits || [])
                      .map(
                        (hit) => `
                          <div class="hit-card source-link" data-object-id="${escapeHtml(hit.objectId || "")}" role="button" tabindex="0">
                            <div class="hit-top">
                              <strong>${escapeHtml(hit.title || "")}</strong>
                              <span class="hit-score">${hit.score?.toFixed ? hit.score.toFixed(3) : hit.score}</span>
                            </div>
                            <div class="hit-path">${escapeHtml(hit.source?.relativePath || "暂无来源路径")} ${hit.source?.startLine ? `· 第 ${escapeHtml(hit.source.startLine)}-${escapeHtml(hit.source.endLine || hit.source.startLine)} 行` : ""}</div>
                            <div class="hit-meta">${escapeHtml(hit.scope || "SHARED")} · ${escapeHtml(hit.freshness || "freshness unavailable")}${hit.virtualProjectId ? ` · project ${escapeHtml(hit.virtualProjectId)}` : ""} · evidence ${(hit.evidence || []).length}</div>
                            <div class="hit-snippet">${escapeHtml(hit.excerpt || "")}</div>
                          </div>
                        `,
                      )
                      .join("")}
                  </div>
                </div>
              </article>
            `;
          }
          if (message.kind === "answer") {
            return `
              <article class="message assistant">
                <div class="message-meta">Answer ${formatTime(message.createdAt)}</div>
                <div class="message-body">
                  <pre>${escapeHtml(message.answer || message.content || "")}</pre>
                  ${renderSources(message.sources || [])}
                </div>
              </article>
            `;
          }
          if (message.kind === "ask-sources") {
            return `
              <article class="message system">
                <div class="message-meta">Sources ${formatTime(message.createdAt)}</div>
                <div class="message-body">
                  ${renderSources(message.sources || [])}
                </div>
              </article>
            `;
          }
          if (message.kind === "system") {
            return `
              <article class="message system">
                <div class="message-meta">System ${formatTime(message.createdAt)}</div>
                <div class="message-body">${escapeHtml(message.text || message.content || "")}</div>
              </article>
            `;
          }
          if (message.kind === "error") {
            return `
              <article class="message error">
                <div class="message-meta">Error ${formatTime(message.createdAt)}</div>
                <div class="message-body">${escapeHtml(message.text || message.content || "")}</div>
              </article>
            `;
          }
          return `
            <article class="message user">
              <div class="message-meta">You ${formatTime(message.createdAt)}</div>
              <div class="message-body">${escapeHtml(message.text || message.content || "")}</div>
            </article>
          `;
        })
        .join("")
    : `<div class="empty-state">
        <strong>Start here.</strong>
        <p>Use the mode switch above, type a query or question, and the thread will grow inside the current browser session.</p>
      </div>`;
}

function renderActivity() {
  dom.activityFeed.innerHTML = state.activity.length
    ? state.activity
        .map(
          (item) => `
            <div class="activity-item ${item.tone}">
              <div class="activity-time">${new Date(item.at).toLocaleTimeString()}</div>
              <div class="activity-text">${escapeHtml(item.text)}</div>
            </div>
          `,
        )
        .join("")
    : `<div class="empty-state compact">No activity yet.</div>`;
}

function renderStatus() {
  const session = activeSession();
  dom.healthText.textContent = state.health.text;
  dom.healthDetail.textContent = state.health.text;
  dom.healthDot.classList.toggle("ok", state.health.ok);
  dom.sessionDetail.textContent = session ? sessionDisplayName(session) : "-";
  dom.messageCountDetail.textContent = session ? String(session.messages.length) : "0";
  dom.storageDetail.textContent = `${state.sessions.length} sessions cached locally; server is source of truth`;
  dom.sidebarDetail.textContent = state.sidebarOpen ? "Visible" : "Hidden by default";
  dom.sidebarRememberOpen.checked = state.sidebarOpen;
}

function renderAll() {
  renderSessions();
  renderThread();
  renderActivity();
  renderStatus();
}

async function checkHealth() {
  const request = ++state.requests.health;
  try {
    const json = await requestJson(api.health);
    if (request !== state.requests.health) return;
    state.health = { ok: json.alive === true, text: json.state || "alive" };
  } catch (err) {
    if (request !== state.requests.health) return;
    state.health = { ok: false, text: err.message };
  }
  renderStatus();
}

async function searchCurrent() {
  const query = dom.promptInput.value.trim();
  if (!query) {
    throw new Error("Please enter a search query.");
  }
  const limit = dom.limitInput.value || "10";
  const sessionId = state.activeSessionId;
  const request = ++state.requests.prompt;
  const url = `${api.search}?query=${encodeURIComponent(query)}&limit=${encodeURIComponent(limit)}`;
  setOperationStatus("loading", "正在检索…");
  const json = await requestJson(url, { method: "GET" });
  if (request !== state.requests.prompt || state.activeSessionId !== sessionId) return;
  const session = activeSession();
  addMessage(session, { kind: "user", text: query });
  addMessage(session, { kind: "search-results", query, hits: json.data?.items || [], complete: json.complete !== false, diagnostics: json.diagnostics || [] });
  pushActivity(`Search completed: ${query}`);
  setOperationStatus(json.complete === false ? "partial" : "success", json.complete === false ? "检索完成，但结果不完整，请查看诊断。" : `检索完成：${json.data?.items?.length || 0} 条结果。`);
  dom.promptInput.value = "";
}

async function askCurrent() {
  const question = dom.promptInput.value.trim();
  if (!question) {
    throw new Error("Please enter a question.");
  }
  const topK = Number(dom.topKInput.value || "5");
  const sessionId = state.activeSessionId;
  const request = ++state.requests.prompt;
  setOperationStatus("loading", "正在生成回答…");
  const json = await requestJson(api.ask, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      question, topK, sessionId,
    }),
  });
  if (request !== state.requests.prompt || state.activeSessionId !== sessionId) return;
  await loadSessionDetail(sessionId);
  state.lastAskResponse = {
    sessionId,
    question,
    answer: json.answer || "",
    sources: json.sources || [],
  };
  saveCache();
  renderAll();
  pushActivity(`Ask completed: ${question}`);
  setOperationStatus("success", "回答完成；来源已附在结果下方。 ");
  dom.promptInput.value = "";
}

async function clearCurrentThread() {
  if (!state.activeSessionId) {
    return;
  }
  await loadSessionDetail(state.activeSessionId);
  state.lastAskResponse = null;
  saveCache();
  renderAll();
  pushActivity(`Reset thread view for ${sessionDisplayName(activeSession())}`);
}

async function createNewSession() {
  const title = `Session ${state.sessions.length + 1}`;
  const created = await requestJson(api.sessions, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title }),
  });
  await refreshSessionsFromServer();
  if (created.session?.id) {
    await selectSession(created.session.id);
  }
  pushActivity(`Created ${sessionDisplayName(activeSession())}`);
}

async function renameCurrentSession() {
  const session = activeSession();
  const nextName = prompt("Rename session", sessionDisplayName(session));
  if (!nextName) {
    return;
  }
  await requestJson(api.sessionById(session.id), {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title: nextName.trim() || sessionDisplayName(session) }),
  });
  await refreshSessionsFromServer();
  await selectSession(session.id);
  pushActivity(`Renamed session to ${sessionDisplayName(activeSession())}`);
}

async function deleteCurrentSession() {
  if (state.sessions.length === 1) {
    alert("Keep at least one session.");
    return;
  }
  const session = activeSession();
  if (!confirm(`Delete "${sessionDisplayName(session)}"?`)) {
    return;
  }
  await requestJson(api.sessionById(session.id), { method: "DELETE" });
  await refreshSessionsFromServer();
  await selectSession(state.sessions[0].id);
  pushActivity(`Deleted session ${sessionDisplayName(session)}`);
}

async function exportSessions() {
  const session = activeSession();
  const json = await requestJson(api.sessionExport(session.id), { method: "GET" });
  const blob = new Blob([JSON.stringify(json, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `asu-libaray-session-${new Date().toISOString().slice(0, 19).replaceAll(":", "-")}.json`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  pushActivity(`Exported ${sessionDisplayName(session)} to JSON`);
}

async function importSessions(file) {
  const text = await file.text();
  const parsed = safeParse(text, null);
  if (!parsed) {
    throw new Error("Invalid session file.");
  }
  const imported = await requestJson(api.sessionImport, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(parsed),
  });
  await refreshSessionsFromServer();
  if (imported.session?.id) {
    await selectSession(imported.session.id);
  }
  pushActivity(`Imported session ${sessionDisplayName(activeSession())}`);
}

async function refreshSessionsFromServerAndRender() {
  await refreshSessionsFromServer();
}

async function openDocumentEditor(documentId) {
  if (!documentId) return;
  const json = await requestJson(api.document(documentId));
  const metadata = json.metadata || {};
  const value = (field) => metadata[field]?.value || "";
  const list = (field) => (metadata[field] || []).map((item) => item.value || item).join(", ");
  const overlay = document.createElement("div");
  overlay.className = "document-editor-overlay";
  overlay.innerHTML = `<div class="card document-editor" role="dialog" aria-modal="true">
    <div class="card-head"><div><span class="section-kicker">Document</span><h2>${escapeHtml(value("title") || json.path || "Document")}</h2></div><button class="ghost close-document" type="button">Close</button></div>
    <p class="hint">Revision ${escapeHtml(json.currentRevision)}</p>
    <label class="field"><span>Title</span><input data-editor-field="title" value="${escapeHtml(value("title"))}" disabled /></label>
    <label class="field"><span>Summary</span><input data-editor-field="summary" value="${escapeHtml(value("summary"))}" disabled /></label>
    <label class="field"><span>Keywords</span><input data-editor-field="keywords" value="${escapeHtml(list("keywords"))}" disabled /></label>
    <label class="field"><span>Tags</span><input data-editor-field="tags" value="${escapeHtml(list("tags"))}" disabled /></label>
    <label class="field"><span>Content</span><textarea data-editor-field="content" rows="18" disabled>${escapeHtml(json.content || "")}</textarea></label>
    <div class="row"><button class="ghost history-document" type="button">History</button><button class="ghost delete-document" type="button">Delete</button><button class="primary edit-document" type="button">Edit</button><button class="primary save-document" type="button" hidden>Save</button><span class="hint document-save-state"></span></div><div class="document-history"></div>
  </div>`;
  document.body.appendChild(overlay);
  const fields = overlay.querySelectorAll("[data-editor-field]");
  overlay.querySelector(".close-document").addEventListener("click", () => overlay.remove());
  overlay.querySelector(".edit-document").addEventListener("click", () => {
    fields.forEach((field) => { field.disabled = false; });
    overlay.querySelector(".edit-document").hidden = true;
    overlay.querySelector(".save-document").hidden = false;
  });
  overlay.querySelector(".history-document").addEventListener("click", async () => {
    const history = overlay.querySelector(".document-history");
    try {
      const result = await requestJson(api.documentRevisions(documentId));
      history.innerHTML = (result.revisions || []).map((revision) => `<div class="history-row"><span>Revision ${escapeHtml(revision.revision)}</span><small>${escapeHtml(revision.createdAt || "")}</small><button class="ghost restore-revision" data-revision="${escapeHtml(revision.revision)}" type="button">Restore</button></div>`).join("");
      history.querySelectorAll(".restore-revision").forEach((button) => button.addEventListener("click", async () => {
        if (!window.confirm(`Restore revision ${button.dataset.revision} as a new revision?`)) return;
        await requestJson(api.restoreRevision(documentId, button.dataset.revision), { method: "POST" });
        overlay.querySelector(".document-save-state").textContent = "Revision restored as a new current revision.";
      }));
    } catch (error) { history.textContent = error.message; }
  });
  overlay.querySelector(".delete-document").addEventListener("click", async () => {
    if (!window.confirm("Move this document to workspace trash?")) return;
    try {
      await requestJson(api.document(documentId), { method: "DELETE" });
      overlay.remove();
      pushActivity(`Moved document ${documentId} to trash`);
    } catch (error) { overlay.querySelector(".document-save-state").textContent = error.message; }
  });
  overlay.querySelector(".save-document").addEventListener("click", async () => {
    const get = (field) => overlay.querySelector(`[data-editor-field="${field}"]`).value;
    const stateText = overlay.querySelector(".document-save-state");
    stateText.textContent = "Saving...";
    try {
      const saved = await requestJson(api.document(documentId), {
        method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: get("content"), expectedBaseHash: json.contentHash,
          title: get("title"), summary: get("summary"),
          keywords: get("keywords").split(",").map((item) => item.trim()).filter(Boolean),
          tags: get("tags").split(",").map((item) => item.trim()).filter(Boolean) }),
      });
      stateText.textContent = saved.projectionState === "indexed"
        ? "File saved; index updated."
        : `File saved; index ${saved.projectionState || "pending"}.`;
    } catch (error) {
      stateText.textContent = error.message;
      if (error.status === 409 && error.payload) {
        const conflict = document.createElement("div");
        conflict.className = "document-conflict";
        conflict.innerHTML = `<strong>Conflict: review server and submitted content</strong>
          <div class="conflict-grid"><div><span>Server</span><pre>${escapeHtml(error.payload.server || "")}</pre></div>
          <div><span>Submitted</span><pre>${escapeHtml(error.payload.submitted || "")}</pre></div></div>`;
        stateText.after(conflict);
      }
    }
  });
}

async function openKnowledgeReference(objectId) {
  if (!objectId) return;
  const response = await requestJson(api.knowledgeObject(objectId));
  const item = response.data?.object;
  if (!item) throw new Error("Knowledge object is unavailable.");
  const overlay = document.createElement("div");
  overlay.className = "document-editor-overlay";
  overlay.innerHTML = `<div class="card document-editor" role="dialog" aria-modal="true">
    <div class="card-head"><div><span class="section-kicker">Reference</span><h2>${escapeHtml(item.title || item.objectId)}</h2></div><button class="ghost close-document" type="button">Close</button></div>
    <p class="hint">${escapeHtml(item.source?.relativePath || item.objectType || "Knowledge object")}</p>
    <pre>${escapeHtml(item.excerpt || "No excerpt available.")}</pre>
  </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector(".close-document").addEventListener("click", () => overlay.remove());
}

async function createArticle() {
  const filename = window.prompt("Filename", "new-article.md");
  if (!filename) return;
  const content = window.prompt("Initial content", "# New Article\n\n");
  if (content === null) return;
  const json = await requestJson(api.documents, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ filename, content }),
  });
  pushActivity(`Created article ${json.path || filename}`);
  if (json.documentId) openDocumentEditor(json.documentId).catch(() => {});
}

async function openTrash() {
  const json = await requestJson(api.trash);
  const entries = json.entries || [];
  if (!entries.length) { window.alert("Trash is empty."); return; }
  const choice = window.prompt(entries.map((entry, index) => `${index + 1}. ${entry.originalPath}`).join("\n") + "\n\nEnter number to restore:");
  const index = Number(choice) - 1;
  if (!Number.isInteger(index) || !entries[index]) return;
  await requestJson(api.restoreTrash(entries[index].documentId), { method: "POST" });
  pushActivity(`Restored document ${entries[index].documentId}`);
}

function bindEvents() {
  dom.newArticleBtn?.addEventListener("click", () => {
    createArticle().catch((error) => pushActivity(`Create article failed: ${error.message}`, "error"));
  });
  dom.trashBtn?.addEventListener("click", () => {
    openTrash().catch((error) => pushActivity(`Trash failed: ${error.message}`, "error"));
  });
  dom.thread.addEventListener("click", (event) => {
    const source = event.target.closest("[data-document-id]");
    const knowledgeSource = event.target.closest("[data-object-id]");
    if (!source && !knowledgeSource) return;
    const operation = source?.dataset.documentId
      ? openDocumentEditor(source.dataset.documentId)
      : openKnowledgeReference(knowledgeSource.dataset.objectId);
    operation
      .catch((error) => pushActivity(`Open document failed: ${error.message}`, "error"));
  });
  dom.sidebarToggle.addEventListener("click", () => {
    setSidebarOpen(!state.sidebarOpen);
    renderStatus();
  });

  dom.sidebarRememberOpen.addEventListener("change", () => {
    setSidebarOpen(dom.sidebarRememberOpen.checked);
    renderStatus();
  });

  dom.searchModeBtn.addEventListener("click", () => {
    setMode("search");
    renderStatus();
  });

  dom.askModeBtn.addEventListener("click", () => {
    setMode("ask");
    renderStatus();
  });
  dom.workspaceForm.addEventListener("submit", (event) => {
    event.preventDefault();
    if (dom.primaryActionBtn.disabled) return;
    setPrimaryBusy(true);
    const run = state.mode === "search" ? searchCurrent() : askCurrent();
    run.catch((err) => {
      pushActivity(`${state.mode} failed: ${err.message}`, "error");
      addMessage(activeSession(), { kind: "error", text: err.message });
      setOperationStatus("error", `${err.code ? `${err.code}：` : ""}${err.message}`);
    }).finally(() => setPrimaryBusy(false));
  });

  dom.newSessionBtn.addEventListener("click", () => {
    createNewSession().catch((err) => pushActivity(`Create failed: ${err.message}`, "error"));
  });
  dom.renameSessionBtn.addEventListener("click", () => {
    renameCurrentSession().catch((err) => pushActivity(`Rename failed: ${err.message}`, "error"));
  });
  dom.deleteSessionBtn.addEventListener("click", () => {
    deleteCurrentSession().catch((err) => pushActivity(`Delete failed: ${err.message}`, "error"));
  });
  dom.sessionExportBtn.addEventListener("click", () => {
    exportSessions().catch((err) => pushActivity(`Export failed: ${err.message}`, "error"));
  });
  dom.sessionImportInput.addEventListener("change", () => {
    const file = dom.sessionImportInput.files?.[0];
    if (!file) {
      return;
    }
    importSessions(file).catch((err) => pushActivity(`Import failed: ${err.message}`, "error"));
    dom.sessionImportInput.value = "";
  });

  dom.clearThreadBtn.addEventListener("click", () => {
    clearCurrentThread().catch((err) => pushActivity(`Reset failed: ${err.message}`, "error"));
  });
  dom.clearActivityBtn.addEventListener("click", () => {
    state.activity = [];
    renderActivity();
  });
  dom.refreshHealthBtn.addEventListener("click", checkHealth);
}

function init() {
  state.sessions = loadCachedSessions();
  state.activeSessionId = localStorage.getItem(storageKeys.activeSession) || state.sessions[0].id;
  state.mode = localStorage.getItem(storageKeys.mode) || state.mode;
  state.sidebarOpen = localStorage.getItem(storageKeys.sidebarOpen) === "1";
  ensureActiveSession();
  bindEvents();
  setSidebarOpen(state.sidebarOpen, false);
  setMode(state.mode, false);
  renderAll();
  refreshSessionsFromServerAndRender().catch((err) => pushActivity(`Failed to load sessions: ${err.message}`, "error"));
  checkHealth();
  setInterval(checkHealth, 10000);
}

init();
