const state = {
  offline: false,
  currentPanel: "home",
  token: localStorage.getItem("lanxin_token") || "",
  user: null
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

async function api(path, options = {}) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (state.token) {
    headers["Authorization"] = "Bearer " + state.token;
  }
  const response = await fetch(path, { headers, ...options });
  if (response.status === 401) {
    localStorage.removeItem("lanxin_token");
    state.token = "";
    showAuth();
    throw new Error("登录已过期");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `API ${path} failed: ${response.status}`);
  }
  return response.json();
}

// ── Auth ──────────────────────────────────────────────

function showAuth() {
  $("#authOverlay").style.display = "grid";
  $("#appShell").style.display = "none";
}

function showApp(user) {
  state.user = user;
  $("#authOverlay").style.display = "none";
  $("#appShell").style.display = "";
  $("#userBadge").textContent = user.name;
}

async function handleLogin(e) {
  e.preventDefault();
  const username = $("#loginUsername").value.trim();
  const password = $("#loginPassword").value;
  try {
    const data = await fetch("/api/v1/user/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password })
    }).then(r => r.json());
    if (data.error) throw new Error(data.error);
    state.token = data.token;
    localStorage.setItem("lanxin_token", data.token);
    showApp(data);
    await bootApp();
  } catch (err) {
    $("#authError").textContent = err.message;
  }
}

async function handleRegister(e) {
  e.preventDefault();
  const username = $("#regUsername").value.trim();
  const password = $("#regPassword").value;
  const name = $("#regName").value.trim();
  const school = $("#regSchool").value.trim();
  const major = $("#regMajor").value.trim();
  const grade = $("#regGrade").value.trim();
  if (!username || !password) {
    $("#authError").textContent = "用户名和密码不能为空";
    return;
  }
  try {
    const data = await fetch("/api/v1/user/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password, name, school, major, grade })
    }).then(r => r.json());
    if (data.error) throw new Error(data.error);
    state.token = data.token;
    localStorage.setItem("lanxin_token", data.token);
    showApp(data);
    await bootApp();
  } catch (err) {
    $("#authError").textContent = err.message;
  }
}

async function handleLogout() {
  try { await api("/api/v1/user/logout", { method: "POST" }); } catch (_) {}
  localStorage.removeItem("lanxin_token");
  state.token = "";
  state.user = null;
  showAuth();
}

// ── Navigation ────────────────────────────────────────

function navTo(panel) {
  state.currentPanel = panel;
  $$(".tab-panel").forEach((node) => node.classList.toggle("active", node.dataset.panel === panel));
  $$(".bottom-nav button").forEach((node) => node.classList.toggle("active", node.dataset.nav === panel));
  if (panel === "notes") loadNotes();
  if (panel === "tasks") loadTasks();
  if (panel === "ai") { loadDocuments(); loadMakeupSources(); }
  if (panel === "report") loadReport();
}

function priorityLabel(priority) {
  return { high: "高优先级", medium: "中优先级", low: "低优先级" }[priority] || "中优先级";
}

// ── Rendering ─────────────────────────────────────────

function renderStats(data) {
  $("#statsGrid").innerHTML = [
    ["笔记", data.noteCount],
    ["待办", data.openReminderCount],
    ["连续学习", `${data.studyDays}天`]
  ].map(([label, value]) => `<div class="stat"><strong>${value}</strong><span>${label}</span></div>`).join("");
}

function reminderItem(reminder) {
  return `
    <article class="list-item">
      <h3>${reminder.title}</h3>
      <p>${reminder.course} · 截止 ${reminder.dueDate}</p>
      <div class="meta-row">
        <span class="tag ${reminder.priority}">${priorityLabel(reminder.priority)}</span>
        <span class="tag">${reminder.source || "AI 管家提醒"}</span>
      </div>
    </article>
  `;
}

function noteItem(note) {
  const tags = (note.tags || []).map((tag) => `<span class="tag">${tag}</span>`).join("");
  return `
    <article class="list-item">
      <h3>${note.title}</h3>
      <p>${note.summary || note.rawText || ""}</p>
      <div class="meta-row">
        <span class="tag">${note.course}</span>
        ${note.offlineCreated ? '<span class="tag low">离线创建</span>' : ""}
        ${tags}
      </div>
    </article>
  `;
}

