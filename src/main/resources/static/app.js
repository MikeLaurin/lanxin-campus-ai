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
  makeupDocumentIds: [],
  voiceInputEnabled: true,
  currentAudio: null,
  currentAudioUrl: null
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

  // \[...\] display math (LaTeX standard — multi-line aware)
  html = html.replace(/\\\[([\s\S]*?)\\\]/g, (_, formula) => {
    const idx = mathBlocks.length;
    mathBlocks.push({ type: "display", formula: formula.trim() });
    return MATH_PLACEHOLDER + idx + "%%";
  });

  // \(...\) inline math (LaTeX standard — single line)
  html = html.replace(/\\\((.+?)\\\)/g, (_, formula) => {
    const idx = mathBlocks.length;
    mathBlocks.push({ type: "inline", formula: formula.trim() });
    return MATH_PLACEHOLDER + idx + "%%";
  });

  // $...$ inline math (single line — processed last to avoid conflicting with $$)
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
  renderProfile(user);
}

function profileValue(value, fallback = "未填写") {
  return value && String(value).trim() ? String(value).trim() : fallback;
}

function profileInitial(user) {
  const raw = profileValue(user?.name, user?.username || "蓝");
  const char = Array.from(raw.trim())[0] || "蓝";
  return char.toUpperCase();
}

