export type DatasourceType = 'MYSQL' | 'REDIS' | 'ELASTICSEARCH' | 'KAFKA' | 'HBASE'
export type QueryMode = 'AUTO' | 'RAW'

export interface ConnectionProfile {
  type: DatasourceType
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string
  baseUrl?: string
  bootstrapServers?: string
  securityProtocol?: string
  saslMechanism?: string
  zookeeperQuorum?: string
  zookeeperClientPort?: number
  znodeParent?: string
  namespace?: string
}

export interface QueryRequest {
  question: string
  type: DatasourceType
  mode: QueryMode
  rawQuery?: string
  connection?: ConnectionProfile
}

export interface QueryResponse {
  schema: {
    type: DatasourceType
    name: string
    summary: string
    schema: Record<string, unknown>
  }
  generatedQuery: {
    type: DatasourceType
    query: string
    reasoning: string
    safetyNotes: string
  }
  result: {
    type: DatasourceType
    executedQuery: string
    queryLanguage: string
    summary: string
    columns: string[]
    rows: Record<string, unknown>[]
    total: number
    elapsedMs: number
    rawResponse: unknown
  }
  answer: string
}

export interface SchemaResponse {
  type: DatasourceType
  name: string
  summary: string
  schema: Record<string, unknown>
}

export interface ConnectionTestResponse {
  success: boolean
  message: string
  elapsedMs: number
  preview: unknown
}

export interface DatasourceOverview {
  type: DatasourceType
  title: string
  subtitle: string
  status: string
  sampleTarget: string
}

export interface QuerySuggestion {
  type: DatasourceType
  title: string
  prompt: string
}

export interface WorkspaceSnapshotResponse {
  datasources: DatasourceOverview[]
  suggestions: QuerySuggestion[]
}

export interface HealthResponse {
  name: string
  status: string
}

export interface MysqlTableListItem {
  tableName: string
  tableComment: string
}

export interface MysqlTableListResponse {
  database: string
  total: number
  tables: MysqlTableListItem[]
}

export interface MysqlTableSchemaResponse {
  database: string
  tableName: string
  tableComment: string
  columns: Record<string, unknown>[]
  indexes: Record<string, unknown>[]
}

export interface MysqlTableOperationRequest {
  connection?: ConnectionProfile
  tableName: string
}

export interface RedisKeyListItem {
  key: string
  type: string
  ttl: number
}

export interface RedisKeyListResponse {
  host: string
  database: string
  total: number
  keys: RedisKeyListItem[]
}

export interface KafkaTopicListPartitionLeader {
  partition: number
  leader: string
}

export interface KafkaTopicListItem {
  name: string
  partitions: number
  internal: boolean
  replicationFactor: number
  partitionLeaders: KafkaTopicListPartitionLeader[]
}

export interface KafkaTopicListResponse {
  bootstrapServers: string
  total: number
  topics: KafkaTopicListItem[]
}

export interface EsIndexListItem {
  index: string
  docsCount: string
  status: string
}

export interface EsIndexListResponse {
  baseUrl: string
  total: number
  indices: EsIndexListItem[]
}

export interface HbaseTableListFamily {
  family: string
  maxVersions: number
  compression: string
}

export interface HbaseTableListItem {
  table: string
  namespace: string
  columnFamilies: HbaseTableListFamily[]
}

export interface HbaseTableListResponse {
  zookeeperQuorum: string
  namespace: string
  total: number
  tables: HbaseTableListItem[]
}