// ── Data loading ──────────────────────────────────────

async function loadDashboard() {
  const [stats, today, notes] = await Promise.all([
    api("/api/v1/stats/dashboard"),
    api("/api/v1/reminders/today"),
    api("/api/v1/notes")
  ]);
  renderStats(stats);
  $("#todayList").innerHTML = today.length ? today.map(reminderItem).join("") : empty("今天没有临近 DDL");
  $("#recentNotes").innerHTML = notes.slice(0, 2).map(noteItem).join("");
}

async function loadNotes() {
  const keyword = $("#noteSearch").value.trim();
  const notes = await api(`/api/v1/notes${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ""}`);
  $("#noteList").innerHTML = notes.length ? notes.map(noteItem).join("") : empty("没有找到匹配笔记");
}

async function loadTasks(priority = false) {
  const tasks = await api(priority ? "/api/v1/reminders/priority" : "/api/v1/reminders");
  $("#taskList").innerHTML = tasks.length ? tasks.map(reminderItem).join("") : empty("暂无 DDL");
}

function renderNoteResult(note) {
  $("#noteResult").innerHTML = `
    <div class="section-head">
      <h2>${note.title}</h2>
      <span class="status-pill">${note.offlineCreated ? "端侧离线" : "云端增强"}</span>
    </div>
    <p class="compact-text">${note.summary}</p>
    <div class="meta-row">${note.keyPoints.map((point) => `<span class="tag">${point}</span>`).join("")}</div>
    <h3 style="margin:14px 0 8px;">公式/术语</h3>
    <div class="meta-row">${note.formulas.map((formula) => `<span class="tag">${formula}</span>`).join("")}</div>
    <h3 style="margin:14px 0 8px;">思维导图</h3>
    <div class="mindmap">${note.mindMap}</div>
  `;
}

// ── Actions ───────────────────────────────────────────

async function processNote() {
  const rawText = $("#noteInput").value.trim();
  const note = await api("/api/v1/ai/note/process", {
    method: "POST",
    body: JSON.stringify({ rawText, offline: state.offline })
  });
  renderNoteResult(note);
  await loadDashboard();
}

async function parseDdl() {
  const text = $("#ddlInput").value.trim();
  await api("/api/v1/reminders/parse", {
    method: "POST",
    body: JSON.stringify({ text })
  });
  await Promise.all([loadTasks(), loadDashboard()]);
}

function escapeHtml(text) {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\n/g, "<br>");
}

function appendMessage(role, content) {
  const node = document.createElement("div");
  node.className = `message ${role}`;
  node.innerHTML = escapeHtml(content);
  $("#chatBox").appendChild(node);
  $("#chatBox").scrollTop = $("#chatBox").scrollHeight;
}

async function sendRagChat() {
  const input = $("#chatInput");
  const message = input.value.trim();
  if (!message) return;
  input.value = "";

  appendMessage("user", message);

  const useRag = $("#ragToggle").checked;
  const endpoint = useRag ? "/api/v1/rag/chat/stream" : "/api/v1/ai/chat/stream";

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(state.token ? { "Authorization": "Bearer " + state.token } : {})
      },
      body: JSON.stringify({ message })
    });

    if (response.status === 401) { showAuth(); return; }
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let msg = text;
      try { const json = JSON.parse(text); msg = json.error || msg; } catch (_) {}
      throw new Error(msg || `请求失败 (${response.status})`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();

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
  } catch (err) {
    appendMessage("assistant", "抱歉，回复失败: " + err.message);
  }
}

// ── RAG Document Management ───────────────────────────

