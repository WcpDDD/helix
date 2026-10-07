<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskSpec from '@/components/TaskSpec.vue'
import { fetchTasks } from '@/api/client'
import { useProjectStore } from '@/stores/projects'
import type { Task } from '@/data/tasks'

const route = useRoute()
const router = useRouter()
const projects = useProjectStore()
const task = ref<Task | null>(null)
const ready = ref(false)
const missing = ref(false)

const code = computed(() => String(route.params.code ?? ''))

watch(
  () => [projects.currentId, code.value] as const,
  async ([id]) => {
    if (id == null) return
    ready.value = false
    missing.value = false
    try {
      const tasks = await fetchTasks()
      task.value = tasks.find((item) => item.code === code.value) ?? null
      missing.value = task.value == null
    } catch {
      task.value = null
      missing.value = true
    } finally {
      ready.value = true
    }
  },
  { immediate: true },
)

function back() {
  router.push({ path: '/', query: { task: code.value } })
}
</script>

<template>
  <main class="spec-page">
    <header class="bar">
      <button type="button" class="back" @click="back">返回任务</button>
      <span class="code">#{{ code }}</span>
      <h1 v-if="task">{{ task.description }}</h1>
    </header>
    <p v-if="!ready" class="wait">正在读取任务。</p>
    <p v-else-if="missing" class="wait">没有这张任务。</p>
    <TaskSpec
      v-else-if="task"
      class="reader"
      :task="task"
      :workspace="projects.current ? `${projects.current.workspace.host}/${projects.current.workspace.path}` : undefined"
    />
  </main>
</template>

<style scoped>
.spec-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.bar {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  margin-bottom: 12px;
}

.back {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
}

.code {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

h1 {
  margin: 0;
  min-width: 0;
  flex: 1 1 240px;
  font-size: 22px;
  line-height: 1.35;
  font-weight: 650;
}

.wait {
  margin: 12px 0 0;
  color: var(--muted);
}

.reader {
  flex: 1;
  min-height: 0;
}

@media (max-width: 800px) {
  h1 {
    flex-basis: 100%;
    font-size: 18px;
  }
}
</style>
