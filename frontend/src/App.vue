<template>
  <div class="page-shell">
    <div class="ambient ambient-left"></div>
    <div class="ambient ambient-right"></div>
    <header class="hero">
      <div class="hero-copy">
        <span class="hero-badge">OSH RAG · Text2SQL Studio</span>
        <h1>一句自然语言，直接查询 MySQL、Redis、Elasticsearch、Kafka</h1>
        <p>
          直接复用服务端默认连接，自动读取结构，生成只读查询，并把结果翻译成清晰中文结论。
        </p>
      </div>
      <div class="hero-metrics">
        <MetricCard label="默认数据源" value="4" sub="MySQL / Redis / ES / Kafka" />
        <MetricCard label="执行模式" value="2" sub="自动生成 / 手动执行" />
        <MetricCard label="结果表达" value="AI" sub="查询 + 中文总结" />
      </div>
    </header>

    <section class="service-strip">
      <div class="service-pill" :class="backendHealthy ? 'is-up' : 'is-down'">
        <span class="status-dot"></span>
        <strong>{{ backendHealthy ? '后端在线' : '后端离线' }}</strong>
        <span>{{ backendStatusMessage }}</span>
      </div>
      <div class="service-pill">
        <strong>API 入口</strong>
        <span>{{ apiDisplayTarget }}</span>
      </div>
      <div class="service-pill">
        <strong>当前连接</strong>
        <span>{{ connectionSummary }}</span>
      </div>
      <el-button class="ghost-button" @click="handleRefreshHealth">刷新状态</el-button>
    </section>

    <main class="workspace">
      <section class="panel control-panel">
        <div class="panel-header">
          <div>
            <h2>查询工作台</h2>
            <p>连接项留空时直接走服务端默认配置，也可以临时覆盖后再查询。</p>
          </div>
          <el-tag effect="dark" :type="backendHealthy ? 'success' : 'danger'">
            {{ backendHealthy ? '服务可用' : '等待后端' }}
          </el-tag>
        </div>

        <div v-if="snapshot" class="datasource-strip">
          <div
            v-for="item in snapshot.datasources"
            :key="item.type"
            class="datasource-chip"
            :class="{ active: form.type === item.type }"
            @click="switchDatasource(item.type)"
          >
            <div class="chip-head">
              <strong>{{ item.title }}</strong>
              <span>{{ item.status }}</span>
            </div>
            <div class="chip-sub">{{ item.subtitle }}</div>
            <div class="chip-meta">{{ item.sampleTarget }}</div>
          </div>
        </div>

        <div class="grid-two">
          <el-form label-position="top">
            <el-form-item label="数据源类型">
              <el-segmented
                v-model="form.type"
                :options="typeOptions"
                @change="handleDatasourceSegmentChange"
              />
            </el-form-item>
            <el-form-item label="执行模式">
              <el-radio-group v-model="form.mode">
                <el-radio-button label="AUTO">自动生成</el-radio-button>
                <el-radio-button label="RAW">手动执行</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-form>

          <div class="tips-card">
            <h3>示例问题</h3>
            <p>点击即可填充，也可直接改写。</p>
            <button
              v-for="item in currentExamples"
              :key="item"
              class="example-chip"
              @click="form.question = item"
            >
              {{ item }}
            </button>
          </div>
        </div>

        <div class="connection-grid">
          <template v-if="form.type === 'MYSQL' || form.type === 'REDIS'">
            <el-input v-model="connection.host" placeholder="主机，留空走服务端默认" />
            <el-input-number v-model="connection.port" :min="1" :max="65535" controls-position="right" />
            <el-input
              v-model="connection.username"
              :placeholder="form.type === 'MYSQL' ? '用户名，留空走服务端默认' : 'Redis 通常无需用户名'"
            />
            <el-input v-model="connection.password" show-password placeholder="密码，留空走服务端默认" />
            <el-input
              v-model="connection.database"
              :placeholder="form.type === 'MYSQL' ? '数据库名，留空走服务端默认' : 'Redis DB 编号，留空走服务端默认'"
            />
          </template>

          <template v-else-if="form.type === 'ELASTICSEARCH'">
            <el-input v-model="connection.baseUrl" placeholder="ES 地址，留空走服务端默认" />
            <el-input v-model="connection.username" placeholder="用户名，留空走服务端默认" />
            <el-input v-model="connection.password" show-password placeholder="密码，留空走服务端默认" />
          </template>

          <template v-else>
            <el-input v-model="connection.bootstrapServers" placeholder="bootstrap servers，留空走服务端默认" />
            <el-select v-model="connection.securityProtocol" placeholder="安全协议，留空走服务端默认">
              <el-option
                v-for="item in kafkaSecurityProtocols"
                :key="item"
                :label="item"
                :value="item"
              />
            </el-select>
            <el-select v-model="connection.saslMechanism" placeholder="SASL 机制，按需填写">
              <el-option
                v-for="item in kafkaMechanisms"
                :key="item"
                :label="item"
                :value="item"
              />
            </el-select>
            <el-input v-model="connection.username" placeholder="用户名，SASL 场景按需填写" />
            <el-input v-model="connection.password" show-password placeholder="密码，SASL 场景按需填写" />
          </template>
        </div>

        <div class="muted">
          当前页面不会保存任何默认库密码。连接信息留空时，后端会自动回落到服务器上的安全环境变量。
        </div>

        <div class="toolbar">
          <el-button class="ghost-button" :loading="testing" @click="handleTestConnection">测试连接</el-button>
          <el-button class="ghost-button" :loading="schemaLoading" @click="handleLoadSchema">刷新结构</el-button>
          <span v-if="testMessage" class="test-message">{{ testMessage }}</span>
        </div>

        <el-form label-position="top">
          <el-form-item label="自然语言问题">
            <el-input
              v-model="form.question"
              type="textarea"
              :rows="4"
              :placeholder="questionPlaceholder"
            />
          </el-form-item>
          <el-form-item v-if="form.mode === 'RAW'" :label="rawLabel">
            <el-input
              v-model="form.rawQuery"
              type="textarea"
              :rows="8"
              :placeholder="rawPlaceholder"
            />
          </el-form-item>
        </el-form>

        <div class="toolbar">
          <el-button type="primary" size="large" :loading="loading" @click="handleQuery">
            开始查询
          </el-button>
          <el-button size="large" @click="handleReset">重置</el-button>
        </div>

        <div v-if="history.length" class="history-card">
          <div class="history-head">
            <h3>最近查询</h3>
            <el-button link type="primary" @click="history = []">清空</el-button>
          </div>
          <button
            v-for="item in history"
            :key="item.id"
            class="history-item"
            @click="restoreHistory(item.id)"
          >
            <span>{{ item.type }}</span>
            <strong>{{ item.question }}</strong>
          </button>
        </div>
      </section>

      <section class="panel result-panel">
        <div class="panel-header">
          <div>
            <h2>执行结果</h2>
            <p>生成查询、执行耗时、结果表格和 AI 总结会一起展示。</p>
          </div>
          <el-tag v-if="result" effect="plain" type="info">
            {{ result.result.queryLanguage }} · {{ result.result.elapsedMs }} ms
          </el-tag>
        </div>

        <template v-if="result">
          <div class="summary-card">
            <div class="summary-title">AI 结论</div>
            <div class="summary-body">{{ result.answer }}</div>
            <div class="summary-actions">
              <el-button size="small" @click="copyText(result.answer)">复制结论</el-button>
              <el-button size="small" @click="downloadResult('json')">导出 JSON</el-button>
              <el-button size="small" @click="downloadResult('csv')">导出 CSV</el-button>
            </div>
          </div>

          <div class="result-grid">
            <div class="code-card">
              <div class="card-title">生成查询</div>
              <pre>{{ result.generatedQuery.query }}</pre>
            </div>
            <div class="code-card">
              <div class="card-title">执行说明</div>
              <pre>{{ result.generatedQuery.reasoning }}</pre>
              <div class="safety-note">安全校验：{{ result.generatedQuery.safetyNotes }}</div>
            </div>
          </div>

          <div class="table-card">
            <div class="card-title">
              查询结果
              <span class="muted">{{ result.result.summary }}</span>
            </div>
            <el-table :data="result.result.rows" border stripe height="420">
              <el-table-column
                v-for="column in result.result.columns"
                :key="column"
                :prop="column"
                :label="column"
                min-width="140"
                show-overflow-tooltip
              />
            </el-table>
          </div>
        </template>

        <div v-else class="empty-state">
          <div class="empty-kicker">READY</div>
          <h3>服务端默认连接已经就绪</h3>
          <p>点击“测试连接”确认环境，再用自然语言发起你的第一条查询。</p>
          <div v-if="snapshot" class="empty-suggestions">
            <button
              v-for="item in snapshot.suggestions"
              :key="item.title"
              class="example-chip"
              @click="applySuggestion(item.type, item.prompt)"
            >
              {{ item.title }} · {{ item.prompt }}
            </button>
          </div>
        </div>

        <div v-if="activeSchema" class="schema-section">
          <div class="schema-header">
            <div>
              <div class="card-title">结构摘要</div>
              <p>{{ activeSchema.summary }}</p>
            </div>
            <el-tag effect="plain" type="success">{{ activeSchema.name }}</el-tag>
          </div>

          <div v-if="activeSchema.type === 'MYSQL'" class="schema-grid mysql-grid">
            <div
              v-for="table in mysqlTables"
              :key="table.name"
              class="schema-card"
            >
              <div class="schema-card-head">
                <strong>{{ table.name }}</strong>
                <span>{{ table.columns.length }} 列</span>
              </div>
              <div class="schema-column-list">
                <div
                  v-for="column in table.columns.slice(0, 8)"
                  :key="`${table.name}-${column.columnName}`"
                  class="schema-column-item"
                >
                  <strong>{{ column.columnName }}</strong>
                  <span>{{ column.dataType }}</span>
                  <em v-if="column.columnComment">{{ column.columnComment }}</em>
                </div>
              </div>
              <div v-if="table.columns.length > 8" class="schema-more">
                还有 {{ table.columns.length - 8 }} 个字段未展开
              </div>
            </div>
          </div>

          <div v-else-if="activeSchema.type === 'REDIS'" class="schema-grid redis-grid">
            <div
              v-for="item in redisKeys"
              :key="item.key"
              class="schema-card"
            >
              <div class="schema-card-head">
                <strong>{{ item.key }}</strong>
                <span>{{ item.type }}</span>
              </div>
              <div class="schema-meta-row">
                TTL: {{ formatTtl(item.ttl) }}
              </div>
            </div>
            <div v-if="!redisKeys.length" class="schema-card schema-empty-card">
              当前 Redis 预览没有扫描到 key，可直接执行只读命令测试。
            </div>
          </div>

          <div v-else-if="activeSchema.type === 'ELASTICSEARCH'" class="schema-grid es-grid">
            <div
              v-for="item in esIndices"
              :key="item.name"
              class="schema-card"
            >
              <div class="schema-card-head">
                <strong>{{ item.name }}</strong>
                <span>{{ item.docsCount }} docs</span>
              </div>
              <div class="schema-meta-row">状态：{{ item.status }}</div>
              <div class="field-cloud">
                <span
                  v-for="field in item.fields.slice(0, 12)"
                  :key="`${item.name}-${field}`"
                  class="field-chip"
                >
                  {{ field }}
                </span>
              </div>
              <div v-if="item.fields.length > 12" class="schema-more">
                还有 {{ item.fields.length - 12 }} 个字段未展开
              </div>
            </div>
          </div>

          <div v-else class="schema-grid kafka-grid">
            <div
              v-for="item in kafkaTopics"
              :key="item.name"
              class="schema-card"
            >
              <div class="schema-card-head">
                <strong>{{ item.name }}</strong>
                <span>{{ item.partitions }} partitions</span>
              </div>
              <div class="schema-meta-row">内部主题：{{ item.internal ? '是' : '否' }}</div>
              <div class="schema-meta-row">副本数：{{ item.replicationFactor }}</div>
              <div class="field-cloud">
                <span
                  v-for="leader in item.partitionLeaders"
                  :key="`${item.name}-${leader.partition}`"
                  class="field-chip"
                >
                  P{{ leader.partition }} · {{ leader.leader }}
                </span>
              </div>
            </div>
            <div v-if="!kafkaTopics.length" class="schema-card schema-empty-card">
              当前 Kafka 预览没有取到 topic，可先测试连接或查看 topic 列表。
            </div>
          </div>
        </div>

        <div v-if="result" class="code-card raw-response-card">
          <div class="card-title">原始响应</div>
          <pre>{{ prettyJson(result.result.rawResponse) }}</pre>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import MetricCard from '@/components/MetricCard.vue'
