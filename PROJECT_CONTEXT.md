# 蓝心校园 AI 管家项目上下文

这份文件用于新开发者接手项目或新对话窗口接力开发。开始前请先阅读本文，再查看对应源码文件。

## 项目位置

项目根目录即本文件所在目录。原始方案文档：[COMPETITION_PROPOSAL.md](COMPETITION_PROPOSAL.md)。

## 当前项目形态

这是根据竞赛方案实现的可运行 MVP，不是正式 vivo 快应用工程。

当前技术栈：

- 后端：Spring Boot 3.4.1
- 前端：原生 HTML + CSS + JavaScript
- 页面托管：Spring Boot `src/main/resources/static`
- 数据存储：H2 文件数据库（`./data/campus-ai.mv.db`），Spring Data JPA
- 用户认证：BCrypt 密码加密 + Token 令牌，每个用户数据隔离
- AI 接入：vivo AIGC 官方 OpenAI 兼容接口

启动后访问：

```
http://localhost:8080
```

打开后先看到登录/注册页面。演示账号：`demo` / `demo123`（首次启动自动创建）。

Spring Boot 已配置 `server.address: 0.0.0.0`，监听所有网络接口，局域网内其他设备可通过本机 IP 访问。

## 开发环境搭建

### 前置条件

- JDK 17+
- Maven 3.6+

> 无需安装 MySQL。项目使用 H2 文件数据库，数据文件存储在 `./data/` 目录下，首次启动自动创建。

### 配置 API Key

vivo AIGC 的 Key 通过环境变量注入（禁止写入代码或配置文件）：

```bash
# Linux / macOS
export LANXIN_API_KEY="你的Key"
export LANXIN_API_URL="https://api-ai.vivo.com.cn/v1"
export LANXIN_MODEL="Doubao-Seed-2.0-mini"

# Windows PowerShell
$env:LANXIN_API_KEY="你的Key"
$env:LANXIN_API_URL="https://api-ai.vivo.com.cn/v1"
$env:LANXIN_MODEL="Doubao-Seed-2.0-mini"
```

未配置 Key 时 AI 功能自动降级到本地 mock。

### H2 数据库控制台

开发时可访问 H2 内置控制台查看数据：

```
http://localhost:8080/h2-console
```

JDBC URL: `jdbc:h2:file:./data/campus-ai`，用户名 `sa`，密码留空。

## 已实现功能

前端页面：

- 首页仪表盘
- 拍照笔记页（支持图片上传 + AI 识别）
- 笔记归档页
- DDL 管理页
- 小蓝 AI 对话页
- 学习周报页
- 登录/注册页

后端能力：

- 用户注册与登录（BCrypt 加密，Token 鉴权）
- 课堂笔记结构化
- DDL 文本解析
- AI 对话（自由对话，不限制话题）
- 逃课补课包
- 学习周报
- **RAG 知识库增强对话**：PDF/TXT 上传 → `pdftotext` 提取文本 → 分块(1000字+200重叠) → embedding 向量化 → 余弦相似度检索 → 注入 prompt，失败自动降级关键词匹配
- **笔记自动入 RAG**：创建/更新笔记时自动索引到 RAG 知识库，AI 对话和补课包会引用用户笔记内容
- **全平台流式输出**：所有 AI 交互（小蓝对话、RAG 问答、补课包）均支持流式输出。后端 `StreamingResponseBody` + `LanxinApiClient.streamChat()`，前端 `response.body.getReader()` + `TextDecoder` 逐字渲染
- **补课包素材选择**：逃课补课包支持勾选笔记/文档作为生成素材，未选素材时自动生成通用学习建议
- **文档删除修复**：RAG 文档和笔记文档删除方法已添加 `@Transactional` 注解，修复 JPA 删除报错
- **PDF 提取**：改用系统 `pdftotext` 工具替代 PDFBox，无需 Java 依赖
- **embedding 批量保存**：文档分块改为每 5 个一批保存，避免大批次内存溢出
- **模型思考模式已禁用**：API 请求中设置 `"thinking": {"type": "disabled"}`，避免推理过程混入输出
- 统计看板
- 蓝心模型配置状态诊断
- 所有数据按用户隔离
- JVM 启动配置 1GB 堆内存

