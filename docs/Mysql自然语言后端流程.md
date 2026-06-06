# MySQL 自然语言后端流程

本文描述当前项目里 **MySQL 自然语言查询** 的后端完整执行链路。内容基于当前代码实现，不是抽象设计稿。

涉及的主要类：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java)
- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java)
- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java)
- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlIntrospector.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlIntrospector.java)
- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlSchemaCacheService.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlSchemaCacheService.java)
- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlQueryAnalyzer.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlQueryAnalyzer.java)
- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/MysqlQueryExecutor.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/MysqlQueryExecutor.java)

---

## 1. 总体流程

```text
前端提交问题
  ->
Text2QueryController.query()
  ->
Text2QueryService.query()
  ->
ConnectionProfileResolver 解析 MySQL 连接
  ->
schemaForQuestion(MYSQL, profile, question)
  ->
MysqlIntrospector.introspect(profile, question)
  ->
优先读 Redis 结构缓存
  ->
按问题筛表 + 裁剪结构
  ->
PromptService.generateQuery()
  ->
AI 生成 SQL
  ->
PromptService.normalizeGeneratedQuery()
  ->
MysqlQueryExecutor.execute()
  ->
SqlSafetyValidator 校验只读 SQL
  ->
JdbcTemplate 执行 SQL
  ->
PromptService.explainResult() 总结结果
  ->
返回 QueryResponse
```

这里有两个关键阶段：

1. **给 AI 准备尽量小但足够用的结构摘要**
2. **把 AI 生成的 SQL 做安全校验后执行**

---

## 2. 请求入口

入口在：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/controller/Text2QueryController.java)

核心入口方法：

```java
public QueryResponse query(@Valid @RequestBody QueryRequest request)
```

这里会把前端的：

- `type`
- `mode`
- `question`
- `rawQuery`
- `connection`

交给 `Text2QueryService.query()`。

---

## 3. Text2QueryService 主链路

主逻辑在：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/Text2QueryService.java)

核心代码顺序可以概括为：

```text
1. 解析连接 profile
2. 读取与问题相关的 schema
3. AUTO 模式走 AI 生成 SQL，RAW 模式直接执行用户输入
4. 交给 MySQL 执行器执行
5. 可选走 AI 总结结果
6. 返回统一响应
```

对应核心代码：

```java
ConnectionProfile profile = profileResolver.resolve(request.getConnection(), request.getType());
DatasourceSchemaResponse schema = schemaForQuestion(request.getType(), profile, request.getQuestion());

GeneratedQuery generatedQuery = request.getMode() == QueryMode.RAW
    ? ...
    : promptService.generateQuery(request.getType(), request.getQuestion(), schema);

QueryExecutionResult result = executor(request.getType()).execute(profile, generatedQuery.getQuery());
```

---

## 4. 连接信息如何确定

连接信息通过 `ConnectionProfileResolver` 解析。

目标是得到一个统一的 `ConnectionProfile`，里面至少包含：

- `host`
- `port`
- `database`
- `username`
- `password`

当前前端页面已经不再暴露连接参数输入，正常情况下直接走服务端默认 MySQL 配置。

所以当前 MySQL 自然语言查询的实际连接来源是：

```text
application.yml / 环境变量里的默认 MySQL 配置
```

---

## 5. schemaForQuestion：只为当前问题准备结构

### 5.1 入口

`Text2QueryService.query()` 不会直接把全库结构扔给 AI，而是先调用：

```java
schemaForQuestion(DatasourceType type, ConnectionProfile profile, String question)
```

对于 MySQL，这一步最终走到：

- `MysqlIntrospector.introspect(profile, question)`

---

## 6. MysqlIntrospector：优先用缓存，再按问题筛表

核心类：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlIntrospector.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlIntrospector.java)

### 6.1 总体逻辑

```text
MysqlIntrospector.introspect(profile, question)
  ->
如果有 question：
  优先查 Redis 的“仅列摘要缓存”
    ->
    命中：先按列摘要筛表，最后只给入选表补索引
    ->
    未命中：直接查 MySQL，只读取候选表结构

如果没有 question：
  走完整缓存 payload
    ->
    命中：返回默认摘要
    ->
    未命中：读取全量结构并写回缓存
```

