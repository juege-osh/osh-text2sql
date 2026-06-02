import { http } from './http'
import type {
  ConnectionProfile,
  ConnectionTestResponse,
  DatasourceType,
  HealthResponse,
  MysqlTableListResponse,
  MysqlTableOperationRequest,
  MysqlTableSchemaResponse,
  QueryRequest,
  QueryResponse,
  SchemaResponse,
  WorkspaceSnapshotResponse
} from '@/types/query'

export function queryData(payload: QueryRequest) {
  return http.post<QueryResponse>('/query', payload)
}

export function testConnection(payload: ConnectionProfile) {
  return http.post<ConnectionTestResponse>('/query/test-connection', payload)
}

export function fetchSchema(type: DatasourceType) {
  return http.get<SchemaResponse>('/query/schema', { params: { type } })
}

export function fetchSchemaByConnection(payload: ConnectionProfile) {
  return http.post<SchemaResponse>('/query/schema', payload)
}

export function fetchSnapshot() {
  return http.get<WorkspaceSnapshotResponse>('/query/snapshot')
}

export function fetchHealth() {
  return http.get<HealthResponse>('/health')
}

export function fetchMysqlTables(payload: ConnectionProfile) {
  return http.post<MysqlTableListResponse>('/query/mysql/tables', payload)
}

export function refreshMysqlTables(payload: ConnectionProfile) {
  return http.post<MysqlTableListResponse>('/query/mysql/tables/refresh', payload)
}

export function fetchMysqlTableSchema(payload: MysqlTableOperationRequest) {
  return http.post<MysqlTableSchemaResponse>('/query/mysql/table-schema', payload)
}

export function fetchMysqlTablePreview(payload: MysqlTableOperationRequest) {
  return http.post<QueryResponse['result']>('/query/mysql/table-preview', payload)
}
