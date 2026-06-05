# OSH Text2SQL 技术教程

本文档面向两类读者：

- 需要快速接手 `osh-text2sql` 的开发、运维或二次定制人员
- 需要理解“自然语言查数”背后完整技术链路的工程师

项目根目录位于：

- `/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql`

## 1. 项目定位

`osh-text2sql` 是一个独立部署的文本查数工作台，目标不是做通用 BI 平台，而是把“自然语言 -> 只读查询 -> 结果表格 -> 中文结论”这一条链路打通，并且在默认环境下第一遍就能直接用。

当前支持的数据源：

- MySQL
- Redis
- Elasticsearch
- Kafka

当前项目的核心设计思想有四个：

1. 前后端都尽量轻依赖，部署成本低。
2. 查询必须可控，所有执行都限定在只读边界内。
3. AI 不是单点依赖，没有模型也能跑基础能力。
4. 用户端尽量不暴露真实连接信息，默认连接回落到服务端环境变量。

## 2. 整体架构

整体是一个前后端分离开发、后端单 jar 一体化交付的项目：

```text
浏览器 Vue 页面
    -> /api/query/*
Spring Boot 后端
    -> 连接信息解析
    -> 数据源结构探测
    -> AI / 规则生成查询
    -> 只读安全校验
    -> 多数据源执行器
    -> 中文结论生成
返回统一 QueryResponse
```

更细一点的运行链路：

```text
用户输入自然语言
    -> Text2QueryController 接收请求
    -> Text2QueryService.query()
    -> ConnectionProfileResolver 合并默认连接
    -> Introspector 抽取结构摘要
    -> PromptService 生成查询
    -> Executor 执行查询
    -> PromptService 总结结果
    -> 前端渲染查询、结果表格、中文结论
```

对应关键入口文件：

- [Text2QueryController.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java)
- [Text2QueryService.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java)
- [PromptService.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java)
- [App.vue](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/frontend/src/App.vue)

## 3. 目录结构说明

推荐先按目录理解项目，而不是直接从某个 controller 开始读。

```text
osh-text2sql
├── backend                     # Spring Boot 后端
│   ├── src/main/java/com/osh/text2sql
│   │   ├── config             # 配置绑定
│   │   ├── controller         # HTTP 接口
│   │   ├── dto                # 请求响应与领域对象
│   │   ├── exception          # 统一异常处理
│   │   ├── executor           # 各数据源执行器
│   │   ├── introspect         # 各数据源结构探测器
│   │   ├── service            # 核心编排与 AI 逻辑
│   │   └── util               # 校验器、JSON 工具等
│   └── src/test/java          # 单元测试
├── frontend                    # Vue 3 + Vite 前端
│   └── src
│       ├── api                # Axios 封装
│       ├── assets/styles      # 全局样式
│       ├── components         # UI 组件
│       └── types              # TS 类型定义
├── deploy
│   ├── local                  # 本地一键部署脚本
│   └── server                 # 远端安装、启停、重启脚本
├── .github/workflows          # GitHub Actions 自动部署
├── package-release.sh         # 前端构建 + 后端打包
├── test-all.sh                # 后端测试 + 前端构建校验
└── README.md
```

## 4. 技术栈与版本选择

### 4.1 后端

后端使用：

- Java 17
- Spring Boot 3.4.8
- Spring AI 1.1.0
- Spring AI Alibaba DashScope Starter
- Spring JDBC
- MySQL Connector/J
- Jedis
- Apache HttpClient 5
- Kafka Clients
- Jackson
- Hutool

对应配置在：

- [backend/pom.xml](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/pom.xml)

这套组合的原因比较务实：

- Spring Boot 3 + Java 17 便于部署，生态稳定。
- MySQL 使用 `JdbcTemplate` 足够，不必为了只读查数引入完整 ORM。
- Redis 直接用 Jedis，执行只读命令和适配返回值都更直接。
- Elasticsearch 用 HttpClient 调 `_search`，避免过重的客户端封装。
- Kafka 同时使用 `AdminClient` 和 `KafkaConsumer`，分别处理 topic 元数据与消息读取。
- AI 部分接入 Spring AI，方便以后切模型，而不是把某家厂商 API 写死在业务代码里。

