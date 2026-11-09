<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchObserveDetail, fetchObserveRuns, type ObserveDetail, type ObserveRun } from '@/api/client'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const maintainer = computed(() => projects.access === 'maintainer')
const runs = ref<ObserveRun[]>([])
const selectedKey = ref('')
const detail = ref<ObserveDetail | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref('')
const detailError = ref('')
const now = ref(Date.now())
let listTimer = 0
let clockTimer = 0
let detailToken = 0

const selected = computed(() => runs.value.find((run) => keyOf(run) === selectedKey.value) ?? null)
const runningSince = computed(() => {
  const turns = detail.value?.turns ?? []
  const last = [...turns].reverse().find((turn) => turn.role === 'assistant' && turn.status === 'running')
  return last?.at || selected.value?.at || ''
})

onMounted(() => {
  clockTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
  if (!maintainer.value) {
    phase.value = 'ready'
    return
  }
  void load(true)
  listTimer = window.setInterval(() => void load(false), 3000)
})

onUnmounted(() => {
  window.clearInterval(listTimer)
  window.clearInterval(clockTimer)
  detailToken += 1
})

function keyOf(run: ObserveRun): string {
  return run.place === 'server' ? `server:${run.sessionId}` : `worker:${run.workerId}:${run.issue}:${run.agent}`
}

async function load(initial: boolean) {
  if (!maintainer.value) return
  if (initial) phase.value = 'loading'
  try {
    const next = await fetchObserveRuns()
    runs.value = next
    phase.value = 'ready'
    error.value = ''
    if (selectedKey.value && !next.some((run) => keyOf(run) === selectedKey.value)) {
      detailError.value = '这轮已经结束。'
    }
    else if (selected.value) {
      void open(selected.value, false)
    }
  } catch (caught) {
    if (initial) {
      phase.value = 'error'
      error.value = caught instanceof Error ? caught.message : '运行情况没有读到。'
    }
  }
}

async function choose(run: ObserveRun) {
  selectedKey.value = keyOf(run)
  detail.value = null
  detailError.value = ''
  await open(run, true)
}

async function open(run: ObserveRun, replace: boolean) {
  const token = ++detailToken
  try {
    const next = await fetchObserveDetail(run)
    if (token !== detailToken || keyOf(run) !== selectedKey.value) return
    detail.value = next
    detailError.value = ''
  } catch (caught) {
    if (token !== detailToken || !replace) return
    detailError.value = caught instanceof Error ? caught.message : '这轮运行没有读到。'
  }
}

function placeOf(run: ObserveRun): string {
  if (run.place === 'server') return run.username ? `服务端 · ${run.username}` : '服务端'
  return `Worker · ${run.workerName || run.workerId}`
}

function clock(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(date)
}

