const state = {
  currentPanel: "home",
  currentFolder: "",
  notesTab: "notes",
  token: localStorage.getItem("lanxin_token") || "",
  refreshToken: localStorage.getItem("lanxin_refresh_token") || "",
  pending: new Set(),
  user: null,
  editingNoteId: null,
  createMode: "write",
  selectedImageFile: null,
  imageProcessing: false,
  imageRequestId: 0,
  extractedDocData: null,
  cameraNoteId: null,
  makeupContent: null,
  makeupNoteIds: [],
  makeupDocumentIds: []
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

async function parseResponseError(response, fallback) {
  const body = await response.json().catch(() => ({}));
  const message = body.error || body.message || fallback || `请求失败：${response.status}`;
  const error = new Error(message);
  error.status = response.status;
  error.code = body.code;
  error.aiStatus = body.aiStatus;
  return error;
}

function clearAuthToken() {
  localStorage.removeItem("lanxin_token");
  localStorage.removeItem("lanxin_refresh_token");
  state.token = "";
  state.refreshToken = "";
}

async function refreshAccessToken() {
  if (!state.refreshToken) return false;
  const response = await fetch("/api/v1/user/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: state.refreshToken })
  });
  if (!response.ok) { clearAuthToken(); return false; }
  const data = await response.json();
  persistAuth(data);
  return true;
}

function persistAuth(data) {
  state.token = data.token;
  state.refreshToken = data.refreshToken || "";
  localStorage.setItem("lanxin_token", state.token);
  if (state.refreshToken) localStorage.setItem("lanxin_refresh_token", state.refreshToken);
}

async function api(path, options = {}, retry = true) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  const response = await fetch(path, { headers, ...options });
  if (response.status === 401) {
    if (retry && await refreshAccessToken()) return api(path, options, false);
    clearAuthToken();
    showAuth();
    throw new Error("登录已过期");
  }
  if (!response.ok) throw await parseResponseError(response, `API ${path} failed: ${response.status}`);
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function withButtonLoading(button, loadingText, task) {
  if (!button || button.disabled) return;
  const oldHtml = button.innerHTML;
  button.disabled = true;
  button.classList.add("is-loading");
  button.textContent = loadingText;
  try { return await task(); } finally {
    button.innerHTML = oldHtml;
    button.classList.remove("is-loading");
    button.disabled = false;
  }
}

function showToast(message) {
  let toast = $("#appToast");
  if (!toast) {
    toast = document.createElement("div");
    toast.id = "appToast";
    toast.className = "app-toast";
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove("show"), 2200);
}

// ── Ripple ──────────────────────────────────────────────

function addRipple(e) {
  const btn = e.target.closest("button");
  if (!btn || btn.classList.contains("ripple-none")) return;
  const rect = btn.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height);
  const ripple = document.createElement("span");
  ripple.className = "ripple";
  ripple.style.width = ripple.style.height = size + "px";
  ripple.style.left = (e.clientX - rect.left - size / 2) + "px";
  ripple.style.top = (e.clientY - rect.top - size / 2) + "px";
  btn.appendChild(ripple);
  ripple.addEventListener("animationend", () => ripple.remove());
}

function animateCounter(el, target, suffix) {
  const duration = 700;
  const startTime = performance.now();
  function update(now) {
    const elapsed = now - startTime;
    const progress = Math.min(elapsed / duration, 1);
    const eased = 1 - Math.pow(1 - progress, 3);
    const val = Math.round(target * eased);
    el.textContent = suffix ? val + suffix : val;
    if (progress < 1) requestAnimationFrame(update);
  }
  requestAnimationFrame(update);
}

// ── Typing indicator ────────────────────────────────────

function showTyping() {
  hideTyping();
  const dots = document.createElement("div");
  dots.className = "typing-dots";
  dots.id = "typingIndicator";
  dots.innerHTML = "<span></span><span></span><span></span>";
  $("#chatBox").appendChild(dots);
  $("#chatBox").scrollTop = $("#chatBox").scrollHeight;
}

function hideTyping() {
  const dots = $("#typingIndicator");
  if (dots) dots.remove();
}

// ── Utility ─────────────────────────────────────────────

