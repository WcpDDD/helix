<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TaskDetail from '@/components/TaskDetail.vue'
import TaskNext from '@/components/TaskNext.vue'
import TaskPeople from '@/components/TaskPeople.vue'
import { fetchTasks, transitionTask } from '@/api/client'
import { useProjectStore } from '@/stores/projects'
import { rememberPeople } from '@/data/people'
import { applyTask } from '@/data/statusDraft'
import { taskTransitionKey } from '@/data/taskTransition'
import { stageFilters, statusTone, type StageFilter, type Task, type TaskStage, type TaskStatus } from '@/data/tasks'

const route = useRoute()
const projects = useProjectStore()
const filter = ref<StageFilter>('全部')
const selectedCode = ref<string | null>(typeof route.query.task === 'string' ? route.query.task : null)
const tasks = ref<Task[]>([])
const ready = ref(false)
const loadError = ref('')

const board = computed(() => tasks.value.map(applyTask))

provide(taskTransitionKey, async (code: string, status: TaskStatus, stage: TaskStage) => {
  const result = await transitionTask(code, status, stage)
  const task = tasks.value.find((item) => item.code === code)
  if (task) {
    task.status = result.status
    task.stage = result.stage
  }
  return result
})

watch(
  () => route.query.task,
  (code) => {
    if (typeof code === 'string' && code) selectedCode.value = code
  },
)

watch(
  () => projects.currentId,
  async (id) => {
    if (id == null) return
    ready.value = false
    loadError.value = ''
    try {
      tasks.value = await fetchTasks()
      rememberPeople(tasks.value)
      if (selectedCode.value && !tasks.value.some((task) => task.code === selectedCode.value)) {
        selectedCode.value = null
      }
    } catch {
      tasks.value = []
      loadError.value = '任务列表没有读到。'
    } finally {
      ready.value = true
    }
  },
  { immediate: true },
)

const openTasks = computed(() =>
  board.value.filter((task) => task.status !== '已关闭' && task.status !== '已上线'),
)
const selected = computed(() => board.value.find((task) => task.code === selectedCode.value) ?? null)

const visibleTasks = computed(() => {
  if (filter.value === '已关闭') return board.value.filter((task) => task.status === '已关闭')
  if (filter.value === '已上线') return board.value.filter((task) => task.status === '已上线')
  if (filter.value === '全部') return openTasks.value
  return openTasks.value.filter((task) => task.stage === filter.value)
})

function open(task: Task) {
  selectedCode.value = task.code
}

function countOf(name: StageFilter): number {
  if (name === '已关闭') return board.value.filter((task) => task.status === '已关闭').length
  if (name === '已上线') return board.value.filter((task) => task.status === '已上线').length
  if (name === '全部') return openTasks.value.length
  return openTasks.value.filter((task) => task.stage === name).length
}

function isOverdue(task: Task): boolean {
  if (task.finishedAt || !task.expectedEndAt) return false
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return new Date(`${task.expectedEndAt}T00:00:00`) < today
}

</script>