function renderProfile(user) {
  if (!$("#profileName")) return;
  const name = profileValue(user?.name, "蓝心同学");
  const username = profileValue(user?.username, "demo");
  $("#profileAvatar").textContent = profileInitial(user);
  $("#profileName").textContent = name;
  $("#profileUsername").textContent = "@" + username;
  $("#profileSchool").textContent = profileValue(user?.school);
  $("#profileMajor").textContent = profileValue(user?.major);
  $("#profileGrade").textContent = profileValue(user?.grade);
  $("#profileAccount").textContent = username;
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
const BUBBLE_SIZE = 72;
const BUBBLE_EDGE_OFFSET = 10;
const BUBBLE_BOTTOM_SAFE = 96;
const BUBBLE_TOOLTIP_GAP = 10;
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
    bubblePos.x = window.innerWidth - BUBBLE_SIZE + BUBBLE_EDGE_OFFSET;
    bubblePos.y = window.innerHeight * 0.55;
  }
  bb.style.left = bubblePos.x + "px";
  bb.style.top = bubblePos.y + "px";
  updateBubbleSnap();

  bb.addEventListener("pointerdown", onBubbleDown);
  bb.addEventListener("click", onBubbleClick);
  window.addEventListener("resize", () => {
    bubblePos.x = Math.max(-BUBBLE_EDGE_OFFSET, Math.min(bubblePos.x, window.innerWidth - BUBBLE_SIZE + BUBBLE_EDGE_OFFSET));
    bubblePos.y = Math.max(10, Math.min(bubblePos.y, window.innerHeight - BUBBLE_BOTTOM_SAFE));
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
  bubblePos.x = Math.max(-BUBBLE_EDGE_OFFSET, Math.min(window.innerWidth - BUBBLE_SIZE + BUBBLE_EDGE_OFFSET, bubblePos.x));
  bubblePos.y = Math.max(10, Math.min(window.innerHeight - BUBBLE_BOTTOM_SAFE, bubblePos.y));
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
  const cx = bubblePos.x + BUBBLE_SIZE / 2;
  bb.classList.remove("snap-left", "snap-right");
  if (cx < window.innerWidth / 2) {
    bubblePos.x = -BUBBLE_EDGE_OFFSET;
    bb.classList.add("snap-right");
  } else {
    bubblePos.x = window.innerWidth - BUBBLE_SIZE + BUBBLE_EDGE_OFFSET;
    bb.classList.add("snap-left");
  }
  bb.style.left = bubblePos.x + "px";
  updateTooltip();
}

function updateTooltip() {
  const tt = tooltip();
  if (!tt) return;
  const cx = bubblePos.x + BUBBLE_SIZE / 2;
  if (cx < window.innerWidth / 2) {
    tt.style.left = (bubblePos.x + BUBBLE_SIZE + BUBBLE_TOOLTIP_GAP) + "px";
    tt.style.top = (bubblePos.y + 12) + "px";
    tt.style.transform = "";
    tt.classList.remove("point-left");
    tt.classList.add("point-right");
  } else {
    tt.style.left = (bubblePos.x - BUBBLE_TOOLTIP_GAP) + "px";
    tt.style.top = (bubblePos.y + 12) + "px";
    tt.classList.remove("point-right");
    tt.classList.add("point-left");
    tt.style.transform = "translateX(-100%)";
  }
}

// ── AudioManager ─────────────────────────────────────
const AudioManager = {
  isRecording: false,
  _mode: null,            // 'speech' | 'media'
  _speechRecognition: null,
  _mediaRecorder: null,
  _audioChunks: [],
  _stream: null,
  _recordingStartTime: 0,
  _timerInterval: null,
  _currentCallback: null,
  _processing: false,

  // ── Recording ─────────────────────────────────────

  async startRecording(onResult) {
    if (this.isRecording || this._processing) return;
    this._currentCallback = onResult;

    // Prefer browser SpeechRecognition — fast, no backend STT dependency.
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (SpeechRecognition) {
      this._startSpeechRecognition();
    } else {
      // Firefox etc.: fall back to MediaRecorder → server upload
      try {
        await this._startMediaRecording();
      } catch (err) {
        console.warn('MediaRecorder unavailable:', err.message);
        showToast('您的浏览器不支持语音输入，请使用 Chrome 或 Edge');
      }
    }
  },

  // ── Primary: browser SpeechRecognition ────────────

  _startSpeechRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    try {
      const recognition = new SpeechRecognition();
      recognition.lang = 'zh-CN';
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;
      this._speechRecognition = recognition;
      this._mode = 'speech';

      this.isRecording = true;
      this._recordingStartTime = Date.now();
      this._showRecordingUI();
      this._startTimer();

      recognition.onresult = (event) => {
        const text = event.results[0][0].transcript;
        this._cleanupSpeech();
        if (text && this._currentCallback) {
          this._currentCallback(text);
        } else {
          showToast('没有听清，请再说一遍');
        }
        this._currentCallback = null;
      };

      recognition.onerror = (event) => {
        console.error('SpeechRecognition error:', event.error);
        if (event.error === 'not-allowed') {
          showToast('请允许麦克风权限后重试');
        } else if (event.error === 'no-speech') {
          showToast('没有检测到语音，请再说一遍');
        } else if (event.error !== 'aborted') {
          showToast('语音识别出错: ' + event.error);
        }
        this._cleanupSpeech();
        this._currentCallback = null;
      };

      recognition.onend = () => {
        // If onresult didn't fire (e.g. user stopped manually), clean up
        if (this._mode === 'speech') {
          this._cleanupSpeech();
          this._currentCallback = null;
        }
      };

      recognition.start();
    } catch (err) {
      console.error('SpeechRecognition init failed:', err);
      this._cleanupSpeech();
      showToast('语音输入不可用，请检查浏览器权限');
    }
  },

  _cleanupSpeech() {
    this._mode = null;
    this.isRecording = false;
    this._stopTimer();
    this._hideRecordingUI();
    this._speechRecognition = null;
  },

  // ── Fallback: MediaRecorder → server STT ──────────

  async _startMediaRecording() {
    this._stream = await navigator.mediaDevices.getUserMedia({ audio: true });

    let mimeType = 'audio/webm';
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
      mimeType = 'audio/webm;codecs=opus';
    } else if (MediaRecorder.isTypeSupported('audio/mp4')) {
      mimeType = 'audio/mp4';
    }

    this._audioChunks = [];
    this._mediaRecorder = new MediaRecorder(this._stream, { mimeType });
    this._mode = 'media';

    this._mediaRecorder.ondataavailable = (e) => {
      if (e.data.size > 0) this._audioChunks.push(e.data);
    };

    this._mediaRecorder.onstop = () => {
      this._processRecording();
    };

    this._mediaRecorder.start(100);
    this.isRecording = true;
    this._recordingStartTime = Date.now();
    this._showRecordingUI();
    this._startTimer();
  },

  _cleanupMediaStream() {
    if (this._stream) {
      this._stream.getTracks().forEach(t => t.stop());
      this._stream = null;
    }
  },

  // ── Stop ──────────────────────────────────────────

  stopRecording() {
    if (!this.isRecording) return;

    if (this._mode === 'speech' && this._speechRecognition) {
      this._speechRecognition.stop();
      // cleanupSpeech will be called in onend/onerror
      return;
    }

    if (this._mode === 'media') {
      this.isRecording = false;
      this._processing = true;
      this._stopTimer();
      this._hideRecordingUI();

      document.querySelectorAll('.voice-btn.recording').forEach(btn => {
        btn.classList.remove('recording');
        btn.classList.add('processing');
      });

      if (this._mediaRecorder && this._mediaRecorder.state !== 'inactive') {
        this._mediaRecorder.stop();
      }
      this._cleanupMediaStream();
    }
  },

  async _processRecording() {
    const mime = this._mediaRecorder ? this._mediaRecorder.mimeType : 'audio/webm';
    const ext = (mime && mime.includes('mp4')) ? 'mp4' : 'webm';
    const blob = new Blob(this._audioChunks, { type: mime || 'audio/webm' });

    try {
      const text = await this._uploadAndTranscribe(blob, ext);
      this._finishProcessing();
      if (text && this._currentCallback) {
        this._currentCallback(text);
      } else {
        showToast('没有听清，请再说一遍');
      }
    } catch (err) {
      console.error('AudioManager: server STT failed', err);
      this._finishProcessing();
      showToast('语音识别失败，请检查网络后重试');
    }
    this._currentCallback = null;
  },

  async _uploadAndTranscribe(blob, ext) {
    const formData = new FormData();
    formData.append('file', blob, 'recording.' + ext);

    const headers = {};
    if (state.token) headers['Authorization'] = 'Bearer ' + state.token;

    const response = await fetch('/api/v1/ai/speech-to-text', {
      method: 'POST',
      headers,
      body: formData
    });

    if (response.status === 401) {
      if (await refreshAccessToken()) return this._uploadAndTranscribe(blob, ext);
      showAuth();
      throw new Error('登录已过期');
    }

    if (!response.ok) {
      const errBody = await response.json().catch(() => ({}));
      throw new Error(errBody.error || errBody.message || '语音识别失败');
    }

    const data = await response.json();
    return data.text || '';
  },

  _finishProcessing() {
    this._mode = null;
    this._processing = false;
    this._audioChunks = [];
    this._mediaRecorder = null;
    document.querySelectorAll('.voice-btn.processing').forEach(btn => {
      btn.classList.remove('processing');
    });
  },

  // ── Timer / UI ────────────────────────────────────

  _startTimer() {
    const timer = document.getElementById('recTimer');
    if (timer) timer.textContent = '0:00';
    this._timerInterval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - this._recordingStartTime) / 1000);
      const mins = Math.floor(elapsed / 60);
      const secs = elapsed % 60;
      if (timer) timer.textContent = mins + ':' + String(secs).padStart(2, '0');
    }, 200);
  },

  _stopTimer() {
    if (this._timerInterval) { clearInterval(this._timerInterval); this._timerInterval = null; }
  },

  _showRecordingUI() {
    const bar = document.getElementById('recordingBar');
    if (bar) bar.classList.add('show');
    document.querySelectorAll('.voice-btn').forEach(b => b.classList.add('recording'));
  },

  _hideRecordingUI() {
    const bar = document.getElementById('recordingBar');
    if (bar) bar.classList.remove('show');
    document.querySelectorAll('.voice-btn.recording').forEach(b => b.classList.remove('recording'));
  },

  // ── TTS (Text-to-Speech) ──────────────────────────

  async playTts(text, buttonEl) {
    if (this._currentAudioObj) { this.stopTts(); return; }
    if (buttonEl) buttonEl.classList.add('loading');

    try {
      const audioUrl = await this._fetchTtsAudio(text);
      if (audioUrl) {
        const audio = new Audio(audioUrl);
        this._currentAudioObj = audio;
        audio.onended = () => { this._clearAudio(); if (buttonEl) buttonEl.classList.remove('playing'); };
        audio.onerror = () => { this._clearAudio(); if (buttonEl) buttonEl.classList.remove('playing'); this._browserTtsFallback(text, buttonEl); };
        if (buttonEl) { buttonEl.classList.remove('loading'); buttonEl.classList.add('playing'); }
        await audio.play();
      } else {
        if (buttonEl) buttonEl.classList.remove('loading');
        this._browserTtsFallback(text, buttonEl);
      }
    } catch (err) {
      console.warn('Server TTS failed:', err.message);
      if (buttonEl) buttonEl.classList.remove('loading');
      this._browserTtsFallback(text, buttonEl);
    }
  },

  async _fetchTtsAudio(text) {
    const headers = { 'Content-Type': 'application/json' };
    if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
    const response = await fetch('/api/v1/ai/text-to-speech', {
      method: 'POST', headers,
      body: JSON.stringify({ text: text.substring(0, 2000), voice: 'alloy' })
    });
    if (response.status === 401) {
      if (await refreshAccessToken()) return this._fetchTtsAudio(text);
      showAuth(); return null;
    }
    if (!response.ok || response.status === 204) return null;
    const blob = await response.blob();
    if (blob.size === 0) return null;
    return URL.createObjectURL(blob);
  },

  _browserTtsFallback(text, buttonEl) {
    if (!('speechSynthesis' in window)) { showToast('浏览器不支持语音朗读'); return; }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    utterance.rate = 1.0;
    utterance.pitch = 1.0;
    const voices = window.speechSynthesis.getVoices();
    const zh = voices.find(v => v.lang.startsWith('zh-CN') || v.lang.startsWith('zh-TW') || v.lang.startsWith('zh'));
    if (zh) utterance.voice = zh;
    if (buttonEl) { buttonEl.classList.remove('loading'); buttonEl.classList.add('playing'); }
    utterance.onend = () => { this._clearBrowserUtterance(); if (buttonEl) buttonEl.classList.remove('playing'); };
    utterance.onerror = () => { this._clearBrowserUtterance(); if (buttonEl) buttonEl.classList.remove('playing'); };
    this._browserUtterance = utterance;
    window.speechSynthesis.speak(utterance);
  },

  stopTts() {
    this._clearAudio();
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    this._clearBrowserUtterance();
    state.currentAudio = null;
    state.currentAudioUrl = null;
  },

  _clearAudio() {
    if (this._currentAudioObj) { this._currentAudioObj.pause(); this._currentAudioObj.src = ''; this._currentAudioObj = null; }
  },

  _clearBrowserUtterance() {
    this._browserUtterance = null;
  }
};

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
    // Add TTS play button if TTS toggle is on
    if ($("#ttsToggle") && $("#ttsToggle").checked) {
      const playBtn = document.createElement('button');
      playBtn.className = 'audio-play-btn';
      playBtn.innerHTML = '<span>🔊</span> 朗读回复';
      playBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        if (playBtn.classList.contains('playing')) {
          AudioManager.stopTts();
          playBtn.classList.remove('playing');
        } else {
          AudioManager.playTts(fullText, playBtn);
        }
      });
      msgDiv.appendChild(playBtn);
    }
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