对应代码分支：

```java
if (question != null && !question.isBlank()) {
    Optional<MysqlSchemaCachePayload> cachedColumnsPayload = cacheService.getSchemaColumnsOnly(profile)
        .filter(this::hasTableMetadata);
    if (cachedColumnsPayload.isPresent()) {
        response = buildResponse(cachedColumnsPayload.get(), question, true, profile);
    } else {
        response = loadQuestionScopedSchema(profile, question);
    }
} else {
    Optional<MysqlSchemaCachePayload> cachedPayload = cacheService.getSchema(profile)
        .filter(this::hasTableMetadata);
    if (cachedPayload.isPresent()) {
        response = buildResponse(cachedPayload.get(), null, false, profile);
    } else {
        MysqlSchemaCachePayload payload = refreshCache(profile);
        response = buildResponse(payload, null, false, profile);
    }
}
```

### 6.2 文本流程图

```text
MysqlIntrospector.introspect()
  ->
根据是否有问题，选择完整缓存或仅列摘要缓存
  ->
是否命中?
  |-- 是 -> buildResponse(payload, question, ...)
  |
  |-- 否且有问题 -> loadQuestionScopedSchema(profile, question)
  |
  |-- 否且没问题 -> refreshCache(profile) -> buildResponse(payload, null, ...)
```

---

## 7. Redis 结构缓存是怎么读的

核心类：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlSchemaCacheService.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlSchemaCacheService.java)

### 7.1 缓存结构

当前缓存 key 设计基于：

```text
{keyPrefix}:{host}:{port}:{database}:v2
```

后面再拆成：

- `:meta`
- `:tables`
- `:table:{tableName}:columns`
- `:table:{tableName}:indexes`

### 7.2 完整缓存命中读取流程

```text
get(meta)
  ->
get(tables)
  ->
mget(all table columns keys)
  ->
mget(all table indexes keys)
  ->
反序列化为 MysqlSchemaCachePayload
```

注意这里已经做过性能优化：

- **不是一张表一张表单独 get**
- 而是 columns 和 indexes 各做一次批量 `mget`

对应代码：

```java
List<String> columnsRawList = redis.getMany(columnKeys);
List<String> indexesRawList = redis.getMany(indexKeys);
```

### 7.3 文本流程图

```text
Redis 完整缓存命中
  ->
读 meta
  ->
读 tables
  ->
mget 所有 columns
  ->
mget 所有 indexes
  ->
组装 payload
```

### 7.4 针对自然语言问题的“仅列摘要缓存”流程

现在 MySQL AUTO 查询命中缓存时，优先不是拿完整 payload，而是拿“列摘要版”：

```text
get(meta)
  ->
get(tables)
  ->
mget 所有 columns
  ->
先不读 indexes
  ->
按问题筛表
  ->
只为最终候选表 mget 对应 indexes
```

这个设计的目标就是：

- 不在一开始把所有表索引都从 Redis 拿出来
- 先靠表名、表注释、列摘要做筛选
- 只有最终候选表才补索引给 prompt

---

## 8. 问题是如何筛成少量表的

核心类：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlQueryAnalyzer.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/introspect/MysqlQueryAnalyzer.java)

### 8.1 它做什么

它不负责生成 SQL，只负责：

1. 从问题里抽取语义词
2. 判断意图
3. 计算候选表上限
4. 对表做打分排序
5. 给出 `preferredTable`

输出对象是：

- `MysqlQueryPlan`

里面关键字段：

- `intent`
- `limit`
- `candidateLimit`
- `candidateTables`
- `preferredTable`

### 8.2 语义词词典

当前内置了一套 term dictionary，例如：

- `user -> 用户/账号/user/users`
- `feedback -> 反馈/工单/feedback`
- `course -> 课程/course`
- `tool -> 工具/tool`
- `quota -> 配额/可用次数/剩余次数`

所以像：

```text
查询可用工具的数量
```

会被识别出类似：

- `tool`
- `COUNT`

### 8.3 意图识别

当前主要几种意图：

- `COUNT`
- `LIST_RECENT`
- `TOP_N`
- `FILTER`
- `LIST`

例如：

