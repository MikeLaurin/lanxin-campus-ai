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

- 首次启动自动创建 `demo/demo123`
- 密码使用 BCrypt 存储
- 登录和注册返回 `token`、`refreshToken`、`expiresAt`
- `/api/v1/user/refresh` 支持刷新 access token
- `AuthService` 不再保存服务端 UUID token，认证状态由 JWT 自包含
- 用户资料有 5 分钟本地缓存，后续可替换为 Redis

注意：`logout` 对 JWT 是客户端语义，后端不维护黑名单。正式场景如需强制失效，应引入 Redis/数据库 token blacklist 或 token version。

### AI 调用与错误处理

- `LanxinApiClient.chat()`、`chatWithImage()`、`embedding()`、`streamChat()` 统一抛出 `AiServiceException`
- AI HTTP 状态码会以 `aiStatus` 返回给前端
- 未配置 API Key 时，部分流程走本地演示/降级逻辑
- `GlobalExceptionHandler` 统一错误响应，不向前端暴露服务端堆栈

统一错误格式示例：

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

### DTO 与数据隔离

- `AppController` 对笔记和 DDL 返回 `NoteDto`、`ReminderDto`
- 不再直接返回 JPA 实体给前端
- DTO 不包含 `userId`、`ragDocumentId` 等内部字段
- 查询、更新、删除均按当前 JWT 用户 ID 过滤

### 参数校验与输入处理

- 登录、注册、笔记、DDL、聊天、补课包等请求体使用 Bean Validation
- `InputSanitizer` 会去除脚本标签、HTML 标签和非法控制字符，并做长度截断
- 前端 `escapeHtml()` 对动态内容做转义，避免用户输入直接进入 DOM
- 数据访问使用 Spring Data JPA 参数绑定，没有手写 SQL 拼接

### 速率限制

`RateLimitInterceptor` 对敏感接口做 IP 级限流：

- 登录/注册：每 IP 每分钟 10 次
- AI、RAG、DDL 解析类接口：每 IP 每分钟 30 次

当前限流桶存储在服务内存中，适合 MVP。多实例部署或长期运行建议迁移 Redis。

### 数据库索引

已为高频查询字段添加 JPA 索引：

- `users.username`
- `notes.userId + updatedAt`
- `notes.userId + createdAt`
- `notes.userId + course`
- `reminders.userId + dueDate`
- `reminders.userId + completed + dueDate`
- `reminders.userId + priority`
- `documents.userId + createdAt`
- `documents.userId + status`
- `document_chunks.documentId`
- `document_chunks.userId`

当前 `ddl-auto=update` 会让 Hibernate 自动维护演示库结构。正式环境建议改为 Flyway/Liquibase。

### 前端体验

- 登录/注册按钮有 loading 状态
- AI 笔记生成、DDL 解析、聊天发送、文档上传、补课包生成有防重复点击
- 普通 API 请求遇到 401 会先尝试 refresh token
- 流式聊天 401 刷新后提示用户重新发送，避免重复提交消息
- 文档上传区域 loading 时禁用交互
- UI 采用 "温暖学伴" 设计风格：浮动光斑背景、流动渐变 Hero、毛玻璃面板、emoji 图标
- 18+ CSS 动画：弹簧过渡、波纹反馈、呼吸发光、弹跳导航、打字指示器
- JS 微交互：按钮水波纹、统计数字滚动动画、AI 回复等待指示器

## RAG 流程

文档上传入口：

```text
POST /api/v1/rag/documents
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
- 不要把真实 `LANXIN_API_KEY`、`APP_JWT_SECRET` 写入仓库。
- 现有 JWT 没有服务端吊销机制，适合 MVP。正式上线需设计 token blacklist、用户 token version 或短 access token + Redis session。
- 当前 IP 限流读取 `X-Forwarded-For` 的第一个 IP。部署在反向代理后要确保代理层正确覆盖该头，避免伪造。
- `spring.mvc.async.request-timeout: 180000`（默认 30s 不足以支持流式长内容）
- 补课包 prompt 已优化，要求详细的知识点解析（含概念解释、误区）、复习方法（含时间分配、验收标准）、自测题目（含答案解析）
- 前端已支持 Markdown 渲染（`renderMarkdown()` 函数），处理 #标题、**加粗**、列表等语法
- 前端是浏览器演示页，后续迁移 vivo 快应用时，主要复用后端 API 和业务流程。
- 前端图标使用 emoji（🏠📸📝⏰✨📊），新增图标时直接用 emoji，不再手写 CSS 图标类。
- 按钮波纹效果（`addRipple()`）、统计数字滚动（`animateCounter()`）、打字指示器（`showTyping()/hideTyping()`）已实现在 app.js 中。

## 当前验证记录

最近一次验证：

- `mvn test`：9 个集成测试全部通过
- `node --check src/main/resources/static/app.js`：通过
- 补课包详细输出 + Markdown 渲染 + 流式超时修复全链路验证通过
- UI v3 动效升级：浮动光斑、流动渐变、弹簧动画、波纹反馈、数字滚动、打字指示器全链路验证通过

测试覆盖：

- 登录返回 JWT 和 refresh token
- 用户资料接口
- AI 笔记处理
- `NoteDto` 不暴露内部字段
- DDL 解析和首页待办
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
