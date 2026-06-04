# 蓝心校园 AI 管家 MVP

面向校园学习场景的 AI 管家演示项目，根据 [竞赛方案](COMPETITION_PROPOSAL.md) 实现。项目采用 Spring Boot + H2 文件数据库 + 原生前端，支持用户账号、课堂笔记、DDL 管理、AI 对话、RAG 知识库、补课包和学习周报。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- 可选：`pdftotext`，用于 PDF 文档解析

项目默认使用 H2 文件数据库，数据保存在 `./data/`，首次启动自动创建，无需安装 MySQL。

### 配置环境变量

AI API Key 通过环境变量注入，不写入代码或配置文件：

```powershell
$env:LANXIN_API_KEY="你的蓝心 API Key"
$env:LANXIN_API_URL="https://api-ai.vivo.com.cn/v1"
$env:LANXIN_MODEL="Doubao-Seed-2.0-mini"
$env:LANXIN_EMBEDDING_MODEL=""
$env:APP_JWT_SECRET="请替换为生产环境强随机密钥"
```

Linux/macOS：

```bash
export LANXIN_API_KEY="你的蓝心 API Key"
export LANXIN_API_URL="https://api-ai.vivo.com.cn/v1"
export LANXIN_MODEL="Doubao-Seed-2.0-mini"
export LANXIN_EMBEDDING_MODEL=""
export APP_JWT_SECRET="请替换为生产环境强随机密钥"
```

未配置 `LANXIN_API_KEY` 时，部分 AI 功能会使用本地演示内容或降级能力，基础业务仍可运行。

### 启动

```bash
mvn spring-boot:run


```

浏览器访问：
 
 
```text
http://localhost:8080
```

演示账号：

```text
demo / demo123
```

也可以打包运行：

```bash
mvn clean package -DskipTests
java -jar target/campus-ai-1.0.0.jar
```

## 技术栈

- 后端：Spring Boot 3.4.1、Java 17、Spring Web、Spring Data JPA、Bean Validation
- 数据库：H2 文件数据库，兼容 MySQL 模式
- 前端：原生 HTML/CSS/JavaScript，静态资源托管在 `src/main/resources/static`，"温暖学伴"设计风格，18+ CSS 动画 + JS 微交互
- 认证：BCrypt 密码加密 + HMAC-SHA256 JWT access token + refresh token
- AI：vivo AIGC OpenAI 兼容接口，支持文本、图片、多轮场景封装和流式输出
- RAG：PDF/TXT 文档解析、分块、embedding 向量检索、关键词检索降级
- 安全：统一异常响应、参数校验、输入清洗、敏感接口 IP 速率限制、DTO 输出隔离

## 已实现功能

### 用户与首页
- 用户注册、登录、刷新令牌、退出，BCrypt 密码加密 + HMAC-SHA256 JWT 双 token 机制
- 首页仪表盘：笔记数、待办数（不含过期）、连续学习天数、今日 DDL（7 天内）、过期 DDL 警告条、最近笔记，统计数字滚动动画

### 课堂笔记
- 三种创建方式：手写笔记、拍照 AI 识别、上传文档提取
- 笔记归档、搜索、编辑、删除（二次确认），文件夹树支持多级目录（folderPath 字段 + 前缀查询）
- 笔记内容「预览」/「原文」双模式切换，预览区 Markdown 格式化渲染
- AI 结构化处理：提取标题、摘要、知识点、公式、标签和思维导图
- 图片上传点击放大灯箱预览，拍照识别后编辑保存（自动去重）
- 笔记创建/更新后自动写入 RAG 知识库并生成 embedding 向量

### DDL 管理
- 三种创建方式：AI 智能解析（支持批量）、手动创建（弹窗表单）、快捷示例模板
- AI 解析预览：解析结果确认弹窗，支持移除/修改后再批量保存，解析前不会直接入库
- AI 智能分类：自动识别事项类别（考试/作业/体测/活动/论文/体育等），不再错误归类为"专业课程"
- 智能日期识别：支持"明天/后天/下周一/周末/月底/3天后"等 20+ 种日期表达
- 未完成/已完成双 Tab 管理，支持筛选（全部/高优先级/本周/已过期）
- 编辑 DDL：点击 ✎ 按钮可修改标题、日期、优先级、类别
- 标记完成自动记录完成时间，支持撤销完成
- 删除二次确认 + 撤销 Toast（5 秒内可撤销）
- 底部导航 DDL 角标实时显示待办数量
- 首页过期警告条（"你有 N 个已过期的 DDL"）+ 过期项红色高亮
- 已完成列表分页加载（每页 20 条）
- 今日提醒只显示未过期项（dueDate ≥ 今天），过期项单独统计
- 首页统计数字不包含已过期 DDL