```text
统计总共有多少个用户 -> COUNT
查询最近 5 条反馈 -> LIST_RECENT
价格最高的 5 本书 -> TOP_N
```

### 8.4 候选表上限

不是固定 8 张表，而是动态决定。当前实现已经比以前更激进：

```text
显式表名问题 -> 最多 3 张
无明显语义问题 -> 最多 8 张
COUNT 类问题 -> 最多 5 张
用户工具配额类强语义 FILTER -> 最多 4 张
LIST_RECENT / TOP_N -> 最多 6 张
普通问题 -> 最多 8 张
```

所以现在很多常见问题只会给 3 到 6 张表，不再是过去那种 8 到 12 张的默认规模。

### 8.5 表打分逻辑

综合考虑：

- 是否显式提到表名
- 是否 `osh_` 业务前缀
- 表名/表注释是否命中语义词
- 列名是否命中语义词
- 是否像用户主表
- 是否像配额表
- 是否具备 recent/top/filter 相关字段

最终输出一个有序候选表列表。

### 8.6 文本流程图

```text
自然语言问题
  ->
MysqlQueryAnalyzer.analyze()
  ->
抽取 normalizedTerms
  ->
识别 intent
  ->
识别 explicitTables
  ->
计算 candidateLimit
  ->
对所有表打分排序
  ->
输出 candidateTables + preferredTable
```

---

## 9. 命中缓存后，仍然不会把全库都给 AI

这是链路里最容易误解的点。

### 9.1 实际逻辑

即使 Redis 里缓存的是全量结构，命中后也不是原样传给 AI，而是：

```text
先从 Redis 读回表清单 + 列摘要
  ->
再根据当前 question 调 MysqlQueryAnalyzer
  ->
先抽出少量候选表
  ->
只给这些候选表补 indexes
  ->
生成精简后的 schema 响应
```

对应位置在 `MysqlIntrospector.buildResponse(...)`：

```java
MysqlQueryPlan plan = queryAnalyzer.analyze(question, sourceTables, payload.schema(), MAX_TABLES);
rankedTables = ...
```

所以“缓存命中”不等于“prompt 变大”。  
真正给 AI 的 schema 仍然是按问题裁剪后的结果。

---

## 10. 问题没命中缓存时，为什么还能比以前快

### 10.1 原因

以前未命中缓存时容易走全库结构。  
现在如果有 question，未命中时会走：

```java
loadQuestionScopedSchema(profile, question)
```

也就是：

1. 先读表清单
2. 先分析问题
3. 只对候选表读列和索引
4. 不再一次性读全库所有列结构

这就是之前把 schema 表数显著压小、prompt 明显变短的根源。

---

## 11. 给 AI 的 schema 还会再做列裁剪

即使进入了候选表集合，每张表的列也不会毫无节制地全量传给 AI。

`MysqlIntrospector` 里对列做了摘要裁剪，目标是：

- 默认最多保留前 `12` 列
- 但确保这些关键列不要被截断：
  - `delete_flag`
  - `deleted`
  - `is_deleted`
  - `del_flag`
  - `status`
  - 索引列

代码注释里写得很明确：

```text
保留结构摘要的精简体积，同时确保 delete_flag、status 和索引列不会被前 12 列截断掉。
```

### 文本流程图

```text
候选表
  ->
列信息摘要化
  ->
前 12 列优先保留
  ->
强制补回 delete_flag/status/索引列
  ->
形成最终 prompt schema
```

---

## 12. PromptService：AI 生成 SQL

核心类：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/service/PromptService.java)

### 12.1 它拿到什么输入

`PromptService.generateQuery(type, question, schema)` 的输入是：

- 数据源类型：`MYSQL`
- 自然语言问题
- 已裁剪后的 `DatasourceSchemaResponse`

也就是说，AI 看到的已经不是全库，而是：

```text
问题
+ MySQL 规则
+ 少量候选表结构摘要
```

### 12.2 MySQL 规则重点

当前 prompt 里显式强调了这些规则：

