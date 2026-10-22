<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchScheduleFiles, fetchScheduleLog, type ScheduleFile, type ScheduleRecord } from '@/api/client'

const files = ref<ScheduleFile[]>([])
const selected = ref('')
const records = ref<ScheduleRecord[]>([])
const bytes = ref(0)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref('')
let timer = 0

onMounted(() => {
  void load(true)
  timer = window.setInterval(() => void load(false), 5000)
})

onUnmounted(() => {
  window.clearInterval(timer)
})

async function load(initial: boolean) {
  if (initial) phase.value = 'loading'
  try {
    const listed = await fetchScheduleFiles()
    files.value = listed
    const current = listed.find((file) => file.name === selected.value) ?? listed.find((file) => file.current) ?? listed[0]
    if (!current) {
      selected.value = ''
      records.value = []
      bytes.value = 0
      phase.value = 'ready'
      return
    }
    if (current.name !== selected.value) selected.value = current.name
    const view = await fetchScheduleLog(current.name)
    records.value = view.records
    bytes.value = view.bytes
    phase.value = 'ready'
    error.value = ''
  } catch (caught) {
    if (initial) {
      phase.value = 'error'
      error.value = caught instanceof Error ? caught.message : '调度日志没有读到。'
    }
  }
}

async function openFile(name: string) {
  selected.value = name
  await load(false)
}

function sizeLabel(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function eventLabel(record: ScheduleRecord): string {
  if (record.event === 'scan') return '扫完'
  if (record.event === 'handoff') return '交给 manager'
  if (record.event === 'scan-failed') return '没扫到'
  if (record.event === 'idle') return '没有登录'
  if (record.event === 'tick-failed') return '这一轮没跑完'
  if (record.event === 'coordinate' && record.action === 'ready') return '可以改状态'
  if (record.event === 'coordinate') return '协调'
  if (record.event === 'mock') return 'mock'
  return record.event || '记录'
}

function detail(record: ScheduleRecord): string {
  if (record.event === 'scan') return `${record.project} · 打开 ${record.opened} · 托管 ${record.managed}`
  if (record.event === 'handoff') return `${record.project} #${record.issue}`
  if (record.event === 'scan-failed') return `${record.project} · ${record.status}`
  if (record.event === 'idle') return record.reason || ''
  if (record.event === 'raw') return record.text || ''
  if (record.event === 'coordinate') return `#${record.issue} · ${record.phase || ''} · ${record.note || ''}`
  if (record.event === 'mock') return `#${record.issue} · ${record.tag} · ${record.text || ''}`
  return ''
}

function clock(value: string | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const time = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(date)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`
}
</script>

<template>
  <main class="schedule-page">
    <header class="head">
      <h1>调度</h1>
    </header>
    <p v-if="phase === 'loading'" class="note">正在读取。</p>
    <p v-else-if="phase === 'error'" class="note">{{ error }}</p>
    <div v-else class="board">
      <ul class="files">
        <li v-if="files.length === 0" class="empty">还没有调度记录。</li>
        <li v-for="file in files" :key="file.name">
          <button type="button" :class="{ on: file.name === selected }" @click="openFile(file.name)">
            <span>{{ file.current ? '当前' : file.name }}</span>
            <span class="size">{{ sizeLabel(file.bytes) }}</span>
          </button>
        </li>
      </ul>
      <div class="log">
        <p v-if="records.length === 0" class="note">这个文件还是空的。</p>
        <ol v-else>
          <li v-for="(record, index) in records" :key="`${record.at}-${index}`">
            <time>{{ clock(record.at) }}</time>
            <strong>{{ eventLabel(record) }}</strong>
            <span>{{ detail(record) }}</span>
          </li>
        </ol>
        <p v-if="selected" class="foot">{{ selected }} · {{ sizeLabel(bytes) }}</p>
      </div>
    </div>
  </main>
</template>

<style scoped>
.schedule-page {
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
.note,
.empty,
.foot {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.board {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 16px;
  flex: 1;
  min-height: 0;
  margin-top: 18px;
}

.files {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.files button {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 10px 12px;
  background: var(--surface);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.files button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.size {
  color: var(--muted);
  font-size: 12px;
}

.log {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

ol {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 8px 0;
  overflow: auto;
  list-style: none;
}

li {
  display: grid;
  grid-template-columns: 148px 112px minmax(0, 1fr);
  gap: 12px;
  padding: 8px 16px;
  font-size: 14px;
}

li:nth-child(even) {
  background: color-mix(in srgb, var(--paper) 70%, transparent);
}

time,
.foot {
  color: var(--muted);
}

.foot {
  margin: 0;
  padding: 10px 16px;
  border-top: 1px solid var(--line);
}

span {
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .board {
    grid-template-columns: 1fr;
  }

  li {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