import { fetchHealth, fetchSchemaByConnection, fetchSnapshot, queryData, testConnection } from '@/api/query'
import type {
  ConnectionProfile,
  DatasourceType,
  QueryMode,
  QueryResponse,
  SchemaResponse,
  WorkspaceSnapshotResponse
} from '@/types/query'

interface MysqlColumnView {
  columnName: string
  dataType: string
  columnComment: string
}

interface MysqlTableView {
  name: string
  columns: MysqlColumnView[]
}

interface RedisKeyView {
  key: string
  type: string
  ttl: number
}

interface EsIndexView {
  name: string
  status: string
  docsCount: string
  fields: string[]
}

interface KafkaPartitionLeaderView {
  partition: number
  leader: string
}

interface KafkaTopicView {
  name: string
  partitions: number
  internal: boolean
  replicationFactor: number
  partitionLeaders: KafkaPartitionLeaderView[]
}

const typeOptions = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'Redis', value: 'REDIS' },
  { label: 'Elasticsearch', value: 'ELASTICSEARCH' },
  { label: 'Kafka', value: 'KAFKA' }
] as const

const kafkaSecurityProtocols = ['PLAINTEXT', 'SASL_PLAINTEXT', 'SSL', 'SASL_SSL']
const kafkaMechanisms = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512']

