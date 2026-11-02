<script setup lang="ts">
import { computed } from 'vue'
import { statusTone, type Task } from '@/data/tasks'

const props = defineProps<{
  tasks: Task[]
  selected: string | null
  vertical: boolean
}>()

const emit = defineEmits<{
  open: [task: Task]
}>()

function parse(value?: string | null): number | null {
  if (!value) return null
  const time = new Date(`${value}T00:00:00`).getTime()
  return Number.isNaN(time) ? null : time
}

function rangeOf(task: Task): { start: number; end: number } | null {
  const points = [task.startedAt, task.expectedEndAt, task.finishedAt, task.releaseOn]
    .map(parse)
    .filter((time): time is number => time != null)
  if (points.length === 0) return null
  return { start: Math.min(...points), end: Math.max(...points) }
}

function dayStart(time: number): number {
  const date = new Date(time)
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

const window = computed(() => {
  const today = dayStart(Date.now())
  const monthStart = new Date()
  monthStart.setDate(1)
  monthStart.setHours(0, 0, 0, 0)
  const monthEnd = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0).getTime()
  let start = monthStart.getTime()
  let end = monthEnd
  for (const task of props.tasks) {
    const range = rangeOf(task)
    if (!range) continue
    start = Math.min(start, dayStart(range.start))
    end = Math.max(end, dayStart(range.end))
  }
  const cap = start + 120 * 86400000
  if (end > cap) end = cap
  if (end < start) end = start
  const days: { key: string; label: string; time: number }[] = []
  for (let time = start; time <= end; time += 86400000) {
    const date = new Date(time)
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    days.push({
      key: `${date.getFullYear()}-${month}-${day}`,
      label: days.length % 7 === 0 ? `${date.getMonth() + 1}/${date.getDate()}` : '',
      time,
    })
  }
  return { start, end, today, days }
})

const rows = computed(() =>
  props.tasks.map((task) => {
    const range = rangeOf(task)
    const days = window.value.days
    const span = Math.max(days.length, 1)
    let bar: { left: string; width: string } | null = null
    if (range && days.length > 0) {
      const start = Math.max(0, Math.round((dayStart(range.start) - window.value.start) / 86400000))
      const finish = Math.min(days.length - 1, Math.round((dayStart(range.end) - window.value.start) / 86400000))
      const left = (Math.min(start, finish) / span) * 100
      const width = (Math.max(1, Math.abs(finish - start) + 1) / span) * 100
      bar = { left: `${left}%`, width: `${width}%` }
    }
    return { task, bar, text: label(task) }
  }),
)

const todayLeft = computed(() => {
  const days = window.value.days
  if (days.length === 0) return ''
  const index = Math.round((window.value.today - window.value.start) / 86400000)
  if (index < 0 || index >= days.length) return ''
  return `${((index + 0.5) / days.length) * 100}%`
})

function label(task: Task): string {
  const start = task.startedAt || ''
  const end = task.finishedAt || task.expectedEndAt || task.releaseOn || ''
  if (start && end && start !== end) return `${start} – ${end}`
  if (task.releaseOn) return `${task.releaseOn} 预计上线`
  if (end) return end
  if (start) return start
  return '还没有时间'
}
</script>

<template>
  <section class="gantt" :class="{ vertical }" :style="{ '--days': String(window.days.length) }" aria-label="任务甘特">
    <div class="sheet">
      <div class="head">
        <span />
        <div class="scale" :style="{ gridTemplateColumns: `repeat(${window.days.length}, minmax(28px, 1fr))` }">
          <span v-for="day in window.days" :key="day.key">{{ day.label }}</span>
          <i v-if="todayLeft" class="today" :style="{ left: todayLeft }" />
        </div>
      </div>
      <button
        v-for="row in rows"
        :key="row.task.code"
        type="button"
        class="row"
        :class="{ on: selected === row.task.code }"
        @click="emit('open', row.task)"
      >
        <span class="label">
          <span class="code">#{{ row.task.code }}</span>
          <span class="state" :class="statusTone(row.task.status)">{{ row.task.status }}</span>
          <span v-if="row.task.hitl" class="hitl">hitl</span>
          <span class="text">{{ row.task.description }}</span>
        </span>
        <span class="track">
          <i v-if="todayLeft" class="today" :style="{ left: todayLeft }" />
          <i v-if="row.bar" class="bar" :class="statusTone(row.task.status)" :style="row.bar" />
          <span class="when">{{ row.text }}</span>
        </span>
      </button>
    </div>
    <p v-if="rows.length === 0" class="note">这个阶段还没有任务。</p>
  </section>
</template>

<style scoped>
.gantt {
  height: 100%;
  min-height: 0;
  overflow: auto;
}

.sheet {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: calc(var(--days) * 28px + 260px);
}

.head,
.row {
  display: grid;
  grid-template-columns: minmax(180px, 260px) minmax(0, 1fr);
  gap: 10px;
  align-items: center;
}

.vertical .sheet {
  min-width: calc(var(--days) * 28px);
}

.vertical .head,
.vertical .row {
  grid-template-columns: 1fr;
}

.vertical .head > span {
  display: none;
}

.scale {
  position: relative;
  display: grid;
  min-height: 18px;
  color: var(--muted);
  font-size: 11px;
}

.row {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.row.on {
  border-color: var(--accent);
  background: #f3faf7;
}

.label {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 6px;
  min-width: 0;
}

.code {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.text {
  min-width: 0;
  overflow: hidden;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vertical .text {
  white-space: normal;
}

.track {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 28px;
  overflow: hidden;
  border-radius: 8px;
  background: #f7f4ee;
}

.bar {
  position: absolute;
  top: 6px;
  height: 16px;
  border-radius: 999px;
  background: var(--accent);
}

.bar.wait {
  background: var(--warn);
}

.bar.done {
  background: #0b7a45;
}

.bar.muted {
  background: #b7b1a6;
}

.bar.proposal {
  background: #2451a3;
}

.today {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--danger);
}

.when {
  position: relative;
  z-index: 1;
  padding: 0 8px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.state,
.hitl {
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--accent-soft);
  color: var(--accent);
}

.hitl {
  background: #fde8e4;
  color: #9a3412;
}

.state.wait {
  background: var(--warn-soft);
  color: var(--warn);
}

.state.done {
  background: #e7f6ee;
  color: #0b7a45;
}

.state.muted {
  background: #eeeae3;
  color: var(--muted);
}

.state.proposal {
  background: #e7eef8;
  color: #2451a3;
}

.note {
  margin: 0;
  color: var(--muted);
}
</style>