"拍照笔记"页已实现真实图片上传：前端支持点击选图/拖拽上传，后端通过 vivo AIGC 多模态接口（image_url）识别图片中的板书内容，再由 AI 生成结构化笔记和复习摘要。

上传前客户端自动压缩：Canvas 缩放至最大 1920px，JPEG 质量 0.7，200KB 以下小图跳过。处理过程中上传区域和 file input 被禁用，防止重复提交；点击「重新选择」通过请求序号（requestId）机制终止旧请求，不依赖 AbortController。

## 开发注意事项

### 静态文件修改后必须重新打包

项目通过 `java -jar target/campus-ai-1.0.0.jar` 运行，Spring Boot 从 jar 包内部读取静态资源，**不是**从 `target/classes/static/` 读取。修改前端文件后只复制到 `target/classes/` 不会生效，必须执行 `mvn package -DskipTests` 重新打包。

```bash
# 开发阶段推荐直接用 mvn spring-boot:run，它会从 target/classes/ 读取，改完即生效
mvn spring-boot:run

# 如果打 jar 运行，则必须重新打包
mvn clean package -DskipTests
java -jar target/campus-ai-1.0.0.jar
```

### 图片上传优化要点

- 手机照片通常 5-15MB，必须客户端压缩后再上传，否则上传慢、API 易超时
- 压缩策略：Canvas 缩放 + JPEG 导出，目标 200-500KB
- 处理中禁用 `<input type="file">` 的 `disabled` 属性比 CSS `pointer-events` 更可靠
- 用递增序号（requestId）判断请求是否被取消，比 `AbortController` 更跨浏览器兼容
- 后端 `catch (RuntimeException)` 应改为 `catch (Exception)`，Jackson 反序列化失败抛的是 checked exception

## 关键文件

后端入口：

```
src/main/java/com/vivo/lanxin/campus/CampusAiApplication.java
```

Controller：

```
src/main/java/com/vivo/lanxin/campus/web/AppController.java
src/main/java/com/vivo/lanxin/campus/web/GlobalExceptionHandler.java
```

实体类：

```
src/main/java/com/vivo/lanxin/campus/model/Note.java        (JPA 实体)
src/main/java/com/vivo/lanxin/campus/model/Reminder.java    (JPA 实体)
src/main/java/com/vivo/lanxin/campus/model/UserEntity.java  (用户实体)
src/main/java/com/vivo/lanxin/campus/model/Document.java    (RAG 文档实体)
src/main/java/com/vivo/lanxin/campus/model/DocumentChunk.java (RAG 切片实体)
```

数据访问层：

```
src/main/java/com/vivo/lanxin/campus/repository/NoteRepository.java
src/main/java/com/vivo/lanxin/campus/repository/ReminderRepository.java
src/main/java/com/vivo/lanxin/campus/repository/UserRepository.java
src/main/java/com/vivo/lanxin/campus/repository/DocumentRepository.java
src/main/java/com/vivo/lanxin/campus/repository/DocumentChunkRepository.java
```

认证服务：

```
src/main/java/com/vivo/lanxin/campus/service/AuthService.java
```

AI 业务封装：

```
src/main/java/com/vivo/lanxin/campus/service/AiMockService.java    (笔记处理、AI对话、补课包、chatStream、chatWithRagStream、makeupPackageStream)
```

RAG 服务：

```
src/main/java/com/vivo/lanxin/campus/service/RagService.java       (pdftotext提取/分块/embedding/向量检索/关键词检索/笔记索引/文档删除)
```

vivo AIGC API 客户端：

```
src/main/java/com/vivo/lanxin/campus/service/LanxinApiClient.java  (chat、chatWithImage、streamChat、mockStream、embedding)
```

前端：

```
src/main/resources/static/index.html
src/main/resources/static/styles.css
src/main/resources/static/app.js
```

配置：

```
src/main/resources/application.yml          (主配置，H2 文件数据库)
src/test/resources/application.yml          (测试配置，H2 内存数据库)
```

测试：

```
src/test/java/com/vivo/lanxin/campus/AppControllerTest.java
```

## 认证机制