1. 只能输出单条 `SELECT / WITH`
2. 用户数量优先选真正的用户主表
3. 不能把含有 `user_id` 的业务表误当成用户主表
4. 若表存在 `delete_flag` 且用户没明确要求已删除/全部数据，默认要带有效值条件
5. 若表存在 `status` 且问题语义是“可用/启用/上架/有效”，默认要带有效状态值条件
6. `delete_flag` 和 `status` 同时存在时优先同时考虑
7. 优先用索引字段做过滤和排序

### 12.3 AI 调用后日志拆解

当前日志会记录：

- `promptLength`
- `schemaTableCount`
- `promptBuildElapsedMs`
- `aiCallElapsedMs`
- `parseElapsedMs`
- `normalizeElapsedMs`

这些日志是为了分析性能瓶颈用的。

---

## 13. normalizeGeneratedQuery：统一结果，但不再偷偷补 SQL

这里有一个重要约束：

**现在不会在 AI 生成 SQL 后再偷偷补 `delete_flag` 条件。**

也就是说：

```text
返回给用户看的 SQL
==
实际执行的 SQL
```

现在 `PromptService` 对软删除相关只做：

- 检查
- 打日志

但不改 SQL 本身。

这样可以避免：

```text
用户看到的 SQL
和
实际执行的 SQL
不一致
```

---

## 14. MysqlQueryExecutor：执行 SQL

核心类：

- [/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/MysqlQueryExecutor.java](/Users/whiskey_liu/IdeaProjects/osh-text2sql/backend/src/main/java/com/osh/text2sql/executor/MysqlQueryExecutor.java)

### 14.1 执行前做什么

执行前会先做：

```java
String safeSql = SqlSafetyValidator.validateSelectQuery(query, properties.getQueryLimit());
```

也就是：

- 只允许只读查询
- 不能执行危险 SQL
- 带统一行数限制

### 14.2 执行方式

然后用 `JdbcTemplate` 直接执行：

```java
List<Map<String, Object>> rows = jdbcTemplate.queryForList(safeSql);
```

执行结果会组装成：

- `executedQuery`
- `summary`
- `columns`
- `rows`
- `total`
- `elapsedMs`

---

## 15. 结果总结

`Text2QueryService.query()` 最后会根据配置决定是否再让 AI 做结果总结：

```java
if (properties.getAi().isExplainResult()) {
    answer = promptService.explainResult(request.getQuestion(), result);
}
```

但如果结果非常简单，例如：

- 只有 `1 行 1 列`

则 `PromptService` 可能跳过 AI 总结，直接走规则摘要。

这样可以少一次 AI 请求，降低整体耗时。

---

## 16. 当前 MySQL 自然语言查询全链路总结

### 16.1 简版流程图

```text
用户问题
  ->
Text2QueryService.query()
  ->
MysqlIntrospector.introspect(profile, question)
  ->
Redis 结构缓存命中?
  |-- 是 -> 从缓存拿表清单 + 列摘要，先筛表，最后只补候选表索引
  |-- 否 -> 按问题只读候选表结构
  ->
列摘要裁剪
  ->
PromptService.generateQuery()
  ->
AI 生成 SQL
  ->
normalizeGeneratedQuery()
  ->
MysqlQueryExecutor.execute()
  ->
SqlSafetyValidator 校验
  ->
JdbcTemplate 执行
  ->
可选 AI 总结
  ->
返回结果
```

### 16.2 一句话版本

**MySQL AUTO 查询 = 优先从 Redis 读表清单与列摘要 -> 按问题筛出少量相关表 -> 只给这些候选表补索引 -> 让 AI 生成 SQL -> 做只读安全校验 -> 执行 SQL -> 输出结果与总结。**

---

## 17. 当前链路里最关键的几个工程点

1. **缓存优先**
   - 命中 Redis 时，不再直接查 MySQL 结构

2. **缓存命中时先列后索引**
   - 不会一开始把所有表索引都取出来
   - 只有最终候选表才补索引

3. **候选表数量是动态的**
   - 已进一步压缩到 3~8 张左右

4. **列信息会再做裁剪**
   - 现在默认控制在 12 列左右

5. **`delete_flag` / `status` 是 prompt 规则，不再做后置 SQL 改写**
   - 保证展示 SQL 与执行 SQL 一致

6. **执行前仍有只读安全校验**
   - AI 不是直接裸执行
