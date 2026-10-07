# Helix

研发项目的进度管理，以及人和 AI 的协同工作台。

研发流程见 [需求说明](docs/requirements.md)。唯一原则是 issue 即协同看板；任务按提案、实现、上线三个阶段推进。数字员工的编制、运行记录和调度也写在这份说明里。

当前代码打通了前后端：任务看板从数据库读取，并用 GitLab 授权码登录。本地库是 SQLite 文件 `backend/helix.db`。库为空时写入 qingflow-workspace 的 GitLab issue。单元测试也用 SQLite。MySQL 的实机校验在 `TaskMysqlE2eTests`，用 Testcontainers 起数据库。

## 技术栈

- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia
- 后端：Java 21、Spring Boot 4.1、Maven

## 目录

- `docs/requirements.md`：研发流程需求
- `frontend`：进度看板
- `backend`：项目与健康检查 API

## 启动

需要 JDK 21。如果 `java -version` 不是 21，先把 `JAVA_HOME` 指到本机的 JDK 21。

```bash
cd backend
./mvnw spring-boot:run
```

另开一个终端：

```bash
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5174>。开发服务器会把 `/api` 代理到 <http://localhost:8080>。

## 登录

登录走 GitLab 的授权码模式，实例是 `https://hackers.oalite.com`。

在 GitLab 新建一个应用（用户设置 → Applications）。回调地址填：

`http://localhost:5174/api/auth/callback`

勾选范围 `read_user`，并保持 Confidential。启动后端前设置应用的 ID 和密钥。本机也可以把它们写进 `backend/application-local.properties`，这个文件不进仓库：

```bash
export GITLAB_CLIENT_ID=应用的 Application ID
export GITLAB_CLIENT_SECRET=应用的 Secret
```

还没登录时打开页面会停在登录页。点「用 GitLab 登录」会跳到这个 GitLab。授权后回到 Helix，顶栏显示当前用户。访问令牌只留在服务端会话里。

## API

- `GET /api/health`
- `GET /api/projects`
- `GET /api/tasks`：返回库里的看板。定时治理线程在有登录身份时按主 issue 对齐状态，默认每 60 秒一轮
- `POST /api/tasks/{code}/status`：先改主 issue 的状态，成功后再改库。主 issue 没改成时返回 409，库改成主 issue 上的状态
- `GET /api/issues/{number}/body`：用当前登录的 GitLab 身份现读这张 issue 的正文，不写入库
- `GET /api/issues/{number}/notes`：用当前登录的 GitLab 身份现读这张 issue 的评论，不写入库
- `GET /api/auth/gitlab`
- `GET /api/auth/gitlab/callback`
- `GET /api/auth/me`
- `POST /api/auth/logout`
