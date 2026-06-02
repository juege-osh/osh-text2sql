<template>
  <div class="hbase-browser">
    <div class="hbase-browser-header">
      <div>
        <h3>HBase 表列表</h3>
        <p>默认展示当前命名空间下的 HBase 表，以及列族概要。</p>
      </div>
      <el-button class="ghost-button" size="small" :loading="loading" @click="$emit('refresh')">
        实时刷新
      </el-button>
    </div>

    <div class="hbase-browser-meta">
      <span>ZooKeeper：{{ data.zookeeperQuorum || '-' }}</span>
      <span>命名空间：{{ data.namespace || 'default' }}</span>
      <span>表总数：{{ data.total }}</span>
      <span>当前匹配：{{ filteredTables.length }}</span>
    </div>

    <div class="hbase-browser-toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索表名" />
    </div>

    <div class="hbase-browser-table">
      <el-table :data="filteredTables" stripe border height="320" empty-text="当前没有匹配到 HBase 表">
        <el-table-column prop="table" label="Table" min-width="220" show-overflow-tooltip />
        <el-table-column prop="namespace" label="Namespace" min-width="120" />
        <el-table-column label="Families" min-width="260">
          <template #default="{ row }">
            <span>{{ formatFamilies(row.columnFamilies) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { HbaseTableListResponse, HbaseTableListFamily } from '@/types/query'

const props = defineProps<{
  data: HbaseTableListResponse
  loading?: boolean
}>()

defineEmits<{
  refresh: []
}>()

const keyword = ref('')

const filteredTables = computed(() => {
  const search = normalizeFuzzyText(keyword.value)
  if (!search) return props.data.tables
  return props.data.tables.filter((item) =>
    fuzzyMatch(item.table, search) || fuzzyMatch(item.namespace, search)
  )
})

function formatFamilies(families: HbaseTableListFamily[]) {
  return families.map((item) => `${item.family}(v${item.maxVersions})`).join(', ')
}

function normalizeFuzzyText(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[_\-\s]+/g, '')
}

function fuzzyMatch(source: string, keyword: string) {
  const normalizedSource = normalizeFuzzyText(source)
  if (!keyword) return true
  if (normalizedSource.includes(keyword)) return true

  let sourceIndex = 0
  let keywordIndex = 0
  while (sourceIndex < normalizedSource.length && keywordIndex < keyword.length) {
    if (normalizedSource[sourceIndex] === keyword[keywordIndex]) {
      keywordIndex++
    }
    sourceIndex++
  }
  return keywordIndex === keyword.length
}
</script>

<style scoped>
.hbase-browser {
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 18px;
  margin: 12px 0 18px;
  background: rgba(255, 252, 247, 0.92);
}

.hbase-browser-header,
.hbase-browser-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.hbase-browser-header {
  align-items: flex-start;
}

.hbase-browser-header p,
.hbase-browser-meta {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.hbase-browser-meta {
  justify-content: flex-start;
  margin-top: 12px;
  flex-wrap: wrap;
}

.hbase-browser-toolbar {
  margin-top: 12px;
}

.hbase-browser-table {
  margin-top: 14px;
}
</style>