function escapeHtml(text) {
  return String(text ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function renderMarkdown(text) {
  if (!text) return "";

  // Protect LaTeX math blocks before markdown processing
  const mathBlocks = [];
  const MATH_PLACEHOLDER = "%%MATH_BLOCK_";

  // $$...$$ display math (multi-line aware)
  let html = text.replace(/\$\$([\s\S]*?)\$\$/g, (_, formula) => {
    const idx = mathBlocks.length;
    mathBlocks.push({ type: "display", formula: formula.trim() });
    return MATH_PLACEHOLDER + idx + "%%";
  });

  // $...$ inline math (single line)
  html = html.replace(/\$(.+?)\$/g, (_, formula) => {
    const idx = mathBlocks.length;
    mathBlocks.push({ type: "inline", formula: formula.trim() });
    return MATH_PLACEHOLDER + idx + "%%";
  });

  // Escape HTML
  html = escapeHtml(html);

  // Headings
  html = html.replace(/^### (.+)$/gm, "<h3>$1</h3>");
  html = html.replace(/^## (.+)$/gm, "<h2>$1</h2>");
  html = html.replace(/^# (.+)$/gm, "<h1>$1</h1>");

  // Bold
  html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");

  // Horizontal rule
  html = html.replace(/^---$/gm, "<hr>");

  // Unordered lists
  html = html.replace(/^- (.+)$/gm, "<li>$1</li>");
  html = html.replace(/(<li>.*<\/li>)/s, "<ul>$1</ul>");

  // Ordered lists
  html = html.replace(/^\d+\.\s+(.+)$/gm, "<li>$1</li>");

  // Paragraphs
  html = html.replace(/\n\n+/g, "</p><p>");
  html = "<p>" + html + "</p>";
  html = html.replace(/<p><\/p>/g, "");

  // Line breaks
  html = html.replace(/\n/g, "<br>");

  // Restore math blocks and render with KaTeX
  for (let i = 0; i < mathBlocks.length; i++) {
    const mb = mathBlocks[i];
    const placeholder = MATH_PLACEHOLDER + i + "%%";
    try {
      const rendered = katex.renderToString(mb.formula, {
        displayMode: mb.type === "display",
        throwOnError: false,
        trust: true
      });
      html = html.replace(placeholder, rendered);
    } catch (e) {
      // Fallback: show raw formula
      const wrapper = mb.type === "display" ? "\\[" + mb.formula + "\\]" : "\\(" + mb.formula + "\\)";
      html = html.replace(placeholder, escapeHtml(wrapper));
    }
  }

  return html;
}

function priorityLabel(p) {
  return { high: "高优先级", medium: "中优先级", low: "低优先级" }[p] || "中优先级";
}

// ── Auth ──────────────────────────────────────────────

function showAuth() {
  $("#authOverlay").style.display = "grid";
  $("#appShell").style.display = "none";
  $("#floatingBubble").style.display = "none";
  $("#bubbleTooltip").style.display = "none";
}

function showApp(user) {
  state.user = user;
  $("#authOverlay").style.display = "none";
  $("#appShell").style.display = "";
  $("#floatingBubble").style.display = "";
  $("#bubbleTooltip").style.display = "";
  $("#userBadge").textContent = user.name;
}

async function handleLogin(e) {
  e.preventDefault();
  const submit = e.submitter || $("#loginForm .auth-submit");
  const username = $("#loginUsername").value.trim();
  const password = $("#loginPassword").value;
  await withButtonLoading(submit, "登录中...", async () => {
    try {
      const data = await fetch("/api/v1/user/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      }).then(async r => { if (!r.ok) throw await parseResponseError(r, "登录失败"); return r.json(); });
      persistAuth(data);
      showApp(data);
      await bootApp();
    } catch (err) { $("#authError").textContent = err.message; }
  });
}

async function handleRegister(e) {
  e.preventDefault();
  const submit = e.submitter || $("#registerForm .auth-submit");
  const username = $("#regUsername").value.trim();
  const password = $("#regPassword").value;
  const name = $("#regName").value.trim();
  const school = $("#regSchool").value.trim();
  const major = $("#regMajor").value.trim();
  const grade = $("#regGrade").value.trim();
  if (!username || !password) { $("#authError").textContent = "用户名和密码不能为空"; return; }
  await withButtonLoading(submit, "注册中...", async () => {
    try {
      const data = await fetch("/api/v1/user/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password, name, school, major, grade })
      }).then(async r => { if (!r.ok) throw await parseResponseError(r, "注册失败"); return r.json(); });
      persistAuth(data);
      showApp(data);
      await bootApp();
    } catch (err) { $("#authError").textContent = err.message; }
  });
}

async function handleLogout() {
  try { await api("/api/v1/user/logout", { method: "POST" }); } catch (_) {}
  clearAuthToken();
  state.user = null;
  showAuth();
}

// ── Navigation ────────────────────────────────────────

function switchNotesTab(tab) {
  state.notesTab = tab;
  $$(".notes-subtab").forEach(t => t.classList.toggle("active", t.dataset.notesTab === tab));
  $("#notesSubpanel").style.display = tab === "notes" ? "" : "none";
  $("#knowledgeSubpanel").style.display = tab === "knowledge" ? "" : "none";
  if (tab === "notes") { loadFolders(); loadNotes(); }
  if (tab === "knowledge") loadKnowledgeBase();
}

function navTo(panel) {
  state.currentPanel = panel;
  $$(".tab-panel").forEach(n => n.classList.toggle("active", n.dataset.panel === panel));
  $$(".bottom-nav button").forEach(n => n.classList.toggle("active", n.dataset.nav === panel));
  if (panel === "notes") {
    if (state.notesTab === "notes") { loadFolders(); loadNotes(); }
    else loadKnowledgeBase();
  }
  if (panel === "tasks") loadTasks();
  if (panel === "report") loadReport();
}

// ── Floating Bubble ───────────────────────────────────

const bubble = () => $("#floatingBubble");
const tooltip = () => $("#bubbleTooltip");
let bubblePos = { x: 0, y: 0 };
let isDragging = false;
let dragStart = { x: 0, y: 0 };
let hasMoved = false;

function initBubble() {
  const bb = bubble();
  const stored = localStorage.getItem("lanxin_bubble_pos");
  if (stored) {
    try {
      const p = JSON.parse(stored);
      bubblePos.x = p.x; bubblePos.y = p.y;
    } catch (_) {}
  }
  if (!bubblePos.x && !bubblePos.y) {
    bubblePos.x = window.innerWidth - 76;
    bubblePos.y = window.innerHeight * 0.55;
  }
  bb.style.left = bubblePos.x + "px";
  bb.style.top = bubblePos.y + "px";
  updateBubbleSnap();

  bb.addEventListener("pointerdown", onBubbleDown);
  bb.addEventListener("click", onBubbleClick);
  window.addEventListener("resize", () => {
    bubblePos.x = Math.min(bubblePos.x, window.innerWidth - 60);
    bubblePos.y = Math.min(bubblePos.y, window.innerHeight - 60);
    bb.style.left = bubblePos.x + "px";
    bb.style.top = bubblePos.y + "px";
    updateBubbleSnap();
  });

  // Show tooltip after a short delay
  setTimeout(() => updateTooltip(), 800);
  setTimeout(() => {
    const tt = tooltip();
    if (tt) tt.classList.add("show");
  }, 1500);
  setTimeout(() => {
    const tt = tooltip();
    if (tt) tt.classList.remove("show");
  }, 7000);
}

function onBubbleDown(e) {
  if (e.target.closest("button")) return;
  isDragging = true;
  hasMoved = false;
  dragStart.x = e.clientX - bubblePos.x;
  dragStart.y = e.clientY - bubblePos.y;
  bubble().setPointerCapture(e.pointerId);
  bubble().classList.add("dragging");
  const tt = tooltip();
  if (tt) tt.classList.remove("show");
  bubble().addEventListener("pointermove", onBubbleMove);
  bubble().addEventListener("pointerup", onBubbleUp);
}

function onBubbleMove(e) {
  if (!isDragging) return;
  const dx = Math.abs(e.clientX - dragStart.x - bubblePos.x);
  const dy = Math.abs(e.clientY - dragStart.y - bubblePos.y);
  if (dx > 3 || dy > 3) hasMoved = true;
  bubblePos.x = e.clientX - dragStart.x;
  bubblePos.y = e.clientY - dragStart.y;
  bubblePos.x = Math.max(-20, Math.min(window.innerWidth - 36, bubblePos.x));
  bubblePos.y = Math.max(10, Math.min(window.innerHeight - 80, bubblePos.y));
  bubble().style.left = bubblePos.x + "px";
  bubble().style.top = bubblePos.y + "px";
  bubble().classList.remove("snap-left", "snap-right");
}

function onBubbleUp() {
  isDragging = false;
  bubble().classList.remove("dragging");
  updateBubbleSnap();
  bubble().removeEventListener("pointermove", onBubbleMove);
  bubble().removeEventListener("pointerup", onBubbleUp);
  localStorage.setItem("lanxin_bubble_pos", JSON.stringify(bubblePos));
}

function onBubbleClick(e) {
  if (hasMoved) { hasMoved = false; return; }
  openChatDrawer();
}

function updateBubbleSnap() {
  const bb = bubble();
  const cx = bubblePos.x + 28;
  bb.classList.remove("snap-left", "snap-right");
  if (cx < window.innerWidth / 2) {
    bubblePos.x = -12;
    bb.classList.add("snap-right");
  } else {
    bubblePos.x = window.innerWidth - 44;
    bb.classList.add("snap-left");
  }
  bb.style.left = bubblePos.x + "px";
  updateTooltip();
}

function updateTooltip() {
  const tt = tooltip();
  if (!tt) return;
  const cx = bubblePos.x + 28;
  if (cx < window.innerWidth / 2) {
    tt.style.left = (bubblePos.x + 56) + "px";
    tt.style.top = (bubblePos.y + 8) + "px";
    tt.classList.remove("point-left");
    tt.classList.add("point-right");
  } else {
    tt.style.left = (bubblePos.x - 10) + "px";
    tt.style.top = (bubblePos.y + 8) + "px";
    tt.classList.remove("point-right");
    tt.classList.add("point-left");
    tt.style.transform = "translateX(-100%)";
  }
}

// ── Chat Drawer ───────────────────────────────────────

function openChatDrawer() {
  $("#chatDrawerOverlay").classList.add("open");
  $("#chatDrawer").classList.add("open");
  switchChatTab("chat");
  if ($("#chatBox").children.length === 0) {
    appendMessage("assistant", "我是小蓝。可以把课堂内容、作业要求或复习目标发给我。");
  }
  loadMakeupSources();
}

function switchChatTab(tab) {
  $$(".chat-drawer-tab").forEach(t => {
    t.classList.toggle("active", t.dataset.chatTab === tab);
  });
  $("#chatTabPanel").classList.toggle("active", tab === "chat");
  $("#makeupTabPanel").classList.toggle("active", tab === "makeup");
  if (tab === "chat") {
    setTimeout(() => $("#chatInput").focus(), 100);
  }
}

function closeChatDrawer() {
  $("#chatDrawerOverlay").classList.remove("open");
  $("#chatDrawer").classList.remove("open");
}

function appendMessage(role, content) {
  const node = document.createElement("div");
  node.className = `message ${role}`;
  node.innerHTML = role === "assistant" ? renderMarkdown(content) : escapeHtml(content).replace(/\n/g, "<br>");
  $("#chatBox").appendChild(node);
  $("#chatBox").scrollTop = $("#chatBox").scrollHeight;
}

async function sendRagChat() {
  if (state.pending.has("chat")) return;
  const input = $("#chatInput");
  const button = $("#sendChat");
  const message = input.value.trim();
  if (!message) return;
  input.value = "";
  input.disabled = true;
  button.disabled = true;
  button.classList.add("is-loading");
  state.pending.add("chat");

  appendMessage("user", message);
  showTyping();

  const useRag = $("#ragToggle").checked;
  const endpoint = useRag ? "/api/v1/rag/chat/stream" : "/api/v1/ai/chat/stream";

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(state.token ? { "Authorization": "Bearer " + state.token } : {}) },
      body: JSON.stringify({ message })
    });
    if (response.status === 401) {
      if (await refreshAccessToken()) { showToast("登录已刷新，请重新发送"); return; }
      showAuth(); return;
    }
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let msg = text;
      try { const json = JSON.parse(text); msg = json.error || msg; } catch (_) {}
      throw new Error(msg || `请求失败 (${response.status})`);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    hideTyping();
    const msgDiv = document.createElement("div");
    msgDiv.className = "message assistant";
    $("#chatBox").appendChild(msgDiv);
    let fullText = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      fullText += decoder.decode(value, { stream: true });
      msgDiv.innerHTML = escapeHtml(fullText);
      $("#chatBox").scrollTop = $("#chatBox").scrollHeight;
    }
    msgDiv.innerHTML = renderMarkdown(fullText);
    $("#chatBox").scrollTop = $("#chatBox").scrollHeight;
  } catch (err) {
    hideTyping();
    appendMessage("assistant", "抱歉，回复失败: " + err.message);
  } finally {
    hideTyping();
    state.pending.delete("chat");
    input.disabled = false;
    button.disabled = false;
    button.classList.remove("is-loading");
    input.focus();
  }
}