const examples: Record<DatasourceType, string[]> = {
  MYSQL: [
    '查询 assistant_feedback 表最近创建的 5 条反馈工单',
    '统计总共有多少个用户',
    '列出 osh_book 表价格最高的 5 本电子书'
  ],
  REDIS: [
    '列出当前 Redis 数据库前 20 个 key',
    '查看某个 key 的类型和 TTL',
    '查询一个列表 key 的前 10 条内容'
  ],
  ELASTICSEARCH: [
    '查询 osh_course_index 中销量最高的 5 个课程',
    '搜索标题包含 测试课程 的课程文档',
    '统计 osh_book_search_read 中最热门的电子书'
  ],
  KAFKA: [
    '列出当前 Kafka 集群的 topic 列表',
    '查看 user-action topic 最近 10 条消息',
    '查看 osh.tool.index topic 的分区详情'
  ]
}

const apiDisplayTarget = import.meta.env.VITE_API_BASE_URL || `${window.location.origin}/api`

const form = reactive<{
  type: DatasourceType
  mode: QueryMode
  question: string
  rawQuery: string
}>({
  type: 'MYSQL',
  mode: 'AUTO',
  question: examples.MYSQL[0],
  rawQuery: ''
})

const connection = reactive<ConnectionProfile>({
  type: 'MYSQL',
  host: '',
  port: undefined,
  database: '',
  username: '',
  password: '',
  baseUrl: '',
  bootstrapServers: '',
  securityProtocol: '',
  saslMechanism: ''
})