function flattenFolderTree(nodes, prefix) {
  prefix = prefix || "";
  let paths = [];
  for (const node of (nodes || [])) {
    const path = prefix ? prefix + "/" + node.name : node.name;
    paths.push(path);
    if (node.children && node.children.length > 0) {
      paths = paths.concat(flattenFolderTree(node.children, path));
    }
  }
  return paths;
}

async function populateEditNoteFolder(selectedPath) {
  const select = $("#editNoteFolderSelect");
  const customInput = $("#editNoteFolderCustom");
  try {
    const folders = await api("/api/v1/notes/folders");
    const paths = flattenFolderTree(folders);
    select.innerHTML = '<option value="">📂 （根目录）</option>';
    paths.forEach(function (p) {
      select.innerHTML += '<option value="' + escapeHtml(p) + '">📁 ' + escapeHtml(p) + '</option>';
    });
    select.innerHTML += '<option value="__custom__">✏️ 自定义路径...</option>';

    if (selectedPath) {
      if (paths.includes(selectedPath)) {
        select.value = selectedPath;
        customInput.style.display = "none";
      } else {
        select.value = "__custom__";
        customInput.style.display = "";
        customInput.value = selectedPath;
      }
    } else {
      select.value = "";
      customInput.style.display = "none";
    }
  } catch (err) {
    select.innerHTML = '<option value="">📂 （根目录）</option><option value="__custom__">✏️ 自定义路径...</option>';
    if (selectedPath) {
      select.value = "__custom__";
      customInput.style.display = "";
      customInput.value = selectedPath;
    }
  }
}