### AI 对话（小蓝聊天）
- 普通对话和 RAG 知识库增强对话，均支持流式输出（SSE）
- 悬浮球入口 + 聊天抽屉双 Tab 布局：小蓝聊天 / 逃课补课包
- RAG 开关可切换是否基于已上传知识库回答
- 流式输出结束后自动渲染 Markdown，typing 指示器动画
- 401 自动刷新 token，刷新后提示重发而非重复提交

### RAG 知识库
- 上传 PDF（pdftotext 解析）、DOCX（ZipXML 解析）、TXT/MD 文档，自动分块切片（~1000 字/块，200 字重叠）
- 文档提取端点：仅提取文本不入库（支持 PDF/DOCX/TXT），前端可预览后选择保存为笔记或加入知识库
- embedding 向量化：支持独立配置 embedding 模型，生成向量后存库
- 检索策略：向量余弦相似度优先，embedding 不可用时关键词匹配兜底
- 文档列表、状态展示（就绪/处理中/失败）、删除
- 笔记自动索引：创建/更新时同步写入 RAG，删除时清理对应文档

### 逃课补课包
- 勾选笔记和已就绪文档作为生成素材，流式生成补课包内容
- 补课包 prompt 要求详细的知识点解析（概念解释、常见误区）、复习方法（时间分配、验收标准）、自测题目（含答案解析）
- 生成后支持追问聊天：对补课包内容流式提问 AI
- 保存为笔记：自定义弹窗填写标题、文件夹路径、标签，预览 Markdown 原文后保存

### 学习周报
- 汇总笔记数量、专注时长、完成 DDL 数、学习亮点和 AI 建议

### 前端交互体验
- "温暖学伴"设计风格：浮动光斑背景、流动渐变 Hero、毛玻璃面板、emoji 图标
- 18+ CSS 动画：弹簧过渡、波纹反馈、呼吸发光、弹跳导航、打字指示器、消息滑入
- JS 微交互：按钮水波纹（ripple）、统计数字滚动计数、AI 回复等待动画
- Toast 消息提示组件，全局统一反馈
- 防重复点击：所有异步操作按钮 loading 禁用态
- Markdown 渲染：支持 #标题、**加粗**、- 列表、数学公式（KaTeX 渲染，支持 `$$`/`$`/`\[`/`\(` 四种 LaTeX 分隔符）
- 悬浮球拖拽定位 + 吸附边缘 + 点击打开聊天抽屉（使用小蓝头像图片）
- Ctrl+K 快捷键唤起聊天抽屉

### 安全与稳定性
- `GlobalExceptionHandler` 统一错误响应，AiServiceException 携带 provider 和 aiStatus
- 流式端点内 try-catch 兜底，避免 StreamingResponseBody lambda 异常导致无响应
- 参数校验：Bean Validation 校验长度、必填、枚举等，`@Valid` + MethodArgumentNotValidException 处理
- 输入清洗：`InputSanitizer` 去除 <script> 标签、HTML 标签、控制字符并截断
- IP 速率限制：登录/注册 10 次/分钟，AI、RAG、DDL 解析 30 次/分钟
- DTO 输出隔离：`NoteDto`、`ReminderDto` 不暴露 userId、ragDocumentId 等内部字段
- 前端 HTML 转义 + Markdown 渲染前 math/HTML 双重防护
- AI 超时保护：单次 API 调用 120s 超时，Spring MVC 异步流 180s 超时
- 数据库索引：userId + updatedAt/dueDate/course/folderPath/status 等复合索引
- 流式输出 XSS 防护：Markdown 渲染前先 escapeHtml，math 块先保护再还原
- 前端截断：文档内容超过 20,000 字符时截断后发送，避免后端 @Size 校验失败
- 拍照去重：后端 process-image 自动保存的笔记在用户保存时先删再建，避免重复