- `POST /api/v1/user/login` — 登录，返回 Token
- `POST /api/v1/user/register` — 注册新用户
- `POST /api/v1/user/logout` — 退出
- `GET /api/v1/user/profile` — 获取用户信息
- 所有数据接口需要 `Authorization: Bearer <token>` 请求头
- Token 在服务端存储在 `AuthService.tokens`（ConcurrentHashMap）
- 重启服务后 Token 全部失效，需重新登录
- 启动时自动创建演示用户 demo（如果不存在）

## vivo AIGC 接入信息

官方文档地址：

```
https://aigc.vivo.com.cn/#/document/index?id=1745
```

已从官方文档确认：

```
POST https://api-ai.vivo.com.cn/v1/chat/completions
Authorization: Bearer AppKey
Content-Type: application/json
query 参数：requestId
```

当前默认模型：

```
Doubao-Seed-2.0-mini
```

`application.yml` 中的默认配置：

```yaml
lanxin:
  api-key: ${LANXIN_API_KEY:}
  api-url: ${LANXIN_API_URL:https://api-ai.vivo.com.cn/v1}
  model: ${LANXIN_MODEL:Doubao-Seed-2.0-mini}
```

注意：API Key 不允许写入代码或文档。当前 Key 只应通过环境变量或启动进程环境注入。

状态检查接口：

```
GET /api/v1/ai/provider/status
```

如果返回 `configured=true`，说明后端读到了 Key、URL 和模型名。

真实模型调用成功时，`POST /api/v1/ai/chat` 的返回里会出现：

```json
{
  "tone": "lanxin"
}
```

如果真实接口失败，代码会自动降级到本地 mock，避免演示中断。

## 常用命令

运行测试（H2 内存数据库）：

```bash
mvn test
```

打包（跳过测试）：

```bash
mvn package -DskipTests
```

开发模式启动（修改静态文件无需重新打包）：

```bash
# 先设置环境变量
export LANXIN_API_KEY="你的Key"   # 或用 $env:LANXIN_API_KEY 在 Windows 上

# 启动
mvn spring-boot:run
```

jar 方式启动：

```bash
LANXIN_API_KEY="你的Key" java -jar target/campus-ai-1.0.0.jar
```

如果 8080 端口被占用：

```bash
# Linux/macOS
lsof -i :8080
# Windows PowerShell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```

## 当前验证记录

- `mvn test` 5 个测试全部通过（H2 内存数据库）
- `mvn package` 打包成功
- H2 文件数据库 + JPA 正常工作
- BCrypt 密码加密 + Token 认证正常
- 用户数据隔离（每个用户只看自己的笔记/DDL）
- 真实模型 `Doubao-Seed-2.0-mini` 支持 `image_url` 多模态输入
- 图片上传全链路验证通过
- RAG 知识库增强对话：文档上传、分块、embedding、检索增强、降级兜底全链路已实现
- 笔记自动索引 RAG：创建/更新时自动标记文档 ID
- 流式输出全链路验证通过（curl 验证 `/ai/chat/stream`、`/rag/chat/stream`、`/ai/makeup/stream`）
- 文档删除 `@Transactional` 修复验证通过
- 补课包素材选择功能验证通过（选中笔记 ID 后 API 正确返回基于该笔记的学习建议）
- PDF 提取改用 `pdftotext` 后支持大文件上传（测试通过 3.3MB PDF）

注意：如果新窗口或新终端重启服务后 `configured=false`，通常是 `LANXIN_API_KEY` 没有传到 Java 进程，不是代码问题。

## 后续待做

1. 正式快应用迁移
   - 当前前端是浏览器演示页。
   - 后续可迁移到 vivo 快应用 `.ux` 组件和 `manifest.json`。

2. 更细的模型错误反馈
   - 目前 `LanxinApiClient` 失败时静默降级。
   - 后续可以记录错误码，但不要在前端暴露 Key。

3. embedding 端点不可用时的持久化降级
   - vivo AIGC `/v1/embeddings` 返回 404，当前用关键词匹配兜底。
   - 后续如有可用的 embedding 模型，可恢复向量检索精度。

4. 前端流式对话的参考来源显示
   - 当前流式 `sendRagChat()` 暂未显示 RAG 参考来源（之前非流式版本有）。
   - 后续可在流式响应结束后追加来源信息。

## 给新窗口的建议开场

新对话开始时可以直接发送：

```
请先阅读项目根目录下的 PROJECT_CONTEXT.md，然后继续这个项目。下一步我要做……
```