async function loadDocuments() {
  try {
    const docs = await api("/api/v1/rag/documents");
    $("#docList").innerHTML = docs.length
      ? docs.map(doc => `
          <article class="list-item">
            <h3>${doc.title}</h3>
            <p>${doc.fileType} · ${doc.chunkCount} 块 · ${doc.status === "READY" ? "就绪" : doc.status}</p>
            <div class="meta-row">
              <span class="tag">${doc.status === "READY" ? "就绪" : doc.status}</span>
              <span class="tag low">${doc.createdAt ? doc.createdAt.substring(0, 10) : ""}</span>
              <button class="text-button" data-delete-doc="${doc.id}"
                style="color:var(--red);font-size:12px;">删除</button>
            </div>
          </article>
        `).join("")
      : empty("尚未上传任何文档");

    const readyDocs = docs.filter(d => d.status === "READY");
    if (readyDocs.length > 0) {
      $("#ragStatus").textContent = `就绪 (${readyDocs.length} 篇)`;
      $("#ragStatus").style.background = "#eaf8ef";
      $("#ragStatus").style.color = "var(--green)";
    } else {
      $("#ragStatus").textContent = "未启用";
      $("#ragStatus").style.background = "";
      $("#ragStatus").style.color = "";
    }

    $$("[data-delete-doc]").forEach(btn => {
      btn.addEventListener("click", async () => {
        const id = btn.dataset.deleteDoc;
        await api(`/api/v1/rag/documents/${id}`, { method: "DELETE" });
        await loadDocuments();
      });
    });
  } catch (err) {
    console.error("Load documents failed:", err);
  }
}

async function uploadDocument(file) {
  if (!file) return;
  $("#docUploadStatus").textContent = "上传处理中...";
  try {
    const formData = new FormData();
    formData.append("file", file);
    const response = await fetch("/api/v1/rag/documents", {
      method: "POST",
      headers: state.token ? { "Authorization": "Bearer " + state.token } : {},
      body: formData
    });
    if (response.status === 401) { showAuth(); return; }
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let msg = text;
      try { const json = JSON.parse(text); msg = json.error || text; } catch (_) {}
      throw new Error(msg || `服务器错误 (${response.status})`);
    }
    const result = await response.json();
    if (result.status === "FAILED") {
      $("#docUploadStatus").textContent = "处理失败: " + (result.errorMessage || "未知错误");
    } else {
      $("#docUploadStatus").textContent = `上传成功！已拆分为 ${result.chunkCount} 个片段`;
    }
    await loadDocuments();
  } catch (err) {
    $("#docUploadStatus").textContent = "上传失败: " + err.message;
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
      container.innerHTML = empty("暂无笔记或文档，请先拍照上传或录入笔记");
      return;
    }

    let html = "";
    notes.forEach(note => {
      html += `
        <label class="makeup-source-item">
          <input type="checkbox" class="makeup-checkbox" data-type="note" data-id="${note.id}">
          <div>
            <strong>${escapeHtml(note.title)}</strong>
            <span class="tag">笔记 · ${escapeHtml(note.course)}</span>
          </div>
        </label>
      `;
    });

    docs.filter(d => d.status === "READY").forEach(doc => {
      html += `
        <label class="makeup-source-item">
          <input type="checkbox" class="makeup-checkbox" data-type="document" data-id="${doc.id}">
          <div>
            <strong>${escapeHtml(doc.title)}</strong>
            <span class="tag">${doc.fileType} 文档</span>
          </div>
        </label>
      `;
    });

    container.innerHTML = html || empty("暂无可用素材");
  } catch (err) {
    console.error("Load makeup sources failed:", err);
  }
}

async function loadMakeup() {
  const box = $("#makeupBox");

  const noteIds = [];
  const documentIds = [];
  $$(".makeup-checkbox:checked").forEach(cb => {
    const id = parseInt(cb.dataset.id);
    if (cb.dataset.type === "note") noteIds.push(id);
    else documentIds.push(id);
  });

  box.innerHTML = "<strong>生成中...</strong><br><br><span id='makeupStream'></span>";

  try {
    const response = await fetch("/api/v1/ai/makeup/stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(state.token ? { "Authorization": "Bearer " + state.token } : {})
      },
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
      $("#makeupStream").innerHTML = fullText.replace(/\n/g, "<br>");
    }
  } catch (err) {
    box.innerHTML = "<strong>补课包生成失败</strong><br>" + err.message;
  }
}

async function loadReport() {
  const report = await api("/api/v1/reports/weekly");
  $("#reportBox").innerHTML = `
    <div class="report-grid">
      <div class="report-cell"><strong>${report.noteCount}</strong><span>归档笔记</span></div>
      <div class="report-cell"><strong>${report.focusHours}</strong><span>专注小时</span></div>
      <div class="report-cell"><strong>${report.completedTasks}</strong><span>完成 DDL</span></div>
    </div>
    <div class="item-list">
      ${report.highlights.map((item) => `<article class="list-item"><h3>${item}</h3></article>`).join("")}
      <article class="list-item"><p>${report.message}</p></article>
    </div>
  `;
}