## 安全与稳定性

`GlobalExceptionHandler` 统一返回错误结构：

```json
{
  "timestamp": "2026-05-27T00:00:00Z",
  "status": 503,
  "code": "AI_SERVICE_UNAVAILABLE",
  "error": "AI 服务调用失败，状态码：503",
  "provider": "lanxin",
  "aiStatus": 503
}
```

- `LanxinApiClient` 对 AI HTTP 状态码、网络异常、超时、流式失败分类抛出 `AiServiceException`，携带 `provider` 和 `aiStatus` 供前端识别。
- 流式端点 `StreamingResponseBody` lambda 内异常无法被 `@RestControllerAdvice` 捕获，已加 try-catch 兜底写出错误文本。
- 登录、注册、AI、RAG、DDL 解析等敏感 `POST` 接口按 IP 速率限制（登录/注册 10/min，AI 类 30/min）。
- AI 单次调用 120s 超时 + Spring MVC 异步流 180s 超时 + `max_tokens=4096` 防止长文本截断。
- 请求体使用 Bean Validation（`@Valid` + Jakarta 注解），`MethodArgumentNotValidException` 统一返回字段级错误。
- `InputSanitizer` 清洗所有用户输入：去 `<script>`、HTML 标签、控制字符，按字段长度截断。
- 前端双重 XSS 防护：`escapeHtml()` 转义 + `renderMarkdown()` 先转义再渲染，LaTeX 数学块先保护再还原。
- DTO 输出隔离：`NoteDto`、`ReminderDto` 不暴露 `userId`、`ragDocumentId` 等内部字段。
- JPA 参数绑定防 SQL 注入，无手写 SQL 拼接。
- 数据库复合索引：`userId + updatedAt`、`userId + dueDate`、`userId + completed + dueDate`、`userId + folderPath`、`userId + status` 等。
- JWT 自包含认证，密码 BCrypt 加密，`refreshToken` 支持无感续期。

## 主要接口

除登录、注册、刷新令牌外，业务接口需要：

```http
Authorization: Bearer <accessToken>
```

### 认证

- `POST /api/v1/user/login`
- `POST /api/v1/user/register`
- `POST /api/v1/user/refresh`
- `POST /api/v1/user/logout`
- `GET /api/v1/user/profile`

### 笔记

- `GET /api/v1/notes`
- `POST /api/v1/notes`
- `GET /api/v1/notes/{id}`
- `PUT /api/v1/notes/{id}`
- `DELETE /api/v1/notes/{id}`
- `POST /api/v1/notes/{id}/mindmap`
- `POST /api/v1/notes/batch-sync`
- `POST /api/v1/ai/note/process`
- `POST /api/v1/ai/note/process-image`
- `POST /api/v1/ai/note/mindmap`

### DDL

- `GET /api/v1/reminders?includeCompleted=&page=&size=`
- `POST /api/v1/reminders`
- `PUT /api/v1/reminders/{id}` — 编辑 DDL
- `DELETE /api/v1/reminders/{id}`
- `PUT /api/v1/reminders/{id}/complete` — 标记完成（自动记录 completedAt）
- `PUT /api/v1/reminders/{id}/uncomplete` — 撤销完成
- `GET /api/v1/reminders/today` — 今日至 7 天内未过期待办
- `GET /api/v1/reminders/overdue` — 已过期 DDL 列表与计数
- `GET /api/v1/reminders/priority` — 按优先级排序
- `POST /api/v1/reminders/parse` — AI 单条解析
- `POST /api/v1/reminders/parse-preview` — AI 批量解析预览（不保存）
- `POST /api/v1/reminders/parse-batch` — AI 批量解析并保存
- `POST /api/v1/reminders/batch-save` — 批量保存确认后的解析结果
- `POST /api/v1/ai/reminder/parse`

### AI 与 RAG

