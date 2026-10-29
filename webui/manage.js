const api = {
  status: "/api/v2/status",
  ingestFile: "/api/v2/ingest/file",
  ingestDir: "/api/v2/ingest/dir",
  rebuild: "/api/v2/index/rebuild",
  roots: "/api/v2/roots",
  removeRoot: "/api/v2/roots/remove",
  documents: "/api/v2/documents",
  document: (id) => `/api/v2/documents/${encodeURIComponent(id)}`,
  virtualProjects: "/api/v2/virtual-projects",
  virtualProject: (id) => `/api/v2/virtual-projects/${encodeURIComponent(id)}`,
  projectResources: (id) => `/api/v2/virtual-projects/${encodeURIComponent(id)}/resources`,
  projectStatus: (id) => `/api/v2/virtual-projects/${encodeURIComponent(id)}/status`,
  projectSync: (id) => `/api/v2/virtual-projects/${encodeURIComponent(id)}/sync`,
};
const requests = { refresh: 0, roots: 0, documents: 0, document: 0, projects: 0, project: 0 };
let selectedProjectId = "";
const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;");

async function requestJson(url, options = {}) {
  const requestHeaders = new Headers(options.headers || {});
  if (!["GET", "HEAD", "OPTIONS"].includes((options.method || "GET").toUpperCase())) requestHeaders.set("X-KB-CSRF", "1");
  const response = await fetch(url, { ...options, headers: requestHeaders });
  const text = await response.text();
  let body = {};
  try { body = text ? JSON.parse(text) : {}; } catch { throw new Error(text || response.statusText); }
  if (!response.ok || body.ok === false) {
    const diagnostic = Array.isArray(body.diagnostics) && body.diagnostics[0];
    const detail = diagnostic?.detail ? `：${diagnostic.detail}` : "";
    const error = new Error(`${body.message || body.error || response.statusText}${detail}`);
    error.code = body.code || diagnostic?.code;
    error.status = response.status;
    error.payload = body;
    throw error;
  }
  return body;
}
function feedback(message, error = false) { $("feedback").textContent = message; $("feedback").classList.toggle("error-text", error); }
function selectedDocument() { const id = $("documentSelect").value; if (!id) throw new Error("请选择目标文档。"); return id; }

function ensureProjectUi() {
  if ($("projectSelect")) return;
  const grid = document.querySelector(".management-grid");
  const card = document.createElement("section");
  card.className = "card project-card";
  card.innerHTML = `<div class="card-head"><div><span class="section-kicker">Projects</span><h2>虚拟项目</h2></div><button id="refreshProjectsBtn" class="ghost" type="button">刷新项目</button></div><label class="field"><span>当前项目</span><select id="projectSelect" aria-label="要查看和同步的虚拟项目"></select></label><div id="projectStatus" class="status-grid" aria-live="polite"></div><div id="projectResources" class="project-resources" aria-live="polite"></div><div class="row"><button id="syncProjectBtn" type="button">同步当前项目</button></div><p id="projectFeedback" class="hint" aria-live="polite">虚拟项目文件仍保存在原始本地路径；此页面只提供查看和显式同步。</p>`;
  grid.insertBefore(card, grid.children[1] || null);
}

function setProjectBusy(busy) {
  const button = $("syncProjectBtn");
  if (!button) return;
  button.disabled = busy;
  button.setAttribute("aria-busy", busy ? "true" : "false");
  button.textContent = busy ? "同步中…" : "同步当前项目";
}

function renderProjectStatus(status) {
  const values = [["索引", status.indexStatus || "—"], ["同步", status.syncStatus || "—"], ["开始时间", status.lastSyncStartedAt || "—"], ["完成时间", status.lastSyncCompletedAt || "—"]];
  $("projectStatus").innerHTML = values.map(([label, value]) => `<div><span class="meta-label">${escapeHtml(label)}</span><strong class="meta-value">${escapeHtml(value)}</strong></div>`).join("");
  $("projectFeedback").textContent = status.lastSyncDiagnostic || "状态已更新；源文件仍保存在原始路径。";
}

function renderProjectResources(resources) {
  $("projectResources").innerHTML = resources.length
    ? `<strong class="source-title">资源引用</strong>${resources.map((resource) => `<div class="project-resource"><span class="state-pill">${escapeHtml(resource.kind || "RESOURCE")}</span><code>${escapeHtml(resource.sourcePath || "路径不可用")}</code></div>`).join("")}`
    : '<p class="empty-state compact">当前项目没有资源引用。</p>';
}

