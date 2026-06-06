<template>
  <div class="redis-browser">
    <div class="redis-browser-header">
      <div>
        <h3>Redis Key 列表</h3>
        <p>默认展示当前 Redis 库里已扫描到的 key、类型与 TTL。</p>
      </div>
      <el-button class="ghost-button" size="small" :loading="loading" @click="$emit('refresh')">
        实时刷新
      </el-button>
    </div>

    <div class="redis-browser-meta">
      <span>连接地址：{{ data.host || '-' }}</span>
      <span>数据库：db{{ data.database || '0' }}</span>
      <span>Key 总数：{{ data.total }}</span>
      <span>当前匹配：{{ filteredKeys.length }}</span>
    </div>

    <div class="redis-browser-toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索 key" />
    </div>

    <div class="redis-browser-table">
      <el-table :data="filteredKeys" stripe border height="320" empty-text="当前没有匹配到 Redis key">
        <el-table-column prop="key" label="Key" min-width="240" show-overflow-tooltip />
        <el-table-column prop="type" label="Type" min-width="120" />
        <el-table-column prop="ttl" label="TTL" min-width="120">
          <template #default="{ row }">
            {{ formatTtl(row.ttl) }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { RedisKeyListResponse } from '@/types/query'

const props = defineProps<{
  data: RedisKeyListResponse
  loading?: boolean
}>()

defineEmits<{
  refresh: []
}>()

const keyword = ref('')

const filteredKeys = computed(() => {
  const search = normalizeFuzzyText(keyword.value)
  if (!search) return props.data.keys
  return props.data.keys.filter((item) => fuzzyMatch(item.key, search))
})

function formatTtl(ttl: number) {
  if (ttl === -1) return '永不过期'
  if (ttl === -2) return 'key 不存在'
  return `${ttl}s`
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
.redis-browser {
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 18px;
  margin: 12px 0 18px;
  background: rgba(255, 252, 247, 0.92);
}

.redis-browser-header,
.redis-browser-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.redis-browser-header {
  align-items: flex-start;
}

.redis-browser-header p,
.redis-browser-meta {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.redis-browser-meta {
  justify-content: flex-start;
  margin-top: 12px;
  flex-wrap: wrap;
}

.redis-browser-toolbar {
  margin-top: 12px;
}

.redis-browser-table {
  margin-top: 14px;
}
</style>
