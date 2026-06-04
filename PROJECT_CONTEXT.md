# 蓝心校园 AI 管家项目上下文

这份文档给后续开发者或新对话窗口接手使用。先读本文，再看代码。

## 项目定位

本项目是根据 [COMPETITION_PROPOSAL.md](COMPETITION_PROPOSAL.md) 实现的浏览器版可运行 MVP，不是正式 vivo 快应用工程。目标是快速演示校园 AI 管家的核心能力：课堂笔记、DDL 管理、AI 学习搭子、RAG 知识库、补课包和学习周报。

启动后访问：

```text
http://localhost:8080
```

演示账号：

```text
demo / demo123
```

Spring Boot 配置了 `server.address: 0.0.0.0`，局域网设备可通过本机 IP 访问。

## 当前技术形态

- 后端：Spring Boot 3.4.1，Java 17
- Web：Spring MVC + `StreamingResponseBody`
- 数据：Spring Data JPA + H2 文件数据库，数据文件在 `./data/`
- 前端：原生 HTML/CSS/JavaScript，位于 `src/main/resources/static`
- 认证：BCrypt + 自实现 HMAC-SHA256 JWT access token/refresh token
- AI：`LanxinApiClient` 通过 vivo AIGC OpenAI 兼容接口调用
- RAG：PDF/TXT 文档解析、分块、embedding、余弦相似度检索，embedding 不可用时关键词兜底
- 安全：统一异常、DTO 输出、参数校验、输入清洗、IP 限流

## 运行配置

主配置文件：

```text
src/main/resources/application.yml
```

关键配置：

```yaml
server:
  port: 8080
  address: 0.0.0.0

spring:
  datasource:
    url: jdbc:h2:file:./data/campus-ai;DB_CLOSE_DELAY=-1;MODE=MYSQL
  jpa:
    hibernate:
      ddl-auto: update

app:
  jwt:
    secret: ${APP_JWT_SECRET:lanxin-campus-ai-dev-secret-change-me}
    access-token-minutes: ${APP_JWT_ACCESS_MINUTES:120}
    refresh-token-days: ${APP_JWT_REFRESH_DAYS:7}

lanxin:
  api-key: ${LANXIN_API_KEY:}
  api-url: ${LANXIN_API_URL:https://api-ai.vivo.com.cn/v1}
  model: ${LANXIN_MODEL:Doubao-Seed-2.0-mini}
  embedding-model: ${LANXIN_EMBEDDING_MODEL:}
```

生产或正式演示前必须设置 `APP_JWT_SECRET`，不要使用默认开发密钥。

## 核心文件

入口：

```text
src/main/java/com/vivo/lanxin/campus/CampusAiApplication.java
```

Controller 和 Web 支撑：

```text
src/main/java/com/vivo/lanxin/campus/web/AppController.java
src/main/java/com/vivo/lanxin/campus/web/GlobalExceptionHandler.java
src/main/java/com/vivo/lanxin/campus/web/ApiException.java
src/main/java/com/vivo/lanxin/campus/web/AiServiceException.java
src/main/java/com/vivo/lanxin/campus/web/RateLimitInterceptor.java
src/main/java/com/vivo/lanxin/campus/web/WebConfig.java
src/main/java/com/vivo/lanxin/campus/web/InputSanitizer.java
src/main/java/com/vivo/lanxin/campus/web/NoteDto.java
src/main/java/com/vivo/lanxin/campus/web/ReminderDto.java
```

业务服务：

```text
src/main/java/com/vivo/lanxin/campus/service/AuthService.java
src/main/java/com/vivo/lanxin/campus/service/JwtService.java
src/main/java/com/vivo/lanxin/campus/service/AiMockService.java
src/main/java/com/vivo/lanxin/campus/service/LanxinApiClient.java
src/main/java/com/vivo/lanxin/campus/service/RagService.java
```

实体和仓储：

```text
src/main/java/com/vivo/lanxin/campus/model/
src/main/java/com/vivo/lanxin/campus/repository/
```

前端：

```text
src/main/resources/static/index.html
src/main/resources/static/styles.css
src/main/resources/static/app.js
```

测试：

```text
src/test/java/com/vivo/lanxin/campus/AppControllerTest.java
src/test/resources/application.yml
```