### 4.2 前端

前端使用：

- Vue 3
- Vite 5
- TypeScript
- Element Plus
- Axios

前端并不是一个复杂的多页系统，而是一个单页工作台，重点是查询效率和结果可读性。

## 5. 后端核心配置

主要配置文件：

- [application.yml](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/resources/application.yml)

几个关键点需要特别理解。

### 5.1 服务端口

```yaml
server:
  port: ${OSH_TEXT2SQL_SERVER_PORT:9100}
```

默认本地端口是 `9100`，线上通常通过环境变量改成 `19100`。

### 5.2 AI 开关不是强依赖

```yaml
spring:
  ai:
    chat:
      client:
        enabled: ${OSH_TEXT2SQL_DASHSCOPE_ENABLED:false}
```

如果 `OSH_TEXT2SQL_DASHSCOPE_ENABLED=false`，或者没有可用 `api-key`，系统仍然能启动，只是进入规则兜底模式。

### 5.3 多数据源默认连接

`osh.text2sql.datasources.*` 下配置了 MySQL、Redis、Elasticsearch、Kafka 的默认连接信息。前端允许这些字段留空，后端会自动补齐。

这意味着：

- 浏览器不用长期保存真实密码
- 用户临时覆盖连接时只需要改少量字段
- 部署到不同环境时只需要改环境变量，不需要改前端代码

## 6. 核心接口说明

查询接口集中在：

- [Text2QueryController.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java)

对外接口如下：

- `POST /api/query`
- `POST /api/query/test-connection`
- `GET /api/query/schema`
- `POST /api/query/schema`
- `GET /api/query/snapshot`
- `GET /api/health`

接口职责：

- `/api/query`：主查询接口，自然语言或 RAW 模式都走这里。
- `/api/query/test-connection`：测试当前连接是否可用。
- `/api/query/schema`：获取结构摘要，`GET` 用默认连接，`POST` 用临时连接。
- `/api/query/snapshot`：返回首页展示的数据源概览和示例提示语。
- `/api/health`：健康检查。

## 7. 主查询链路详解

主逻辑在：

- [Text2QueryService.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java)

`query()` 方法大致分成六步。

### 7.1 解析连接信息

先通过 `ConnectionProfileResolver` 合并前端传入的连接和服务端默认连接：

- [ConnectionProfileResolver.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/ConnectionProfileResolver.java)

这个类做的不是简单赋值，而是按数据源类型分别补字段：

- MySQL：`host`、`port`、`database`、`username`、`password`
- Redis：`host`、`port`、`database`、`password`
- Elasticsearch：`baseUrl`、`username`、`password`
- Kafka：`bootstrapServers`、`securityProtocol`、`saslMechanism`、`username`、`password`

这里是整个项目“默认连接回落”能力的关键。

### 7.2 提取数据结构摘要

主服务不会盲目把整库、整索引、整 topic 元数据丢给模型，而是调用不同 `Introspector` 获取可控的结构摘要。

涉及文件：

- [MysqlIntrospector.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlIntrospector.java)
- [RedisIntrospector.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/RedisIntrospector.java)
- [ElasticsearchIntrospector.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/ElasticsearchIntrospector.java)
- [KafkaIntrospector.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/KafkaIntrospector.java)

其中 MySQL 做得最细，因为表最多、歧义也最多。

### 7.3 生成查询

如果是 `RAW` 模式，后端直接使用用户提供的原始查询，但仍然会继续走服务端安全校验。

如果是 `AUTO` 模式，就走：

- [PromptService.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java)

`PromptService.generateQuery()` 会：

1. 判断 `ChatClient` 是否可用
2. 可用则调用 AI 生成 JSON 结构的查询结果
3. 不可用或失败时退化到规则生成
4. 对 MySQL 用户统计场景做额外纠偏

### 7.4 执行查询

根据数据源类型选中不同执行器：

- [MysqlQueryExecutor.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/MysqlQueryExecutor.java)
- [RedisQueryExecutor.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/RedisQueryExecutor.java)
- [ElasticsearchQueryExecutor.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/ElasticsearchQueryExecutor.java)
- [KafkaQueryExecutor.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/KafkaQueryExecutor.java)

### 7.5 AI 结果总结

