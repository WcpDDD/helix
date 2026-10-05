# Helix

研发项目的进度管理，以及人和 AI 的协同工作台。

当前版本打通了前后端：页面读取 Java API 里的项目进度。账号、进度写入和模型调用留到后续。

## 技术栈

- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia
- 后端：Java 21、Spring Boot 4.1、Maven

## 目录

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

打开 <http://localhost:5173>。开发服务器会把 `/api` 代理到 <http://localhost:8080>。

## API

- `GET /api/health`
- `GET /api/projects`
