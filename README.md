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

- 用户注册、登录、刷新令牌、退出
- 首页仪表盘：笔记数、待办数、连续学习天数、今日 DDL、最近笔记
- 课堂笔记：文本结构化、图片识别、笔记归档、搜索、删除
- DDL 管理：手动创建、AI 文本解析、临近任务、优先级排序
- AI 对话：普通对话和 RAG 增强对话，支持流式输出
- RAG 知识库：上传 PDF/TXT，生成文档切片，支持文档列表和删除
- 笔记自动索引：笔记创建/更新后自动写入 RAG 知识库
- 逃课补课包：可勾选笔记/文档作为素材，流式生成学习建议
- 学习周报：汇总笔记和 DDL 完成情况
- 前端 Markdown 渲染：AI 生成的 #标题、**加粗**、列表等 Markdown 语法自动转为 HTML
- 补课包输出增强：prompt 要求详细的知识点解析、复习方法、自测题目及答案解析
- 前端体验：等待服务端响应时显示 loading，禁用重复点击，流式完成后自动移除状态提示
- 接口输出：使用 `NoteDto`、`ReminderDto` 等 DTO，不直接暴露 JPA 实体内部字段
- UI 动效升级：浮动光斑背景、流动渐变 Hero、弹簧动画、按钮波纹反馈、数字滚动统计、打字指示器、毛玻璃面板

## 安全与稳定性

- `GlobalExceptionHandler` 统一返回错误结构：

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

- `LanxinApiClient` 对 AI HTTP 状态码、网络异常、超时、流式调用失败进行明确提示。
- 登录、注册、AI 生成、RAG 对话等敏感 `POST` 接口有 IP 速率限制。
- 请求体使用 Bean Validation 校验长度、必填字段、优先级枚举等。
- 用户输入经过基础清洗，去除脚本标签、HTML 标签和非法控制字符。
- 前端渲染动态内容前做 HTML 转义，降低 XSS 风险。
- 数据库为高频查询字段添加索引，例如 `userId + updatedAt`、`userId + dueDate`、`userId + status`。

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

- `GET /api/v1/reminders`
- `POST /api/v1/reminders`
- `DELETE /api/v1/reminders/{id}`
- `PUT /api/v1/reminders/{id}/complete`
- `GET /api/v1/reminders/today`
- `GET /api/v1/reminders/priority`
- `POST /api/v1/reminders/parse`
- `POST /api/v1/ai/reminder/parse`

### AI 与 RAG

- `POST /api/v1/ai/chat`
- `POST /api/v1/ai/chat/stream`
- `POST /api/v1/ai/makeup/generate`
- `POST /api/v1/ai/makeup/stream`
- `GET /api/v1/ai/provider/status`
- `POST /api/v1/rag/documents`
- `GET /api/v1/rag/documents`
- `DELETE /api/v1/rag/documents/{id}`
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

当前集成测试覆盖登录、JWT 返回、用户信息、笔记处理、DTO 输出隔离、DDL 解析、首页待办、参数校验等核心路径。测试使用 H2 内存数据库。

## 开发注意事项

- 开发时推荐 `mvn spring-boot:run`，修改静态文件后刷新浏览器即可。
- 使用 `java -jar` 运行时，静态资源来自 jar 包内部，改动前端后需要重新 `mvn clean package`。
- 前端采用 emoji 图标 + CSS 动画方案，新增图标时使用 emoji 而非 CSS 手绘形状。
- 生产环境必须设置强随机 `APP_JWT_SECRET`，不要使用默认开发密钥。
- `application.yml` 中 `spring.jpa.hibernate.ddl-auto=update` 适合演示环境，正式环境应改为迁移脚本管理表结构。
- `spring.mvc.async.request-timeout` 默认 30s，流式长内容场景需调大（当前 180s）。
- 如后续引入 Redis，可替换当前内存限流桶和本地用户信息缓存。

更多接手细节见 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)。