## 当前已实现能力

### 用户与认证

- 首次启动自动创建 `demo/demo123`，密码 BCrypt 加密
- 登录和注册返回 `token`、`refreshToken`、`expiresAt`
- `JwtService` 自实现 HMAC-SHA256 JWT，access token 默认 120 分钟，refresh token 7 天
- `/api/v1/user/refresh` 支持无感续期，前端所有 API 遇 401 自动 refresh 后重试
- 用户资料有 5 分钟本地缓存（ConcurrentHashMap），后续可替换为 Redis

注意：`logout` 对 JWT 是客户端语义，后端不维护黑名单。正式场景需引入 Redis token blacklist 或 token version。

### 课堂笔记

三种创建方式：
- **手写笔记**：标题、课程、文件夹路径、标签、正文，支持「预览」/「原文」双模式
- **拍照 AI 识别**：支持拖拽/选择图片，前端 `compressImage()` 压缩（1920px + 0.7 质量），调用 `/ai/note/process-image` AI 识别，返回标题、正文、标签，用户编辑后保存
- **上传文档提取**：支持 PDF/TXT/DOCX（最大 20MB），调用 `/rag/documents/extract` 提取文本，可保存为笔记或加入知识库

笔记管理：
- 多级文件夹树：横向 + 纵向滚动条，长路径完整展示，节点折叠/展开，前缀匹配 + 去重查询
- 搜索：关键词搜索，按 folderPath 精确匹配和前缀筛选
- `Note` 模型新增 `folderPath` 字段 + DB 索引 `idx_notes_user_folder`，`NoteDto` 输出包含 `folderPath`
- 编辑/删除：编辑弹窗支持 AI 结构化（提取要点、公式、标签、思维导图），删除需二次确认
- 图片灯箱：上传图片点击放大预览
- DTO 输出：`NoteDto` 不暴露 `userId`、`ragDocumentId`

### DDL 管理

创建方式：
- AI 智能解析（支持单条和批量）：POST `/reminders/parse`（单条）和 `/reminders/parse-preview`（批量预览，不保存）
  - AI 解析使用精简 prompt（~120 tokens），调用 `LanxinApiClient.chat()` 返回 JSON
  - 返回 title、dueDate、priority、category、description 五个字段
  - 批量解析自动按换行/编号/分号拆分，返回 JSON 数组
  - 前端解析完成后弹出确认卡片（可移除/修改），确认后调用 `/reminders/batch-save` 批量保存
  - AI 降级逻辑：30+ 课程/类别关键词匹配、20+ 日期表达识别、智能优先级推断
  - 不再强行归类为"专业课程"：体测→体育、开会→活动、论文→论文
- 手动创建：弹窗表单（标题、类别、日期、优先级、备注），调 POST `/reminders`
- 快捷示例：🏃 体测 / 📐 交作业 / 🔬 实验报告 三个一键填入按钮

管理与交互：
- 未完成/已完成双 Tab，筛选条（全部/高优先级/本周/已过期）
- 标记完成自动记录 `completedAt`（`PUT /reminders/{id}/complete`），撤销完成（`PUT /reminders/{id}/uncomplete`）
- 编辑 DDL：`PUT /reminders/{id}`，可修改标题、日期、优先级、类别，前端 ✎ 按钮 → 复用手动弹窗
- 删除二次确认 + 撤销 Toast（5 秒内可撤销 `showUndoToast()`）
- 已完成列表分页（`GET /reminders?includeCompleted=true&page=0&size=20`），加载更多按钮
- DDL 卡片可点击展开/收起完整描述

日期与统计：
- 今日待办 `GET /reminders/today`：dueDate BETWEEN today AND today+7，不含过期项
- 过期 DDL：`GET /reminders/overdue` 返回列表 + 计数
- 首页过期警告条：`renderOverdueWarning(count)` 显示"你有 N 个已过期的 DDL"
- Dashboard `openReminderCount` 只统计非过期项（today ≤ dueDate ≤ today+30）
- Dashboard 新增 `overdueReminderCount` 和 `today` 字段
- 底部导航 DDL 角标：`renderNavBadge(stats)` 显示过期+紧急总数