<template>
  <main class="tasks">
    <header class="tasks-head">
      <div>
        <h1>任务</h1>
      </div>
      <div class="filters" role="tablist" aria-label="按阶段筛选">
        <button
          v-for="name in stageFilters"
          :key="name"
          type="button"
          role="tab"
          :aria-selected="filter === name"
          :class="{ on: filter === name }"
          @click="filter = name"
        >
          {{ name }}
          <span>{{ countOf(name) }}</span>
        </button>
      </div>
    </header>

    <p v-if="!ready && !loadError" class="empty">正在读取任务。</p>
    <p v-else-if="loadError" class="empty">{{ loadError }}</p>

    <div v-if="ready && selected" class="split">
      <aside class="rail" aria-label="任务">
        <div
          v-for="task in visibleTasks"
          :key="task.code"
          class="rail-item"
          role="button"
          tabindex="0"
          :class="{ on: selected?.code === task.code }"
          @click="open(task)"
          @keydown.enter="open(task)"
        >
          <span class="rail-top">
            <span class="code">
              #{{ task.code }}
              <span v-if="(task.issues?.length ?? 0) > 0" class="issue-count">{{ task.issues?.length }} 张子 issue</span>
            </span>
            <span class="state" :class="statusTone(task.status)">{{ task.status }}</span>
          </span>
          <span class="rail-desc">{{ task.description }}</span>
          <TaskPeople :creator="task.issue.author" :owners="task.owners ?? []" />
          <TaskNext :task="task" variant="row" />
        </div>
      </aside>
      <TaskDetail v-if="selected" :task="selected" @close="selectedCode = null" />
    </div>

    <div v-if="ready && !loadError && !selected" class="table-wrap">
      <table>
        <colgroup>
          <col class="col-code" />
          <col class="col-desc" />
          <col class="col-creator" />
          <col class="col-owners" />
          <col class="col-state" />
          <col class="col-next" />
          <col class="col-date" />
          <col class="col-date" />
          <col class="col-date" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">编号</th>
            <th scope="col">描述</th>
            <th scope="col">创建人</th>
            <th scope="col">负责人</th>
            <th scope="col">状态</th>
            <th scope="col">下一步</th>
            <th class="date" scope="col">开始时间</th>
            <th class="date" scope="col">预计结束</th>
            <th class="date" scope="col">实际结束</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="task in visibleTasks"
            :key="task.code"
            role="button"
            tabindex="0"
            @click="open(task)"
            @keydown.enter="open(task)"
          >
            <td class="code">
              #{{ task.code }}
              <span v-if="(task.issues?.length ?? 0) > 0" class="issue-count">{{ task.issues?.length }} 张子 issue</span>
            </td>
            <td class="desc">{{ task.description }}</td>
            <td class="creator">
              <TaskPeople part="creator" :creator="task.issue.author" />
            </td>
            <td class="owners">
              <TaskPeople part="owners" :owners="task.owners ?? []" />
            </td>
            <td>
              <span class="state" :class="statusTone(task.status)">{{ task.status }}</span>
            </td>
            <td class="next">
              <TaskNext :task="task" variant="row" />
            </td>
            <td class="date">{{ task.startedAt }}</td>
            <td class="date" :class="{ overdue: isOverdue(task) }">{{ task.expectedEndAt ?? '—' }}</td>
            <td class="date">{{ task.finishedAt ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <ul v-if="ready && !loadError && !selected" class="task-cards">
      <li
        v-for="task in visibleTasks"
        :key="task.code"
        role="button"
        tabindex="0"
        @click="open(task)"
        @keydown.enter="open(task)"
      >
        <div class="card-top">
          <span class="code">
            #{{ task.code }}
            <span v-if="(task.issues?.length ?? 0) > 0" class="issue-count">{{ task.issues?.length }} 张子 issue</span>
          </span>
          <span class="state" :class="statusTone(task.status)">{{ task.status }}</span>
        </div>
        <p>{{ task.description }}</p>
        <TaskPeople :creator="task.issue.author" :owners="task.owners ?? []" />
        <div class="card-actions">
          <TaskNext :task="task" variant="row" />
        </div>
        <dl>
          <div>
            <dt>开始时间</dt>
            <dd>{{ task.startedAt }}</dd>
          </div>
          <div>
            <dt>预计结束</dt>
            <dd :class="{ overdue: isOverdue(task) }">{{ task.expectedEndAt ?? '—' }}</dd>
          </div>
          <div>
            <dt>实际结束</dt>
            <dd>{{ task.finishedAt ?? '—' }}</dd>
          </div>
        </dl>
      </li>
    </ul>

    <p v-if="ready && !loadError && visibleTasks.length === 0" class="empty">这个阶段还没有任务。</p>
  </main>
</template>

<style scoped>
.tasks-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.filters {
  display: flex;
  gap: 6px;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
}

.filters button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.filters button.on {
  background: var(--accent-soft);
  color: var(--accent);
}

.filters span {
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.split {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.rail {
  overflow: auto;
  display: grid;
  align-content: start;
  gap: 8px;
  padding-right: 2px;
  scrollbar-width: none;
}

.rail::-webkit-scrollbar {
  display: none;
}

.rail-item {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.rail-item.on {
  border-color: var(--accent);
  background: #f3faf7;
}

.rail-top,
.rail-owner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.rail-desc {
  font-weight: 550;
  line-height: 1.4;
}

.issue-count {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.rail-owner {
  justify-content: flex-start;
  color: var(--muted);
  font-size: 13px;
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

table {
  width: 100%;
  min-width: 1340px;
  border-collapse: collapse;
  table-layout: fixed;
}

.col-code {
  width: 132px;
}

.col-desc {
  width: auto;
}

.col-creator {
  width: 156px;
}

.col-owners {
  width: 190px;
}

.col-state {
  width: 90px;
}

.col-next {
  width: 230px;
}

.col-date {
  width: 120px;
}

td.next {
  overflow: visible;
}

th,
td {
  padding: 14px 16px;
  text-align: left;
  vertical-align: middle;
  border-bottom: 1px solid var(--line);
}

th {
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  background: #f7f4ee;
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr {
  cursor: pointer;
}

tbody tr:hover td,
tbody tr.selected td {
  background: #f3faf7;
}

tbody tr.selected td:first-child {
  box-shadow: inset 3px 0 0 var(--accent);
}

.code {
  font-variant-numeric: tabular-nums;
  color: var(--muted);
  white-space: nowrap;
}

.desc {
  overflow: hidden;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.owner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.avatar {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.state {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
  background: var(--accent-soft);
  color: var(--accent);
}

.state.proposal {
  background: #e7eef8;
  color: #2451a3;
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

th.date,
td.date {
  padding-right: 8px;
  padding-left: 8px;
  text-align: center;
  vertical-align: middle;
}

.date {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--ink);
}

.creator,
.owners {
  vertical-align: middle;
}

.overdue {
  color: var(--danger);
  font-weight: 650;
}

.task-cards {
  display: none;
}

.empty {
  margin: 18px 0 0;
  color: var(--muted);
}

@media (max-width: 800px) {
  .split {
    display: flex;
    flex-direction: column;
    min-height: 0;
  }

  .rail {
    display: none;
  }

  .tasks-head {
    flex-direction: column;
    align-items: stretch;
  }

  .filters {
    overflow: auto;
    scrollbar-width: none;
  }

  .filters button {
    flex: none;
    white-space: nowrap;
  }

  .filters::-webkit-scrollbar {
    display: none;
  }

  .table-wrap {
    display: none;
  }

  .task-cards {
    display: grid;
    gap: 12px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .task-cards li {
    min-width: 0;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--surface);
    cursor: pointer;
  }

  .task-cards li.selected {
    border-color: var(--accent);
  }

  .card-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
  }

  .task-cards p {
    margin: 10px 0;
    overflow-wrap: anywhere;
    font-weight: 550;
  }

  .card-actions {
    margin-top: 16px;
  }

  dl {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    margin: 14px 0 0;
  }

  dt {
    color: var(--muted);
    font-size: 12px;
  }

  dd {
    margin: 2px 0 0;
    font-variant-numeric: tabular-nums;
    font-size: 13px;
  }
}
</style>