const loading = ref(false)
const testing = ref(false)
const schemaLoading = ref(false)
const testMessage = ref('')
const result = ref<QueryResponse | null>(null)
const snapshot = ref<WorkspaceSnapshotResponse | null>(null)
const schemaPreview = ref<SchemaResponse | null>(null)
const backendHealthy = ref(false)
const backendStatusMessage = ref('等待检查')
const history = ref<Array<{ id: string; type: DatasourceType; question: string; payload: QueryResponse }>>([])

let healthTimer: number | undefined

const currentExamples = computed(() => examples[form.type])
const currentDatasource = computed(() => snapshot.value?.datasources.find((item) => item.type === form.type) ?? null)
const questionPlaceholder = computed(() => examples[form.type][0])
const rawLabel = computed(() => {
  if (form.type === 'MYSQL') return '手动 SQL'
  if (form.type === 'REDIS') return '手动 Redis 命令'
  if (form.type === 'ELASTICSEARCH') return '手动 Elasticsearch DSL'
  return '手动 Kafka Query DSL'
})
const rawPlaceholder = computed(() => {
  if (form.type === 'MYSQL') {
    return 'SELECT id, title, create_time FROM assistant_feedback ORDER BY create_time DESC LIMIT 5'
  }
  if (form.type === 'REDIS') {
    return 'SCAN 0'
  }
  if (form.type === 'ELASTICSEARCH') {
    return `{\n  "_index": "osh_course_index",\n  "size": 5,\n  "query": { "match_all": {} }\n}`
  }
  return `{\n  "operation": "READ_MESSAGES",\n  "topic": "user-action",\n  "limit": 10,\n  "from": "LATEST"\n}`
})
const connectionSummary = computed(() => {
  if (form.type === 'ELASTICSEARCH') {
    return connection.baseUrl || currentDatasource.value?.subtitle || '留空时使用服务端默认 ES 连接'
  }
  if (form.type === 'KAFKA') {
    return connection.bootstrapServers || currentDatasource.value?.subtitle || '留空时使用服务端默认 Kafka 连接'
  }
  if (connection.host || connection.port || connection.database) {
    return `${connection.host || '未设置主机'}:${connection.port || '-'} / ${connection.database || '-'}`
  }
  return currentDatasource.value?.subtitle || '留空时使用服务端默认连接'
})
const activeSchema = computed<SchemaResponse | null>(() => schemaPreview.value ?? result.value?.schema ?? null)

