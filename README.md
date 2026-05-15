# 蓝心校园 AI 管家 MVP

根据[竞赛方案](COMPETITION_PROPOSAL.md)实现的可运行演示项目。Spring Boot 3.4.1 + MySQL 8.4 + 原生前端，每个用户独立账号，数据持久化存储。

## 前置条件

在本机开发需要安装：

- **JDK 17+**（项目编译目标为 Java 17）
- **Maven 3.6+**（或使用项目自带的 `mvnw`）
- **MySQL 8.4**（数据库名 `lanxin_campus`，字符集 utf8mb4）

> 仅跑测试不需要 MySQL，测试使用 H2 内存数据库。

## 快速开始

### 1. 安装并启动 MySQL

确保 MySQL 8.4 已安装并运行。然后创建数据库：

```sql
CREATE DATABASE lanxin_campus CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

应用首次启动时会通过 JPA `ddl-auto: update` 自动建表，无需手动导入 SQL。

### 2. 配置环境变量

vivo AIGC API Key 通过环境变量注入，不写入代码或配置文件：

```bash
# Linux / macOS
export LANXIN_API_KEY="你的蓝心 API Key"
export LANXIN_API_URL="https://api-ai.vivo.com.cn/v1"
export LANXIN_MODEL="Doubao-Seed-2.0-mini"

# Windows PowerShell
$env:LANXIN_API_KEY="你的蓝心 API Key"
$env:LANXIN_API_URL="https://api-ai.vivo.com.cn/v1"
$env:LANXIN_MODEL="Doubao-Seed-2.0-mini"
```

如未配置 API Key，AI 功能会自动降级到本地 mock，不影响其他功能。

### 3. 启动应用

```bash
# 开发模式（推荐）
mvn spring-boot:run

# 或打包运行
mvn clean package -DskipTests
java -jar target/campus-ai-1.0.0.jar
```

### 4. 打开浏览器

```
http://localhost:8080
```

演示账号：`demo` / `demo123`

### 5. 自定义数据库连接

默认连接 `localhost:3306`，用户 `root`，密码通过环境变量 `MYSQL_PASSWORD` 配置，默认为 `vivo2026!`。

如需自定义：

```bash
export MYSQL_USER="你的用户名"
export MYSQL_PASSWORD="你的密码"
```

或直接修改 `src/main/resources/application.yml` 中的连接字符串。

## 技术栈

- 后端：Spring Boot 3.4.1 + Spring Data JPA
- 数据库：MySQL 8.4
- 前端：HTML + CSS + JavaScript（托管在 `src/main/resources/static/`）
- 认证：BCrypt 密码加密 + Token 令牌（服务端 ConcurrentHashMap）
- AI：vivo AIGC OpenAI 兼容接口（失败自动降级 mock）
- RAG：PDF/TXT 文档上传 → 分块 → embedding 向量化 → 检索增强对话

## 项目结构

```
lanxin-campus-ai/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/vivo/lanxin/campus/
│   │   │   ├── CampusAiApplication.java      # 入口
│   │   │   ├── config/                         # 配置类
│   │   │   ├── model/                          # JPA 实体
│   │   │   ├── repository/                     # 数据访问层
│   │   │   ├── service/                        # 业务层（Auth, AI, RAG）
│   │   │   └── web/                            # Controller + 异常处理
│   │   └── resources/
│   │       ├── application.yml                 # 主配置
│   │       └── static/                         # 前端页面
│   └── test/
│       ├── java/.../AppControllerTest.java     # 5 个集成测试
│       └── resources/application.yml           # 测试用 H2 配置
└── PROJECT_CONTEXT.md                          # 详细开发上下文
```

## 已实现功能

- 用户注册/登录（BCrypt 加密，Token 鉴权，数据隔离）
- 课堂多模态智能笔记：真实图片上传（点击/拖拽），AI 识别板书/PPT，结构化整理
- DDL 智能管理：从文本解析课程、截止日期、优先级
- AI 学习搭子：自由对话，不限话题
- RAG 知识库增强对话：上传 PDF/TXT 参考资料后，AI 基于文档内容回答，显示参考来源
- 逃课补课包：知识点、题型、自测
- 学习周报：笔记、DDL 完成情况、鼓励式反馈
- 首页仪表盘：统计看板、今日待办、最近笔记

## 主要接口

### 认证
- `POST /api/v1/user/login` — 登录
- `POST /api/v1/user/register` — 注册
- `POST /api/v1/user/logout` — 退出
- `GET /api/v1/user/profile` — 用户信息

### 笔记
- `GET /api/v1/notes` — 笔记列表
- `POST /api/v1/notes` — 创建笔记
- `GET /api/v1/notes/{id}` — 笔记详情
- `PUT /api/v1/notes/{id}` — 更新笔记
- `DELETE /api/v1/notes/{id}` — 删除笔记
- `POST /api/v1/ai/note/process` — AI 结构化笔记
- `POST /api/v1/ai/note/process-image` — AI 拍照识别（multipart, `file` 字段）

### DDL
- `GET /api/v1/reminders` — DDL 列表
- `POST /api/v1/reminders` — 创建 DDL
- `GET /api/v1/reminders/today` — 今日待办
- `GET /api/v1/reminders/priority` — 优先级排序
- `POST /api/v1/reminders/parse` — AI 解析 DDL
- `PUT /api/v1/reminders/{id}/complete` — 完成 DDL

### RAG 知识库
- `POST /api/v1/rag/documents` — 上传文档（PDF/TXT，multipart）
- `GET /api/v1/rag/documents` — 文档列表
- `DELETE /api/v1/rag/documents/{id}` — 删除文档
- `POST /api/v1/rag/chat` — RAG 增强对话

### AI & 统计
- `POST /api/v1/ai/chat` — AI 对话
- `POST /api/v1/ai/makeup/generate` — 补课包
- `GET /api/v1/reports/weekly` — 学习周报
- `GET /api/v1/stats/dashboard` — 统计看板
- `GET /api/v1/ai/provider/status` — 模型配置状态

> 除登录/注册外，所有接口需要 `Authorization: Bearer <token>` 请求头。

## 运行测试

```bash
mvn test
```

测试使用 H2 内存数据库，无需 MySQL。

## 开发注意事项

**修改前端文件后必须重新打包**，因为 `java -jar` 运行时静态资源从 jar 包内加载，不从 `target/classes/` 读取：

```bash
mvn clean package -DskipTests
java -jar target/campus-ai-1.0.0.jar
```

**图片压缩**：上传前客户端会自动压缩（Canvas 缩放至 1920px，JPEG 质量 0.7）。手机照片通常 5-15MB，不压缩会超时。

**Token 存储**：Token 在服务端内存（ConcurrentHashMap），重启后全部失效，需重新登录。

更多开发细节和关键文件说明见 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)。
