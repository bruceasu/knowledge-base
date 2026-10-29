"use strict";
const $ = (id) => document.getElementById(id);
const base = "/api/v2/personal";
const labels = {pending:"原件已保存，等待处理",ready:"可查询",partial:"部分可查询",failed:"处理失败",unsupported:"原件已保存，暂不支持提取正文"};
let revision, nextCursor, documentId, refreshSequence = 0, pollTimer;
let pendingNote = null;
try { pendingNote = JSON.parse(sessionStorage.getItem("kb.pendingNote") || "null"); } catch { sessionStorage.removeItem("kb.pendingNote"); }
async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.method && options.method !== "GET") headers.set("X-KB-CSRF", "1");
  const response = await fetch(base + path, {...options, headers, redirect:"error"});
  let envelope;
  try { envelope = await response.json(); } catch { throw new Error("连接或登录状态异常，请刷新页面重新登录。"); }
  if (!response.ok) { const error = new Error(`${envelope.error?.message || "请求失败"}（${envelope.error?.code || response.status}）`); error.status = response.status; throw error; }
  return envelope.data;
}
function say(message) { $("notice").textContent = message; }
function link(id, title) { const a = document.createElement("a"); a.href = `/webui/documents/${encodeURIComponent(id)}`; a.textContent = title; return a; }
async function refresh() {
  const sequence = ++refreshSequence;
  const [imports, recent] = await Promise.all([request("/imports"), request("/documents")]);
  if (sequence !== refreshSequence) return;
  $("imports").replaceChildren();
  for (const item of imports.items) {
    const li = document.createElement("li"), text = document.createElement("span"), state = document.createElement("small");
    text.textContent = item.filename; state.textContent = (labels[item.state] || item.state) + ((item.diagnostics || []).length ? "；" + item.diagnostics.join("；") : ""); li.append(text, state);
    if (["ready","partial"].includes(item.state)) li.append(link(item.documentId, "阅读"));
    if (["failed","unsupported","partial"].includes(item.state)) {
      const retry = document.createElement("button"); retry.textContent = "重新处理";
      retry.addEventListener("click", async () => { retry.disabled = true; try { await request(`/imports/${encodeURIComponent(item.id)}/retry`, {method:"POST",headers:{"Content-Type":"application/json"},body:"{}"}); await refresh(); } catch(error) { say(error.message); retry.disabled=false; } }); li.append(retry);
    }
    const original = document.createElement("a"); original.href=`${base}/documents/${encodeURIComponent(item.documentId)}/original`; original.textContent="原件"; original.download=""; li.append(original);
    $("imports").append(li);
  }
  if (!imports.items.length) $("imports").textContent="还没有上传资料。";
  $("recent").replaceChildren();
  for (const item of recent.items) { const li=document.createElement("li"), detail=document.createElement("small"); detail.textContent=`${item.format} · ${new Date(item.updatedAt).toLocaleString()}`; li.append(link(item.documentId,item.title),detail); $("recent").append(li); }
  if (!recent.items.length) $("recent").textContent="保存一份资料或笔记后，会显示在这里。";
}
$("refresh").addEventListener("click", () => refresh().catch(error => say(error.message)));
$("uploadForm").addEventListener("submit", async event => {
  event.preventDefault(); const files=Array.from($("files").files);
  if (files.some(file=>file.size>100000000)) { say("单个文件不能超过 100 MB。"); return; }
  $("uploadButton").disabled=true;
  try {
    for (let i=0;i<files.length;i++) { $("uploadStatus").textContent=`正在保存 ${i+1}/${files.length}：${files[i].name}`; const data=new FormData(); data.append("file",files[i]); await request("/uploads",{method:"POST",body:data}); }
    $("uploadStatus").textContent="原件已保存。正文处理进度见下方列表。"; $("files").value=""; await refresh();
  } catch(error) { $("uploadStatus").textContent=`${error.message}。已完成的上传不会撤销；连接中断时先刷新确认状态。`; }
  finally { $("uploadButton").disabled=false; }
});
function lockNote(locked) { for (const id of ["title","content","tags","noteButton"]) $(id).disabled=locked; }
function rememberNote(value) { pendingNote=value; if(value) sessionStorage.setItem("kb.pendingNote",JSON.stringify(value)); else sessionStorage.removeItem("kb.pendingNote"); }
async function reconcileNote() {
  if (!pendingNote) return;
  lockNote(true);
  try {
    let result;
    try { result=await request(`/note-creations/${pendingNote.key}`); }
    catch(error) {
      if(error.status!==404) throw error;
      try { result=await request("/notes",{method:"POST",headers:{"Content-Type":"application/json","Idempotency-Key":pendingNote.key},body:JSON.stringify(pendingNote.body)}); }
      catch(rejected) {
        if([400,404,413,415,422].includes(rejected.status)) {
          rememberNote(null); clearTimeout(pollTimer); lockNote(false);
          $("noteStatus").textContent=`请求未被接受：${rejected.message}。草稿已保留，请修改后重新提交。`;
          return;
        }
        throw rejected;
      }
    }
    if(result.state==="pending") { $("noteStatus").textContent="已接收，正在保存…"; pollTimer=setTimeout(reconcileNote,3000); return; }
    rememberNote(null); lockNote(false);
    if(result.state==="succeeded") { $("noteStatus").replaceChildren(document.createTextNode(result.indexState==="ready"?"已保存，可查询。 ":"已保存，索引尚未就绪。 "),link(result.documentId,"阅读笔记")); $("noteForm").reset(); await refresh(); }
    else $("noteStatus").textContent="保存失败，可修改后重新提交。";
  } catch(error) { $("noteStatus").textContent=`保存结果待确认：${error.message}。保留原请求，恢复连接后继续核对。`; pollTimer=setTimeout(reconcileNote,10000); }
}
$("noteForm").addEventListener("submit", async event => {
  event.preventDefault(); if(pendingNote) return;
  const body={title:$("title").value.trim(),content:$("content").value,format:"markdown",tags:$("tags").value.split(/[,，]/).map(x=>x.trim()).filter(Boolean)};
  if(Array.from(body.title).length>200 || body.tags.length>20 || body.tags.some(tag=>Array.from(tag).length>100)) { say("标题最多 200 字符；标签最多 20 个，每个最多 100 字符。"); return; }
  if(!body.title || !body.content || Array.from(body.content).length>100000 || new TextEncoder().encode(JSON.stringify(body)).length>262144) { say("请填写标题与正文；正文最多 100,000 字符，请求不超过 256 KiB。"); return; }
  rememberNote({key:crypto.randomUUID(),body}); await reconcileNote();
});
async function readMore() {
  $("more").disabled=true;
  try {
    const params=new URLSearchParams(); if(revision) params.set("revision",revision); if(nextCursor) params.set("cursor",nextCursor);
    const item=await request(`/documents/${encodeURIComponent(documentId)}?${params}`);
    $("readingTitle").textContent=item.title; $("readingContent").textContent+=item.content; revision=item.revision; nextCursor=item.nextCursor;
    $("more").hidden=!nextCursor; $("download").hidden=item.source.kind!=="import"; $("download").href=`${base}/documents/${encodeURIComponent(documentId)}/original`;
  } catch(error) { say(error.message); } finally { $("more").disabled=false; }
}
$("more").addEventListener("click",readMore);
const match=location.pathname.match(/^\/webui\/documents\/([A-Za-z0-9_-]+)$/);
if(match) { documentId=match[1]; $("reader").hidden=false; readMore(); }
if(pendingNote) { $("title").value=pendingNote.body.title; $("content").value=pendingNote.body.content; $("tags").value=pendingNote.body.tags.join(", "); reconcileNote(); }
refresh().catch(error=>say(error.message));
window.addEventListener("pagehide",()=>clearTimeout(pollTimer));