function empty(text) {
  return `<article class="list-item"><p>${text}</p></article>`;
}

// ── Image upload ──────────────────────────────────────

const IMG_MAX = 1920;
const IMG_QUALITY = 0.7;

let selectedImageFile = null;
let imageProcessing = false;
let imageRequestId = 0;

function compressImage(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const w = img.width;
      const h = img.height;
      if (w <= IMG_MAX && h <= IMG_MAX && file.size < 300 * 1024) {
        resolve(file);
        return;
      }
      const scale = Math.min(IMG_MAX / w, IMG_MAX / h, 1);
      const cw = Math.round(w * scale);
      const ch = Math.round(h * scale);
      const canvas = document.createElement("canvas");
      canvas.width = cw;
      canvas.height = ch;
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, cw, ch);
      canvas.toBlob((blob) => {
        if (blob) resolve(blob);
        else reject(new Error("压缩失败"));
      }, "image/jpeg", IMG_QUALITY);
    };
    img.onerror = () => reject(new Error("图片加载失败"));
    img.src = url;
  });
}

function handleImageSelect(file) {
  if (!file || !file.type.startsWith("image/")) return;
  if (imageProcessing) return;
  selectedImageFile = file;
  const reader = new FileReader();
  reader.onload = (e) => {
    $("#imagePreview").src = e.target.result;
    $("#imagePreview").style.display = "block";
    $("#uploadPlaceholder").style.display = "none";
    $("#uploadArea").classList.add("has-image");
  };
  reader.readAsDataURL(file);
}

function resetImageSelection() {
  if (imageProcessing) {
    imageRequestId++;
    imageProcessing = false;
  }
  selectedImageFile = null;
  $("#imageFile").value = "";
  $("#imagePreview").style.display = "none";
  $("#uploadPlaceholder").style.display = "";
  $("#uploadArea").classList.remove("has-image");
  $("#processImage").innerHTML = '<span class="spark-icon"></span>AI 识别照片';
  $("#processImage").disabled = false;
}

async function processImage() {
  if (!selectedImageFile || imageProcessing) return;
  imageProcessing = true;
  imageRequestId++;
  const myId = imageRequestId;
  const btn = $("#processImage");
  const fileInput = $("#imageFile");
  btn.textContent = "压缩中...";
  btn.disabled = true;
  fileInput.disabled = true;
  try {
    const compressed = await compressImage(selectedImageFile);
    if (myId !== imageRequestId) return;
    btn.textContent = "上传识别中...";
    const formData = new FormData();
    formData.append("file", compressed, compressed.name || "image.jpg");
    const response = await fetch("/api/v1/ai/note/process-image", {
      method: "POST",
      headers: state.token ? { "Authorization": "Bearer " + state.token } : {},
      body: formData
    });
    if (myId !== imageRequestId) return;
    if (response.status === 401) { showAuth(); return; }
    if (!response.ok) throw new Error(`Upload failed: ${response.status}`);
    const note = await response.json();
    if (myId !== imageRequestId) return;
    renderNoteResult(note);
    await loadDashboard();
    selectedImageFile = null;
    $("#imageFile").value = "";
    $("#imagePreview").style.display = "none";
    $("#uploadPlaceholder").style.display = "";
    $("#uploadArea").classList.remove("has-image");
  } catch (error) {
    if (myId !== imageRequestId) return;
    console.error(error);
    $("#noteResult").innerHTML = `<div class="section-head"><h2>识别失败</h2></div><p class="compact-text">${error.message}</p>`;
  } finally {
    if (myId === imageRequestId) {
      btn.innerHTML = '<span class="spark-icon"></span>AI 识别照片';
      btn.disabled = false;
      fileInput.disabled = false;
      imageProcessing = false;
    }
  }
}

// ── Event binding ─────────────────────────────────────