const mysqlTables = computed<MysqlTableView[]>(() => {
  if (activeSchema.value?.type !== 'MYSQL') return []
  return Object.entries(asObject(activeSchema.value.schema)).map(([name, raw]) => ({
    name,
    columns: Array.isArray(raw) ? raw.map((item) => normalizeMysqlColumn(item)) : []
  }))
})

const redisKeys = computed<RedisKeyView[]>(() => {
  if (activeSchema.value?.type !== 'REDIS') return []
  return Object.entries(asObject(activeSchema.value.schema)).map(([key, raw]) => {
    const record = asObject(raw)
    return {
      key,
      type: String(record.type ?? 'unknown'),
      ttl: Number(record.ttl ?? -1)
    }
  })
})

const esIndices = computed<EsIndexView[]>(() => {
  if (activeSchema.value?.type !== 'ELASTICSEARCH') return []
  return Object.entries(asObject(activeSchema.value.schema)).map(([name, raw]) => {
    const record = asObject(raw)
    return {
      name,
      status: String(record.status ?? 'unknown'),
      docsCount: String(record.docsCount ?? 0),
      fields: toStringArray(record.fields)
    }
  })
})

const kafkaTopics = computed<KafkaTopicView[]>(() => {
  if (activeSchema.value?.type !== 'KAFKA') return []
  return Object.entries(asObject(activeSchema.value.schema)).map(([name, raw]) => {
    const record = asObject(raw)
    const leaders = Array.isArray(record.partitionLeaders)
      ? record.partitionLeaders.map((item) => {
          const leaderRecord = asObject(item)
          return {
            partition: Number(leaderRecord.partition ?? 0),
            leader: String(leaderRecord.leader ?? 'unknown')
          }
        })
      : []
    return {
      name,
      partitions: Number(record.partitions ?? 0),
      internal: Boolean(record.internal),
      replicationFactor: Number(record.replicationFactor ?? 0),
      partitionLeaders: leaders
    }
  })
})