function elapsed(value: string): string {
  if (!value) return ''
  const start = new Date(value).getTime()
  if (Number.isNaN(start)) return ''
  const seconds = Math.max(0, Math.floor((now.value - start) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

function toolLabel(status: string): string {
  if (status === 'running') return '进行中'
  if (status === 'failed') return '失败'
  return '完成'
}
</script>

<template>
  <main class="observe-page">
    <header class="head">
      <h1>观测</h1>
      <p>正在运行的 Agent。服务端和 worker 上的都能点开看。</p>
    </header>
    <p v-if="!maintainer" class="note">只有维护者可以看 Agent 运行情况。</p>
    <p v-else-if="phase === 'loading'" class="note">正在读取。</p>
    <p v-else-if="phase === 'error'" class="note">{{ error }}</p>
    <div v-else class="board">
      <section class="list" aria-label="正在运行">
        <p v-if="runs.length === 0" class="note">现在没有正在运行的 Agent。</p>
        <button
          v-for="run in runs"
          :key="keyOf(run)"
          type="button"
          class="card"
          :class="{ on: keyOf(run) === selectedKey }"
          @click="choose(run)"
        >
          <header>
            <strong>{{ run.title }}</strong>
            <span class="pill">进行中</span>
          </header>
          <p class="place">{{ placeOf(run) }}</p>
          <p class="activity">{{ run.activity }}</p>
          <p v-if="elapsed(run.at)" class="when">已进行 {{ elapsed(run.at) }}</p>
        </button>
      </section>
      <section class="detail" aria-label="运行详情">
        <p v-if="!selected && !detail" class="note">点开一条正在运行的 Agent。</p>
        <template v-else>
          <header class="detail-head">
            <div>
              <h2>{{ detail?.title || selected?.title }}</h2>
              <p>{{ selected ? placeOf(selected) : detail?.workerName }} <span v-if="elapsed(runningSince)">· 已进行 {{ elapsed(runningSince) }}</span></p>
            </div>
            <RouterLink v-if="(detail?.issue || selected?.issue)" class="task" :to="{ name: 'tasks', params: { code: String(detail?.issue || selected?.issue) } }">打开任务</RouterLink>
          </header>
          <p v-if="detailError" class="note">{{ detailError }}</p>
          <p v-else-if="!detail" class="note">正在读取这轮运行。</p>
          <p v-if="detail?.note" class="note">{{ detail.note }}</p>
          <ol v-if="detail" class="turns">
            <li v-for="(turn, index) in detail.turns" :key="`${turn.role}-${index}`" class="turn" :class="turn.role">
              <header>
                <strong>{{ turn.role === 'user' ? '用户' : 'Agent' }}</strong>
                <span>{{ clock(turn.at) }}</span>
                <em v-if="turn.status === 'running'">进行中</em>
              </header>
              <details v-if="turn.reasoning" class="fold" :open="turn.status === 'running' && !turn.text">
                <summary>思考</summary>
                <pre>{{ turn.reasoning }}</pre>
              </details>
              <details v-for="(tool, toolIndex) in turn.tools" :key="`${tool.name}-${toolIndex}`" class="fold" :open="tool.status === 'running'">
                <summary>
                  {{ tool.name }}
                  <em>{{ toolLabel(tool.status) }}</em>
                </summary>
                <pre v-if="tool.arguments">{{ tool.arguments }}</pre>
                <pre v-if="tool.observation" class="observation">{{ tool.observation }}</pre>
              </details>
              <pre v-if="turn.text" class="text">{{ turn.text }}</pre>
              <p v-if="turn.role === 'assistant' && turn.status === 'running' && !turn.text" class="wait">正在回复</p>
            </li>
          </ol>
        </template>
      </section>
    </div>
  </main>
</template>

<style scoped>
.observe-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.head h1 {
  margin: 0;
  font-size: 28px;
}

.head p,
.note,
.place,
.activity,
.when,
.detail-head p,
.wait {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.board {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  flex: 1;
  min-height: 0;
  margin-top: 16px;
}

.list,
.detail {
  min-height: 0;
  overflow: auto;
}

.card {
  display: grid;
  width: 100%;
  margin: 0 0 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: white;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.card.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.card header,
.detail-head,
.turn header,
.fold summary {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card strong,
.detail-head h2 {
  margin: 0;
  font-size: 15px;
}

.pill,
.turn em,
.fold em {
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-style: normal;
}

.detail {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fbf9f4;
}

.detail-head {
  justify-content: space-between;
  align-items: flex-start;
}

.task {
  color: var(--accent);
  font-size: 13px;
  white-space: nowrap;
}

.turns {
  display: grid;
  gap: 12px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.turn {
  padding: 12px;
  border-radius: 12px;
  background: white;
}

.turn.user {
  background: #eef4f1;
}

.turn header span,
.fold summary {
  color: var(--muted);
  font-size: 12px;
}

.fold {
  margin-top: 8px;
}

.fold summary {
  cursor: pointer;
}

pre {
  margin: 8px 0 0;
  padding: 8px;
  overflow: auto;
  max-height: 220px;
  border-radius: 8px;
  background: #f4f1ea;
  white-space: pre-wrap;
  word-break: break-word;
  font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
}

.observation {
  background: #f7f7f5;
}

@media (max-width: 800px) {
  .board {
    grid-template-columns: 1fr;
  }
}
</style>