查询结果执行完后，如果 `osh.text2sql.ai.explain-result=true`，后端会尝试再生成一段中文结论。

如果 AI 总结失败，则回退到 `result.getSummary()` 这种规则摘要，不影响查询成功。

### 7.6 返回统一响应结构

最终统一返回 `QueryResponse`，包含：

- `schema`
- `generatedQuery`
- `result`
- `answer`

这让前端不需要针对 MySQL、Redis、ES、Kafka 写四套页面模型。

## 8. AI 生成与规则兜底机制

这是这个项目可用性的核心。

### 8.1 为什么不能只靠大模型

如果只靠 AI，有几个问题：

- 模型服务不稳定时，整个系统不可用
- 模型可能输出不合法 JSON
- 模型可能把“用户数量”理解成带 `user_id` 的业务表统计
- 有些场景实际是机械规则，更适合本地直接处理

所以当前项目使用的是“AI 优先，规则兜底，执行前强校验”的思路。

### 8.2 Prompt 输出固定结构

`PromptService` 要求模型只输出 JSON，字段固定：

- `query`
- `reasoning`
- `safetyNotes`

这样可以避免让前端或执行器去解析一段含自然语言描述的复杂文本。

### 8.3 使用 `UserMessage` 避免模板重渲染问题

当前实现不是直接 `.user(String)`，而是：

- `messages(new UserMessage(...))`

这一步很关键。原因是查询结构、DSL JSON、schema 摘要里有大量花括号，如果直接走某些模板渲染路径，容易被二次解释，导致内容变形。

### 8.4 无 AI 时的兜底能力

`fallbackGenerateQuery()` 针对不同数据源做了规则化生成：

- MySQL：根据问题关键词和表结构生成 `SELECT` 或 `COUNT(*)`
- Redis：默认生成只读命令，优先 `SCAN`
- Elasticsearch：生成 `_search` body JSON
- Kafka：生成自定义只读查询 DSL

这意味着即使没有模型，基础演示和大部分明确问题仍然可用。

## 9. MySQL 细节设计

MySQL 是最容易“看起来能查，实际查错”的数据源，所以单独讲。

### 9.1 Schema 不是全量展开

`MysqlIntrospector` 的两个重要常量：

- `MAX_TABLES = 30`
- `MAX_COLUMNS = 20`

它不会把整个数据库几百张表全部交给 AI，而是：

1. 读取当前库的表清单
2. 基于用户问题抽取关键词
3. 给表名和表注释打分
4. 只保留前 30 张相关表
5. 每张表最多保留前 20 个字段

这样做有三个好处：

- 控制 prompt token 长度
- 减少模型在无关表中误判
- 响应更快

### 9.2 用户总数问题的纠偏

用户之前已经遇到过“查用户数量结果乱查”的问题，所以现在专门做了修正。

`PromptService` 中有两层保护：

1. Prompt 规则明确要求优先使用真正的用户主表，而不是看到 `user_id` 就乱统计。
2. `normalizeMysqlUserCountQuery()` 会在 AI 生成后再强制纠偏。

优先用户表大致规则是：

- `osh_user`
- `user`
- `users`
- `sys_user`
- 其他以 `_user` 结尾或明显用户主表的表

这部分设计的意义不是“追求模型更聪明”，而是把高频高风险问题从模型能力转成工程规则。

### 9.3 SQL 安全边界

SQL 校验在：

- [SqlSafetyValidator.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/util/SqlSafetyValidator.java)

规则包括：

- 只允许 `SELECT` 或 `WITH`
- 去掉末尾分号
- 禁止多语句执行
- 禁止 `INSERT`、`UPDATE`、`DELETE`、`DROP`、`ALTER` 等危险关键字
- 如果没有 `LIMIT`，自动补 `LIMIT query-limit`

这里的设计原则很明确：

- 宁可少支持一点，也不能把系统变成数据库写入入口

## 10. Redis 细节设计

Redis 的特点是“并没有 SQL”，所以项目实际做的是文本转只读命令。

### 10.1 命令白名单

校验文件：

- [RedisCommandValidator.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/util/RedisCommandValidator.java)

允许的命令包括：

