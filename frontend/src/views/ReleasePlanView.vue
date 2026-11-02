<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchTasks, scheduleRelease, transitionTask } from '@/api/client'
import { statusTone, type Task, type TaskStatus } from '@/data/tasks'
import { movesFor, stageFor, type Move } from '@/data/transitions'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const maintainer = computed(() => projects.access === 'maintainer')
const tasks = ref<Task[]>([])
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref('')
const now = new Date()
const cursor = ref(new Date(now.getFullYear(), now.getMonth(), 1))
const selected = ref<string | null>(null)
const draft = ref('')
const saving = ref(false)
const movingCode = ref('')
const notice = ref('')

const weekdays = ['一', '二', '三', '四', '五', '六', '日']

watch(
  () => projects.currentId,
  () => {
    void load()
  },
  { immediate: true },
)

async function load() {
  phase.value = 'loading'
  error.value = ''
  try {
    tasks.value = await fetchTasks()
    phase.value = 'ready'
  } catch {
    tasks.value = []
    phase.value = 'error'
    error.value = '发布计划没有读到。'
  }
}

const planned = computed(() =>
  tasks.value.filter((task) => task.status === '待上线池' || (task.status === '已上线' && task.releaseOn)),
)
const unscheduled = computed(() => tasks.value.filter((task) => task.status === '待上线池' && !task.releaseOn))
const picked = computed(() => tasks.value.find((task) => task.code === selected.value) ?? null)
const pickedMoves = computed(() => (picked.value ? statusMoves(picked.value) : []))
const title = computed(() => `${cursor.value.getFullYear()}年${cursor.value.getMonth() + 1}月`)

function iso(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function todayKey(): string {
  return iso(new Date())
}

const cells = computed(() => {
  const first = new Date(cursor.value.getFullYear(), cursor.value.getMonth(), 1)
  const lead = (first.getDay() + 6) % 7
  const start = new Date(first)
  start.setDate(1 - lead)
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start)
    date.setDate(start.getDate() + index)
    const key = iso(date)
    return {
      key,
      day: date.getDate(),
      outside: date.getMonth() !== cursor.value.getMonth(),
      today: key === todayKey(),
      tasks: planned.value.filter((task) => task.releaseOn === key),
    }
  })
})

function choose(task: Task) {
  selected.value = task.code
  draft.value = task.releaseOn ?? ''
  notice.value = ''
  if (!task.releaseOn) return
  const date = new Date(`${task.releaseOn}T00:00:00`)
  if (!Number.isNaN(date.getTime())) cursor.value = new Date(date.getFullYear(), date.getMonth(), 1)
}

function shift(months: number) {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + months, 1)
}

function thisMonth() {
  const date = new Date()
  cursor.value = new Date(date.getFullYear(), date.getMonth(), 1)
}

function overdue(task: Task): boolean {
  return task.status === '待上线池' && !!task.releaseOn && task.releaseOn < todayKey()
}

function statusMoves(task: Task): Move[] {
  return movesFor(task.status, false).filter((move) => !move.disabledReason && !move.needsReason && !move.needsOwners && !move.needsRelease)
}

async function applyMove(task: Task, status: TaskStatus) {
  if (movingCode.value || saving.value) return
  const move = statusMoves(task).find((item) => item.to === status)
  if (!move) return
  movingCode.value = task.code
  notice.value = ''
  selected.value = task.code
  try {
    const result = await transitionTask(task.code, move.to, stageFor(move.to, task.stage))
    task.status = result.status
    task.stage = result.stage
    if (result.releaseOn) task.releaseOn = result.releaseOn
    if (!result.synced) notice.value = '主 issue 没有接受这次流转，任务已按 issue 上的状态对齐。'
  } catch {
    notice.value = '状态没有改成，请再试一次。'
  } finally {
    movingCode.value = ''
  }
}