onMounted(() => {
  void loadSnapshot()
  void checkBackendHealth()
  healthTimer = window.setInterval(() => {
    void checkBackendHealth()
  }, 15000)
})

onUnmounted(() => {
  if (healthTimer) {
    window.clearInterval(healthTimer)
  }
})

function handleDatasourceSegmentChange(value: string | number | boolean) {
  switchDatasource(value as DatasourceType)
}

function switchDatasource(type: DatasourceType, question?: string) {
  form.type = type
  resetConnection(type)
  form.question = question ?? examples[type][0]
  form.rawQuery = ''
  result.value = null
  schemaPreview.value = null
  testMessage.value = ''
}

async function handleRefreshHealth() {
  await checkBackendHealth(true)
}

async function checkBackendHealth(showMessage = false) {
  try {
    const { data } = await fetchHealth()
    backendHealthy.value = data.status === 'UP'
    backendStatusMessage.value = `${data.name} · ${data.status}`
    if (showMessage) {
      ElMessage.success('后端状态正常')
    }
  } catch (error: any) {
    backendHealthy.value = false
    backendStatusMessage.value = error?.userMessage ?? '未连接到后端服务'
    if (showMessage) {
      ElMessage.error(backendStatusMessage.value)
    }
  }
}

async function handleTestConnection() {
  testing.value = true
  try {
    const { data } = await testConnection(buildConnectionPayload())
    testMessage.value = `${data.message} · ${data.elapsedMs}ms`
    await checkBackendHealth()
    ElMessage.success(data.message)
  } catch (error: any) {
    testMessage.value = error?.userMessage ?? error?.response?.data?.message ?? '连接测试失败'
    ElMessage.error(testMessage.value)
  } finally {
    testing.value = false
  }
}

async function handleQuery() {
  loading.value = true
  try {
    const { data } = await queryData({
      question: form.question,
      type: form.type,
      mode: form.mode,
      rawQuery: form.rawQuery,
      connection: buildConnectionPayload()
    })
    result.value = data
    schemaPreview.value = data.schema
    history.value = [
      {
        id: `${Date.now()}`,
        type: form.type,
        question: form.question,
        payload: data
      },
      ...history.value
    ].slice(0, 8)
    ElMessage.success('查询完成')
  } catch (error: any) {
    ElMessage.error(error?.userMessage ?? error?.response?.data?.message ?? '查询失败')
  } finally {
    loading.value = false
  }
}

async function handleLoadSchema() {
  schemaLoading.value = true
  try {
    const { data } = await fetchSchemaByConnection(buildConnectionPayload())
    schemaPreview.value = data
    ElMessage.success(data.summary)
  } catch (error: any) {
    ElMessage.error(error?.userMessage ?? error?.response?.data?.message ?? '刷新结构失败')
  } finally {
    schemaLoading.value = false
  }
}

async function loadSnapshot() {
  try {
    const { data } = await fetchSnapshot()
    snapshot.value = data
  } catch {
    snapshot.value = null
  }
}

function handleReset() {
  switchDatasource(form.type)
}

function applySuggestion(type: DatasourceType, prompt: string) {
  switchDatasource(type, prompt)
}