- `GET`
- `MGET`
- `HGET`
- `HGETALL`
- `LRANGE`
- `SMEMBERS`
- `ZRANGE`
- `SCAN`
- `TTL`
- `TYPE`
- `EXISTS`

重点是：

- 不允许写命令
- 不鼓励 `KEYS`
- 查看 key 时优先 `SCAN`

### 10.2 结构摘要与结果适配

Redis 执行结果天然不统一，可能是：

- 字符串
- 列表
- 哈希
- 集合
- 有序集合

执行器会把不同类型尽量转成统一的行列结构，保证前端能用表格展示。

## 11. Elasticsearch 细节设计

Elasticsearch 模式下，AI 生成的不是“HTTP 请求”，而是 `_search` 的 body JSON。

### 11.1 DSL 约束

校验文件：

- [ElasticsearchQueryValidator.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/util/ElasticsearchQueryValidator.java)

当前规则要求：

- 输入必须是合法 JSON
- 不能包含明显高风险字段
- 执行时必须能确定 `_index`

### 11.2 执行模式

执行器会把 `_index` 从请求体中取出，然后调用：

- `POST /{index}/_search`

再把返回结果里的 `_source` 抽出来，映射成统一行数据。

这意味着：

- 前端看到的结果和 MySQL 一样是表格
- 后端仍然保留 `rawResponse` 便于调试

## 12. Kafka 细节设计

Kafka 是这次项目里最像“额外扩展能力”的部分，因为它不是传统数据库，但非常适合做消息排查。

### 12.1 Kafka 不是 SQL，而是自定义查询 DSL

Kafka 当前只支持三种操作：

- `LIST_TOPICS`
- `DESCRIBE_TOPIC`
- `READ_MESSAGES`

校验文件：

- [KafkaQueryValidator.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/util/KafkaQueryValidator.java)

常见字段：

- `operation`
- `topic`
- `partition`
- `limit`
- `from`
- `offset`
- `includeInternal`
- `keyContains`
- `valueContains`

### 12.2 读取消息的执行逻辑

执行器：

- [KafkaQueryExecutor.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/KafkaQueryExecutor.java)

内部流程大致是：

1. `AdminClient` 拉 topic 元信息和 offset
2. `KafkaConsumer` 按 topic / partition 建立读取
3. 按 `LATEST`、`EARLIEST` 或 `OFFSET` 定位
4. 轮询消息
5. 按 `keyContains` / `valueContains` 做本地过滤
6. 转成统一表格返回

这里有两个很实用的点：

- 查最近消息时默认按末尾 offset 倒推
- 可以通过 key / value 子串过滤做问题排查

## 13. 前端工作台设计

主页面在：

- [App.vue](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/frontend/src/App.vue)

前端不是简单表单，而是一个单页仪表盘，主要分五块：

1. Hero 区，解释产品能力
2. 数据源状态条，显示健康状态、API 入口、当前连接摘要
3. 查询控制面板，包含数据源切换、连接覆盖、自然语言输入
4. 执行结果区，展示生成查询、结果表格、AI 结论
5. 结构摘要和历史记录区

### 13.1 为什么前端允许连接留空

这是一个很重要的产品化决策。

如果要求用户每次都输入完整连接：

- 会暴露密码
- 会降低使用效率
- 很难用于团队共享环境

所以当前设计是：

- 页面展示可编辑连接项
- 用户可以临时覆盖
- 留空时由后端补默认值

### 13.2 API 地址设计

前端 Axios 入口：

- [http.ts](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/frontend/src/api/http.ts)

默认逻辑：

- `VITE_API_BASE_URL` 有值时使用它
- 否则使用 `/api`

开发环境通过 Vite 代理把 `/api` 指到本地 Spring Boot。

### 13.3 查询体验优化

前端已经支持：

- 自动模式 / RAW 模式切换
- 查询历史回放
- 测试连接
- 结构刷新
- JSON 导出
- CSV 导出
- 示例问题一键填充

## 14. 统一返回模型

前端 TS 类型定义在：

- [query.ts](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/frontend/src/types/query.ts)

主响应模型：

```ts
interface QueryResponse {
  schema: { ... }
  generatedQuery: { ... }
  result: { ... }
  answer: string
}
```

这个结构很关键，因为它把“生成过程”和“执行结果”拆开了：

