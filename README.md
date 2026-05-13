# OSH Text2SQL

独立的 `Java 17 + Spring Boot 3 + Vue 3` 文本查数项目，目录位于 `osh-rag/osh-text2sql`。

当前能力：

- MySQL 自然语言转只读 SQL
- Redis 自然语言转只读命令
- Elasticsearch 自然语言转 `_search` DSL
- AI 中文结论总结
- 查询历史回放
- JSON / CSV 导出
- 数据源结构摘要可视化
- 服务端默认连接回落
- 独立 jar 打包与远端一键部署

## 项目结构

```text
osh-text2sql
├── backend
├── frontend
├── deploy
│   ├── local
│   └── server
├── .github/workflows
├── package-release.sh
├── test-all.sh
├── start-backend.sh
├── start-frontend.sh
├── run-backend-bg.sh
├── run-frontend-bg.sh
└── stop-services.sh
```

## 本地开发

要求：

- Java 17
- Node.js 18+

本地敏感配置放在 [backend/.env.local](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/.env.local)，不会进入 Git。

启动后端：

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./start-backend.sh
```

启动前端：

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./start-frontend.sh
```

开发访问地址：

- 前端：`http://127.0.0.1:9101`
- 后端：`http://127.0.0.1:9100/api/health`

前端默认不再保存任何数据库密码。连接字段留空时，后端自动使用服务端环境变量里的默认连接。

## 测试与打包

完整校验：

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./test-all.sh
```

构建集成前端的后端 jar：

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./package-release.sh
```

打包完成后产物位于：

- `backend/target/osh-text2sql-backend-1.0.0-SNAPSHOT.jar`

这个 jar 已经包含前端静态页面，线上只需要启动一个 Java 进程。

## 远端部署

当前默认远端约定：

- 服务器：`43.242.200.25`
- SSH 端口：`58753`
- 部署目录：`/www/osh-text2sql`
- 应用端口：`19100`

本机一键部署：

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./deploy/local/deploy-server.sh
```

远端脚本：

- `deploy/server/install.sh`
- `deploy/server/start.sh`
- `deploy/server/stop.sh`
- `deploy/server/restart.sh`
- `deploy/server/status.sh`

远端环境变量模板：

- `deploy/server/app.env.example`

线上默认健康检查：

```bash
curl http://43.242.200.25:19100/api/health
```

## GitHub Actions

工作流文件：

- [.github/workflows/deploy.yml](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/.github/workflows/deploy.yml)

建议配置的 Secrets：

- `DEPLOY_SSH_PRIVATE_KEY`
- `OSH_TEXT2SQL_DASHSCOPE_API_KEY`
- `OSH_TEXT2SQL_MYSQL_USERNAME`
- `OSH_TEXT2SQL_MYSQL_PASSWORD`
- `OSH_TEXT2SQL_REDIS_PASSWORD`
- `OSH_TEXT2SQL_ES_USERNAME`
- `OSH_TEXT2SQL_ES_PASSWORD`

建议配置的 Variables：

- `DEPLOY_HOST`
- `DEPLOY_USER`
- `DEPLOY_PORT`
- `DEPLOY_ROOT`
- `OSH_TEXT2SQL_SERVER_PORT`
- `OSH_TEXT2SQL_DASHSCOPE_MODEL`
- `OSH_TEXT2SQL_MYSQL_HOST`
- `OSH_TEXT2SQL_MYSQL_PORT`
- `OSH_TEXT2SQL_MYSQL_DATABASE`
- `OSH_TEXT2SQL_REDIS_HOST`
- `OSH_TEXT2SQL_REDIS_PORT`
- `OSH_TEXT2SQL_REDIS_DATABASE`
- `OSH_TEXT2SQL_ES_BASE_URL`

## 已处理的关键问题

- 前端 API 不再写死 `127.0.0.1:9100`
- 查询“总共有多少个用户”时，会优先命中真正的用户主表
- 仓库中不再保留数据库密码和 DashScope API Key
- 线上部署不依赖已有容器，不触碰现有 `:80` 和 `:8081` 服务