async function openNoteEditor(noteId) {
  try {
    const note = await api(`/api/v1/notes/${noteId}`);
    state.editingNoteId = noteId;
    $("#editNoteTitle").value = note.title || "";
    $("#editNoteCourse").value = note.course || "";
    await populateEditNoteFolder(note.folderPath || "");
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
  const folderSelect = $("#editNoteFolderSelect");
  const folderCustom = $("#editNoteFolderCustom");
  let folderPath = folderSelect.value === "__custom__" ? folderCustom.value.trim() : folderSelect.value;
  folderPath = folderPath || "";
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
  const totalPending = (data.overdueReminderCount || 0) + (data.openReminderCount || 0);
  $("#statsGrid").innerHTML = [
    ["笔记", data.noteCount, ""],
    ["待办", totalPending, ""],
    ["连续学习", data.studyDays, "天"]
  ].map(([label, value, suffix]) =>
    `<div class="stat"><strong class="count-target">${value}${suffix}</strong><span>${label}</span></div>`
  ).join("");
  setTimeout(() => {
    const items = [
      { el: $$(".stat .count-target")[0], val: data.noteCount, s: "" },
      { el: $$(".stat .count-target")[1], val: totalPending, s: "" },
      { el: $$(".stat .count-target")[2], val: data.studyDays, s: "天" }
    ];
    items.forEach(({ el, val, s }) => { if (el) animateCounter(el, val, s); });
  }, 100);
}

function reminderItem(reminder) {
  const dueDate = reminder.dueDate || "";
  const today = new Date().toISOString().substring(0, 10);
  const isOverdue = dueDate && dueDate < today;
  return `
    <article class="list-item${isOverdue ? " overdue" : ""}">
      <h3>${escapeHtml(reminder.title || "")}</h3>
      <p>${reminder.course ? escapeHtml(reminder.course) + " · " : ""}截止 ${escapeHtml(dueDate || "未指定")}${isOverdue ? " <span class=\"due-overdue-label\">（已过期）</span>" : ""}</p>
      <div class="meta-row">
        <span class="tag ${reminder.priority}">${priorityLabel(reminder.priority)}</span>
        ${reminder.source ? `<span class="tag">${escapeHtml(reminder.source.replace("AI 解析：", "").substring(0, 40))}</span>` : ""}
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
  renderNavBadge(stats);
  renderOverdueWarning(stats.overdueReminderCount || 0);
  $("#todayList").innerHTML = today.length ? today.map(reminderItem).join("") : empty("今天没有临近 DDL");
  $("#recentNotes").innerHTML = notes.slice(0, 3).map(noteItem).join("");
}

function renderNavBadge(stats) {
  const badge = $("#navDdlBadge");
  if (!badge) return;
  const totalPending = (stats.overdueReminderCount || 0) + (stats.urgentReminderCount || 0);
  if (totalPending > 0) {
    badge.textContent = totalPending > 99 ? "99+" : totalPending;
    badge.style.display = "";
  } else {
    badge.style.display = "none";
  }
}

function renderOverdueWarning(count) {
  const warning = $("#homeOverdueWarning");
  if (!warning) return;
  if (count > 0) {
    $("#homeOverdueText").textContent = `你有 ${count} 个已过期的 DDL，请及时处理`;
    warning.style.display = "flex";
  } else {
    warning.style.display = "none";
  }
}

// ── DDL ───────────────────────────────────────────────

let ddlTab = "incomplete";
let ddlFilter = "all";
let ddlCompletedPage = 0;

function switchDdlTab(tab) {
  ddlTab = tab;
  ddlCompletedPage = 0;
  ddlFilter = "all";
  $$(".ddl-tab").forEach(t => t.classList.toggle("active", t.dataset.ddlTab === tab));
  // Show/hide filter bar (only for incomplete)
  const filterBar = $("#ddlFilterBar");
  if (filterBar) filterBar.style.display = tab === "incomplete" ? "flex" : "none";
  $$(".ddl-filter-btn").forEach(b => b.classList.toggle("active", b.dataset.filter === "all"));
  loadTasks();
}

function switchDdlFilter(filter) {
  ddlFilter = filter;
  $$(".ddl-filter-btn").forEach(b => b.classList.toggle("active", b.dataset.filter === filter));
  loadTasks();
}

async function loadTasks(page = 0) {
  try {
    if (ddlTab === "completed") {
      ddlCompletedPage = page;
      const tasks = await api(`/api/v1/reminders?includeCompleted=true&page=${page}&size=20`);
      const completed = tasks.filter(t => t.completed);
      if (completed.length === 0) {
        $("#taskList").innerHTML = empty(page === 0 ? "暂无已完成的 DDL" : "没有更多了");
        $("#taskLoadMore").style.display = "none";
        return;
      }
      $("#taskList").innerHTML = completed.map(ddlItem).join("");
      $("#taskLoadMore").style.display = completed.length >= 20 ? "" : "none";
    } else {
      const tasks = await api("/api/v1/reminders");
      let filtered = tasks.filter(t => !t.completed);
      const today = new Date().toISOString().substring(0, 10);

      // Apply filter
      if (ddlFilter === "high") {
        filtered = filtered.filter(t => t.priority === "high");
      } else if (ddlFilter === "week") {
        const weekEnd = new Date(Date.now() + 7 * 86400000).toISOString().substring(0, 10);
        filtered = filtered.filter(t => t.dueDate && t.dueDate >= today && t.dueDate <= weekEnd);
      } else if (ddlFilter === "overdue") {
        filtered = filtered.filter(t => t.dueDate && t.dueDate < today);
      }

      if (filtered.length === 0) {
        const emptyMsgs = {
          all: "暂无待办的 DDL，真棒！",
          high: "没有高优先级 DDL",
          week: "本周没有截止的 DDL",
          overdue: "没有已过期的 DDL，继续保持！"
        };
        $("#taskList").innerHTML = empty(emptyMsgs[ddlFilter] || "暂无 DDL");
        $("#taskLoadMore").style.display = "none";
        return;
      }

      // Sort: overdue first, then by due date
      filtered.sort((a, b) => {
        const aOverdue = a.dueDate && a.dueDate < today ? 0 : 1;
        const bOverdue = b.dueDate && b.dueDate < today ? 0 : 1;
        if (aOverdue !== bOverdue) return aOverdue - bOverdue;
        return (a.dueDate || "").localeCompare(b.dueDate || "");
      });

      $("#taskList").innerHTML = filtered.map(ddlItem).join("");
      $("#taskLoadMore").style.display = "none";
    }

    // Bind DDL action buttons
    $$(".ddl-complete-btn").forEach(btn => {
      btn.addEventListener("click", (e) => { e.stopPropagation(); completeReminder(parseInt(btn.dataset.reminderId)); });
    });
    $$(".ddl-delete-btn").forEach(btn => {
      btn.addEventListener("click", (e) => { e.stopPropagation(); deleteReminder(parseInt(btn.dataset.reminderId), btn); });
    });
    $$(".ddl-uncomplete-btn").forEach(btn => {
      btn.addEventListener("click", (e) => { e.stopPropagation(); uncompleteReminder(parseInt(btn.dataset.reminderId)); });
    });
    $$(".ddl-edit-btn").forEach(btn => {
      btn.addEventListener("click", (e) => { e.stopPropagation(); openEditDdl(parseInt(btn.dataset.reminderId)); });
    });
    // Card click to expand
    $$(".ddl-card").forEach(card => {
      card.addEventListener("click", (e) => {
        if (e.target.closest("button")) return;
        card.classList.toggle("expanded");
      });
    });
  } catch (err) { console.error("Load tasks failed:", err); }
}

function formatDueDate(dateStr) {
  if (!dateStr) return '<span class="due-badge">未指定日期</span>';
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const due = new Date(dateStr + "T00:00:00");
  const diffDays = Math.ceil((due - today) / (1000 * 60 * 60 * 24));
  if (diffDays < 0) return `<span class="due-badge overdue">已过期 ${Math.abs(diffDays)} 天</span>`;
  if (diffDays === 0) return `<span class="due-badge today">今天截止</span>`;
  if (diffDays === 1) return `<span class="due-badge tomorrow">明天截止</span>`;
  return `<span class="due-badge upcoming">${dateStr}（${diffDays} 天后）</span>`;
}

function ddlItem(reminder) {
  const completed = reminder.completed;
  const dueDateHtml = formatDueDate(reminder.dueDate);
  const priorityIcon = { high: "🔴", medium: "🟡", low: "🟢" }[reminder.priority] || "🟡";
  const categoryHtml = reminder.course
    ? `<span class="ddl-category">${escapeHtml(reminder.course)}</span>`
    : "";
  const completedInfo = completed && reminder.completedAt
    ? `<span class="ddl-completed-at">完成于 ${(reminder.completedAt || "").substring(0, 10)}</span>`
    : "";
  const isOverdue = reminder.dueDate && reminder.dueDate < new Date().toISOString().substring(0, 10) && !completed;
  const sourceText = (reminder.source || "").replace("AI 解析：", "");
  const sourceFull = sourceText.length > 60 ? sourceText : "";

  return `
    <article class="ddl-card${completed ? " completed" : ""}${isOverdue ? " overdue" : ""}">
      <div class="ddl-card-main">
        <div class="ddl-card-header">
          <span class="ddl-priority-icon">${priorityIcon}</span>
          <h3 class="ddl-title">${escapeHtml(reminder.title || "")}</h3>
        </div>
        <div class="ddl-card-meta">
          ${dueDateHtml}
          ${categoryHtml}
          ${completedInfo}
          <span class="tag ${reminder.priority}">${priorityLabel(reminder.priority)}</span>
          ${sourceText ? `<span class="ddl-source">${escapeHtml(sourceText.substring(0, 60))}${sourceText.length > 60 ? "..." : ""}</span>` : ""}
        </div>
        ${sourceFull ? `<div class="ddl-card-expand"><p>${escapeHtml(sourceFull)}</p></div>` : ""}
      </div>
      <div class="ddl-card-actions">
        ${!completed ? `<button class="ddl-edit-btn" data-reminder-id="${reminder.id}" title="编辑">✎</button>` : ""}
        ${completed
          ? `<button class="ddl-uncomplete-btn" data-reminder-id="${reminder.id}" title="恢复为未完成">↩</button>`
          : `<button class="ddl-complete-btn" data-reminder-id="${reminder.id}" title="标记为完成">✓</button>`
        }
        <button class="ddl-delete-btn" data-reminder-id="${reminder.id}" title="删除">✕</button>
      </div>
    </article>
  `;
}

// ── DDL Actions ─────────────────────────────────────

async function completeReminder(id) {
  try {
    await api(`/api/v1/reminders/${id}/complete`, { method: "PUT" });
    showUndoToast("DDL 已标记为完成", async () => {
      await api(`/api/v1/reminders/${id}/uncomplete`, { method: "PUT" });
      await refreshAll();
    });
    await refreshAll();
  } catch (err) { showToast("操作失败：" + err.message); }
}

async function uncompleteReminder(id) {
  try {
    await api(`/api/v1/reminders/${id}/uncomplete`, { method: "PUT" });
    showToast("DDL 已恢复为未完成");
    await refreshAll();
  } catch (err) { showToast("操作失败：" + err.message); }
}

async function deleteReminder(id, btn) {
  if (btn && btn.dataset.confirming !== "true") {
    btn.dataset.confirming = "true";
    btn.textContent = "确认";
    btn.style.color = "var(--danger)";
    clearTimeout(btn._confirmTimer);
    btn._confirmTimer = setTimeout(() => {
      btn.dataset.confirming = "";
      btn.textContent = "✕";
      btn.style.color = "";
    }, 3000);
    return;
  }
  if (btn) clearTimeout(btn._confirmTimer);
  try {
    await api(`/api/v1/reminders/${id}`, { method: "DELETE" });
    showUndoToast("DDL 已删除", async () => {
      // Can't really undo delete easily, but we keep the toast pattern
      showToast("请重新创建该 DDL");
    });
    await refreshAll();
  } catch (err) { showToast("删除失败：" + err.message); }
}

function showUndoToast(message, onUndo) {
  let toast = $("#appToastUndo");
  if (!toast) {
    toast = document.createElement("div");
    toast.id = "appToastUndo";
    toast.className = "app-toast undo-toast";
    document.body.appendChild(toast);
  }
  toast.innerHTML = `${message} <button class="toast-undo-btn" id="toastUndoBtn">撤销</button>`;
  toast.classList.add("show");
  clearTimeout(showUndoToast.timer);
  const undoBtn = $("#toastUndoBtn");
  if (undoBtn) {
    undoBtn.addEventListener("click", () => {
      clearTimeout(showUndoToast.timer);
      toast.classList.remove("show");
      if (onUndo) onUndo();
    });
  }
  showUndoToast.timer = setTimeout(() => toast.classList.remove("show"), 5000);
}

async function refreshAll() {
  await Promise.all([loadTasks(), loadDashboard()]);
}

// ── Manual DDL ──────────────────────────────────────

let editingDdlId = null;

function openManualDdl(id) {
  editingDdlId = id || null;
  const overlay = $("#manualDdlOverlay");
  if (id) {
    $("#manualDdlModalTitle").textContent = "编辑 DDL";
    loadDdlForEdit(id);
  } else {
    $("#manualDdlModalTitle").textContent = "手动添加 DDL";
    $("#manualDdlTitle").value = "";
    $("#manualDdlCategory").value = "";
    $("#manualDdlDate").value = new Date(Date.now() + 86400000).toISOString().substring(0, 10);
    $("#manualDdlPriority").value = "medium";
    $("#manualDdlNote").value = "";
  }
  overlay.classList.add("open");
}

function closeManualDdl() {
  $("#manualDdlOverlay").classList.remove("open");
  editingDdlId = null;
}

async function loadDdlForEdit(id) {
  try {
    const tasks = await api("/api/v1/reminders?includeCompleted=true");
    const task = tasks.find(t => t.id === id);
    if (!task) { showToast("DDL 未找到"); return; }
    $("#manualDdlTitle").value = task.title || "";
    $("#manualDdlDate").value = task.dueDate || "";
    $("#manualDdlPriority").value = task.priority || "medium";
    $("#manualDdlCategory").value = task.course || "";
    $("#manualDdlNote").value = (task.source || "").replace("AI 解析：", "").replace("手动创建", "");
  } catch (err) { showToast("加载失败：" + err.message); }
}

async function saveManualDdl() {
  const title = $("#manualDdlTitle").value?.trim();
  if (!title) { showToast("请输入标题"); return; }
  const course = $("#manualDdlCategory").value.trim();
  const dueDate = $("#manualDdlDate").value || null;
  const priority = $("#manualDdlPriority").value || "medium";
  const source = $("#manualDdlNote").value.trim() || "手动创建";

  const btn = $("#saveManualDdl");
  await withButtonLoading(btn, "保存中...", async () => {
    try {
      const body = { title, course, dueDate, priority, source };
      if (editingDdlId) {
        await api(`/api/v1/reminders/${editingDdlId}`, { method: "PUT", body: JSON.stringify(body) });
        showToast("DDL 已更新");
      } else {
        await api("/api/v1/reminders", { method: "POST", body: JSON.stringify(body) });
        showToast("DDL 已创建");
      }
      closeManualDdl();
      await refreshAll();
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

async function openEditDdl(id) {
  openManualDdl(id);
}

// ── AI Parse with Preview ────────────────────────────

let pendingParseResults = [];

async function parseDdl(event) {
  const button = event?.currentTarget || $("#parseDdl");
  if (state.pending.has("parse-ddl")) return;
  const text = $("#ddlInput").value.trim();
  if (!text) { showToast("请输入 DDL 文本"); return; }
  await withButtonLoading(button, "AI 解析中...", async () => {
    state.pending.add("parse-ddl");
    try {
      // Try batch parse first
      let results;
      try {
        results = await api("/api/v1/reminders/parse-preview", { method: "POST", body: JSON.stringify({ text }) });
      } catch (e) {
        // Fallback to single parse
        const single = await api("/api/v1/reminders/parse", { method: "POST", body: JSON.stringify({ text }) });
        results = [single];
      }
      if (!results || results.length === 0) {
        showToast("未能解析出 DDL，请尝试更明确的描述");
        return;
      }
      pendingParseResults = results;
      renderParsePreview(results);
      $("#parsePreviewBlock").style.display = "";
    } catch (err) { showToast("解析失败：" + err.message); }
    finally { state.pending.delete("parse-ddl"); }
  });
}

function renderParsePreview(results) {
  $("#parsePreviewList").innerHTML = results.map((r, i) => {
    const dueDateHtml = formatDueDate(r.dueDate);
    const priorityIcon = { high: "🔴", medium: "🟡", low: "🟢" }[r.priority] || "🟡";
    return `
      <article class="ddl-card parse-preview-item">
        <div class="ddl-card-main">
          <div class="ddl-card-header">
            <span class="ddl-priority-icon">${priorityIcon}</span>
            <h3 class="ddl-title">${escapeHtml(r.title || "")}</h3>
          </div>
          <div class="ddl-card-meta">
            ${dueDateHtml}
            ${r.course ? `<span class="ddl-category">${escapeHtml(r.course)}</span>` : ""}
            <span class="tag ${r.priority}">${priorityLabel(r.priority)}</span>
            ${r.source ? `<span class="ddl-source">${escapeHtml((r.source || "").replace("AI 解析：", "").substring(0, 50))}</span>` : ""}
          </div>
        </div>
        <button class="ddl-delete-btn parse-remove-btn" data-parse-idx="${i}" title="移除此项">✕</button>
      </article>
    `;
  }).join("");

  // Bind remove buttons
  $$(".parse-remove-btn").forEach(btn => {
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      const idx = parseInt(btn.dataset.parseIdx);
      pendingParseResults.splice(idx, 1);
      if (pendingParseResults.length === 0) {
        $("#parsePreviewBlock").style.display = "none";
      } else {
        renderParsePreview(pendingParseResults);
      }
    });
  });
}

async function confirmParseResults() {
  if (pendingParseResults.length === 0) return;
  const btn = $("#confirmParseResults");
  await withButtonLoading(btn, "保存中...", async () => {
    try {
      const requests = pendingParseResults.map(r => ({
        title: r.title,
        course: r.course || "",
        dueDate: r.dueDate,
        priority: r.priority || "medium",
        source: r.source || ""
      }));
      await api("/api/v1/reminders/batch-save", { method: "POST", body: JSON.stringify(requests) });
      showToast(`已保存 ${requests.length} 个 DDL`);
      $("#parsePreviewBlock").style.display = "none";
      pendingParseResults = [];
      $("#ddlInput").value = "";
      ddlTab = "incomplete";
      $$(".ddl-tab").forEach(t => t.classList.toggle("active", t.dataset.ddlTab === "incomplete"));
      await refreshAll();
    } catch (err) { showToast("保存失败：" + err.message); }
  });
}

function cancelParseResults() {
  $("#parsePreviewBlock").style.display = "none";
  pendingParseResults = [];
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
  $("#editNoteFolderSelect").addEventListener("change", function () {
    $("#editNoteFolderCustom").style.display = this.value === "__custom__" ? "" : "none";
  });

  // DDL
  $("#parseDdl").addEventListener("click", parseDdl);
  $("#ddlInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      parseDdl();
    }
  });
  $("#ddlInput").addEventListener("input", () => {
    const len = $("#ddlInput").value.length;
    const counter = $("#ddlCharCount");
    if (counter) counter.textContent = `${len} / 200`;
  });
  $("#clearDdlInput").addEventListener("click", () => {
    $("#ddlInput").value = "";
    $("#ddlInput").focus();
    const counter = $("#ddlCharCount");
    if (counter) counter.textContent = "0 / 200";
  });

  // DDL tabs
  $$(".ddl-tab").forEach(tab => {
    tab.addEventListener("click", () => switchDdlTab(tab.dataset.ddlTab));
  });

  // DDL filter buttons
  $$(".ddl-filter-btn").forEach(btn => {
    btn.addEventListener("click", () => switchDdlFilter(btn.dataset.filter));
  });

  // Parse preview
  $("#confirmParseResults").addEventListener("click", confirmParseResults);
  $("#cancelParseResults").addEventListener("click", cancelParseResults);

  // Load more completed
  $("#loadMoreTasks").addEventListener("click", () => loadTasks(ddlCompletedPage + 1));

  // Manual DDL
  $("#openManualDdl").addEventListener("click", () => openManualDdl(null));
  $("#closeManualDdl").addEventListener("click", closeManualDdl);
  $("#cancelManualDdl").addEventListener("click", closeManualDdl);
  $("#manualDdlOverlay").addEventListener("click", (e) => {
    if (e.target === $("#manualDdlOverlay")) closeManualDdl();
  });
  $("#saveManualDdl").addEventListener("click", saveManualDdl);

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

  // ── Voice / Speech event bindings ─────────────────────

  // Chat mic button — voice input for chat (auto-send after transcript)
  const voiceChatBtn = $("#voiceChatBtn");
  if (voiceChatBtn) {
    voiceChatBtn.addEventListener("click", () => {
      if (AudioManager.isRecording) {
        AudioManager.stopRecording();
      } else {
        AudioManager.startRecording((text) => {
          const chatInput = $("#chatInput");
          if (chatInput) {
            chatInput.value = text;
            sendRagChat();
          }
        });
      }
    });
  }

  // DDL voice button — fill textarea directly
  const voiceDdlBtn = $("#voiceDdlBtn");
  if (voiceDdlBtn) {
    voiceDdlBtn.addEventListener("click", () => {
      if (AudioManager.isRecording) {
        AudioManager.stopRecording();
      } else {
        AudioManager.startRecording((text) => {
          const ddlInput = $("#ddlInput");
          if (ddlInput) {
            const existing = ddlInput.value.trim();
            ddlInput.value = existing ? existing + '\n' + text : text;
            ddlInput.dispatchEvent(new Event('input', { bubbles: true }));
          }
        });
      }
    });
  }

  // Note voice button — fill textarea
  const voiceNoteBtn = $("#voiceNoteBtn");
  if (voiceNoteBtn) {
    voiceNoteBtn.addEventListener("click", () => {
      if (AudioManager.isRecording) {
        AudioManager.stopRecording();
      } else {
        AudioManager.startRecording((text) => {
          const noteContent = $("#newNoteContent");
          if (noteContent) {
            const existing = noteContent.value.trim();
            noteContent.value = existing ? existing + '\n' + text : text;
          }
          const preview = $("#noteVoicePreview");
          if (preview) { preview.textContent = '🎙️ ' + text; preview.style.display = 'block'; }
        });
      }
    });
  }

  // Stop recording bar button
  const stopBarBtn = $("#stopRecordingBar");
  if (stopBarBtn) {
    stopBarBtn.addEventListener("click", () => AudioManager.stopRecording());
  }

  // Global Escape to stop recording
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && AudioManager.isRecording) {
      AudioManager.stopRecording();
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
