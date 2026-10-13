<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchWorkers, type WorkerRecord } from '@/api/client'

const workers = ref<WorkerRecord[]>([])
const phase = ref<'loading' | 'ready' | 'error'>('loading')
let timer = 0

onMounted(() => {
  void load()
  timer = window.setInterval(() => void load(false), 5000)
})

onUnmounted(() => {
  window.clearInterval(timer)
})

async function load(initial = true) {
  if (initial) phase.value = 'loading'
  try {
    workers.value = await fetchWorkers()
    phase.value = 'ready'
  } catch {
    if (initial) phase.value = 'error'
  }
}

function formatHeartbeat(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const clock = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(date)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${clock}`
}
</script>

<template>
  <main class="worker-page">
    <header class="head">
      <h1>Worker</h1>
      <p>登记过的执行端。心跳断了就标成不存活。</p>
    </header>
    <p v-if="phase === 'loading'" class="note">正在读取。</p>
    <p v-else-if="phase === 'error'" class="note">worker 没有读到。</p>
    <p v-else-if="workers.length === 0" class="note">还没有 worker 来登记。</p>
    <ul v-else class="cards">
      <li v-for="worker in workers" :key="worker.id" class="card">
        <header>
          <h2>{{ worker.name }}</h2>
          <span class="pill" :class="{ down: !worker.alive }">{{ worker.alive ? '存活' : '不存活' }}</span>
        </header>
        <dl>
          <div>
            <dt>标识</dt>
            <dd>{{ worker.id }}</dd>
          </div>
          <div>
            <dt>最近心跳</dt>
            <dd>{{ formatHeartbeat(worker.lastHeartbeat) }}</dd>
          </div>
        </dl>
      </li>
    </ul>
  </main>
</template>

<style scoped>
.worker-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.head h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.head p,
.note {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin: 28px 0 0;
  padding: 0;
  list-style: none;
}

.card {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.card header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
}

.card h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  overflow-wrap: anywhere;
}

.pill {
  flex: none;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.pill.down {
  background: var(--danger-soft);
  color: var(--danger);
}

dl {
  display: grid;
  gap: 10px;
  margin: 16px 0 0;
}

dl div {
  display: grid;
  gap: 2px;
}

dt {
  color: var(--muted);
  font-size: 12px;
}

dd {
  margin: 0;
  font-size: 14px;
  overflow-wrap: anywhere;
}
</style>