async function loadMakeupSources() {
  try {
    const [notes, docs] = await Promise.all([
      api("/api/v1/notes"),
      api("/api/v1/rag/documents")
    ]);
    const container = $("#makeupSources");
    if (notes.length === 0 && docs.length === 0) {
      container.innerHTML = '<p style="font-size:12px;color:var(--muted);">暂无笔记或文档素材</p>';
      return;
    }
    let html = "";
    notes.forEach(note => {
      html += `<label class="makeup-source-item">
        <input type="checkbox" class="makeup-checkbox" data-type="note" data-id="${note.id}">
        <span>${escapeHtml(note.title)} <span class="tag">笔记</span></span>
      </label>`;
    });
    docs.filter(d => d.status === "READY").forEach(doc => {
      html += `<label class="makeup-source-item">
        <input type="checkbox" class="makeup-checkbox" data-type="document" data-id="${doc.id}">
        <span>${escapeHtml(doc.title)} <span class="tag">${doc.fileType}</span></span>
      </label>`;
    });
    container.innerHTML = html || '<p style="font-size:12px;color:var(--muted);">暂无可用素材</p>';
  } catch (err) { console.error("Load makeup sources failed:", err); }
}

async function loadMakeup() {
  const button = $("#makeupBtn");
  if (state.pending.has("makeup")) return;
  const box = $("#makeupBox");
  const noteIds = []; const documentIds = [];
  $$(".makeup-checkbox:checked").forEach(cb => {
    const id = parseInt(cb.dataset.id);
    if (cb.dataset.type === "note") noteIds.push(id);
    else documentIds.push(id);
  });
  box.innerHTML = "<strong>生成中...</strong>";
  await withButtonLoading(button, "生成中...", async () => {
    state.pending.add("makeup");
    try {
      const response = await fetch("/api/v1/ai/makeup/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json", ...(state.token ? { "Authorization": "Bearer " + state.token } : {}) },
        body: JSON.stringify({ noteIds, documentIds })
      });
      if (response.status === 401) { showAuth(); return; }
      if (!response.ok) throw new Error("Stream failed: " + response.status);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let fullText = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        fullText += decoder.decode(value, { stream: true });
        box.innerHTML = renderMarkdown(fullText);
      }
      // Store for follow-up chat and save
      state.makeupContent = fullText;
      state.makeupNoteIds = noteIds;
      state.makeupDocumentIds = documentIds;
      $("#makeupActions").style.display = "flex";
      $("#makeupChatSection").style.display = "flex";
      $("#makeupChatBox").innerHTML = "";
    } catch (err) { box.innerHTML = "<strong>补课包生成失败</strong><br>" + err.message; }
    finally { state.pending.delete("makeup"); }
  });
}

// ── Makeup follow-up chat ─────────────────────────────

function appendMakeupChatMessage(role, content) {
  const node = document.createElement("div");
  node.className = "message " + role;
  node.innerHTML = role === "assistant" ? renderMarkdown(content) : escapeHtml(content).replace(/\n/g, "<br>");
  $("#makeupChatBox").appendChild(node);
  $("#makeupChatBox").scrollTop = $("#makeupChatBox").scrollHeight;
}

function showMakeupChatTyping() {
  hideMakeupChatTyping();
  const dots = document.createElement("div");
  dots.className = "typing-dots";
  dots.id = "makeupTypingIndicator";
  dots.innerHTML = "<span></span><span></span><span></span>";
  $("#makeupChatBox").appendChild(dots);
  $("#makeupChatBox").scrollTop = $("#makeupChatBox").scrollHeight;
}

function hideMakeupChatTyping() {
  const dots = $("#makeupTypingIndicator");
  if (dots) dots.remove();
}

async function sendMakeupChat() {
  if (state.pending.has("makeup-chat")) return;
  const input = $("#makeupChatInput");
  const button = $("#sendMakeupChat");
  const message = input.value.trim();
  if (!message || !state.makeupContent) return;
  input.value = "";
  input.disabled = true;
  button.disabled = true;
  button.classList.add("is-loading");
  state.pending.add("makeup-chat");

  appendMakeupChatMessage("user", message);
  showMakeupChatTyping();

  try {
    const response = await fetch("/api/v1/ai/makeup/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(state.token ? { "Authorization": "Bearer " + state.token } : {}) },
      body: JSON.stringify({ makeupContent: state.makeupContent, message })
    });
    if (response.status === 401) {
      if (await refreshAccessToken()) { showToast("登录已刷新，请重新发送"); return; }
      showAuth(); return;
    }
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let msg = text;
      try { const json = JSON.parse(text); msg = json.error || msg; } catch (_) {}
      throw new Error(msg || `请求失败 (${response.status})`);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    hideMakeupChatTyping();
    const msgDiv = document.createElement("div");
    msgDiv.className = "message assistant";
    $("#makeupChatBox").appendChild(msgDiv);
    let fullText = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      fullText += decoder.decode(value, { stream: true });
      msgDiv.innerHTML = escapeHtml(fullText);
      $("#makeupChatBox").scrollTop = $("#makeupChatBox").scrollHeight;
    }
    msgDiv.innerHTML = renderMarkdown(fullText);
  } catch (err) {
    hideMakeupChatTyping();
    appendMakeupChatMessage("assistant", "抱歉，回复失败: " + err.message);
  } finally {
    state.pending.delete("makeup-chat");
    input.disabled = false;
    button.disabled = false;
    button.classList.remove("is-loading");
    input.focus();
  }
}

function openSaveMakeupModal() {
  if (!state.makeupContent) {
    showToast("请先生成补课包");
    return;
  }
  // Pre-fill defaults
  $("#saveMakeupTitle").value = "补课包 - " + new Date().toLocaleDateString();
  $("#saveMakeupFolder").value = "";
  // Clear existing tags
  $$("#saveMakeupTagsWrap .tag-chip").forEach(c => c.remove());
  // Add default tags
  addTagChip("saveMakeupTagsWrap", "补课");
  addTagChip("saveMakeupTagsWrap", "复习");
  // Render preview
  $("#saveMakeupPreview").innerHTML = renderMarkdown(state.makeupContent);
  // Show modal
  $("#saveMakeupNoteOverlay").classList.add("open");
}

function closeSaveMakeupModal() {
  $("#saveMakeupNoteOverlay").classList.remove("open");
}