数据模型：
- `Reminder` 新增 `completedAt`（完成时间）、`recurrence`（重复模式：none/weekly/biweekly/monthly）
- `ReminderDto` 包含完整字段输出
- 新增 Repository 查询：`dueDateBetween`、`dueDateLessThan`、`completedTrueOrderByCompletedAtDesc`（分页）、`countByCompletedTrueAndCompletedAtBetween`

### AI 对话（小蓝聊天）

- 悬浮球入口：拖拽定位 + 松手吸附边缘 + 提示气泡 + Ctrl+K 快捷键
- 聊天抽屉双 Tab：小蓝聊天 / 逃课补课包，各带独立输入区和消息区
- RAG 开关：勾选后将在已上传知识库中检索相关内容注入 prompt
- 全平台流式输出（SSE）：`StreamingResponseBody` + `text/plain;charset=UTF-8`
- 流式完成后调用 `renderMarkdown()` 渲染 Markdown，typing 指示器
- 401 处理：流式 401 刷新后提示重发，避免重复提交

### RAG 知识库

文档生命周期：
1. 上传：`POST /rag/documents`，校验类型和大小（20MB），状态跟踪（PROCESSING → READY / FAILED）
2. 提取：`POST /rag/documents/extract`，仅提取文本不入库，前端预览后可选择保存为笔记或加入知识库
3. 入库：`POST /rag/documents/ingest-text`，已提取文本直接写入
4. 列表：`GET /rag/documents`，按用户过滤，显示状态、分块数、日期
5. 删除：`DELETE /rag/documents/{id}`，级联删除 chunks

文档处理：
- PDF 使用系统 `pdftotext` 提取（Git for Windows / poppler-utils），DOCX 通过 ZipInputStream + XML DOM 解析（`word/document.xml`），TXT/MD 直接读取
- `extractDocxText()` 解析 OOXML 格式，从 `<w:p>` 段落和 `<w:t>` 文本节点提取内容，禁用外部实体防 XXE
- `extractText()` 统一入口根据 fileType 分发到 PDF/DOCX/TXT 处理器
- `ingestText()` 接受已提取文本直接入库（`doIngest()`），与 `ingestDocument()`（从 InputStream 提取）分离
- `doIngest()` 内部方法：分块 → 批量保存 → 标记 READY
- 按 ~1000 字切片，~200 字重叠，批量保存（5 个/批）
- embedding 向量化：支持独立 embedding 模型配置，调用 `/embeddings` 生成向量并序列化存储
- 检索：向量余弦相似度优先，embedding 不可用时关键词匹配兜底

笔记联动：
- 创建/更新笔记时调用 `indexNote()` 自动写入 RAG，删除笔记时调用 `deleteNoteDocument()` 清理
- `reindexNote()` 先删旧文档再重建，用于笔记更新场景（当前未包 try-catch，更新时可能抛异常）

### 逃课补课包

- 素材选择：勾选笔记和状态为 READY 的文档作为生成素材
- 流式生成：`POST /ai/makeup/stream`，SSE 流式返回
- 增强 prompt：要求详细的知识点解析（概念解释、常见误区）、复习方法（时间分配、验收标准）、自测题目（含答案解析）
- 追问聊天：生成后 `POST /ai/makeup/chat/stream` 对补课包内容流式提问
- 保存为笔记：自定义弹窗 → 填写标题、文件夹路径、标签 → Markdown 原文预览 → `POST /notes` 保存

### 学习周报

- `GET /reports/weekly`：汇总笔记数、专注时长、完成 DDL、学习亮点、AI 建议
- 统计日连续学习天数通过 `findStudyDatesByUserId()` 计算

### 前端交互体验

