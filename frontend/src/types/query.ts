export type DatasourceType = 'MYSQL' | 'REDIS' | 'ELASTICSEARCH' | 'KAFKA'
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