async function refreshProjectDetails(projectId) {
  if (!projectId) {
    $("projectStatus").innerHTML = '<p class="empty-state compact">暂无虚拟项目。</p>';
    $("projectResources").innerHTML = "";
    return;
  }
  const request = ++requests.project;
  const [project, status, resources] = await Promise.all([requestJson(api.virtualProject(projectId)), requestJson(api.projectStatus(projectId)), requestJson(api.projectResources(projectId))]);
  if (request !== requests.project || selectedProjectId !== projectId) return;
  renderProjectStatus(status);
  renderProjectResources(resources.resources || project.resources || []);
}

async function refreshProjects() {
  const request = ++requests.projects;
  const result = await requestJson(api.virtualProjects);
  if (request !== requests.projects) return;
  const projects = result.projects || [];
  const select = $("projectSelect");
  select.innerHTML = projects.map((project) => `<option value="${escapeHtml(project.id)}">${escapeHtml(project.name || project.id)}</option>`).join("") || '<option value="">没有虚拟项目</option>';
  selectedProjectId = projects.some((project) => project.id === selectedProjectId) ? selectedProjectId : (projects[0]?.id || "");
  select.value = selectedProjectId;
  await refreshProjectDetails(selectedProjectId);
}

async function syncProject() {
  if (!selectedProjectId) throw new Error("请先选择虚拟项目。");
  setProjectBusy(true);
  $("projectFeedback").textContent = "正在同步项目并更新派生索引…";
  try {
    await requestJson(api.projectSync(selectedProjectId), { method: "POST" });
    await refreshProjectDetails(selectedProjectId);
    feedback("虚拟项目同步完成。", false);
  } finally {
    setProjectBusy(false);
  }
}

function renderStatus(status) {
  $("serverState").textContent = status.state || "unknown";
  $("serverState").className = `state-pill state-${escapeHtml(status.state || "unknown")}`;
  const freshness = status.freshness || {};
  const items = [["就绪", status.ready === true ? "yes" : "no"], ["队列", status.queueDepth ?? 0], ["业务检查点", freshness.businessCheckpoint ?? "—"], ["投影检查点", freshness.projectionCheckpoint ?? "—"]];
  $("serviceStatus").innerHTML = items.map(([label, value]) => `<div><span class="meta-label">${escapeHtml(label)}</span><strong class="meta-value">${escapeHtml(value)}</strong></div>`).join("");
}
async function refresh({ roots = true } = {}) { const request = ++requests.refresh; const status = await requestJson(api.status); if (request !== requests.refresh) return; renderStatus(status); if (roots) await refreshRoots(); if (request !== requests.refresh) return; await refreshDocuments(); if (request !== requests.refresh) return; await refreshProjects(); }
async function ingestFile() { const file = $("filePathInput").value.trim(); if (!file) throw new Error("请输入服务器本地文件路径。"); await requestJson(`${api.ingestFile}?file=${encodeURIComponent(file)}`, { method: "POST" }); $("filePathInput").value = ""; await refresh(); feedback(`已导入 ${file}。`); }
async function syncDirectory() { const root = $("rootInput").value.trim(); if (!root) throw new Error("请输入服务器本地目录路径。"); await requestJson(`${api.ingestDir}?root=${encodeURIComponent(root)}`, { method: "POST" }); await refresh(); feedback(`已同步 ${root}。`); }
async function rebuildIndex() { if (!confirm("重建当前服务的检索索引？现有内容不会被删除。")) return; feedback("正在重建索引…"); await requestJson(api.rebuild, { method: "POST" }); await refresh(); feedback("索引已重建。"); }