async function confirmSaveMakeupNote() {
  const title = $("#saveMakeupTitle").value.trim();
  if (!title) { showToast("请输入标题"); return; }
  const folderPath = $("#saveMakeupFolder").value.trim();
  const tags = getTags("saveMakeupTagsWrap");

  const btn = $("#confirmSaveMakeupNote");
  await withButtonLoading(btn, "保存中...", async () => {
    try {
      await api("/api/v1/notes", {
        method: "POST",
        body: JSON.stringify({
          title: title,
          course: "",
          folderPath: folderPath || null,
          rawText: state.makeupContent,
          summary: state.makeupContent ? state.makeupContent.substring(0, 500) : "",
          tags,
          offlineCreated: false
        })
      });
      showToast("补课包已保存为笔记");
      closeSaveMakeupModal();
      await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

// ── Content Preview Toggle ─────────────────────────────

function switchContentPreview(targetId, mode) {
  const rendered = $("#" + targetId + "Rendered");
  const textarea = $("#" + targetId);
  const toggles = $$("[data-preview-target='" + targetId + "']");

  toggles.forEach(t => t.classList.toggle("active", t.dataset.mode === mode));

  if (mode === "rendered") {
    rendered.classList.add("active");
    rendered.style.display = "block";
    textarea.style.display = "none";
    rendered.innerHTML = renderMarkdown(textarea.value);
  } else {
    rendered.classList.remove("active");
    rendered.style.display = "none";
    textarea.style.display = "";
  }
}

// ── Notes Panel ───────────────────────────────────────

async function loadFolders() {
  try {
    const folders = await api("/api/v1/notes/folders");
    const tree = $("#folderTree");
    let html = `<div class="folder-tree-item active" data-folder="" style="padding-left:8px;">
      <span class="folder-icon">📋</span>全部笔记
    </div>`;

    function renderNode(node, depth) {
      const hasChildren = node.children && node.children.length > 0;
      const childCount = hasChildren ? node.children.length : 0;
      const autoCollapse = childCount > 5;
      const toggleIcon = hasChildren ? (autoCollapse ? '▶' : '▼') : '';
      const pl = 8 + depth * 18;

      html += `<div class="folder-tree-item${hasChildren ? ' has-children' : ''}" data-folder="${escapeHtml(node.path)}" data-depth="${depth}" style="padding-left:${pl}px;">
        <span class="folder-toggle">${toggleIcon}</span>
        <span class="folder-icon">${hasChildren ? '📁' : '📄'}</span>${escapeHtml(node.name)}
      </div>`;

      if (hasChildren) {
        html += `<div class="folder-children${autoCollapse ? ' collapsed' : ''}" data-parent="${escapeHtml(node.path)}">`;
        node.children.forEach(c => renderNode(c, depth + 1));
        html += `</div>`;
      }
    }

    folders.forEach(f => renderNode(f, 0));
    tree.innerHTML = html;

    // Expand ancestors of current folder
    if (state.currentFolder) {
      const parts = state.currentFolder.split('/');
      let ancestor = '';
      for (let i = 0; i < parts.length - 1; i++) {
        ancestor = ancestor ? ancestor + '/' + parts[i] : parts[i];
        const children = tree.querySelector(`.folder-children[data-parent="${escapeHtml(ancestor)}"]`);
        if (children) {
          children.classList.remove("collapsed");
          const item = tree.querySelector(`.folder-tree-item[data-folder="${escapeHtml(ancestor)}"]`);
          if (item) { const t = item.querySelector(".folder-toggle"); if (t) t.textContent = '▼'; }
        }
      }
    }

    // Bind events
    $$("#folderTree .folder-tree-item").forEach(item => {
      if (item.dataset.folder === state.currentFolder) item.classList.add("active");

      const toggle = item.querySelector(".folder-toggle");
      if (toggle) {
        toggle.addEventListener("click", (e) => {
          e.stopPropagation();
          const children = tree.querySelector(`.folder-children[data-parent="${escapeHtml(item.dataset.folder)}"]`);
          if (children) {
            const collapsed = !children.classList.contains("collapsed");
            children.classList.toggle("collapsed");
            toggle.textContent = collapsed ? '▶' : '▼';
          }
        });
      }

      item.addEventListener("click", (e) => {
        if (e.target.closest(".folder-toggle")) return;
        state.currentFolder = item.dataset.folder;
        $$("#folderTree .folder-tree-item").forEach(i => i.classList.remove("active"));
        item.classList.add("active");
        loadNotes();
      });
    });
  } catch (err) { console.error("Load folders failed:", err); }
}

async function loadNotes() {
  try {
    let url = "/api/v1/notes";
    const keyword = ($("#noteSearch")?.value || "").trim();
    const params = [];
    if (keyword) params.push("keyword=" + encodeURIComponent(keyword));
    if (state.currentFolder) params.push("folder=" + encodeURIComponent(state.currentFolder));
    if (params.length) url += "?" + params.join("&");

    const notes = await api(url);
    $("#noteList").innerHTML = notes.length ? notes.map(n => noteCard(n)).join("")
      : empty("暂无笔记，点击「+ 新建」创建第一条笔记");

    // Bind note card events
    $$(".note-card").forEach(card => {
      card.addEventListener("click", (e) => {
        // Don't open editor if clicking action buttons
        if (e.target.closest(".note-action-btn")) return;
        openNoteEditor(parseInt(card.dataset.noteId));
      });
    });
    $$(".note-action-btn[data-action='edit']").forEach(btn => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        openNoteEditor(parseInt(btn.dataset.noteId));
      });
    });
    $$(".note-action-btn[data-action='delete']").forEach(btn => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        deleteNote(parseInt(btn.dataset.noteId), btn);
      });
    });
  } catch (err) { console.error("Load notes failed:", err); }
}

function noteCard(note) {
  const folder = note.folderPath ? `<span class="note-folder">${escapeHtml(note.folderPath)}</span>` : "";
  const tags = (note.tags || []).map(t => `<span class="tag">${escapeHtml(t)}</span>`).join("");
  const summary = escapeHtml((note.summary || note.rawText || "").substring(0, 120));
  return `
    <div class="note-card" data-note-id="${note.id}">
      <div class="note-actions">
        <button class="note-action-btn" data-action="edit" data-note-id="${note.id}" title="编辑">✎</button>
        <button class="note-action-btn danger" data-action="delete" data-note-id="${note.id}" title="删除">✕</button>
      </div>
      <h3>${escapeHtml(note.title)}</h3>
      <p style="font-size:12px;color:var(--ink-secondary);margin:4px 0;">${summary}${summary.length >= 120 ? "..." : ""}</p>
      <div class="note-meta">
        ${folder}
        ${tags}
        <span>${escapeHtml(note.course || "")}</span>
        <span>${note.updatedAt ? note.updatedAt.substring(0, 10) : ""}</span>
      </div>
    </div>
  `;
}

// ── Create Note Modal ─────────────────────────────────

function openCreateNote() {
  state.createMode = "write";
  $("#createNoteOverlay").classList.add("open");
  updateCreatePanel();
  $("#newNoteTitle").focus();
}

function closeCreateNote() {
  $("#createNoteOverlay").classList.remove("open");
  resetCreateForm();
}

function updateCreatePanel() {
  $$(".create-tab").forEach(t => t.classList.toggle("active", t.dataset.mode === state.createMode));
  $$(".create-panel").forEach(p => p.style.display = "none");
  const panelId = "createPanel" + state.createMode.charAt(0).toUpperCase() + state.createMode.slice(1);
  const panel = $("#" + panelId);
  if (panel) panel.style.display = "";

  // Reset camera edit form when switching to camera mode
  if (state.createMode === "camera") {
    $("#cameraEditForm").style.display = "none";
    $("#processCameraImage").style.display = "";
  }
  // Reset upload edit form when switching to upload mode
  if (state.createMode === "upload") {
    $("#docEditForm").style.display = "none";
    $("#docUploadPlaceholderModal").style.display = "";
    state.extractedDocData = null;
  }

  // Show/hide footer save button (only for write mode; camera/upload have their own buttons)
  const footer = $("#createModalFooter");
  if (footer) footer.style.display = state.createMode === "write" ? "" : "none";
}

