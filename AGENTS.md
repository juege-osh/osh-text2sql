# 仓库说明

## 项目结构与模块组织
本仓库分为 `backend/` 和 `frontend/` 两部分。后端是基于 Java 17 的 Spring Boot 服务，代码位于 `backend/src/main/java/com/osh/text2sql`，测试位于 `backend/src/test/java`。运行时配置位于 `backend/src/main/resources/application.yml`。前端是基于 Vue 3 + Vite 的应用，源码位于 `frontend/src`，其中接口请求工具放在 `frontend/src/api`，共享类型定义放在 `frontend/src/types`，样式文件放在 `frontend/src/assets/styles`。部署脚本位于 `deploy/`，仓库根目录下的 shell 脚本用于本地启动、停止、测试和发布流程。

## 构建、测试与开发命令
尽量优先使用仓库根目录下的脚本：

- `./start-backend.sh`：加载 `backend/.env.local`，默认在 `9100` 端口启动 Spring Boot。
- `./start-frontend.sh`：默认在 `9101` 端口启动 Vite。
- `./test-all.sh`：先执行 `./mvnw test`，再执行 `npm ci` 和 `npm run build`。
- `./package-release.sh`：构建前端，并将前端静态资源打包进后端 jar。


模块级命令同样可用：`cd backend && ./mvnw test`、`cd frontend && npm run dev`、`npm run build`、`npm run type-check`。

## 编码风格与命名规范
遵循现有代码风格，不要引入新的风格体系。Java 代码使用 4 空格缩进，类名使用 `UpperCamelCase`，方法和字段使用 `lowerCamelCase`，包路径位于 `com.osh.text2sql` 下。Vue 和 TypeScript 文件同样使用 4 空格缩进；组件文件命名为 `UpperCamelCase.vue`，接口与类型模块使用简洁的小写文件名，例如 `query.ts`、`http.ts`。继续保持当前 controller、service、executor、introspector、validator 之间的职责分离。
- 新建类是加上类作用注释 
- 新增接口添加接口注释,请求参数实体类文件名以 `Request` 结尾，例如 `QueryRequest`。
- log 尽量写中文
## 测试规范
后端测试使用 Spring Boot Test 和 JUnit 5 风格约定。新增测试请放在 `backend/src/test/java` 下，文件名以 `Test` 结尾，例如 `PromptServiceTest`。合并前需要覆盖新的查询生成、校验逻辑和数据源行为。前端目前没有提交正式测试套件，因此前端改动至少需要执行 `npm run build` 和 `npm run type-check`。
- 不需要执行测试
- 改完之后确认下代码不能报错

## 提交与 Pull Request 规范
近期提交历史使用 Conventional Commits，例如 `feat: add kafka datasource`、`fix: harden ai prompts`。请保持这一格式：使用小写前缀，如 `feat`、`fix`、`chore` 等。Pull Request 需要包含简要说明、影响范围（`backend`、`frontend`、`deploy`）、执行过的验证命令，以及 UI 改动对应的截图。若有关联 issue，请一并链接。

## 安全与配置建议
不要提交任何密钥或敏感信息。本地覆盖配置请放在 `backend/.env.local`，数据源凭证和 DashScope 配置优先使用环境变量。修改部署行为前，请检查 `deploy/server/app.env.example` 和 GitHub Actions secrets。