- `generatedQuery.query` 用来解释模型到底生成了什么
- `generatedQuery.reasoning` 用来解释为什么这么生成
- `result.executedQuery` 表示最终执行的查询
- `answer` 则是人类更容易读的结论

这对排错很重要。用户说“查错了”时，不需要猜：

1. 是结构摘要错了
2. 还是模型生成错了
3. 还是执行器行为错了
4. 还是结果解释错了

## 15. 典型 API 用法

### 15.1 查询 MySQL 用户数量

请求：

```bash
curl -X POST http://127.0.0.1:9100/api/query \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "MYSQL",
    "mode": "AUTO",
    "question": "系统总共有多少个用户"
  }'
```

服务端会做的事情：

1. 使用默认 MySQL 连接
2. 选出和“用户”最相关的表
3. AI 或规则生成统计 SQL
4. 如果命中了错误业务表，再做用户主表纠偏
5. 执行只读 SQL 并返回结果

### 15.2 手动执行 Redis 命令

```bash
curl -X POST http://127.0.0.1:9100/api/query \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "REDIS",
    "mode": "RAW",
    "question": "列出前 20 个 key",
    "rawQuery": "SCAN 0"
  }'
```

### 15.3 查询 Elasticsearch

```bash
curl -X POST http://127.0.0.1:9100/api/query \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "ELASTICSEARCH",
    "mode": "AUTO",
    "question": "查询 osh_course_index 中销量最高的 5 个课程"
  }'
```

### 15.4 查看 Kafka 最近消息

```bash
curl -X POST http://127.0.0.1:9100/api/query \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "KAFKA",
    "mode": "AUTO",
    "question": "查看 user-action topic 最近 10 条消息"
  }'
```

## 16. 本地开发教程

### 16.1 环境要求

- Java 17
- Node.js 18+
- 可访问目标数据源

本地敏感配置放在：

- `backend/.env.local`

这个文件不会进 Git，适合作为个人或服务器环境变量入口。

### 16.2 启动后端

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./start-backend.sh
```

脚本会：

1. 尝试切到 Java 17
2. 加载 `backend/.env.local`
3. 执行 `./mvnw spring-boot:run`

### 16.3 启动前端

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./start-frontend.sh
```

默认前端端口：

- `9101`

默认访问地址：

- 前端：`http://127.0.0.1:9101`
- 后端健康检查：`http://127.0.0.1:9100/api/health`