function bindEvents() {
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
  $$("[data-nav]").forEach((node) => node.addEventListener("click", () => navTo(node.dataset.nav)));

  // Offline toggle
  $("#offlineToggle").addEventListener("click", () => {
    state.offline = !state.offline;
    $(".signal-icon").classList.toggle("offline", state.offline);
    $("#modeBadge").textContent = state.offline ? "端侧离线" : "云端增强";
  });

  // Notes
  $("#loadSample").addEventListener("click", () => {
    $("#noteInput").value = "数据结构：二叉树前序遍历先访问根节点，再访问左子树和右子树。递归出口为空节点，时间复杂度 O(n)，空间复杂度与树高相关。";
  });
  $("#processNote").addEventListener("click", processNote);
  $("#noteSearch").addEventListener("input", loadNotes);

  // DDL
  $("#addDdlSample").addEventListener("click", () => {
    $("#ddlInput").value = "明天截止：提交数据结构实验报告，必须包含代码、运行截图和复杂度分析。";
  });
  $("#parseDdl").addEventListener("click", parseDdl);
  $("#sortPriority").addEventListener("click", () => loadTasks(true));

  // Chat
  $("#sendChat").addEventListener("click", sendRagChat);
  $("#chatInput").addEventListener("keydown", (event) => {
    if (event.key === "Enter") sendRagChat();
  });

  // RAG document upload
  $("#docUploadArea").addEventListener("click", () => $("#docFileInput").click());
  $("#docFileInput").addEventListener("change", (event) => {
    if (event.target.files && event.target.files[0]) {
      uploadDocument(event.target.files[0]);
      event.target.value = "";
    }
  });
  $("#docUploadArea").addEventListener("dragover", (event) => {
    event.preventDefault();
    $("#docUploadArea").style.borderColor = "var(--blue)";
  });
  $("#docUploadArea").addEventListener("dragleave", () => {
    $("#docUploadArea").style.borderColor = "";
  });
  $("#docUploadArea").addEventListener("drop", (event) => {
    event.preventDefault();
    $("#docUploadArea").style.borderColor = "";
    if (event.dataTransfer.files && event.dataTransfer.files[0]) {
      uploadDocument(event.dataTransfer.files[0]);
    }
  });
  $("#refreshDocs").addEventListener("click", loadDocuments);

  // Makeup
  $("#makeupBtn").addEventListener("click", loadMakeup);
  $("#makeupRefreshSources").addEventListener("click", loadMakeupSources);

  // Refresh
  $("#refreshToday").addEventListener("click", loadDashboard);
  $("#refreshReport").addEventListener("click", loadReport);

  // Image upload
  $("#uploadArea").addEventListener("click", () => {
    if (imageProcessing) return;
    $("#imageFile").click();
  });
  $("#imageFile").addEventListener("change", (event) => {
    if (event.target.files && event.target.files[0]) handleImageSelect(event.target.files[0]);
  });
  $("#uploadArea").addEventListener("dragover", (event) => {
    event.preventDefault();
    if (!imageProcessing) $("#uploadArea").style.borderColor = "var(--blue)";
  });
  $("#uploadArea").addEventListener("dragleave", () => {
    $("#uploadArea").style.borderColor = "";
  });
  $("#uploadArea").addEventListener("drop", (event) => {
    event.preventDefault();
    $("#uploadArea").style.borderColor = "";
    if (imageProcessing) return;
    if (event.dataTransfer.files && event.dataTransfer.files[0]) handleImageSelect(event.dataTransfer.files[0]);
  });
  $("#processImage").addEventListener("click", processImage);
  $("#cancelImage").addEventListener("click", resetImageSelection);
}

// ── Boot ──────────────────────────────────────────────

async function bootApp() {
  appendMessage("assistant", "我是小蓝。可以把课堂内容、作业要求或复习目标发给我。");
  await Promise.all([loadDashboard(), loadTasks(), loadReport()]);
}

async function boot() {
  bindEvents();
  if (state.token) {
    try {
      const user = await api("/api/v1/user/profile");
      showApp(user);
      appendMessage("assistant", "我是小蓝。可以把课堂内容、作业要求或复习目标发给我。");
      await Promise.all([loadDashboard(), loadTasks(), loadReport()]);
    } catch (_) {
      showAuth();
    }
  } else {
    showAuth();
  }
}

boot().catch((error) => {
  console.error(error);
  document.body.insertAdjacentHTML("beforeend", `<div class="fatal">${error.message}</div>`);
});