function resetCreateForm() {
  $("#newNoteTitle").value = "";
  $("#newNoteCourse").value = "";
  $("#newNoteFolder").value = "";
  $("#newNoteContent").value = "";
  $$("#newNoteTags .tag-chip").forEach(c => c.remove());
  $("#newNoteTagInput").value = "";
  // Reset camera
  resetCameraSelection();
  $("#cameraEditForm").style.display = "none";
  // Reset upload
  state.extractedDocData = null;
  $("#docUploadStatusModal").textContent = "";
  $("#docEditForm").style.display = "none";
  $$("#uploadNoteTags .tag-chip").forEach(c => c.remove());
  // Reset camera edit form
  $$("#cameraNoteTags .tag-chip").forEach(c => c.remove());
  $("#cameraNoteContent").value = "";
  $("#cameraNoteTitle").value = "";
}

function getTags(containerId) {
  const tags = [];
  $$(`#${containerId} .tag-chip`).forEach(chip => tags.push(chip.dataset.tag));
  return tags;
}

async function saveNewNote() {
  const title = $("#newNoteTitle").value.trim();
  if (!title) { showToast("请输入标题"); return; }
  const course = $("#newNoteCourse").value.trim();
  const folderPath = $("#newNoteFolder").value.trim();
  const content = $("#newNoteContent").value.trim();
  const tags = [];
  $$("#newNoteTags .tag-chip").forEach(chip => {
    tags.push(chip.dataset.tag);
  });

  const button = $("#saveNewNote");
  await withButtonLoading(button, "保存中...", async () => {
    try {
      await api("/api/v1/notes", {
        method: "POST",
        body: JSON.stringify({
          title, course, folderPath: folderPath || null,
          rawText: content, summary: content ? content.substring(0, 500) : "",
          tags, offlineCreated: false
        })
      });
      showToast("笔记已保存");
      closeCreateNote();
      await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

// ── Camera Mode (in create modal) ─────────────────────

const IMG_MAX = 1920;
const IMG_QUALITY = 0.7;

function compressImage(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const w = img.width, h = img.height;
      if (w <= IMG_MAX && h <= IMG_MAX && file.size < 300 * 1024) { resolve(file); return; }
      const scale = Math.min(IMG_MAX / w, IMG_MAX / h, 1);
      const cw = Math.round(w * scale), ch = Math.round(h * scale);
      const canvas = document.createElement("canvas");
      canvas.width = cw; canvas.height = ch;
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, cw, ch);
      canvas.toBlob((blob) => { if (blob) resolve(blob); else reject(new Error("压缩失败")); }, "image/jpeg", IMG_QUALITY);
    };
    img.onerror = () => reject(new Error("图片加载失败"));
    img.src = url;
  });
}

function handleCameraSelect(file) {
  if (!file || !file.type.startsWith("image/")) return;
  if (state.imageProcessing) return;
  state.selectedImageFile = file;
  const reader = new FileReader();
  reader.onload = (e) => {
    $("#cameraPreview").src = e.target.result;
    $("#cameraPreview").style.display = "block";
    $("#cameraPlaceholder").style.display = "none";
    $("#cameraUploadArea").classList.add("has-image");
  };
  reader.readAsDataURL(file);
}

function resetCameraSelection() {
  if (state.imageProcessing) {
    state.imageRequestId++;
    state.imageProcessing = false;
  }
  state.selectedImageFile = null;
  state.cameraNoteId = null;
  $("#cameraFileInput").value = "";
  $("#cameraPreview").style.display = "none";
  $("#cameraPlaceholder").style.display = "";
  $("#cameraUploadArea").classList.remove("has-image");
  $("#cameraResult").innerHTML = "";
  $("#cameraEditForm").style.display = "none";
}

async function processCameraImage() {
  if (!state.selectedImageFile || state.imageProcessing) return;
  state.imageProcessing = true;
  state.imageRequestId++;
  const myId = state.imageRequestId;
  const btn = $("#processCameraImage");

  await withButtonLoading(btn, "识别中...", async () => {
    try {
      const compressed = await compressImage(state.selectedImageFile);
      if (myId !== state.imageRequestId) return;
      const formData = new FormData();
      formData.append("file", compressed, compressed.name || "image.jpg");
      const response = await fetch("/api/v1/ai/note/process-image", {
        method: "POST",
        headers: state.token ? { "Authorization": "Bearer " + state.token } : {},
        body: formData
      });
      if (myId !== state.imageRequestId) return;
      if (response.status === 401) { showAuth(); return; }
      if (!response.ok) throw new Error(`识别失败: ${response.status}`);
      const note = await response.json();
      if (myId !== state.imageRequestId) return;

      // Store the already-saved note ID so we update instead of duplicate
      state.cameraNoteId = note.id;

      // Show editable form with AI results
      $("#cameraNoteTitle").value = note.title || "";
      $("#cameraNoteFolder").value = "";
      const cameraContent = note.rawText || note.summary || "";
      $("#cameraNoteContent").value = cameraContent;
      // Populate rendered preview
      $("#cameraPreviewContentRendered").innerHTML = renderMarkdown(cameraContent);
      $("#cameraPreviewContentRendered").style.display = "block";
      $("#cameraNoteContent").style.display = "none";
      $$("[data-preview-target='cameraPreviewContent']").forEach(t =>
        t.classList.toggle("active", t.dataset.mode === "rendered")
      );
      $$("#cameraNoteTags .tag-chip").forEach(c => c.remove());
      (note.tags || []).forEach(tag => addTagChip("cameraNoteTags", tag));
      setupTagInput("cameraNoteTags", "cameraNoteTagInput");

      $("#processCameraImage").style.display = "none";
      $("#cameraEditForm").style.display = "";
      $("#cameraResult").innerHTML = `<p style="color:var(--success);font-size:13px;">识别完成，请检查并编辑内容后保存</p>`;
      showToast("识别完成，请编辑后保存");
    } catch (err) {
      if (myId === state.imageRequestId) {
        $("#cameraResult").innerHTML = `<p style="color:var(--danger);font-size:13px;">识别失败: ${err.message}</p>`;
      }
    }
  });
  if (myId === state.imageRequestId) {
    state.imageProcessing = false;
  }
}