async function save() {
  const task = picked.value
  if (!task || task.status !== '待上线池' || !draft.value || saving.value) return
  saving.value = true
  notice.value = ''
  try {
    task.releaseOn = await scheduleRelease(task.code, draft.value)
    const date = new Date(`${task.releaseOn}T00:00:00`)
    cursor.value = new Date(date.getFullYear(), date.getMonth(), 1)
  } catch (caught) {
    notice.value = caught instanceof Error ? caught.message : '发布日期没有保存。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="release-page">
    <header class="head">
      <h1>发布计划</h1>
      <div class="month">
        <button type="button" @click="shift(-1)">上个月</button>
        <strong>{{ title }}</strong>
        <button type="button" @click="thisMonth">本月</button>
        <button type="button" @click="shift(1)">下个月</button>
      </div>
    </header>
    <p v-if="phase === 'loading'" class="note">正在读取。</p>
    <p v-else-if="phase === 'error'" class="note">{{ error }}</p>
    <div v-else class="board">
      <section class="calendar" aria-label="发布日历">
        <div class="weekdays">
          <span v-for="name in weekdays" :key="name">{{ name }}</span>
        </div>
        <div class="grid">
          <div v-for="cell in cells" :key="cell.key" class="day" :class="{ outside: cell.outside, today: cell.today }">
            <span class="num">{{ cell.day }}</span>
            <button
              v-for="task in cell.tasks"
              :key="task.code"
              type="button"
              class="chip"
              :class="[statusTone(task.status), { on: selected === task.code, late: overdue(task) }]"
              :title="`#${task.code} ${task.description}`"
              @click="choose(task)"
            >
              <span>#{{ task.code }}</span>
              {{ task.description }}
            </button>
          </div>
        </div>
      </section>
      <aside class="side">
        <section v-if="picked" class="card">
          <p class="code">#{{ picked.code }}</p>
          <h2>{{ picked.description }}</h2>
          <p class="state" :class="statusTone(picked.status)">{{ picked.status }}</p>
          <label v-if="picked.status === '待上线池' && maintainer">
            发布日期
            <input v-model="draft" type="date" />
          </label>
          <p v-else-if="picked.releaseOn" class="note">{{ picked.releaseOn }} {{ picked.status === '已上线' ? '已上线' : '预计上线' }}</p>
          <div v-if="pickedMoves.length" class="moves">
            <button
              v-for="move in pickedMoves"
              :key="move.to"
              type="button"
              class="choice"
              :class="move.tone"
              :disabled="movingCode === picked.code || saving"
              @click="applyMove(picked, move.to)"
            >
              {{ movingCode === picked.code ? '正在更新' : move.label }}
            </button>
          </div>
          <p v-if="notice" class="notice">{{ notice }}</p>
          <div class="actions">
            <button v-if="picked.status === '待上线池' && maintainer" type="button" class="go" :disabled="!draft || saving || movingCode === picked.code" @click="save">
              {{ saving ? '保存中' : '保存日期' }}
            </button>
            <RouterLink class="open" :to="{ path: '/tasks', query: { task: picked.code } }">打开任务</RouterLink>
          </div>
        </section>
        <section class="card">
          <h2>未排期</h2>
          <p v-if="unscheduled.length === 0" class="note">待上线池里的任务都有发布日期。</p>
          <div v-for="task in unscheduled" :key="task.code" class="pending" :class="{ on: selected === task.code }">
            <button type="button" class="pending-main" @click="choose(task)">
              <span>#{{ task.code }}</span>
              <span>{{ task.description }}</span>
            </button>
            <button
              v-for="move in statusMoves(task)"
              :key="move.to"
              type="button"
              class="choice"
              :class="move.tone"
              :disabled="movingCode === task.code"
              @click="applyMove(task, move.to)"
            >
              {{ movingCode === task.code ? '更新中' : move.label }}
            </button>
          </div>
        </section>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.release-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.head h1,
.card h2 {
  margin: 0;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.head h1 {
  font-size: 28px;
  line-height: 1.2;
}

.month,
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.month strong {
  min-width: 7em;
  text-align: center;
}

.month button,
.open,
.go {
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  cursor: pointer;
}

.go {
  background: var(--accent);
  color: white;
}

.go:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.note,
.notice {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.notice {
  color: var(--warn);
}

.board {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 16px;
  flex: 1;
  min-height: 0;
  margin-top: 18px;
}

.calendar,
.card {
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.calendar {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.weekdays,
.grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
}

.grid {
  flex: 1;
  min-height: 0;
  grid-template-rows: repeat(6, minmax(0, 1fr));
}

.weekdays span {
  padding: 10px 8px 6px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}

.day {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  padding: 6px;
  overflow: hidden;
  border-top: 1px solid var(--line);
  border-left: 1px solid var(--line);
}

.day:nth-child(7n + 1) {
  border-left: 0;
}

.day.outside {
  background: color-mix(in srgb, var(--paper) 65%, transparent);
}

.day.today .num {
  background: var(--accent);
  color: white;
}

.num {
  width: fit-content;
  border-radius: 999px;
  padding: 0 6px;
  color: var(--muted);
  font-size: 12px;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow: auto;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
}

.card:last-child {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.card h2,
.code {
  font-size: 16px;
}

.code,
.state {
  margin: 0;
}

label {
  display: grid;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
}

input {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: white;
  color: var(--ink);
  font: inherit;
}

.chip,
.pending-main,
.choice {
  overflow: hidden;
  border: 0;
  border-radius: 8px;
  padding: 3px 6px;
  background: var(--accent-soft);
  color: var(--accent);
  font: inherit;
  font-size: 12px;
  line-height: 1.3;
  text-align: left;
  cursor: pointer;
}

.choice {
  border-radius: 999px;
  padding: 5px 10px;
  white-space: nowrap;
}

.choice:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.choice.forward {
  background: var(--accent);
  color: white;
}

.choice.back {
  background: var(--warn-soft);
  color: var(--warn);
}

.choice.stop {
  background: var(--danger-soft);
  color: var(--danger);
}

.moves {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chip span,
.pending-main span:first-child {
  margin-right: 4px;
  font-weight: 650;
}

.pending {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--warn-soft);
  color: var(--warn);
}

.pending-main {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 8px;
  width: 100%;
  padding: 0;
  background: transparent;
  color: inherit;
  white-space: normal;
}

.pending-main span:last-child {
  overflow-wrap: anywhere;
}

.chip.on,
.pending.on {
  outline: 2px solid var(--accent);
}

.chip.wait,
.chip.late {
  background: var(--warn-soft);
  color: var(--warn);
}

.chip.done {
  background: #e7f6ee;
  color: #0b7a45;
}

.state {
  width: fit-content;
  border-radius: 999px;
  padding: 2px 8px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.state.wait {
  background: var(--warn-soft);
  color: var(--warn);
}

.state.done {
  background: #e7f6ee;
  color: #0b7a45;
}

.open {
  color: var(--accent);
}

@media (max-width: 1080px) {
  .board {
    grid-template-columns: minmax(0, 1fr) 240px;
  }
}

@media (max-width: 760px) {
  .head,
  .board {
    display: flex;
    flex-direction: column;
  }

  .calendar {
    min-height: 420px;
  }

  .month {
    flex-wrap: wrap;
  }
}
</style>