"温暖学伴"设计风格：
- 视觉：浮动光斑背景（3 个光斑轨道运动）、流动渐变 Hero、毛玻璃面板（`backdrop-filter`）、emoji 图标全方案
- 18+ CSS 动画：弹簧物理过渡（`cubic-bezier`）、按钮波纹（`::after` pseudo）、呼吸发光 CTA、弹跳导航栏、消息滑入、抽屉展开
- JS 微交互：
  - 按钮波纹 `addRipple()`：点击时从鼠标位置扩散圆形波纹
  - 数字滚动 `animateCounter()`：700ms easeOutCubic 动画
  - 打字指示器 `showTyping()/hideTyping()`：三圆点弹跳动画
  - Toast 消息 `showToast()`：底部滑入，2.2s 自动消失
  - 撤销 Toast `showUndoToast()`：带撤销按钮，5 秒内可点击撤销（完成/删除操作后出现）
  - 导航角标 `renderNavBadge()`：DDL 按钮红色数字角标
  - 过期警告 `renderOverdueWarning()`：首页过期 DDL 警告条
  - 悬浮球：`pointerdown/pointermove/pointerup` 拖拽 + 松手吸附左右边缘 + 位置 localStorage 持久化 + bot 头像图片 (`assets/xiaolan-bot-head.png`)
  - DDL 解析预览 `renderParsePreview()`：AI 批量解析结果确认卡片
  - 字数统计 `$("#ddlCharCount")`：DDL 输入框实时字数显示
  - 快捷示例：DDL 输入框三个一键填入按钮
  - DDL 筛选 `switchDdlFilter()`：全部/高优先级/本周/已过期
  - DDL 卡片展开：点击卡片展开/收起完整描述
- 内容预览切换：`switchContentPreview()` 在「预览」（`renderMarkdown()` 渲染）和「原文」（textarea 编辑）间切换
- Markdown 渲染：`renderMarkdown()` 处理 #标题、**加粗**、- 列表、`---` 分割线、段落、换行
- LaTeX 数学公式：KaTeX 渲染，支持四种分隔符：`$$...$$`（显示）、`$...$`（行内）、`\[...\]`（LaTeX 显示）、`\(...\)`（LaTeX 行内）
- 图片灯箱：点击预览图放大，Escape 关闭
- 版本缓存：`styles.css?v=N` 和 `app.js?v=N` 控制前端缓存刷新

### AI 调用与错误处理

- `LanxinApiClient` 四个核心方法：`chat()`、`chatWithImage()`、`embedding()`、`streamChat()`
- 统一抛出 `AiServiceException extends ApiException`，携带 `provider`、`aiStatus`、HTTP 状态码
- `GlobalExceptionHandler` 分层处理：AiServiceException → ApiException → IllegalArgumentException → MethodArgumentNotValidException → ConstraintViolationException → MissingRequestHeaderException → Exception
- 流式端点 lambda 内异常无法被 `@RestControllerAdvice` 捕获，已在 `makeupChatStream()` 和 `chatWithRagStream()` 中加 try-catch 兜底
- 未配置 API Key 时，部分流程走本地演示/降级逻辑
- 超时配置：AI 调用 connect 60s + read 120s，Spring MVC 异步流 180s，`max_tokens=4096`

### 参数校验与输入处理

- 登录、注册、笔记、DDL、聊天、补课包等请求体使用 Jakarta Bean Validation（`@Valid` + `@NotBlank`/`@Size`/`@Pattern`）
- `InputSanitizer.clean()`：去 `<script>` 标签、HTML 标签、控制字符，trim 并截断到指定长度
- `InputSanitizer.nullable()`：同 clean + 空字符串转 null
- `InputSanitizer.cleanList()`：批量清洗 + 去空白 + 限条数
- 前端 `escapeHtml()`：`&<>"` 转义，所有动态内容渲染前先转义
- Spring Data JPA 参数绑定，无手写 SQL

### 速率限制

`RateLimitInterceptor` 内存限流桶：

| 接口 | 限制 |
|------|------|
| 登录/注册 | 10 次/IP/分钟 |
| AI 对话、RAG、DDL 解析、补课包 | 30 次/IP/分钟 |
| GET 查询类 | 不限 |

读取 `X-Forwarded-For` 首个 IP，适合 MVP。多实例部署建议迁移 Redis。

### 数据库索引

JPA 声明的复合索引：

- `users.username`（唯一）
- `notes`: `userId + updatedAt`、`userId + createdAt`、`userId + course`、`userId + folderPath`（新增）
- `reminders`: `userId + dueDate`、`userId + completed + dueDate`、`userId + priority`（新增 `completedAt` 和 `recurrence` 字段）
- `documents`: `userId + createdAt`、`userId + status`
- `document_chunks`: `documentId`、`userId`

H2 文件数据库 `ddl-auto=update` 自动维护。正式环境改 Flyway/Liquibase + MySQL。