function restoreHistory(id: string) {
  const target = history.value.find((item) => item.id === id)
  if (!target) return
  form.type = target.type
  result.value = target.payload
  schemaPreview.value = target.payload.schema
  form.question = target.question
  form.rawQuery = ''
  testMessage.value = ''
  resetConnection(target.type)
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function downloadResult(format: 'json' | 'csv') {
  if (!result.value) return
  const filename = `osh-text2sql-result-${Date.now()}.${format}`
  const content = format === 'json'
    ? prettyJson(result.value)
    : toCsv(result.value.result.rows, result.value.result.columns)
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}

function toCsv(rows: Record<string, unknown>[], columns: string[]) {
  const header = columns.join(',')
  const body = rows.map((row) => columns.map((column) => csvCell(row[column])).join(','))
  return [header, ...body].join('\n')
}

function csvCell(value: unknown) {
  const text = value == null
    ? ''
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value)
  return `"${text.replace(/"/g, '""')}"`
}

function prettyJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function normalizeMysqlColumn(value: unknown): MysqlColumnView {
  const record = asObject(value)
  return {
    columnName: String(record.columnName ?? '-'),
    dataType: String(record.dataType ?? '-'),
    columnComment: String(record.columnComment ?? '')
  }
}

function asObject(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  return {}
}

function toStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : []
}

function formatTtl(ttl: number) {
  if (ttl === -1) return '永不过期'
  if (ttl === -2) return 'key 不存在'
  return `${ttl}s`
}

function resetConnection(type: DatasourceType) {
  Object.assign(connection, {
    type,
    host: '',
    port: undefined,
    database: '',
    username: '',
    password: '',
    baseUrl: '',
    bootstrapServers: '',
    securityProtocol: '',
    saslMechanism: ''
  })
}

function buildConnectionPayload(): ConnectionProfile {
  const payload: ConnectionProfile = { type: form.type }

  if (form.type === 'ELASTICSEARCH') {
    if (connection.baseUrl?.trim()) payload.baseUrl = connection.baseUrl.trim()
    if (connection.username?.trim()) payload.username = connection.username.trim()
    if (connection.password?.trim()) payload.password = connection.password
    return payload
  }

  if (form.type === 'KAFKA') {
    if (connection.bootstrapServers?.trim()) payload.bootstrapServers = connection.bootstrapServers.trim()
    if (connection.securityProtocol?.trim()) payload.securityProtocol = connection.securityProtocol.trim()
    if (connection.saslMechanism?.trim()) payload.saslMechanism = connection.saslMechanism.trim()
    if (connection.username?.trim()) payload.username = connection.username.trim()
    if (connection.password?.trim()) payload.password = connection.password
    return payload
  }

  if (connection.host?.trim()) payload.host = connection.host.trim()
  if (typeof connection.port === 'number') payload.port = connection.port
  if (connection.username?.trim()) payload.username = connection.username.trim()
  if (connection.password?.trim()) payload.password = connection.password
  if (connection.database?.trim()) payload.database = connection.database.trim()
  return payload
}
</script>

<style scoped>
.service-pill strong,
.service-pill span,
.chip-head span,
.chip-sub,
.chip-meta,
.history-item span,
.panel-header p,
.muted,
.tips-card p,
.empty-state p,
.schema-header p,
.schema-card-head span,
.schema-meta-row,
.schema-more,
.safety-note,
.test-message {
  line-height: 1.6;
}

.datasource-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.connection-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 8px 0 18px;
}

.datasource-chip,
.history-item {
  border: 1px solid var(--line);
  background: rgba(255, 252, 247, 0.95);
  border-radius: 18px;
  padding: 14px;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, background 0.18s ease;
}

.datasource-chip:hover,
.history-item:hover {
  transform: translateY(-1px);
  border-color: rgba(184, 92, 56, 0.35);
}

.datasource-chip.active {
  background: linear-gradient(135deg, rgba(184, 92, 56, 0.12), rgba(31, 107, 103, 0.08));
  border-color: rgba(184, 92, 56, 0.3);
}

.chip-head,
.history-head,
.summary-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.chip-head span,
.chip-sub,
.chip-meta,
.history-item span {
  color: var(--muted);
  font-size: 13px;
}

.chip-sub,
.chip-meta {
  margin-top: 6px;
}

