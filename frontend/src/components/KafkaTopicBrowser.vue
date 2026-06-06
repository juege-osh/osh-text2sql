<template>
  <div class="kafka-browser">
    <div class="kafka-browser-header">
      <div>
        <h3>Kafka Topic 列表</h3>
        <p>默认展示当前 Kafka 集群可见 topic、分区数和副本信息。</p>
      </div>
      <el-button class="ghost-button" size="small" :loading="loading" @click="$emit('refresh')">
        实时刷新
      </el-button>
    </div>

    <div class="kafka-browser-meta">
      <span>连接地址：{{ data.bootstrapServers || '-' }}</span>
      <span>Topic 总数：{{ data.total }}</span>
      <span>当前匹配：{{ filteredTopics.length }}</span>
    </div>

    <div class="kafka-browser-toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索 topic" />
    </div>

    <div class="kafka-browser-table">
      <el-table :data="filteredTopics" stripe border height="320" empty-text="当前没有匹配到 Kafka topic">
        <el-table-column prop="name" label="Topic" min-width="220" show-overflow-tooltip />
        <el-table-column prop="partitions" label="Partitions" min-width="120" />
        <el-table-column prop="replicationFactor" label="Replicas" min-width="120" />
        <el-table-column prop="internal" label="Internal" min-width="120">
          <template #default="{ row }">
            {{ row.internal ? 'Yes' : 'No' }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { KafkaTopicListResponse } from '@/types/query'

const props = defineProps<{
  data: KafkaTopicListResponse
  loading?: boolean
}>()

defineEmits<{
  refresh: []
}>()

const keyword = ref('')

const filteredTopics = computed(() => {
  const search = normalizeFuzzyText(keyword.value)
  if (!search) return props.data.topics
  return props.data.topics.filter((item) => fuzzyMatch(item.name, search))
})

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
.kafka-browser {
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 18px;
  margin: 12px 0 18px;
  background: rgba(255, 252, 247, 0.92);
}

.kafka-browser-header,
.kafka-browser-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.kafka-browser-header {
  align-items: flex-start;
}

.kafka-browser-header p,
.kafka-browser-meta {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.kafka-browser-meta {
  justify-content: flex-start;
  margin-top: 12px;
  flex-wrap: wrap;
}

.kafka-browser-toolbar {
  margin-top: 12px;
}

.kafka-browser-table {
  margin-top: 14px;
}
</style>
