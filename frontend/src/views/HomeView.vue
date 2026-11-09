<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskCreate from '@/components/TaskCreate.vue'
import TaskDetail from '@/components/TaskDetail.vue'
import TaskGantt from '@/components/TaskGantt.vue'
import TaskNext from '@/components/TaskNext.vue'
import OwnerEdit from '@/components/OwnerEdit.vue'
import TaskPeople from '@/components/TaskPeople.vue'
import { fetchTasks, scheduleRelease, transitionTask } from '@/api/client'
import { useProjectStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { loadDirectory, rememberPeople } from '@/data/people'
import { applyTask, editingOwnerId } from '@/data/statusDraft'
import { taskTransitionKey } from '@/data/taskTransition'
import { stageFilters, statusTone, type StageFilter, type Task, type TaskStage, type TaskStatus } from '@/data/tasks'

const taskViews = ['列表', '甘特', '我的'] as const
type TaskView = (typeof taskViews)[number]

const route = useRoute()
const router = useRouter()
const projects = useProjectStore()
const session = useSessionStore()
const filter = ref<StageFilter>('全部')
const view = ref<TaskView>('列表')
const selectedCode = computed(() => {
  const param = route.params.code
  if (typeof param === 'string' && param) return param
  const query = route.query.task
  return typeof query === 'string' && query ? query : null
})
const tasks = ref<Task[]>([])
const ready = ref(false)
const loadError = ref('')
const creating = ref(false)
const savingCode = ref('')
const releaseError = ref('')
const maintainer = computed(() => projects.access === 'maintainer')

const board = computed(() => tasks.value.map(applyTask))

provide(taskTransitionKey, async (code: string, status: TaskStatus, stage: TaskStage, releaseOn?: string | null, owners?: string[]) => {
  const result = await transitionTask(code, status, stage, releaseOn, owners)
  const task = tasks.value.find((item) => item.code === code)
  if (task) {
    task.status = result.status
    task.stage = result.stage
    if (result.releaseOn) task.releaseOn = result.releaseOn
    if (owners && owners.length > 0 && result.synced) task.owners = owners
  }
  return result
})

watch(
  () => route.query.task,
  (task) => {
    if (typeof task !== 'string' || !task || route.params.code) return
    void router.replace({ name: 'tasks', params: { code: task } })
  },
  { immediate: true },
)

watch(
  () => projects.currentId,
  async (id) => {
    editingOwnerId.value = null
    if (id == null) return
    ready.value = false
    loadError.value = ''
    try {
      tasks.value = await fetchTasks()
      rememberPeople(tasks.value)
      void loadDirectory()
      if (selectedCode.value && !tasks.value.some((task) => task.code === selectedCode.value)) {
        await router.replace('/tasks')
      }
    } catch (error) {
      tasks.value = []
      loadError.value = error instanceof Error && error.message === '需要重新登录。' ? error.message : '任务列表没有读到。'
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

const shownTasks = computed(() => (view.value === '我的' ? visibleTasks.value.filter(isMine) : visibleTasks.value))

const emptyText = computed(() => (view.value === '我的' ? '没有分给你的任务。' : '这个阶段还没有任务。'))

function isMine(task: Task): boolean {
  const user = session.user
  if (!user) return false
  const keys = [user.name, user.username].map((item) => item.trim()).filter(Boolean)
  const people = [task.issue?.author, task.owner, ...(task.owners ?? [])].filter((item): item is string => !!item)
  return people.some((person) => keys.includes(person.trim()))
}

function open(task: Task) {
  if (route.params.code === task.code) return
  void router.push({ name: 'tasks', params: { code: task.code } })
}

function close() {
  void router.push('/tasks')
}

async function created(code: string) {
  creating.value = false
  tasks.value = await fetchTasks()
  rememberPeople(tasks.value)
  void loadDirectory()
  void router.push({ name: 'tasks', params: { code } })
}

async function reloadBoard() {
  tasks.value = await fetchTasks()
  rememberPeople(tasks.value)
  void loadDirectory()
}

let boardTimer = 0
const refreshing = ref(false)

async function manualRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await reloadBoard()
  }
  catch {
    // 保持现有数据，不因刷新失败清空
  }
  finally {
    refreshing.value = false
  }
}

onMounted(() => {
  boardTimer = window.setInterval(() => {
    if (document.hidden || creating.value) return
    void reloadBoard()
  }, 8000)
})

onUnmounted(() => window.clearInterval(boardTimer))

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

function canSchedule(task: Task): boolean {
  return maintainer.value && task.status !== '已关闭' && task.status !== '已驳回'
}

async function saveRelease(task: Task, event: Event) {
  const input = event.target as HTMLInputElement
  const value = input.value
  if (!value || value === (task.releaseOn ?? '') || savingCode.value) {
    input.value = task.releaseOn ?? ''
    return
  }
  savingCode.value = task.code
  releaseError.value = ''
  try {
    const saved = await scheduleRelease(task.code, value)
    const source = tasks.value.find((item) => item.code === task.code)
    if (source) source.releaseOn = saved
  } catch (error) {
    releaseError.value = error instanceof Error ? error.message : '预计上线时间没有保存。'
    input.value = task.releaseOn ?? ''
  } finally {
    savingCode.value = ''
  }
}

function rememberRelease(code: string, date: string) {
  const source = tasks.value.find((item) => item.code === code)
  if (source) source.releaseOn = date
}

function rememberOwners(code: string, owners: string[]) {
  const source = tasks.value.find((item) => item.code === code)
  if (!source) return
  source.owners = owners
  source.owner = owners[0] ?? ''
}

</script>

<template>
  <main class="tasks">
    <header class="tasks-head">
      <div class="title">
        <h1>任务</h1>
        <button type="button" class="create" @click="creating = true">创建任务</button>
        <button type="button" class="refresh" :disabled="refreshing" @click="manualRefresh">{{ refreshing ? '刷新中…' : '刷新' }}</button>
      </div>
      <div class="views" role="tablist" aria-label="任务视图">
        <button
          v-for="name in taskViews"
          :key="name"
          type="button"
          role="tab"
          :aria-selected="view === name"
          :class="{ on: view === name }"
          @click="view = name"
        >
          {{ name }}
        </button>
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
    <p v-if="releaseError" class="empty">{{ releaseError }}</p>

    <div v-if="ready && !loadError && shownTasks.length > 0" class="board" :class="{ split: selected }">
      <div class="pane">
        <TaskGantt v-if="view === '甘特'" :tasks="shownTasks" :selected="selectedCode" :vertical="!!selected" @open="open" />
        <template v-else>
          <div v-if="!selected" class="table-wrap">
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
          <col class="col-release" />
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
            <th class="date" scope="col">预计上线时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="task in shownTasks"
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
            <td class="owners" @click.stop>
              <OwnerEdit :code="task.code" :owners="task.owners ?? []" @saved="rememberOwners(task.code, $event)" />
            </td>
            <td>
              <span class="marks">
                <span class="state" :class="statusTone(task.status)">{{ task.status }}</span>
                <span v-if="task.hitl" class="hitl">hitl</span>
              </span>
            </td>
            <td class="next">
              <TaskNext :task="task" variant="row" />
            </td>
            <td class="date">{{ task.startedAt }}</td>
            <td class="date" :class="{ overdue: isOverdue(task) }">{{ task.expectedEndAt ?? '—' }}</td>
            <td class="date">{{ task.finishedAt ?? '—' }}</td>
            <td class="date" @click.stop>
              <input
                v-if="canSchedule(task)"
                class="release-edit"
                type="date"
                :value="task.releaseOn ?? ''"
                :disabled="savingCode === task.code"
                :aria-label="`#${task.code} 预计上线时间`"
                @click.stop
                @keydown.enter.stop
                @change="saveRelease(task, $event)"
              />
              <span v-else>{{ task.releaseOn || '—' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
          </div>

          <ul class="task-cards">
      <li
        v-for="task in shownTasks"
        :key="task.code"
        role="button"
        tabindex="0"
        :class="{ on: selected?.code === task.code }"
        @click="open(task)"
        @keydown.enter="open(task)"
      >
        <div class="card-top">
          <span class="code">
            #{{ task.code }}
            <span v-if="(task.issues?.length ?? 0) > 0" class="issue-count">{{ task.issues?.length }} 张子 issue</span>
          </span>
          <span class="marks">
            <span class="state" :class="statusTone(task.status)">{{ task.status }}</span>
            <span v-if="task.hitl" class="hitl">hitl</span>
          </span>
        </div>
        <p>{{ task.description }}</p>
        <TaskPeople part="creator" labeled :creator="task.issue.author" />
        <OwnerEdit labeled :code="task.code" :owners="task.owners ?? []" @saved="rememberOwners(task.code, $event)" />
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
          <div @click.stop>
            <dt>预计上线时间</dt>
            <dd>
              <input
                v-if="canSchedule(task)"
                class="release-edit"
                type="date"
                :value="task.releaseOn ?? ''"
                :disabled="savingCode === task.code"
                :aria-label="`#${task.code} 预计上线时间`"
                @click.stop
                @keydown.enter.stop
                @change="saveRelease(task, $event)"
              />
              <template v-else>{{ task.releaseOn || '—' }}</template>
            </dd>
          </div>
        </dl>
      </li>
          </ul>
        </template>
      </div>
      <TaskDetail
        v-if="selected"
        :key="selected.code"
        :task="selected"
        :can-edit-release="canSchedule(selected)"
        @close="close"
        @refresh="reloadBoard"
        @release="rememberRelease(selected.code, $event)"
        @owners="rememberOwners(selected.code, $event)"
      />
    </div>

    <p v-if="ready && !loadError && shownTasks.length === 0" class="empty">{{ emptyText }}</p>
    <TaskCreate v-if="creating" @close="creating = false" @created="created" />
  </main>
</template>

<style scoped>
.tasks {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.tasks-head {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.title {
  display: flex;
  align-items: center;
  gap: 16px;
}

h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.create {
  border: 0;
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--accent);
  color: white;
  cursor: pointer;
}

.refresh {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
  font-size: 13px;
}

.refresh:disabled {
  opacity: 0.45;
  cursor: default;
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

.views {
  display: flex;
  gap: 6px;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
}

.views button {
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.views button.on {
  background: var(--accent-soft);
  color: var(--accent);
}

.board {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  gap: 16px;
}

.board.split {
  grid-template-columns: 240px minmax(0, 1fr);
}

.pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.board.split .pane {
  overflow: auto;
}

.marks {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.marks {
  flex-wrap: nowrap;
  justify-content: flex-end;
}

td .marks {
  justify-content: flex-start;
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

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

table {
  width: 100%;
  min-width: 1566px;
  border-collapse: separate;
  border-spacing: 0;
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
  width: 156px;
}

.col-next {
  width: 230px;
}

.col-date {
  width: 120px;
}

.col-release {
  width: 156px;
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
  position: sticky;
  top: 0;
  z-index: 1;
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

.state,
.hitl {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
  background: var(--accent-soft);
  color: var(--accent);
}

.hitl {
  background: #fde8e4;
  color: #9a3412;
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

.release-edit {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 4px 6px;
  background: white;
  color: var(--ink);
  font: inherit;
  font-size: 13px;
}

.task-cards {
  display: none;
}

.board.split .task-cards {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.board.split .task-cards li {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  cursor: pointer;
}

.board.split .task-cards li.on {
  border-color: var(--accent);
}

.board.split .task-cards p {
  margin: 10px 0;
  overflow-wrap: anywhere;
  font-weight: 550;
}

.board.split .card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.board.split .marks {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.board.split dl {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
  margin: 14px 0 0;
}

.board.split .release-edit {
  max-width: 100%;
}

.board.split dt {
  color: var(--muted);
  font-size: 12px;
}

.board.split dd {
  margin: 2px 0 0;
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}

.empty {
  margin: 18px 0 0;
  color: var(--muted);
}

@media (max-width: 800px) {
  .board.split {
    grid-template-columns: minmax(0, 1fr);
  }

  .board.split .pane {
    display: none;
  }

  .views {
    overflow: auto;
    scrollbar-width: none;
  }

  .views button {
    flex: none;
    white-space: nowrap;
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
    flex: 1;
    align-content: start;
    min-height: 0;
    gap: 12px;
    margin: 0;
    padding: 0;
    overflow: auto;
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
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
