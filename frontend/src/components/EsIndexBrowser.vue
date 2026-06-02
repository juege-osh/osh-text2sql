<template>
  <div class="es-browser">
    <div class="es-browser-header">
      <div>
        <h3>Elasticsearch 索引列表</h3>
        <p>默认展示当前集群已有的 index，以及每个 index 当前的文档数量和状态。</p>
      </div>
      <el-button class="ghost-button" size="small" :loading="loading" @click="emit('refresh')">
        刷新列表
      </el-button>
    </div>

    <div class="es-browser-meta">
      <span>连接地址：{{ data.baseUrl || '-' }}</span>
      <span>索引总数：{{ data.total }}</span>
      <span>当前匹配：{{ filteredIndices.length }}</span>
    </div>

    <div class="es-browser-toolbar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索 index 名"
      />
    </div>

    <div class="es-browser-table">
      <el-table :data="filteredIndices" stripe border height="360" empty-text="当前没有匹配到 Elasticsearch index">
        <el-table-column prop="index" label="Index" min-width="260" show-overflow-tooltip />
        <el-table-column prop="docsCount" label="Documents" min-width="140" />
        <el-table-column prop="status" label="Status" min-width="120" />
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { EsIndexListResponse } from '@/types/query'

const props = defineProps<{
  loading: boolean
  data: EsIndexListResponse
}>()

const emit = defineEmits<{
  refresh: []
}>()

const keyword = ref('')

const filteredIndices = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  if (!search) return props.data.indices
  return props.data.indices.filter((item) => item.index.toLowerCase().includes(search))
})
</script>

<style scoped>
.es-browser {
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 18px;
  margin: 12px 0 18px;
  background: rgba(255, 252, 247, 0.92);
}

.es-browser-header,
.es-browser-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.es-browser-header {
  align-items: flex-start;
}

.es-browser-header p,
.es-browser-meta {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.es-browser-meta {
  justify-content: flex-start;
  margin-top: 12px;
  flex-wrap: wrap;
}

.es-browser-toolbar {
  margin-top: 12px;
}

.es-browser-table {
  margin-top: 14px;
}

@media (max-width: 760px) {
  .es-browser-header {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