## RAG 流程

文档上传入口：

```text
POST /api/v1/rag/documents
```

文档提取（仅提取文本，不入库）：

```text
POST /api/v1/rag/documents/extract
```

文本入库（提取后的内容直接写入知识库）：

```text
POST /api/v1/rag/documents/ingest-text
```

处理流程：

1. 校验文件类型和大小
2. PDF 使用系统 `pdftotext` 提取文本，TXT/MD 直接读取
3. 按约 1000 字切片，保留约 200 字重叠
4. 如配置 embedding 模型，调用 `/embeddings` 生成向量
5. 检索时优先向量相似度，失败或无向量时使用关键词匹配
6. 将召回内容注入 prompt，调用聊天模型回答

笔记创建/更新时会自动写入 RAG 文档，删除笔记时同步删除对应 RAG 文档。

PDF 依赖：

- Windows：`C:\Program Files\Git\mingw64\bin\pdftotext.exe` 常由 Git for Windows 提供
- Linux：`sudo apt install poppler-utils`
- macOS：`brew install poppler`

## vivo AIGC 接入

默认接口形态：

```text
POST https://api-ai.vivo.com.cn/v1/chat/completions?requestId=<uuid>
Authorization: Bearer <LANXIN_API_KEY>
Content-Type: application/json
```

默认模型：

```text
Doubao-Seed-2.0-mini
```

状态检查接口：

```text
GET /api/v1/ai/provider/status
```

如果返回 `configured=true`，说明 Java 进程读取到了 API Key、URL 和模型名。若为 false，通常是环境变量没有传入当前启动进程。

## 常用命令

开发模式启动：

```bash
mvn spring-boot:run
```

运行测试：

```bash
mvn test
```

打包：

```bash
mvn clean package -DskipTests
```

运行 jar：

```bash
java -jar target/campus-ai-1.0.0.jar
```

检查前端脚本语法：

```bash
node --check src/main/resources/static/app.js
```

