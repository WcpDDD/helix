<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchIssueBody, fetchIssueNotes, type IssueNote } from '@/api/client'
import TaskNext from '@/components/TaskNext.vue'
import TaskPeople from '@/components/TaskPeople.vue'
import { renderMarkdown } from '@/markdown'
import {
  issueLayers,
  issueUnlocked,
  statusTone,
  type IssueEdge,
  type Task,
  type TaskIssue,
} from '@/data/tasks'

const props = defineProps<{
  task: Task
}>()

const emit = defineEmits<{
  close: []
}>()

const pane = ref<'issue' | 'agent'>('issue')
const bodyEl = ref<HTMLElement | null>(null)
const selectedId = ref(defaultIssueId(props.task))
const selectedEdgeId = ref('')
const focus = ref<'issue' | 'edge'>('issue')

function defaultIssueId(task: Task): string {
  return task.code
}

watch(
  () => props.task.code,
  async () => {
    selectedId.value = defaultIssueId(props.task)
    selectedEdgeId.value = ''
    focus.value = 'issue'
    await nextTick()
    bodyEl.value?.scrollTo({ top: 0 })
  },
)

watch(pane, async () => {
  await nextTick()
  bodyEl.value?.scrollTo({ top: 0 })
})

const graphIssues = computed(() => props.task.issues ?? [])
const graphEdges = computed(() => props.task.edges ?? [])
const hasChildren = computed(() => graphIssues.value.length > 0)
const orderedIssues = computed(() => issueLayers(graphIssues.value, graphEdges.value).flat())
const showingContract = computed(() => hasChildren.value && focus.value === 'edge')

const mainIssue = computed((): TaskIssue => ({
  id: props.task.code,
  url: props.task.issue.url,
  title: props.task.description,
  status: props.task.status,
  stage: props.task.stage,
  owner: props.task.owner,
  kind: props.task.issue.kind,
  author: props.task.issue.author,
  allowedScope: props.task.issue.allowedScope,
  blastRadius: props.task.issue.blastRadius,
  review: props.task.issue.review,
  version: props.task.issue.version,
  conclusion: props.task.issue.conclusion,
  mergeRequests: props.task.issue.mergeRequests,
  environments: props.task.issue.environments,
}))

const issueView = computed((): TaskIssue => {
  return graphIssues.value.find((item) => item.id === selectedId.value) ?? mainIssue.value
})

const selectedEdge = computed(() => graphEdges.value.find((edge) => edge.id === selectedEdgeId.value) ?? null)

const mergeRequests = computed(() => issueView.value.mergeRequests ?? [])
const environments = computed(() => issueView.value.environments ?? [])

const issueBody = ref('')
const issueBodyState = ref<'loading' | 'ready' | 'empty' | 'login' | 'error'>('loading')
const issueBodyHtml = computed(() => (issueBody.value ? renderMarkdown(issueBody.value) : ''))
const issueNotes = ref<IssueNote[]>([])
const issueNotesState = ref<'loading' | 'ready' | 'empty' | 'login' | 'error'>('loading')
const notesExpanded = ref(false)
const hiddenNotes = computed(() => (issueNotes.value.length > 3 ? issueNotes.value.slice(1, -1) : []))
const leadingNotes = computed(() => (hiddenNotes.value.length > 0 ? issueNotes.value.slice(0, 1) : issueNotes.value))
const trailingNotes = computed(() => (hiddenNotes.value.length > 0 ? issueNotes.value.slice(-1) : []))

watch(
  () => issueView.value.id,
  (id) => {
    const requestId = id
    issueBody.value = ''
    issueBodyState.value = 'loading'
    issueNotes.value = []
    issueNotesState.value = 'loading'
    notesExpanded.value = false
    fetchIssueBody(requestId)
      .then((text) => {
        if (issueView.value.id !== requestId) return
        issueBody.value = text
        issueBodyState.value = text ? 'ready' : 'empty'
      })
      .catch((error: unknown) => {
        if (issueView.value.id !== requestId) return
        issueBodyState.value = readFailure(error)
      })
    fetchIssueNotes(requestId)
      .then((notes) => {
        if (issueView.value.id !== requestId) return
        issueNotes.value = notes
        issueNotesState.value = notes.length > 0 ? 'ready' : 'empty'
      })
      .catch((error: unknown) => {
        if (issueView.value.id !== requestId) return
        issueNotesState.value = readFailure(error)
      })
  },
  { immediate: true },
)