.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.ghost-button {
  --el-button-bg-color: #fff8f1;
  --el-button-border-color: rgba(184, 92, 56, 0.18);
  --el-button-text-color: var(--accent-deep);
}

.test-message {
  color: var(--teal);
  font-size: 13px;
}

.summary-card {
  border-radius: 24px;
  padding: 20px 22px;
  margin-bottom: 16px;
  background: linear-gradient(135deg, rgba(184, 92, 56, 0.12), rgba(31, 107, 103, 0.08));
}

.summary-title {
  font-size: 13px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--accent-deep);
}

.summary-body {
  margin-top: 10px;
  font-size: 16px;
  line-height: 1.7;
}

.summary-actions {
  margin-top: 14px;
  justify-content: flex-start;
}

.code-card,
.table-card,
.empty-state,
.history-card {
  border-radius: 24px;
  padding: 18px;
}

.table-card {
  margin: 16px 0;
}

.code-card pre {
  margin-top: 12px;
  padding: 16px;
  border-radius: 18px;
  background: #1f1815;
  color: #f8ecde;
  min-height: 180px;
  font-size: 13px;
  line-height: 1.65;
}

.safety-note {
  margin-top: 12px;
  color: var(--muted);
  font-size: 13px;
}

.empty-state {
  min-height: 540px;
  display: grid;
  place-content: center;
  text-align: center;
  background:
    linear-gradient(145deg, rgba(255, 244, 231, 0.96), rgba(243, 237, 227, 0.88)),
    repeating-linear-gradient(135deg, transparent, transparent 12px, rgba(184, 92, 56, 0.03) 12px, rgba(184, 92, 56, 0.03) 24px);
}

.empty-kicker {
  color: var(--teal);
  letter-spacing: 0.2em;
  font-size: 12px;
  text-transform: uppercase;
}

.history-card {
  margin-top: 10px;
  background: rgba(255, 251, 246, 0.95);
  border: 1px solid var(--line);
}

.history-item {
  width: 100%;
  text-align: left;
  margin-top: 10px;
  display: block;
}

.history-item strong {
  display: block;
  margin-top: 6px;
  color: var(--text);
}

.empty-suggestions {
  margin-top: 18px;
  width: min(680px, 100%);
}

.schema-section {
  margin-top: 16px;
}

.schema-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}

.schema-grid {
  display: grid;
  gap: 14px;
}

.mysql-grid,
.redis-grid,
.es-grid,
.kafka-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.schema-card {
  background: rgba(255, 251, 246, 0.96);
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 16px;
}

.schema-empty-card {
  color: var(--muted);
}

.schema-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.schema-card-head span,
.schema-meta-row,
.schema-more {
  color: var(--muted);
  font-size: 13px;
}

.schema-column-list {
  margin-top: 12px;
  display: grid;
  gap: 10px;
}

.schema-column-item {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) auto;
  gap: 4px 12px;
  align-items: start;
  padding-bottom: 10px;
  border-bottom: 1px dashed rgba(92, 59, 32, 0.1);
}

.schema-column-item:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.schema-column-item strong {
  min-width: 0;
  word-break: break-word;
}

.schema-column-item em {
  grid-column: 1 / -1;
  color: var(--muted);
  font-style: normal;
  font-size: 12px;
  line-height: 1.5;
}

.schema-more {
  margin-top: 10px;
}

.field-cloud {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.field-chip {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 999px;
  background: #fff4e9;
  color: var(--accent-deep);
  font-size: 12px;
}

.raw-response-card {
  margin-top: 16px;
}

@media (max-width: 1180px) {
  .datasource-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .connection-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mysql-grid,
  .redis-grid,
  .es-grid,
  .kafka-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 760px) {
  .datasource-strip,
  .connection-grid,
  .mysql-grid,
  .redis-grid,
  .es-grid,
  .kafka-grid {
    grid-template-columns: 1fr;
  }

  .schema-header {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