查看 8080 端口占用，Windows PowerShell：

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```

## 开发注意事项

- 用 `mvn spring-boot:run` 开发时，静态资源刷新浏览器即可看到改动。
- 用 `java -jar` 运行时，静态资源来自 jar 包内部，修改前端后必须重新打包。
- 前端缓存通过 `styles.css?v=N` 和 `app.js?v=N` 控制版本号，修改 JS/CSS 后需同步更新 `index.html` 中的引用。
- 不要把真实 `LANXIN_API_KEY`、`APP_JWT_SECRET` 写入仓库。
- `LANXIN_EMBEDDING_MODEL` 可选，不配置时 RAG 自动使用关键词检索降级（不依赖 `/embeddings` 端点）。
- 现有 JWT 没有服务端吊销机制，适合 MVP。正式上线需引入 Redis token blacklist 或 token version。
- 当前 IP 限流读取 `X-Forwarded-For` 的第一个 IP。部署在反向代理后确保代理覆盖该头。
- `spring.mvc.async.request-timeout: 180000`（默认 30s 不支持流式长内容）
- AI 调用超时：`lanxin.connect-timeout: 60000`（连接），`lanxin.read-timeout: 120000`（读取流），API `max_tokens: 4096`
- JVM 堆内存已配置 1GB（`-Xmx1024m`），大文件 embedding 时注意 OOM
- 文档分块批量保存改为 5 个/批，避免大批量 embedding 内存溢出
- 补课包 prompt 已优化，要求详细的知识点解析（含概念解释、误区）、复习方法（含时间分配、验收标准）、自测题目（含答案解析）
- 前端 `renderMarkdown()` 支持 #标题、**加粗**、列表、LaTeX 数学公式（KaTeX 渲染，支持 `$$`/`$`/`\[`/`\(` 四种分隔符）
- 前端是浏览器演示页，后续迁移 vivo 快应用时主要复用后端 API
- 前端图标统一使用 emoji，不手写 CSS 图标类
- 按钮波纹 `addRipple()`、数字滚动 `animateCounter()`、打字指示器 `showTyping()/hideTyping()` 已实现
- 撤销 Toast `showUndoToast()` 支持完成/删除后 5 秒内撤销
- 导航角标 `renderNavBadge()`：DDL 底部导航红点数字角标
- 过期警告 `renderOverdueWarning()`：首页过期 DDL 警告条
- DDL 解析预览 `renderParsePreview()`：AI 批量解析结果确认（支持移除/修改后批量保存）
- DDL 卡片点击展开/收起完整描述（`.ddl-card.expanded` CSS class 切换）
- 悬浮球定位持久化在 `localStorage.lanxin_bubble_pos`
- 笔记文件夹树项使用 `width: fit-content; min-width: 100%` 实现长路径不截断
- `StreamingResponseBody` lambda 内异常不会被 `@RestControllerAdvice` 捕获，流式方法内部需要独立 try-catch
- 拍照识别的 `processImage` 端点在识别时就保存了笔记（含 RAG 索引），前端保存时先 DELETE 旧笔记再 POST 新笔记来去重
- DDL 解析 AI prompt 已精简到 ~120 tokens，降级逻辑覆盖 30+ 课程/类别和 20+ 日期表达
- `Reminder` 模型新增 `completedAt`（完成时间）和 `recurrence`（重复模式）字段
- `Note` 模型新增 `folderPath` 字段，`NoteDto` 输出包含此字段，DB 索引 `idx_notes_user_folder`
- `NoteRepository` 新增 `findByUserIdAndFolderPath`、`findByUserIdAndFolderPathPrefix`、`findDistinctFolderPathsByUserId`
- `RagService` 新增 `extractDocxText()` 支持 DOCX 文件，`extractText()` 统一提取入口，`ingestText()` 接受预提取文本
- 悬浮球使用 `assets/xiaolan-bot-head.png` 作为 bot 头像，加载失败不影响功能

## 当前验证记录

最近一次验证（2026-06-04）：

- `mvn test`：9 个集成测试全部通过
- `node --check src/main/resources/static/app.js`：通过
- DDL 全面优化全链路验证通过：
  - 手动创建、编辑、删除（二次确认 + 撤销 Toast）、完成/撤销完成
  - AI 单条/批量解析（预览→确认→批量保存）
  - 已完成分页、筛选条（全部/高优/本周/过期）
  - 导航角标实时更新、过期警告条
  - completedAt 自动记录、delete API 返回 204
  - LaTeX `\(\)`/`\[\]` 分隔符渲染
- Note folderPath 增强：模型字段 + 数据库索引 + DTO 输出 + 前缀查询
- DOCX 文档解析：ZipInputStream + XML DOM 提取 word/document.xml
- 悬浮球 bot 头像图片 (`assets/xiaolan-bot-head.png`)
- 补课包详细输出 + Markdown 渲染 + 流式超时修复全链路验证通过
- UI v3 动效升级：浮动光斑、流动渐变、弹簧动画、波纹反馈、数字滚动、打字指示器全链路验证通过
- 聊天抽屉双Tab + 补课包追问聊天 + 保存为笔记弹窗全链路通过
- 内容预览切换（预览/原文）全链路通过
- 文件夹树横向 + 纵向滚动条验证通过
- 文档上传保存为笔记截断修复 + 拍照保存去重修复验证通过

测试覆盖：

- 登录返回 JWT 和 refresh token
- 用户资料接口
- AI 笔记处理
- `NoteDto` 不暴露内部字段
- DDL 解析（含 AI 智能解析）和首页待办
- 笔记列表
- 非法笔记请求返回 400

## 后续建议

1. 引入 Redis
   - 共享限流计数
   - 用户信息缓存
   - JWT 黑名单或 token version

2. 数据库迁移脚本
   - 用 Flyway/Liquibase 替代 `ddl-auto=update`
   - 为 MySQL 生产环境补充显式索引和字段长度

3. AI 可观测性
   - 记录 requestId、模型名、耗时、HTTP 状态码
   - 区分配置错误、限流、超时、模型返回格式异常

4. 前端体验
   - 流式 RAG 对话结束后展示参考来源
   - 补充全局请求 loading 或顶部网络状态提示

5. 快应用迁移
   - 当前页面是浏览器 HTML/CSS/JS
   - 后续可迁移为 vivo 快应用 `.ux` 组件和 `manifest.json`

## 给新窗口的建议开场

```text
请先阅读 PROJECT_CONTEXT.md 和 README.md，然后继续这个项目。下一步我要做……
```
