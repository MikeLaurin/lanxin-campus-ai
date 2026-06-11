# 蓝心校园 AI 管家 — 项目说明书

## 技术栈
- **后端**: Spring Boot 3.4.1 + Java 17 + Maven
- **前端**: 原生 HTML/CSS/JS（无框架），单页面应用
- **构建/运行**: `mvn spring-boot:run`
- **数据库**: 内存 Map 模拟（无真实 DB）

## 项目结构

```
src/main/java/com/vivo/lanxin/campus/
├── CampusAiApplication.java          # Spring Boot 入口
├── model/                            # 数据模型
│   ├── UserEntity.java               # 用户
│   ├── Note.java                     # 笔记（支持 folderPath 树形结构）
│   ├── Reminder.java                 # DDL 待办
│   └── Document.java                 # 知识库文档
├── repository/                       # 数据仓库（内存 Map 存储）
├── service/
│   ├── AuthService.java              # 认证 + JWT
│   ├── JwtService.java               # JWT 生成/验证
│   ├── RagService.java               # RAG 知识库
│   ├── LanxinApiClient.java          # 调用蓝心大模型 API
│   └── AiMockService.java            # AI Mock（离线演示）
└── web/                              # Controller + DTO
    ├── AuthController.java           # /api/v1/user/*
    ├── NoteController.java           # /api/v1/notes/*
    ├── ReminderController.java       # /api/v1/reminders/*
    ├── AiController.java             # /api/v1/ai/* (chat, STT, TTS)
    ├── RagController.java            # /api/v1/rag/*
    └── ReportController.java         # /api/v1/reports/*

src/main/resources/static/
├── index.html                        # 主页面（~600行）
├── styles.css                        # 全部样式（~3700行，CSS变量主题）
└── app.js                            # 全部逻辑（~2800行，全局 state 对象）
```

## 前端架构要点

### 全局状态 (`app.js` 顶部 `state` 对象)
```js
state = { currentPanel, currentFolder, notesTab, token, refreshToken,
          pending, user, editingNoteId, createMode, selectedImageFile, ... }
```

### 工具函数
- `$(selector)` — `document.querySelector` 简写
- `$$()` — `querySelectorAll` 简写
- `api(path, options)` — 统一 API 调用，自动附带 JWT，401 时自动刷新 token
- `showToast(msg)` — 顶部弹窗提示
- `renderMarkdown(text)` — 渲染 Markdown + KaTeX 数学公式
- `escapeHtml(text)` — HTML 转义

### 页面导航
4 个 Tab 面板：`home` / `notes` / `tasks` / `report`，底部导航栏切换。
`navTo(panel)` 切换面板，`switchNotesTab(tab)` 切换笔记子面板。

### 主题系统
CSS 变量定义在 `:root`，深色模式通过 `[data-theme="dark"]` 覆盖。
JS 中 `initTheme()` / `toggleTheme()` 控制，偏好存 localStorage。
切换按钮在 `.topbar` 右侧 `#themeToggle`。

### 组件对应关系
| 功能 | HTML 选择器 | JS 核心函数 |
|------|------------|------------|
| 认证 | `#authOverlay` | `handleLogin`, `handleRegister` |
| 悬浮气泡 | `#floatingBubble` | `initBubble` → 拖拽 + 点击打开聊天 |
| 聊天抽屉 | `#chatDrawer` | `sendRagChat` (SSE流式) |
| 补课包 | `#makeupTabPanel` | `loadMakeup` (流式生成) |
| 笔记面板 | `#notesSubpanel` | `loadFolders`, `loadNotes` |
| 新建笔记 | `#createNoteOverlay` | 三种模式：手写/拍照/上传 |
| 笔记编辑器 | `#noteEditorOverlay` | `openNoteEditor`, `saveNoteEdit` |
| DDL 面板 | `#tasks` | `loadTasks`, `parseDdl` (AI解析) |
| 知识库 | `#knowledgeSubpanel` | `loadKnowledgeBase` |
| 语音输入 | `AudioManager` | SpeechRecognition / MediaRecorder 双模式 |
| 图片灯箱 | `#imageLightbox` | `openImageLightbox` |

### 关键 CSS 类
- `.phone-shell` — 手机外壳容器
- `.tab-panel` — 页面面板（`.active` 控制显示）
- `.section-block` — 内容卡片
- `.modal-overlay` / `.modal-card` — 通用模态框
- `.primary-action` / `.secondary-action` / `.text-button` — 按钮体系
- `.ddl-card` — DDL 条目卡片
- `.note-card` — 笔记列表卡片

## 注意事项
- 前端 JS 是单文件 ~2800 行，修改时精确定位函数，避免重复全文读取
- CSS 变量主题系统很完善，新增 UI 必须用 `var(--xxx)` 而非硬编码颜色
- 深色模式需同步覆盖，见 `styles.css` 中 `[data-theme="dark"]` 块
- API 调用统一用 `api()` 函数，它会自动处理 JWT 和 token 刷新
- 流式 API (chat/makeup) 手动 fetch + ReadableStream 读取