function rootPath(root) { return typeof root === "string" ? root : root.path || root.root || root.toString(); }
function renderRoots(roots) {
  $("rootList").innerHTML = roots.map((root) => { const path = rootPath(root); const failed = root.state === "purge_failed"; return `<article class="root-item"><code>${escapeHtml(path)}</code><span class="state-pill state-${escapeHtml(root.state || "active")}">${escapeHtml(root.state || "active")}</span>${root.reason ? `<p class="warning-text">${escapeHtml(root.reason)}</p>` : ""}<div class="workspace-actions"><button type="button" class="ghost" data-detach="${escapeHtml(path)}">Detach</button><button type="button" class="ghost danger" data-purge="${escapeHtml(path)}">${failed ? "Retry purge" : "Purge"}</button></div></article>`; }).join("") || '<p class="empty-state compact">No roots.</p>';
  document.querySelectorAll("[data-detach]").forEach((button) => button.addEventListener("click", () => removeRoot(button.dataset.detach, "detach")));
  document.querySelectorAll("[data-purge]").forEach((button) => button.addEventListener("click", () => removeRoot(button.dataset.purge, "purge")));
}
async function refreshRoots() { const request = ++requests.roots; const result = await requestJson(api.roots); if (request === requests.roots) renderRoots(result.roots || []); }
async function refreshDocuments() { const request = ++requests.documents; const result = await requestJson(api.documents); if (request !== requests.documents) return; const documents = result.documents || []; $("documentSelect").innerHTML = documents.map((document) => `<option value="${escapeHtml(document.id)}">${escapeHtml(document.path)}</option>`).join("") || '<option value="">没有已导入文档</option>'; if (documents.length) await inspectDocument(); else $("documentMetadata").textContent = "当前服务没有文档。"; }
function metadataValue(value) { return value && typeof value === "object" ? value.value || "" : ""; }
function metadataSource(value) { return value && typeof value === "object" ? value.source || "unknown" : "unknown"; }
async function inspectDocument() { const documentId = $("documentSelect").value; if (!documentId) return; const request = ++requests.document; const result = await requestJson(api.document(documentId)); if (request !== requests.document || $("documentSelect").value !== documentId) return; const metadata = result.metadata || {}; const title = metadata.title; const summary = metadata.summary; $("metadataTitleInput").value = metadataValue(title); $("metadataSummaryInput").value = metadataValue(summary); $("metadataTagsInput").value = (metadata.tags || []).map(metadataValue).join(", "); $("metadataKeywordsInput").value = (metadata.keywords || []).map(metadataValue).join(", "); $("documentMetadata").innerHTML = `<dl class="metadata-list"><div><dt>标题来源</dt><dd>${escapeHtml(metadataSource(title))}</dd></div><div><dt>摘要来源</dt><dd>${escapeHtml(metadataSource(summary))}</dd></div></dl>`; }
async function saveMetadata(event) { event.preventDefault(); const documentId = selectedDocument(); const body = { title: $("metadataTitleInput").value, summary: $("metadataSummaryInput").value, tags: $("metadataTagsInput").value.split(",").map((value) => value.trim()).filter(Boolean), keywords: $("metadataKeywordsInput").value.split(",").map((value) => value.trim()).filter(Boolean) }; await requestJson(api.document(documentId), { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }); await inspectDocument(); feedback(`文档 ${documentId} 的手工元数据已保存。`); }
async function removeRoot(root, mode) { const isPurge = mode === "purge"; const message = isPurge ? `清理“${root}”的关联数据？此操作无法自动撤销。` : `解除“${root}”的关联？历史数据会被保留。`; if (!confirm(message)) return; if (isPurge && !confirm("请再次确认清理操作。")) return; await requestJson(api.removeRoot, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ root, mode, confirm: isPurge }) }); await refresh(); feedback(isPurge ? `已请求清理 ${root}。` : `已解除关联 ${root}。`); }

$("refreshBtn").addEventListener("click", () => refresh().then(() => feedback("状态已刷新。")).catch((error) => feedback(error.message, true)));
$("ingestFileBtn").addEventListener("click", () => ingestFile().catch((error) => feedback(error.message, true)));
$("syncBtn").addEventListener("click", () => syncDirectory().catch((error) => feedback(error.message, true)));
$("rebuildBtn").addEventListener("click", () => rebuildIndex().catch((error) => feedback(error.message, true)));
$("refreshRootsBtn").addEventListener("click", () => refreshRoots().then(() => feedback("目录已刷新。")).catch((error) => feedback(error.message, true)));
$("refreshDocumentsBtn").addEventListener("click", () => refreshDocuments().then(() => feedback("文档已刷新。")).catch((error) => feedback(error.message, true)));
$("documentSelect").addEventListener("change", () => inspectDocument().catch((error) => feedback(error.message, true)));
$("metadataForm").addEventListener("submit", (event) => saveMetadata(event).catch((error) => feedback(error.message, true)));
ensureProjectUi();
$("refreshProjectsBtn").addEventListener("click", () => refreshProjects().then(() => feedback("虚拟项目已刷新。")).catch((error) => feedback(error.message, true)));
$("projectSelect").addEventListener("change", () => {
  selectedProjectId = $("projectSelect").value;
  refreshProjectDetails(selectedProjectId).catch((error) => feedback(error.message, true));
});
$("syncProjectBtn").addEventListener("click", () => syncProject().catch((error) => {
  $("projectFeedback").textContent = error.message;
  feedback(error.message, true);
}));
refresh().catch((error) => feedback(error.message, true));