function readFailure(error: unknown): 'login' | 'error' {
  return error instanceof Error && error.name === 'IssueReadAuthError' ? 'login' : 'error'
}

function commentTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function incoming(id: string): IssueEdge[] {
  return graphEdges.value.filter((edge) => edge.to === id)
}

function locked(issue: TaskIssue): boolean {
  return !issueUnlocked(issue, graphIssues.value, graphEdges.value)
}

function issueTitle(id: string): string {
  return graphIssues.value.find((issue) => issue.id === id)?.title ?? id
}

function selectIssue(id: string) {
  selectedId.value = id
  focus.value = 'issue'
}

function selectEdge(id: string) {
  const edge = graphEdges.value.find((item) => item.id === id)
  selectedEdgeId.value = id
  if (edge) selectedId.value = edge.to
  focus.value = 'edge'
  pane.value = 'issue'
}

function edgeSelected(edge: IssueEdge): boolean {
  return focus.value === 'edge' && selectedEdgeId.value === edge.id
}

function mrStateTone(state: string): string {
  if (state === '打开') return 'wait'
  if (state === '已合并') return 'done'
  return 'muted'
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <section class="detail" aria-labelledby="task-detail-title">
    <header class="detail-head">
      <div class="detail-title">
        <button class="back" type="button" @click="emit('close')">返回列表</button>
        <span class="code">#{{ task.code }}</span>
      </div>
      <h2 id="task-detail-title">{{ task.description }}</h2>
      <span class="state" :class="statusTone(task.status)">{{ task.status }}</span>
      <TaskPeople :creator="task.issue.author" :owners="task.owners ?? []" />
      <nav v-if="hasChildren" class="lane" aria-label="主 issue 和实现顺序">
        <button
          type="button"
          class="chip main"
          :class="{ on: focus === 'issue' && selectedId === task.code }"
          @click="selectIssue(task.code)"
        >
          <span class="chip-role">主</span>
          <span class="code">#{{ task.code }}</span>
        </button>
        <span class="lane-k">顺序</span>
        <template v-for="issue in orderedIssues" :key="issue.id">
          <button
            v-for="edge in incoming(issue.id)"
            :key="edge.id"
            type="button"
            class="knot"
            :class="[edge.needsContract ? '要契约' : '顺序', { on: edgeSelected(edge) }]"
            :title="edge.contractRef ? `${issueTitle(edge.from)} → ${issue.title} · ${edge.contractRef}` : `${issueTitle(edge.from)} → ${issue.title}`"
            @click="selectEdge(edge.id)"
          >
            {{ edge.needsContract ? '要契约' : '顺序' }}
          </button>
          <button
            type="button"
            class="chip"
            :class="{ on: focus === 'issue' && issue.id === selectedId, locked: locked(issue) }"
            @click="selectIssue(issue.id)"
          >
            <span class="code">#{{ issue.id }}</span>
            <span class="chip-title">{{ issue.title }}</span>
            <span v-if="locked(issue)" class="chip-meta warn">未解锁</span>
          </button>
        </template>
      </nav>
      <div class="panes" role="tablist" aria-label="详情内容">
        <button type="button" role="tab" :aria-selected="pane === 'issue'" :class="{ on: pane === 'issue' }" @click="pane = 'issue'">
          Issue 信息
        </button>
        <button type="button" role="tab" :aria-selected="pane === 'agent'" :class="{ on: pane === 'agent' }" @click="pane = 'agent'">
          Agent 会话
        </button>
      </div>
    </header>

    <div v-if="pane === 'issue'" ref="bodyEl" class="detail-body">
      <div class="issue-actions">
        <RouterLink class="spec-entry" :to="{ name: 'task-spec', params: { code: task.code } }">规格</RouterLink>
        <a
          v-if="!showingContract"
          class="gitlab"
          :href="issueView.url"
          target="_blank"
          rel="noopener noreferrer"
        >在 GitLab 打开 #{{ issueView.id }}</a>
      </div>
      <section v-if="showingContract && selectedEdge" class="contract">
        <h3>
          依赖
          <span>{{ selectedEdge.needsContract ? '要契约' : '顺序' }}</span>
        </h3>
        <p class="contract-ends">{{ issueTitle(selectedEdge.from) }} → {{ issueTitle(selectedEdge.to) }}</p>
        <p v-if="selectedEdge.contractRef" class="body">{{ selectedEdge.contractRef }}</p>
        <p v-else class="graph-note">这条边只表示实现顺序。</p>
        <p v-if="selectedEdge.needsContract" class="graph-note">承诺和有没有锚定在这份规格里，不在库里。</p>
      </section>

      <template v-else>
      <section class="next-panel">
        <h3>下一步</h3>
        <TaskNext :task="task" variant="panel" />
      </section>
      <h3 v-if="hasChildren && issueView.id !== task.code" class="issue-heading">
        <span class="code">#{{ issueView.id }}</span>
        {{ issueView.title }}
      </h3>
      <p v-if="hasChildren && issueView.id !== task.code" class="issue-creator">创建人 {{ issueView.author }}</p>
      <dl class="facts">
        <div>
          <dt>性质</dt>
          <dd>{{ issueView.kind }}</dd>
        </div>
        <div>
          <dt>阶段</dt>
          <dd>{{ issueView.stage }}</dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd><span class="state" :class="statusTone(issueView.status)">{{ issueView.status }}</span></dd>
        </div>
        <div>
          <dt>版本</dt>
          <dd>{{ issueView.version || '未排期' }}</dd>
        </div>
      </dl>

      <section class="bounds">
        <div>
          <h3>允许变更范围</h3>
          <p>{{ issueView.allowedScope }}</p>
        </div>
        <div>
          <h3>爆炸半径</h3>
          <p>{{ issueView.blastRadius }}</p>
        </div>
      </section>

      <section class="links">
        <h3>合并请求</h3>
        <p v-if="mergeRequests.length === 0" class="empty">还没有关联合并请求。</p>
        <ul v-else class="mr-list">
          <li v-for="mr in mergeRequests" :key="mr.iid">
            <a class="iid" :href="mr.url" target="_blank" rel="noreferrer">!{{ mr.iid }}</a>
            <span class="mr-title">{{ mr.title }}</span>
            <a class="state mr-state" :class="mrStateTone(mr.state)" :href="mr.url" target="_blank" rel="noreferrer">{{ mr.state }}</a>
          </li>
        </ul>
      </section>

      <section class="links">
        <h3>测试环境</h3>
        <p v-if="environments.length === 0" class="empty">还没有测试环境。</p>
        <ul v-else class="env-list">
          <li v-for="env in environments" :key="env.url">
            <span>{{ env.name }}</span>
            <a :href="env.url" target="_blank" rel="noreferrer">{{ env.url }}</a>
          </li>
        </ul>
      </section>

      <section>
        <h3>结论</h3>
        <p class="body">{{ issueView.conclusion }}</p>
      </section>

      <section>
        <h3>审阅</h3>
        <p class="body">{{ issueView.review }}</p>
      </section>

      <section>
        <h3>正文</h3>
        <p v-if="issueBodyState === 'loading'" class="empty">正在读取 GitLab 上的正文。</p>
        <p v-else-if="issueBodyState === 'login'" class="empty">登录后才能读取 GitLab 上的正文。</p>
        <p v-else-if="issueBodyState === 'error'" class="empty">GitLab 上的正文没有读到。</p>
        <p v-else-if="issueBodyState === 'empty'" class="empty">这张 issue 在 GitLab 上没有正文。</p>
        <div v-else class="issue-body" v-html="issueBodyHtml" />
      </section>

      <section class="comments">
        <h3>评论</h3>
        <p v-if="issueNotesState === 'loading'" class="empty">正在读取 GitLab 上的评论。</p>
        <p v-else-if="issueNotesState === 'login'" class="empty">登录后才能读取 GitLab 上的评论。</p>
        <p v-else-if="issueNotesState === 'error'" class="empty">GitLab 上的评论没有读到。</p>
        <p v-else-if="issueNotesState === 'empty'" class="empty">这张 issue 在 GitLab 上没有评论。</p>
        <ol v-else>
          <li v-for="note in leadingNotes" :key="note.id" class="comment">
            <div class="who">
              <span class="avatar">{{ note.author.slice(0, 1) || '·' }}</span>
              <strong>{{ note.author }}</strong>
              <time :datetime="note.createdAt">{{ commentTime(note.createdAt) }}</time>
            </div>
            <div class="issue-body" v-html="renderMarkdown(note.body)" />
          </li>
          <li v-if="hiddenNotes.length > 0" class="fold-row">
            <button type="button" class="fold" @click="notesExpanded = !notesExpanded">
              {{ notesExpanded ? `收起中间 ${hiddenNotes.length} 条` : `展开中间 ${hiddenNotes.length} 条` }}
            </button>
          </li>
          <template v-if="notesExpanded">
            <li v-for="note in hiddenNotes" :key="note.id" class="comment">
              <div class="who">
                <span class="avatar">{{ note.author.slice(0, 1) || '·' }}</span>
                <strong>{{ note.author }}</strong>
                <time :datetime="note.createdAt">{{ commentTime(note.createdAt) }}</time>
              </div>
              <div class="issue-body" v-html="renderMarkdown(note.body)" />
            </li>
          </template>
          <li v-for="note in trailingNotes" :key="note.id" class="comment">
            <div class="who">
              <span class="avatar">{{ note.author.slice(0, 1) || '·' }}</span>
              <strong>{{ note.author }}</strong>
              <time :datetime="note.createdAt">{{ commentTime(note.createdAt) }}</time>
            </div>
            <div class="issue-body" v-html="renderMarkdown(note.body)" />
          </li>
        </ol>
      </section>
      </template>
    </div>

    <div v-else ref="bodyEl" class="detail-body">
      <p class="run-line">#{{ issueView.id }} {{ issueView.title }}</p>
      <p class="empty">运行记录下一阶段再落表。</p>
    </div>
  </section>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.detail-head,
.detail-body {
  padding-right: 22px;
  padding-left: 22px;
}

.detail-head {
  flex-shrink: 0;
  padding-top: 16px;
  border-bottom: 1px solid var(--line);
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back,
.fold,
.merged button,
.reply button {
  border: 0;
  background: transparent;
  cursor: pointer;
}

.back {
  padding: 0;
  color: var(--accent);
}

.code {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

h2 {
  margin: 8px 0 10px;
  font-size: 22px;
  line-height: 1.35;
  font-weight: 650;
}

.panes {
  display: flex;
  gap: 18px;
  overflow-x: auto;
  scrollbar-width: none;
}

.panes::-webkit-scrollbar {
  display: none;
}

.panes button {
  flex: none;
  border: 0;
  border-bottom: 2px solid transparent;
  padding: 10px 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.panes button.on {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.detail-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-top: 18px;
  padding-bottom: 28px;
  scrollbar-width: none;
}

.detail-body::-webkit-scrollbar {
  display: none;
}

.facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px 16px;
  margin: 0 0 20px;
}

.bounds {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 20px;
}

.bounds p {
  margin: 0;
}

.mr-list,
.env-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.mr-list li,
.env-list li {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
}

.mr-list a.iid,
.env-list a {
  color: var(--accent);
}

a.mr-state {
  margin-bottom: 0;
  cursor: pointer;
}

.mr-title {
  flex: 1;
  min-width: 0;
}

.env-list a {
  min-width: 0;
  overflow-wrap: anywhere;
}

.empty {
  margin: 0;
  color: var(--muted);
}

.graph-note,
.contract-ends {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 13px;
}

.lane {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  margin: 2px 0 4px;
  overflow-x: auto;
  scrollbar-width: none;
}

.lane::-webkit-scrollbar {
  display: none;
}

.lane-k {
  flex: none;
  color: var(--muted);
  font-size: 12px;
}

.chip,
.knot {
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;
  line-height: 1.3;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  padding: 3px 7px;
  border: 1px solid var(--line);
  background: transparent;
  color: inherit;
}

.chip-title {
  white-space: nowrap;
}

.chip-meta {
  flex: none;
  color: var(--muted);
}

.chip-meta.warn {
  color: var(--warn);
}

.chip.on {
  border-color: var(--accent);
  background: #f3faf7;
}

.chip.main {
  flex: none;
  background: var(--accent-soft);
  border-color: transparent;
}

.chip.main.on {
  border-color: var(--accent);
}

.chip-role {
  color: var(--accent);
  font-weight: 650;
}

.chip.locked {
  border-style: dashed;
}

.knot {
  flex: none;
  padding: 2px 6px;
  border: 0;
  background: #eeeae3;
  color: var(--muted);
}

.knot.要契约 {
  background: var(--accent-soft);
  color: var(--accent);
}

.knot.on {
  outline: 1px solid currentColor;
}

.issue-heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 16px;
}

.issue-creator {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 13px;
}

.contract-body {
  display: grid;
  gap: 12px;
  margin: 0;
}

.contract-body dd {
  margin: 2px 0 0;
}

dt,
.who time,
.comments h3 span,
.run-line {
  color: var(--muted);
}

dt,
.who time,
.comments h3 span {
  font-size: 12px;
}

dd,
.body,
.issue-body,
.comment p,
.merged p,
.bubble p {
  margin: 2px 0 0;
}

.issue-body {
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.issue-body :deep(h1),
.issue-body :deep(h2),
.issue-body :deep(h3),
.issue-body :deep(h4) {
  margin: 16px 0 8px;
  line-height: 1.35;
  font-weight: 650;
}

.issue-body :deep(h1) {
  font-size: 20px;
}

.issue-body :deep(h2) {
  font-size: 17px;
}

.issue-body :deep(h3) {
  font-size: 15px;
}

.issue-body :deep(h4),
.issue-body :deep(h5),
.issue-body :deep(h6) {
  font-size: 14px;
}

.issue-body :deep(p),
.issue-body :deep(ul),
.issue-body :deep(ol),
.issue-body :deep(pre),
.issue-body :deep(blockquote),
.issue-body :deep(table) {
  margin: 8px 0;
}

.issue-body :deep(ul),
.issue-body :deep(ol) {
  padding-left: 1.3em;
}

.issue-body :deep(li + li) {
  margin-top: 4px;
}

.issue-body :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.issue-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em;
  padding: 0 4px;
  border-radius: 4px;
  background: #f6f3ec;
}

.issue-body :deep(pre) {
  overflow: auto;
  padding: 12px 14px;
  border-radius: 12px;
  background: #f6f3ec;
}

.issue-body :deep(pre code) {
  padding: 0;
  background: none;
}

.issue-body :deep(blockquote) {
  margin-left: 0;
  padding-left: 12px;
  border-left: 3px solid var(--line);
  color: var(--muted);
}

.issue-body :deep(table) {
  width: 100%;
  max-width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}

.issue-body :deep(th),
.issue-body :deep(td) {
  padding: 6px 8px;
  border: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
}

.issue-body :deep(img) {
  max-width: 100%;
  height: auto;
}

.issue-body :deep(hr) {
  margin: 16px 0;
  border: 0;
  border-top: 1px solid var(--line);
}

.issue-body :deep(input[type='checkbox']) {
  margin-right: 6px;
}

section + section {
  margin-top: 20px;
}

h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 650;
}

.artifacts {
  margin: 0;
  padding-left: 18px;
}

.comments ol,
.thread {
  display: grid;
  gap: 12px;
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.comment,
.merged {
  min-width: 0;
  max-width: 100%;
  padding: 12px 14px;
  border-radius: 14px;
  background: #f6f3ec;
}

.comment .issue-body {
  margin-top: 8px;
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
}

.avatar {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.merged {
  border: 1px dashed var(--line);
  background: #fbf9f4;
}

.merged p {
  margin: 0;
  font-weight: 650;
}

.merged ul {
  display: grid;
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.merged li {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.45;
}

.merged li span {
  margin-right: 6px;
  color: var(--ink);
  font-weight: 650;
}

.merged button,
.fold {
  margin-top: 8px;
  padding: 0;
  color: var(--accent);
}

.fold-row {
  display: flex;
  justify-content: center;
}

.fold-row .fold {
  margin: 0;
  font-size: 13px;
}

.run-line {
  margin: 0 0 14px;
  font-size: 13px;
}

.thread li.human {
  display: flex;
  justify-content: flex-end;
}

.bubble {
  max-width: 86%;
  padding: 10px 12px;
  border-radius: 14px;
  background: #f6f3ec;
}

.human .bubble {
  background: var(--accent-soft);
}

.bubble p {
  margin: 4px 0 0;
}

.reply {
  display: grid;
  flex-shrink: 0;
  gap: 8px;
  margin: 0;
  padding: 12px 22px 16px;
  border-top: 1px solid var(--line);
}

.reply label {
  color: var(--muted);
  font-size: 12px;
}

textarea {
  width: 100%;
  resize: vertical;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  color: var(--ink);
}

.reply button {
  justify-self: end;
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--accent);
  color: white;
}

.reply button:disabled {
  opacity: 0.45;
  cursor: default;
}

.issue-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.spec-entry,
.gitlab {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 650;
  text-decoration: none;
}

.spec-entry {
  background: var(--accent);
  color: #fff;
}

.gitlab {
  border: 1px solid var(--line);
  color: var(--accent);
  font-weight: 500;
}

.next-panel {
  margin-bottom: 20px;
}

.state {
  display: inline-flex;
  margin-bottom: 8px;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
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

@media (max-width: 800px) {
  .facts {
    grid-template-columns: 1fr 1fr;
  }

  .bounds {
    grid-template-columns: 1fr;
  }

  .mr-list li,
  .env-list li {
    flex-wrap: wrap;
  }
}
</style>