async function saveCameraNote() {
  const title = $("#cameraNoteTitle").value.trim();
  if (!title) { showToast("请输入标题"); return; }
  const folderPath = $("#cameraNoteFolder").value.trim();
  const content = $("#cameraNoteContent").value.trim();
  const tags = getTags("cameraNoteTags");

  const btn = $("#saveCameraNote");
  await withButtonLoading(btn, "保存中...", async () => {
    try {
      // Clean up the auto-saved note from process-image to avoid duplicates
      if (state.cameraNoteId) {
        await api(`/api/v1/notes/${state.cameraNoteId}`, { method: "DELETE" });
        state.cameraNoteId = null;
      }
      await api("/api/v1/notes", {
        method: "POST",
        body: JSON.stringify({
          title, course: "", folderPath: folderPath || null,
          rawText: content.substring(0, 19900), summary: content ? content.substring(0, 500) : "",
          tags, offlineCreated: false
        })
      });
      showToast("笔记已保存");
      resetCameraSelection();
      closeCreateNote();
      await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

// ── Document Upload (in create modal) ─────────────────

async function uploadDocumentModal(file) {
  if (!file) return;
  if (state.pending.has("upload-doc")) return;
  state.pending.add("upload-doc");
  const statusEl = $("#docUploadStatusModal");
  statusEl.textContent = "提取文档内容中...";
  try {
    // Step 1: Extract text only (no DB save)
    const formData = new FormData();
    formData.append("file", file);
    const response = await fetch("/api/v1/rag/documents/extract", {
      method: "POST",
      headers: state.token ? { "Authorization": "Bearer " + state.token } : {},
      body: formData
    });
    if (response.status === 401) { showAuth(); return; }
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let msg = text;
      try { const json = JSON.parse(text); msg = json.error || text; } catch (_) {}
      throw new Error(msg || `提取失败 (${response.status})`);
    }
    const result = await response.json();

    // Store extracted data for later use
    state.extractedDocData = {
      title: result.title || file.name,
      fileType: result.fileType,
      fileSize: result.fileSize,
      fullText: result.fullText || ""
    };

    // Show editable form
    $("#uploadNoteTitle").value = state.extractedDocData.title;
    $("#uploadNoteContent").value = state.extractedDocData.fullText;
    // Populate rendered preview
    $("#uploadPreviewContentRendered").innerHTML = renderMarkdown(state.extractedDocData.fullText);
    $("#uploadPreviewContentRendered").style.display = "block";
    $("#uploadNoteContent").style.display = "none";
    $$("[data-preview-target='uploadPreviewContent']").forEach(t =>
      t.classList.toggle("active", t.dataset.mode === "rendered")
    );
    $$("#uploadNoteTags .tag-chip").forEach(c => c.remove());
    setupTagInput("uploadNoteTags", "uploadNoteTagInput");

    statusEl.textContent = `文档内容提取成功（${result.fileType}，${(result.fileSize / 1024).toFixed(1)}KB），请编辑后保存`;
    statusEl.style.color = "var(--success)";
    $("#docUploadPlaceholderModal").style.display = "none";
    $("#docEditForm").style.display = "";
  } catch (err) {
    statusEl.textContent = "提取失败: " + err.message;
  } finally {
    state.pending.delete("upload-doc");
  }
}

async function saveUploadAsNote() {
  const title = $("#uploadNoteTitle").value.trim();
  if (!title) { showToast("请输入标题"); return; }
  const folderPath = $("#uploadNoteFolder").value.trim();
  const content = $("#uploadNoteContent").value.trim();
  const tags = getTags("uploadNoteTags");

  const btn = $("#saveUploadAsNote");
  await withButtonLoading(btn, "保存中...", async () => {
    try {
      await api("/api/v1/notes", {
        method: "POST",
        body: JSON.stringify({
          title, course: "", folderPath: folderPath || null,
          rawText: content.substring(0, 19900), summary: content ? content.substring(0, 500) : "",
          tags, offlineCreated: false
        })
      });
      showToast("笔记已保存");
      closeCreateNote();
      await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

async function saveUploadToKB() {
  if (!state.extractedDocData) {
    showToast("请先上传文档"); return;
  }
  const btn = $("#saveUploadToKB");
  await withButtonLoading(btn, "保存到知识库...", async () => {
    try {
      const result = await api("/api/v1/rag/documents/ingest-text", {
        method: "POST",
        body: JSON.stringify({
          title: state.extractedDocData.title,
          originalFilename: state.extractedDocData.title,
          fileType: state.extractedDocData.fileType,
          fullText: state.extractedDocData.fullText
        })
      });
      showToast(`已加入知识库（${result.chunkCount} 个片段）`);
      closeCreateNote();
      await loadKnowledgeBase();
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

// ── Knowledge Base Management ──────────────────────────

async function loadKnowledgeBase() {
  try {
    const docs = await api("/api/v1/rag/documents");
    const container = $("#knowledgeBaseList");
    if (!docs || docs.length === 0) {
      container.innerHTML = empty("暂无已上传的文档，通过「+ 新建 → 上传文档」添加");
      return;
    }
    container.innerHTML = docs.map(doc => {
      const statusLabel = { READY: "就绪", PROCESSING: "处理中", FAILED: "失败" }[doc.status] || doc.status;
      const statusCls = doc.status ? doc.status.toLowerCase() : "";
      const fileIcon = { PDF: "📕", DOCX: "📘", TXT: "📄" }[doc.fileType] || "📎";
      const fileCls = doc.fileType ? doc.fileType.toLowerCase() : "";
      return `<div class="knowledge-item">
        <div class="kb-icon ${fileCls}">${fileIcon}</div>
        <div class="kb-info">
          <div class="kb-title">${escapeHtml(doc.title)}</div>
          <div class="kb-meta">${doc.fileType} · ${doc.chunkCount || 0} 片段 · ${(doc.createdAt || "").substring(0, 10)}</div>
        </div>
        <span class="kb-status ${statusCls}">${statusLabel}</span>
        <button class="text-button kb-delete-btn" data-doc-id="${doc.id}" style="color:var(--danger);font-size:12px;" title="删除文档">删除</button>
      </div>`;
    }).join("");

    // Bind delete buttons
    $$(".kb-delete-btn").forEach(btn => {
      btn.addEventListener("click", () => deleteKnowledgeDocument(parseInt(btn.dataset.docId), btn));
    });
  } catch (err) { console.error("Load knowledge base failed:", err); }
}

async function deleteKnowledgeDocument(id, btn) {
  if (btn.dataset.confirming !== "true") {
    btn.dataset.confirming = "true";
    btn.textContent = "确认";
    clearTimeout(btn.confirmTimer);
    btn.confirmTimer = setTimeout(() => { btn.dataset.confirming = ""; btn.textContent = "删除"; }, 3000);
    return;
  }
  clearTimeout(btn.confirmTimer);
  try {
    await api(`/api/v1/rag/documents/${id}`, { method: "DELETE" });
    showToast("文档已从知识库删除");
    await loadKnowledgeBase();
  } catch (err) { showToast("删除失败：" + err.message); }
}

// ── Note Editor ───────────────────────────────────────

async function openNoteEditor(noteId) {
  try {
    const note = await api(`/api/v1/notes/${noteId}`);
    state.editingNoteId = noteId;
    $("#editNoteTitle").value = note.title || "";
    $("#editNoteCourse").value = note.course || "";
    $("#editNoteFolder").value = note.folderPath || "";
    $("#editNoteContent").value = note.rawText || note.summary || "";
    // Set tags
    $$("#editNoteTags .tag-chip").forEach(c => c.remove());
    (note.tags || []).forEach(tag => addTagChip("editNoteTags", tag));
    $("#noteEditorOverlay").classList.add("open");
  } catch (err) { showToast("加载笔记失败：" + err.message); }
}

function closeNoteEditor() {
  $("#noteEditorOverlay").classList.remove("open");
  state.editingNoteId = null;
}

async function saveNoteEdit() {
  if (!state.editingNoteId) return;
  const title = $("#editNoteTitle").value.trim();
  const course = $("#editNoteCourse").value.trim();
  const folderPath = $("#editNoteFolder").value.trim();
  const content = $("#editNoteContent").value.trim();
  const tags = [];
  $$("#editNoteTags .tag-chip").forEach(chip => tags.push(chip.dataset.tag));

  const button = $("#saveEditNote");
  await withButtonLoading(button, "保存中...", async () => {
    try {
      await api(`/api/v1/notes/${state.editingNoteId}`, {
        method: "PUT",
        body: JSON.stringify({
          title: title || "未命名笔记",
          course: course || "",
          folderPath: folderPath || null,
          rawText: content,
          summary: content ? content.substring(0, 500) : "",
          tags,
          keyPoints: [], formulas: [], mindMap: "", offlineCreated: false
        })
      });
      showToast("笔记已更新");
      closeNoteEditor();
      await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

async function deleteNoteFromEditor() {
  if (!state.editingNoteId) return;
  const id = state.editingNoteId;
  const button = $("#editorDeleteNote");
  if (button.dataset.confirming !== "true") {
    button.dataset.confirming = "true";
    button.textContent = "确认删除";
    clearTimeout(button.confirmTimer);
    button.confirmTimer = setTimeout(() => { button.dataset.confirming = ""; button.textContent = "删除笔记"; }, 3000);
    return;
  }
  await withButtonLoading(button, "删除中...", async () => {
    try {
      await api(`/api/v1/notes/${id}`, { method: "DELETE" });
      showToast("笔记已删除");
      closeNoteEditor();
      await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
    } catch (err) { showToast("删除失败：" + err.message); }
  });
}

async function aiStructureNote() {
  if (!state.editingNoteId) return;
  const note = await api(`/api/v1/notes/${state.editingNoteId}`);
  const content = note.rawText || note.summary || "";
  if (!content.trim()) { showToast("笔记内容为空，无法结构化"); return; }

  const button = $("#editorAiStructure");
  await withButtonLoading(button, "AI 处理中...", async () => {
    try {
      const result = await api("/api/v1/ai/note/process", {
        method: "POST",
        body: JSON.stringify({ rawText: content, offline: false })
      });
      $("#editNoteTitle").value = result.title || note.title;
      $("#editNoteContent").value = result.rawText || content;
      if (result.summary) {
        $$("#editNoteTags .tag-chip").forEach(c => c.remove());
        (result.tags || []).forEach(tag => addTagChip("editNoteTags", tag));
        (result.keyPoints || []).forEach(kp => addTagChip("editNoteTags", kp));
      }
      showToast("AI 结构化完成");
    } catch (err) { showToast("AI 处理失败：" + err.message); }
  });
}

async function deleteNote(id, btn) {
  if (btn.dataset.confirming !== "true") {
    btn.dataset.confirming = "true";
    btn.textContent = "确认";
    clearTimeout(btn.confirmTimer);
    btn.confirmTimer = setTimeout(() => { btn.dataset.confirming = ""; btn.textContent = ""; btn.title = "删除"; }, 3000);
    return;
  }
  clearTimeout(btn.confirmTimer);
  try {
    await api(`/api/v1/notes/${id}`, { method: "DELETE" });
    showToast("笔记已删除");
    await Promise.all([loadFolders(), loadNotes(), loadDashboard()]);
  } catch (err) {
    if (err.status === 404) {
      document.querySelector(`[data-note-id="${id}"]`)?.remove();
      showToast("这条笔记已不存在");
      return;
    }
    showToast("删除失败：" + err.message);
  }
}

// ── Tag helpers ───────────────────────────────────────

function addTagChip(wrapId, tag) {
  const wrap = $("#" + wrapId);
  if (!wrap) return;
  // Check duplicate
  const existing = wrap.querySelectorAll(".tag-chip");
  for (const chip of existing) {
    if (chip.dataset.tag === tag) return;
  }
  const chip = document.createElement("span");
  chip.className = "tag-chip";
  chip.dataset.tag = tag;
  chip.innerHTML = `${escapeHtml(tag)} <button class="tag-remove">&times;</button>`;
  chip.querySelector(".tag-remove").addEventListener("click", () => chip.remove());
  wrap.insertBefore(chip, wrap.querySelector("input"));
}

function setupTagInput(wrapId, inputId) {
  const input = $("#" + inputId);
  if (!input) return;
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      const tag = input.value.trim();
      if (tag) { addTagChip(wrapId, tag); input.value = ""; }
    }
  });
  input.addEventListener("blur", () => {
    const tag = input.value.trim();
    if (tag) { addTagChip(wrapId, tag); input.value = ""; }
  });
}

// ── Dashboard / Home ──────────────────────────────────

function renderStats(data) {
  $("#statsGrid").innerHTML = [
    ["笔记", data.noteCount, ""],
    ["待办", data.openReminderCount, ""],
    ["连续学习", data.studyDays, "天"]
  ].map(([label, value, suffix]) =>
    `<div class="stat"><strong class="count-target">${value}${suffix}</strong><span>${label}</span></div>`
  ).join("");
  setTimeout(() => {
    const items = [
      { el: $$(".stat .count-target")[0], val: data.noteCount, s: "" },
      { el: $$(".stat .count-target")[1], val: data.openReminderCount, s: "" },
      { el: $$(".stat .count-target")[2], val: data.studyDays, s: "天" }
    ];
    items.forEach(({ el, val, s }) => { if (el) animateCounter(el, val, s); });
  }, 100);
}

function reminderItem(reminder) {
  return `
    <article class="list-item">
      <h3>${escapeHtml(reminder.title || "")}</h3>
      <p>${escapeHtml(reminder.course || "")} · 截止 ${escapeHtml(reminder.dueDate || "")}</p>
      <div class="meta-row">
        <span class="tag ${reminder.priority}">${priorityLabel(reminder.priority)}</span>
        <span class="tag">${escapeHtml(reminder.source || "AI 管家提醒")}</span>
      </div>
    </article>
  `;
}

function noteItem(note) {
  const tags = (note.tags || []).map(t => `<span class="tag">${escapeHtml(t)}</span>`).join("");
  return `
    <article class="list-item">
      <h3>${escapeHtml(note.title || "")}</h3>
      <p>${escapeHtml(note.summary || note.rawText || "")}</p>
      <div class="meta-row">
        <span class="tag">${escapeHtml(note.course || "")}</span>
        ${tags}
      </div>
    </article>
  `;
}

async function loadDashboard() {
  const [stats, today, notes] = await Promise.all([
    api("/api/v1/stats/dashboard"),
    api("/api/v1/reminders/today"),
    api("/api/v1/notes")
  ]);
  renderStats(stats);
  $("#todayList").innerHTML = today.length ? today.map(reminderItem).join("") : empty("今天没有临近 DDL");
  $("#recentNotes").innerHTML = notes.slice(0, 3).map(noteItem).join("");
}

// ── DDL ───────────────────────────────────────────────

async function loadTasks(priority = false) {
  const tasks = await api(priority ? "/api/v1/reminders/priority" : "/api/v1/reminders");
  $("#taskList").innerHTML = tasks.length ? tasks.map(reminderItem).join("") : empty("暂无 DDL");
}

async function parseDdl(event) {
  const button = event?.currentTarget || $("#parseDdl");
  if (state.pending.has("parse-ddl")) return;
  const text = $("#ddlInput").value.trim();
  if (!text) { showToast("请输入 DDL 文本"); return; }
  await withButtonLoading(button, "解析中...", async () => {
    state.pending.add("parse-ddl");
    try {
      await api("/api/v1/reminders/parse", { method: "POST", body: JSON.stringify({ text }) });
      showToast("DDL 已创建");
      await Promise.all([loadTasks(), loadDashboard()]);
    } catch (err) { showToast("解析失败：" + err.message); }
    finally { state.pending.delete("parse-ddl"); }
  });
}

// ── Weekly Report ─────────────────────────────────────

async function loadReport() {
  const report = await api("/api/v1/reports/weekly");
  $("#reportBox").innerHTML = `
    <div class="report-grid">
      <div class="report-cell"><strong>${report.noteCount}</strong><span>归档笔记</span></div>
      <div class="report-cell"><strong>${report.focusHours}</strong><span>专注小时</span></div>
      <div class="report-cell"><strong>${report.completedTasks}</strong><span>完成 DDL</span></div>
    </div>
    <div class="item-list">
      ${report.highlights.map(item => `<article class="list-item"><h3>${escapeHtml(item)}</h3></article>`).join("")}
      <article class="list-item"><p>${escapeHtml(report.message)}</p></article>
    </div>
  `;
}

function empty(text) {
  return `<article class="list-item"><p style="color:var(--muted);text-align:center;">${escapeHtml(text)}</p></article>`;
}

// ── Image Lightbox ────────────────────────────────────

function openImageLightbox(src) {
  $("#imageLightboxImg").src = src;
  $("#imageLightbox").classList.add("open");
  $("#imageLightbox").setAttribute("aria-hidden", "false");
}

function closeImageLightbox() {
  $("#imageLightbox").classList.remove("open");
  $("#imageLightbox").setAttribute("aria-hidden", "true");
  $("#imageLightboxImg").src = "";
}

// ── Event Binding ─────────────────────────────────────

function bindEvents() {
  // Ripple
  document.addEventListener("click", (e) => {
    const btn = e.target.closest("button");
    if (btn && !btn.classList.contains("ripple-none")) addRipple(e);
  });

  // Auth
  $("#loginForm").addEventListener("submit", handleLogin);
  $("#registerForm").addEventListener("submit", handleRegister);
  $("#logoutBtn").addEventListener("click", handleLogout);
  $$(".auth-tab").forEach(tab => {
    tab.addEventListener("click", () => {
      $$(".auth-tab").forEach(t => t.classList.remove("active"));
      tab.classList.add("active");
      const isLogin = tab.dataset.tab === "login";
      $("#loginForm").style.display = isLogin ? "" : "none";
      $("#registerForm").style.display = isLogin ? "none" : "";
      $("#authError").textContent = "";
    });
  });

  // Navigation
  $$("[data-nav]").forEach(node => node.addEventListener("click", () => navTo(node.dataset.nav)));

  // Chat drawer
  $("#closeChatDrawer").addEventListener("click", closeChatDrawer);
  $("#chatDrawerOverlay").addEventListener("click", closeChatDrawer);
  $("#sendChat").addEventListener("click", sendRagChat);
  $("#chatInput").addEventListener("keydown", (event) => {
    if (event.key === "Enter") { event.preventDefault(); sendRagChat(); }
  });

  // Chat drawer tabs
  $$(".chat-drawer-tab").forEach(tab => {
    tab.addEventListener("click", () => switchChatTab(tab.dataset.chatTab));
  });

  // Makeup
  $("#makeupBtn").addEventListener("click", loadMakeup);
  $("#refreshMakeupSources").addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    loadMakeupSources();
  });
  $("#sendMakeupChat").addEventListener("click", sendMakeupChat);
  $("#makeupChatInput").addEventListener("keydown", (event) => {
    if (event.key === "Enter") { event.preventDefault(); sendMakeupChat(); }
  });
  $("#saveMakeupAsNote").addEventListener("click", openSaveMakeupModal);

  // Save makeup note modal
  $("#closeSaveMakeupNote").addEventListener("click", closeSaveMakeupModal);
  $("#cancelSaveMakeupNote").addEventListener("click", closeSaveMakeupModal);
  $("#saveMakeupNoteOverlay").addEventListener("click", (e) => {
    if (e.target === $("#saveMakeupNoteOverlay")) closeSaveMakeupModal();
  });
  $("#confirmSaveMakeupNote").addEventListener("click", confirmSaveMakeupNote);
  setupTagInput("saveMakeupTagsWrap", "saveMakeupTagInput");

  // Content preview toggles
  $$(".content-preview-toggle").forEach(btn => {
    btn.addEventListener("click", () => {
      switchContentPreview(btn.dataset.previewTarget, btn.dataset.mode);
    });
  });
  // Sync preview on textarea edits
  $("#cameraNoteContent").addEventListener("input", () => {
    if ($("#cameraPreviewContentRendered").style.display !== "none") {
      $("#cameraPreviewContentRendered").innerHTML = renderMarkdown($("#cameraNoteContent").value);
    }
  });
  $("#uploadNoteContent").addEventListener("input", () => {
    if ($("#uploadPreviewContentRendered").style.display !== "none") {
      $("#uploadPreviewContentRendered").innerHTML = renderMarkdown($("#uploadNoteContent").value);
    }
  });

  // Notes panel
  $("#openCreateNote").addEventListener("click", openCreateNote);
  $("#noteSearch").addEventListener("input", loadNotes);

  // Create note modal
  $("#closeCreateNote").addEventListener("click", closeCreateNote);
  $("#cancelCreateNote").addEventListener("click", closeCreateNote);
  $("#createNoteOverlay").addEventListener("click", (e) => {
    if (e.target === $("#createNoteOverlay")) closeCreateNote();
  });
  $("#saveNewNote").addEventListener("click", saveNewNote);

  // Create mode tabs
  $$(".create-tab").forEach(tab => {
    tab.addEventListener("click", () => {
      state.createMode = tab.dataset.mode;
      updateCreatePanel();
    });
  });

  // Tag inputs
  setupTagInput("newNoteTags", "newNoteTagInput");
  setupTagInput("editNoteTags", "editNoteTagInput");

  // Camera mode
  $("#cameraUploadArea").addEventListener("click", () => {
    if (state.imageProcessing || state.selectedImageFile) return;
    $("#cameraFileInput").click();
  });
  $("#cameraFileInput").addEventListener("change", (e) => {
    if (e.target.files && e.target.files[0]) handleCameraSelect(e.target.files[0]);
  });
  $("#cameraUploadArea").addEventListener("dragover", (e) => {
    e.preventDefault();
    if (!state.imageProcessing) $("#cameraUploadArea").style.borderColor = "var(--blue)";
  });
  $("#cameraUploadArea").addEventListener("dragleave", () => {
    $("#cameraUploadArea").style.borderColor = "";
  });
  $("#cameraUploadArea").addEventListener("drop", (e) => {
    e.preventDefault();
    $("#cameraUploadArea").style.borderColor = "";
    if (state.imageProcessing) return;
    if (e.dataTransfer.files && e.dataTransfer.files[0]) handleCameraSelect(e.dataTransfer.files[0]);
  });
  $("#cameraPreview").addEventListener("click", (e) => {
    e.stopPropagation();
    if ($("#cameraPreview").src) openImageLightbox($("#cameraPreview").src);
  });
  $("#processCameraImage").addEventListener("click", processCameraImage);

  // Document upload mode
  $("#docUploadAreaModal").addEventListener("click", () => $("#docFileInputModal").click());
  $("#docFileInputModal").addEventListener("change", (e) => {
    if (e.target.files && e.target.files[0]) {
      uploadDocumentModal(e.target.files[0]);
      e.target.value = "";
    }
  });
  $("#docUploadAreaModal").addEventListener("dragover", (e) => {
    e.preventDefault();
    $("#docUploadAreaModal").style.borderColor = "var(--blue)";
  });
  $("#docUploadAreaModal").addEventListener("dragleave", () => {
    $("#docUploadAreaModal").style.borderColor = "";
  });
  $("#docUploadAreaModal").addEventListener("drop", (e) => {
    e.preventDefault();
    $("#docUploadAreaModal").style.borderColor = "";
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      uploadDocumentModal(e.dataTransfer.files[0]);
    }
  });

  // Save camera note
  $("#saveCameraNote").addEventListener("click", saveCameraNote);

  // Save upload as note / KB
  $("#saveUploadAsNote").addEventListener("click", saveUploadAsNote);
  $("#saveUploadToKB").addEventListener("click", saveUploadToKB);

  // Knowledge base
  $("#refreshKnowledgeBase").addEventListener("click", loadKnowledgeBase);

  // Notes sub-tabs
  $$(".notes-subtab").forEach(tab => {
    tab.addEventListener("click", () => switchNotesTab(tab.dataset.notesTab));
  });

  // Note editor
  $("#closeNoteEditor").addEventListener("click", closeNoteEditor);
  $("#cancelEditNote").addEventListener("click", closeNoteEditor);
  $("#noteEditorOverlay").addEventListener("click", (e) => {
    if (e.target === $("#noteEditorOverlay")) closeNoteEditor();
  });
  $("#saveEditNote").addEventListener("click", saveNoteEdit);
  $("#editorDeleteNote").addEventListener("click", deleteNoteFromEditor);
  $("#editorAiStructure").addEventListener("click", aiStructureNote);

  // DDL
  $("#addDdlSample").addEventListener("click", () => {
    $("#ddlInput").value = "明天截止：提交数据结构实验报告，必须包含代码、运行截图和复杂度分析。";
  });
  $("#parseDdl").addEventListener("click", parseDdl);
  $("#sortPriority").addEventListener("click", () => loadTasks(true));

  // Refresh
  $("#refreshToday").addEventListener("click", loadDashboard);
  $("#refreshReport").addEventListener("click", loadReport);

  // Lightbox
  $("#imageLightbox").addEventListener("click", closeImageLightbox);
  $("#imageLightboxImg").addEventListener("click", (e) => e.stopPropagation());
  $("#closeImageLightbox").addEventListener("click", closeImageLightbox);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      closeImageLightbox();
      closeCreateNote();
      closeNoteEditor();
      closeChatDrawer();
      closeSaveMakeupModal();
    }
  });

  // Keyboard shortcut: Ctrl+K to open chat
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "k") {
      e.preventDefault();
      openChatDrawer();
    }
  });
}

// ── Boot ──────────────────────────────────────────────

async function bootApp() {
  await Promise.all([loadDashboard(), loadTasks(), loadReport()]);
}

async function boot() {
  bindEvents();
  initBubble();

  if (state.token) {
    try {
      const user = await api("/api/v1/user/profile");
      showApp(user);
      await bootApp();
    } catch (_) { showAuth(); }
  } else {
    showAuth();
  }
}

boot().catch(error => {
  console.error(error);
  document.body.insertAdjacentHTML("beforeend", `<div class="fatal">${error.message}</div>`);
});