### 16.4 完整校验

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./test-all.sh
```

这个脚本会执行：

1. `backend` 的 `./mvnw test`
2. `frontend` 的 `npm ci`
3. `frontend` 的 `npm run build`

这不是完整集成测试，但已经覆盖了：

- 主要校验器
- PromptService 关键逻辑
- MySQL 结构筛选逻辑
- 前端构建是否通过

当前可见的后端测试包括：

- `MysqlIntrospectorTest`
- `PromptServiceTest`
- `KafkaQueryValidatorTest`
- `RedisCommandValidatorTest`
- `SqlSafetyValidatorTest`

## 17. 打包机制

打包脚本：

- [package-release.sh](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/package-release.sh)

执行：

```bash
cd /Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql
./package-release.sh
```

流程是：

1. 前端 `npm ci`
2. 前端 `npm run build`
3. 后端 `mvn clean package -DskipTests`
4. Maven 在 `prepare-package` 阶段把 `frontend/dist` 复制进后端静态目录
5. Spring Boot 重新打包成可运行 jar

最终产物：

- `backend/target/osh-text2sql-backend-1.0.0-SNAPSHOT.jar`

这个 jar 已经包含前端静态页面，线上只需要一个 Java 进程即可提供前后端服务。

## 18. 部署设计

本项目刻意避免依赖已有服务和容器，尽量不碰其他业务。

### 18.1 本地一键部署脚本

脚本：

- [deploy-server.sh](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/deploy/local/deploy-server.sh)

作用：

1. 加载本地 `backend/.env.local`
2. 自动判断是否启用 DashScope
3. 强制生成远端环境变量文件
4. 打包前后端一体 jar
5. 上传 jar 与远端脚本
6. 远端重启服务
7. 轮询健康检查

### 18.2 远端脚本

远端脚本目录：

- `deploy/server/install.sh`
- `deploy/server/start.sh`
- `deploy/server/stop.sh`
- `deploy/server/restart.sh`
- `deploy/server/status.sh`

这些脚本的目标是让线上操作标准化，不用每次手工找 PID 或手敲长命令。

### 18.3 默认部署约定

当前 README 中约定的默认线上值是：

- 主机：`43.242.200.25`
- SSH 端口：`58753`
- 部署目录：`/www/osh-text2sql`
- 应用端口：`19100`

只要环境变量不冲突，它不会影响已有 `:80`、`:8081` 或现有容器服务。

## 19. GitHub Actions 自动部署

工作流文件：

- [deploy.yml](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/.github/workflows/deploy.yml)

触发条件：

- push 到 `main`
- push 到 `master`
- push 到 `release/**`
- 手动触发 `workflow_dispatch`

工作流做的事情：

1. checkout 代码
2. 安装 Node.js 和 Java
3. 构建前端
4. 构建后端 jar
5. 校验 jar
6. 建立 SSH 代理
7. 写远端环境变量文件
8. 上传 jar 和部署脚本
9. 远端重启服务
10. 健康检查

建议重点配置的 Secrets：

- `DEPLOY_SSH_PRIVATE_KEY`
- `OSH_TEXT2SQL_DASHSCOPE_API_KEY`
- `OSH_TEXT2SQL_AICODEE_API_KEY`
- `OSH_TEXT2SQL_OPENAI_API_KEY`
- `OSH_TEXT2SQL_MYSQL_USERNAME`
- `OSH_TEXT2SQL_MYSQL_PASSWORD`
- `OSH_TEXT2SQL_REDIS_PASSWORD`
- `OSH_TEXT2SQL_ES_USERNAME`
- `OSH_TEXT2SQL_ES_PASSWORD`
- `OSH_TEXT2SQL_KAFKA_USERNAME`
- `OSH_TEXT2SQL_KAFKA_PASSWORD`

建议配置的 Variables：

- `DEPLOY_HOST`
- `DEPLOY_USER`
- `DEPLOY_PORT`
- `DEPLOY_ROOT`
- `OSH_TEXT2SQL_SERVER_PORT`
- `OSH_TEXT2SQL_DASHSCOPE_ENABLED`
- `OSH_TEXT2SQL_DASHSCOPE_MODEL`
- `OSH_TEXT2SQL_AI_PROVIDER`
- `OSH_TEXT2SQL_AI_REASONING_EFFORT`
- `OSH_TEXT2SQL_AICODEE_BASE_URL`
- `OSH_TEXT2SQL_AICODEE_MODEL`
- `OSH_TEXT2SQL_AICODEE_COMPLETIONS_PATH`
- `OSH_TEXT2SQL_OPENAI_BASE_URL`
- `OSH_TEXT2SQL_OPENAI_MODEL`
- `OSH_TEXT2SQL_OPENAI_COMPLETIONS_PATH`
- `OSH_TEXT2SQL_MYSQL_HOST`
- `OSH_TEXT2SQL_MYSQL_PORT`
- `OSH_TEXT2SQL_MYSQL_DATABASE`
- `OSH_TEXT2SQL_REDIS_HOST`
- `OSH_TEXT2SQL_REDIS_PORT`
- `OSH_TEXT2SQL_REDIS_DATABASE`
- `OSH_TEXT2SQL_ES_BASE_URL`
- `OSH_TEXT2SQL_KAFKA_BOOTSTRAP_SERVERS`
- `OSH_TEXT2SQL_KAFKA_SECURITY_PROTOCOL`
- `OSH_TEXT2SQL_KAFKA_SASL_MECHANISM`
- `OSH_TEXT2SQL_HBASE_ZOOKEEPER_QUORUM`
- `OSH_TEXT2SQL_HBASE_ZOOKEEPER_CLIENT_PORT`
- `OSH_TEXT2SQL_HBASE_ZNODE_PARENT`
- `OSH_TEXT2SQL_HBASE_NAMESPACE`

当前仓库已按 `43.242.200.25` 预置 `DEPLOY_*` 与基础数据源变量。线上默认 AI provider 使用 `dashscope`，因此至少需要配置 `OSH_TEXT2SQL_DASHSCOPE_API_KEY`；如果切到 `aicodee` 或 `openai`，再分别配置对应 API Key。

## 20. 安全边界总结

这个项目本质上是“让用户用自然语言碰数据”，安全边界必须写死在后端，而不是寄希望于前端按钮。

当前安全边界如下：

### 20.1 MySQL

- 只允许 `SELECT` / `WITH`
- 禁止写入、DDL、导出类操作
- 自动补 `LIMIT`

### 20.2 Redis

- 只允许白名单只读命令
- 默认鼓励 `SCAN`
- 不允许写命令

### 20.3 Elasticsearch

- 只接受 JSON DSL
- 只走 `_search`
- 阻断高风险字段

### 20.4 Kafka

- 只支持列 topic、查 topic 详情、读消息
- 限制 `limit`
- 限制 `from`
- 校验 `offset` 和 `partition`

安全这块的判断原则不是“做全功能客户端”，而是“做一个受控的数据观察工具”。

## 21. 常见问题排查

### 21.1 为什么前端连不上后端

先看：

```bash
curl http://127.0.0.1:9100/api/health
```

如果本地前端报网络错误，再看：

- `VITE_API_BASE_URL` 是否设置错误
- 开发代理是否正常
- 后端端口是否被改成了非 `9100`

### 21.2 为什么 AI 没生效

先看环境变量：

- `OSH_TEXT2SQL_DASHSCOPE_ENABLED`
- `OSH_TEXT2SQL_DASHSCOPE_API_KEY`

即使 AI 不生效，系统仍然可以用，只是会走规则兜底。

### 21.3 为什么 MySQL 查到的不是用户主表

先看返回里的：

- `generatedQuery.query`
- `generatedQuery.reasoning`

如果问题是“系统有多少个用户”，但仍然查错，应优先检查：

1. 目标数据库里是否真的有标准用户表
2. 表名是否过于业务化，无法被规则识别
3. schema 摘要里是否因为关键词不足未包含该表

### 21.4 为什么 Kafka 查不到消息

重点看：

- topic 是否存在
- partition 是否指定错误
- `from` 是否正确
- consumer 凭证是否正确
- `keyContains` / `valueContains` 过滤是否过严

## 22. 二次开发建议

如果你准备继续扩展这个项目，建议按下面的思路做。

### 22.1 新增一个数据源

至少要补四层：

1. `DatasourceType` 新枚举
2. `Introspector` 结构探测
3. `Executor` 执行器
4. `PromptService` 的 prompt 约束和 fallback 规则

同时还要补：

- 前端类型定义
- 数据源切换 UI
- 示例问题
- 测试

### 22.2 提升 MySQL 准确率

建议优先做工程强化，而不是只改 prompt：

- 加更细的业务词典
- 加主表优先级配置
- 加列级语义标签
- 加查询结果二次校验

### 22.3 做企业级权限隔离

当前项目偏单租户运维工具，如果要走更正式场景，建议增加：

- 用户登录
- 数据源权限隔离
- 操作审计
- 查询日志持久化
- 限流和配额

## 23. 阅读代码的推荐顺序

如果你要快速接手项目，建议按这个顺序读：

1. [README.md](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/README.md)
2. [Text2QueryController.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java)
3. [Text2QueryService.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java)
4. [PromptService.java](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java)
5. `introspect/*`
6. `executor/*`
7. `util/*Validator*`
8. [App.vue](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/frontend/src/App.vue)
9. [deploy-server.sh](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/deploy/local/deploy-server.sh)
10. [deploy.yml](/Users/rengang/chuangye/osh-projects/osh-rag/osh-text2sql/.github/workflows/deploy.yml)

这样能先看“主干编排”，再看“具体数据源实现”，最后看“交付链路”。

## 24. 一句话总结

`osh-text2sql` 不是一个只会把自然语言翻成 SQL 的 demo，它更像一个带安全边界、带默认连接回落、带多数据源执行器、带 AI/规则双通路、可直接部署上线的文本查数工作台。

如果你要继续维护它，最值得优先守住的三件事是：

1. 查询准确性
2. 只读安全边界
3. 一体化交付稳定性