- `POST /api/v1/ai/chat`
- `POST /api/v1/ai/chat/stream`
- `POST /api/v1/ai/makeup/generate`
- `POST /api/v1/ai/makeup/stream`
- `POST /api/v1/ai/makeup/chat/stream`
- `GET /api/v1/ai/provider/status`
- `POST /api/v1/rag/documents`
- `GET /api/v1/rag/documents`
- `DELETE /api/v1/rag/documents/{id}`
- `POST /api/v1/rag/documents/extract`
- `POST /api/v1/rag/documents/ingest-text`
- `POST /api/v1/rag/chat`
- `POST /api/v1/rag/chat/stream`

### 报告与统计

- `GET /api/v1/reports/weekly`
- `GET /api/v1/reports/weekly/list`
- `POST /api/v1/reports/weekly/generate`
- `GET /api/v1/stats/dashboard`
- `GET /api/v1/stats/continuity`

## 项目结构

```text
lanxin-campus-ai/
├── pom.xml
├── README.md
├── PROJECT_CONTEXT.md
├── COMPETITION_PROPOSAL.md
├── src/
│   ├── main/
│   │   ├── java/com/vivo/lanxin/campus/
│   │   │   ├── CampusAiApplication.java
│   │   │   ├── model/          # JPA 实体
│   │   │   ├── repository/     # Spring Data JPA 仓储
│   │   │   ├── service/        # Auth/JWT/AI/RAG 业务服务
│   │   │   └── web/            # Controller、DTO、异常处理、限流、输入清洗
│   │   └── resources/
│   │       ├── application.yml
│   │       └── static/         # index.html、styles.css、app.js
│   └── test/
│       ├── java/com/vivo/lanxin/campus/AppControllerTest.java
│       └── resources/application.yml
└── data/                       # 本地 H2 数据文件，运行后生成
```

## H2 控制台

开发环境可访问：

```text
http://localhost:8080/h2-console
```

连接信息：

```text
JDBC URL: jdbc:h2:file:./data/campus-ai
User Name: sa
Password: 留空
```

## PDF 解析依赖

RAG 上传 PDF 需要系统可执行文件 `pdftotext`。

- Windows：可安装 Git for Windows，并将 `C:\Program Files\Git\mingw64\bin\` 加入 PATH
- Linux：`sudo apt install poppler-utils`
- macOS：`brew install poppler`

未安装时，PDF 上传可能出现 `Cannot run program "pdftotext"`。

## 测试

```bash
mvn test
```

当前集成测试覆盖登录、JWT 返回、用户信息、笔记处理、DTO 输出隔离、DDL 解析（含 AI 智能解析）、首页待办、参数校验等核心路径。测试使用 H2 内存数据库。

## 开发注意事项

- 开发时推荐 `mvn spring-boot:run`，修改静态文件（HTML/CSS/JS）刷新浏览器即可，无需重启。
- 使用 `java -jar` 运行时，静态资源来自 jar 包内部，改动前端后必须重新 `mvn clean package`。
- 前端缓存版本通过 `styles.css?v=N` 和 `app.js?v=N` 控制，修改 JS/CSS 后需递增版本号。
- 前端采用 emoji 图标方案，新增图标时直接使用 emoji 而非 CSS 手绘形状。
- 生产环境必须设置强随机 `APP_JWT_SECRET`，不要使用默认开发密钥。
- `LANXIN_EMBEDDING_MODEL` 可选，不配置时 RAG 使用关键词检索降级。
- `application.yml` 中 `spring.jpa.hibernate.ddl-auto=update` 适合演示环境，正式环境应改为 Flyway/Liquibase。
- `spring.mvc.async.request-timeout` 默认 30s，流式长内容需要调大（当前 180s）。
- AI 调用超时 `lanxin.connect-timeout` 60s + `lanxin.read-timeout` 120s，模型响应慢时可适当增加。
- JVM 堆内存已配置 `-Xmx1024m`，大文件上传或批量 embedding 时注意内存。
- PDF 解析依赖系统 `pdftotext`（通过 Git for Windows / poppler-utils 提供）。
- 当前 IP 限流读取 `X-Forwarded-For` 首个 IP，反向代理后需确保代理正确覆盖该头。
- 如后续引入 Redis，可替换当前内存限流桶、本地用户信息缓存和 JWT 黑名单。

更多接手细节见 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)。
