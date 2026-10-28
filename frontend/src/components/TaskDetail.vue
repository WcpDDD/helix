<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchIssueAgents, createIssueAgentSession, fetchAgentConversations, fetchAgentConversation, sendAgentConversation, fetchConversationFile, fetchIssueBody, fetchIssueNotes, fetchGoal, startGoal, stopGoal, confirmHitl, releaseHitl, type IssueAgent, type ConversationMessage, type ConversationPart, type IssueNote, type GoalStatus } from '@/api/client'
import TaskNext from '@/components/TaskNext.vue'
import TaskPeople from '@/components/TaskPeople.vue'
import { agentTags } from '@/data/agentTags'
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
  refresh: []
}>()

type ShownMessage = ConversationMessage & { agentId: number; agentName: string; agentTag: string }
type Relay = { fromName: string; fromTag: string }

const pane = ref<'issue' | 'goal' | 'agent'>('issue')
const sessionFull = ref(false)
const goalDraft = ref('')
const goalText = ref('')
const goalPhase = ref<GoalStatus['phase']>('idle')
const goalNote = ref('')
const goalError = ref('')
const goalBusy = ref(false)
const hitlDraft = ref('')
const hitlBusy = ref(false)
const hitlError = ref('')
const hitlReason = ref('')
const hitlCleared = ref(false)
const hitlCollapsed = ref(false)
let goalTimer = 0
const showHitl = computed(() => props.task.hitl && !hitlCleared.value)
const seats = ref<IssueAgent[]>([])
const focusAgent = ref<'group' | number>('group')
const sessionState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const creating = ref(false)
const createError = ref('')
const messages = ref<ShownMessage[]>([])
const relayMessages = ref<ShownMessage[]>([])
const hasMore = ref(false)
const loadingOlder = ref(false)
const threadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const draft = ref('')
const sending = ref(false)
const sendError = ref('')
const threadEl = ref<HTMLElement | null>(null)
const fileView = ref<{ name: string; path: string; text: string; binary: boolean } | null>(null)
const fileError = ref('')
let sessionRequest = 0
let threadRequest = 0
const streams = new Map<string, EventSource>()
const currentSeat = computed(() => seats.value.find((seat) => seat.agentId === focusAgent.value) ?? null)
const orderedSeats = computed(() =>
  [...seats.value].sort((left, right) => {
    const rank = stageRank(left.tag) - stageRank(right.tag)
    return rank !== 0 ? rank : left.name.localeCompare(right.name, 'zh')
  }),
)

function stageRank(tag: string): number {
  const index = agentTags.indexOf(tag as (typeof agentTags)[number])
  return index < 0 ? agentTags.length : index
}
let stickToBottom = true
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
    sessionFull.value = false
    await nextTick()
    bodyEl.value?.scrollTo({ top: 0 })
  },
)

watch(pane, async () => {
  await nextTick()
  bodyEl.value?.scrollTo({ top: 0 })
  if (pane.value === 'goal' && !goalText.value) void refreshOpenGoal()
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

watch(
  [pane, () => issueView.value.id],
  ([nextPane, number]) => {
    if (nextPane !== 'agent') {
      closeStream()
      return
    }
    void loadRoster(number)
  },
)

async function loadRoster(number: string) {
  const request = ++sessionRequest
  sessionState.value = 'loading'
  createError.value = ''
  focusAgent.value = 'group'
  relayMessages.value = []
  try {
    const next = await fetchIssueAgents(number)
    if (request !== sessionRequest) return
    seats.value = next
    sessionState.value = 'ready'
    void loadView(number)
  } catch {
    if (request !== sessionRequest) return
    seats.value = []
    sessionState.value = 'error'
  }
}

function selectAgent(id: 'group' | number) {
  if (focusAgent.value === id) return
  focusAgent.value = id
  void loadView(issueView.value.id)
}

async function loadView(number: string) {
  if (focusAgent.value === 'group') {
    await loadGroup(number)
    return
  }
  const seat = currentSeat.value
  if (!seat?.assigned || !seat.alive) {
    closeStream()
    messages.value = []
    threadState.value = 'ready'
    return
  }
  await loadThread(number, seat.agentId, seat.name)
}

async function createSession() {
  if (creating.value || focusAgent.value === 'group') return
  const agentId = focusAgent.value
  const number = issueView.value.id
  creating.value = true
  createError.value = ''
  try {
    const next = await createIssueAgentSession(number, agentId)
    const index = seats.value.findIndex((seat) => seat.agentId === agentId)
    if (index >= 0) seats.value[index] = next
    if (next.assigned && next.alive) await loadThread(number, next.agentId, next.name)
  } catch (error) {
    createError.value = error instanceof Error && error.message ? error.message : '创建 Agent 会话失败。'
  } finally {
    creating.value = false
  }
}

const selectedEdge = computed(() => graphEdges.value.find((edge) => edge.id === selectedEdgeId.value) ?? null)

const mergeRequests = computed(() => issueView.value.mergeRequests ?? [])
const environments = computed(() => issueView.value.environments ?? [])

function localAgentPrompt(number: string): string {
  return [
    `继续 Helix issue #${number} 的本地开发。`,
    '',
    '在本机的 qingflow-workspace 仓库根目录操作。不要使用 Helix worker 的 issue 工作区。',
    '',
    '按 skills/worktree-setup/SKILL.md：',
    `1. 为这次任务新建独立 worktree，目录是 .worktrees/task-${number}/。目录已存在就沿用。不要建到别的路径，也不要检出仓库的默认分支。`,
    `2. 读父 issue #${number} 正文的「子仓协同」。对列出的每个仓库，查出关联 MR 的 source_branch，fetch 这条分支，并在 .worktrees/task-${number}/<仓目录> 检出。`,
    `3. 运行 scripts/worktree-setup.sh ${number}`,
    '4. 做完后列出每个仓的 worktree 路径和实际检出的分支。某个仓没有 MR 或还没 clone，停在那一项并说明，不要猜分支名。',
  ].join('\n')
}

const localAgentCopied = ref(false)
let localAgentTimer = 0

async function copyLocalAgentPrompt() {
  const text = localAgentPrompt(props.task.code)
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.left = '-9999px'
    document.body.appendChild(area)
    area.select()
    document.execCommand('copy')
    area.remove()
  }
  localAgentCopied.value = true
  window.clearTimeout(localAgentTimer)
  localAgentTimer = window.setTimeout(() => {
    localAgentCopied.value = false
  }, 2000)
}

const issueBody = ref('')
const issueBodyState = ref<'loading' | 'ready' | 'empty' | 'login' | 'error'>('loading')
const issueBodyHtml = computed(() => (issueBody.value ? renderMarkdown(issueBody.value) : ''))
const issueNotes = ref<Array<IssueNote & { html: string; time: string }>>([])
const latestNote = computed(() => issueNotes.value[issueNotes.value.length - 1] ?? null)
const issueNotesState = ref<'loading' | 'ready' | 'empty' | 'login' | 'error'>('loading')
const notesExpanded = ref(false)
const hiddenNotes = computed(() => (issueNotes.value.length > 3 ? issueNotes.value.slice(1, -1) : []))
const leadingNotes = computed(() => (hiddenNotes.value.length > 0 ? issueNotes.value.slice(0, 1) : issueNotes.value))
const trailingNotes = computed(() => (hiddenNotes.value.length > 0 ? issueNotes.value.slice(-1) : []))

function applyGoal(status: GoalStatus) {
  goalPhase.value = status.phase
  goalNote.value = status.note
}

async function loadHitlReason() {
  hitlReason.value = ''
  try {
    const seats = await fetchIssueAgents(props.task.code)
    const manager = seats.find((seat) => seat.tag === 'manager' && seat.assigned)
    if (!manager) return
    const page = await fetchAgentConversations(props.task.code, manager.agentId)
    const latest = [...page.messages].reverse().find((item) => item.role === 'assistant' && item.preview)
    if (!latest) return
    const full = await fetchAgentConversation(props.task.code, manager.agentId, latest.seq)
    const text = full.parts.filter((part) => part.kind === 'text' && part.text).map((part) => part.text).join('\n')
    hitlReason.value = text.trim()
  } catch {
    hitlReason.value = ''
  }
}

async function submitHitl() {
  hitlBusy.value = true
  hitlError.value = ''
  try {
    const status = await confirmHitl(props.task.code, hitlDraft.value.trim())
    applyGoal(status)
    hitlCleared.value = true
    hitlDraft.value = ''
    emit('refresh')
  } catch (error) {
    hitlError.value = error instanceof Error ? error.message : '确认没有送出去'
  } finally {
    hitlBusy.value = false
  }
}

async function dismissHitl() {
  hitlBusy.value = true
  hitlError.value = ''
  try {
    const status = await releaseHitl(props.task.code)
    applyGoal(status)
    hitlCleared.value = true
    emit('refresh')
  } catch (error) {
    hitlError.value = error instanceof Error ? error.message : '确认没有结束'
  } finally {
    hitlBusy.value = false
  }
}

watch(
  () => props.task.hitl,
  (waiting) => {
    if (!waiting) return
    hitlCleared.value = false
    hitlCollapsed.value = false
    void loadHitlReason()
  },
  { immediate: true },
)

async function commitGoal() {
  const text = goalDraft.value.trim() || '做到测试通过'
  goalBusy.value = true
  goalError.value = ''
  try {
    const status = await startGoal(props.task.code, text)
    goalText.value = text
    applyGoal(status)
  }
  catch (error) {
    goalError.value = error instanceof Error ? error.message : '委托没有发出去'
  }
  finally {
    goalBusy.value = false
  }
}

async function clearGoal() {
  goalBusy.value = true
  goalError.value = ''
  try {
    await stopGoal(props.task.code)
    goalText.value = ''
    goalDraft.value = ''
    applyGoal({ phase: 'idle', note: '' })
  }
  catch (error) {
    goalError.value = error instanceof Error ? error.message : '委托没有停下来'
  }
  finally {
    goalBusy.value = false
  }
}

async function refreshGoal() {
  if (!goalText.value) return
  try {
    const status = await fetchGoal(props.task.code)
    if (status.phase === 'idle') return
    applyGoal(status)
  }
  catch {
    // 轮询失败时留着上一轮的阶段
  }
}

function stopGoalPoll() {
  window.clearInterval(goalTimer)
  goalTimer = 0
}

async function refreshOpenGoal() {
  try {
    const status = await fetchGoal(props.task.code)
    if (status.phase === 'idle') return
    if (!goalText.value) goalText.value = '做到测试通过'
    applyGoal(status)
  }
  catch {
    // 打不开进度时留在委托表单
  }
}

function startGoalPoll() {
  stopGoalPoll()
  if (!goalText.value) return
  goalTimer = window.setInterval(() => {
    void refreshGoal()
  }, 4000)
}

watch(goalText, () => {
  startGoalPoll()
})

async function resumeGoal(text: string) {
  goalBusy.value = true
  goalError.value = ''
  try {
    applyGoal(await startGoal(props.task.code, text))
    startGoalPoll()
  }
  catch (error) {
    goalError.value = error instanceof Error ? error.message : '委托没有发出去'
  }
  finally {
    goalBusy.value = false
  }
}

watch(
  () => issueView.value.id,
  (id) => {
    const requestId = id
    goalDraft.value = ''
    goalText.value = ''
    goalError.value = ''
    applyGoal({ phase: 'idle', note: '' })
    if (pane.value === 'goal') void refreshOpenGoal()
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
        issueNotes.value = notes.map((note) => ({
          ...note,
          html: renderMarkdown(note.body),
          time: commentTime(note.createdAt),
        }))
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

let issueTimer = 0
let rosterTimer = 0
let rosterPollRequest = 0

function notesSignature(list: Array<{ id: number; createdAt: string; body: string }>): string {
  return list.map((note) => `${note.id}:${note.createdAt}:${note.body.length}`).join('|')
}

async function pollIssue() {
  if (document.hidden || pane.value !== 'issue') return
  const id = issueView.value.id
  try {
    const [text, notes] = await Promise.all([fetchIssueBody(id), fetchIssueNotes(id)])
    if (id !== issueView.value.id) return
    if (text !== issueBody.value) {
      issueBody.value = text
      issueBodyState.value = text ? 'ready' : 'empty'
    }
    const mapped: Array<IssueNote & { html: string; time: string }> = notes.map((note) => ({
      ...note,
      html: renderMarkdown(note.body),
      time: commentTime(note.createdAt),
    }))
    if (notesSignature(mapped) !== notesSignature(issueNotes.value)) issueNotes.value = mapped
  }
  catch {
    // 静默，下一轮再试
  }
}

async function pollRoster() {
  if (document.hidden || pane.value !== 'agent') return
  const id = issueView.value.id
  const request = ++rosterPollRequest
  try {
    const next = await fetchIssueAgents(id)
    if (request !== rosterPollRequest || id !== issueView.value.id) return
    const shape = (list: IssueAgent[]) =>
      list.map((seat) => [seat.agentId, seat.assigned, seat.alive, seat.workerId].join(':')).join('|')
    if (shape(next) !== shape(seats.value)) seats.value = next
    if (focusAgent.value === 'group' || threadState.value !== 'ready') return
    const seat = currentSeat.value
    if (!seat?.assigned || !seat.alive) return
    const page = await fetchAgentConversations(id, seat.agentId)
    if (request !== rosterPollRequest || id !== issueView.value.id) return
    const last = page.messages[page.messages.length - 1]
    if (!last || last.status !== 'running') return
    const loaded = messages.value.filter((message) => message.agentId === seat.agentId)
    const loadedSeq = loaded.length > 0 ? loaded[loaded.length - 1].seq : 0
    if (last.seq > loadedSeq) void loadView(id)
  }
  catch {
    // 静默，下一轮再试
  }
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
  if (event.key !== 'Escape') return
  if (fileView.value) {
    fileView.value = null
    return
  }
  if (sessionFull.value) {
    sessionFull.value = false
    return
  }
  emit('close')
}

const replying = computed(() =>
  focusAgent.value !== 'group' &&
  messages.value.some((message) => message.agentId === focusAgent.value && message.role === 'assistant' && message.status === 'running'),
)

async function loadGroup(number: string) {
  const request = ++threadRequest
  closeStream()
  threadState.value = 'loading'
  sendError.value = ''
  fileView.value = null
  const seated = seats.value.filter((seat) => seat.assigned && seat.alive)
  try {
    const pages = await Promise.all(seated.map((seat) => readPage(number, seat, undefined)))
    if (request !== threadRequest) return
    const flat = pages.flat().sort(byTime)
    relayMessages.value = flat
    messages.value = flat
    hasMore.value = false
    threadState.value = 'ready'
    stickToBottom = true
    await nextTick()
    scrollThreadToEnd()
    for (const message of messages.value) {
      if (message.role === 'assistant' && message.status === 'running') watchStream(number, message.agentId, message.seq)
    }
  } catch {
    if (request !== threadRequest) return
    messages.value = []
    threadState.value = 'error'
  }
}

async function loadThread(number: string, agentId: number, agentName: string) {
  const request = ++threadRequest
  closeStream()
  threadState.value = 'loading'
  sendError.value = ''
  fileView.value = null
  try {
    const page = await fetchAgentConversations(number, agentId)
    if (request !== threadRequest) return
    const full = await Promise.all(page.messages.map((item) => stamp(number, agentId, agentName, currentSeat.value?.tag ?? '', item.seq)))
    if (request !== threadRequest) return
    messages.value = full
    hasMore.value = page.hasMore
    threadState.value = 'ready'
    stickToBottom = true
    await nextTick()
    scrollThreadToEnd()
    const last = full[full.length - 1]
    if (last?.role === 'assistant' && last.status === 'running') watchStream(number, agentId, last.seq)
    void warmRelays(number, request)
  } catch {
    if (request !== threadRequest) return
    messages.value = []
    threadState.value = 'error'
  }
}

async function readPage(number: string, seat: IssueAgent, before?: number): Promise<ShownMessage[]> {
  const page = await fetchAgentConversations(number, seat.agentId, before)
  return Promise.all(page.messages.map((item) => stamp(number, seat.agentId, seat.name, seat.tag, item.seq)))
}

async function stamp(number: string, agentId: number, agentName: string, agentTag: string, seq: number): Promise<ShownMessage> {
  const message = await fetchAgentConversation(number, agentId, seq)
  return { ...message, agentId, agentName, agentTag }
}

async function warmRelays(number: string, request: number) {
  if (relayMessages.value.length > 0) return
  const seated = seats.value.filter((seat) => seat.assigned && seat.alive)
  try {
    const pages = await Promise.all(seated.map((seat) => readPage(number, seat)))
    if (request !== threadRequest) return
    relayMessages.value = pages.flat()
  } catch {
    relayMessages.value = []
  }
}

const relays = computed(() => indexRelays(focusAgent.value === 'group' ? messages.value : relayMessages.value))

function indexRelays(list: ShownMessage[]): Map<string, Relay> {
  const found = new Map<string, Relay>()
  for (const message of list) {
    if (message.role !== 'assistant') continue
    for (const part of message.parts) {
      if (part.kind !== 'tool') continue
      const raw = `${part.arguments ?? ''}\n${part.observation ?? ''}`
      for (const sent of sentTasks(raw)) {
        const target = seatByKey(sent.target)
        if (!target || target.agentId === message.agentId || !sent.text) continue
        found.set(`${target.agentId}\u0000${sent.text}`, { fromName: message.agentName, fromTag: message.agentTag })
      }
    }
  }
  return found
}

function seatByKey(key: string): IssueAgent | undefined {
  if (/^\d+$/.test(key)) return seats.value.find((seat) => String(seat.agentId) === key)
  return seats.value.find((seat) => seat.tag === key || seat.name === key)
}

function sentTasks(raw: string): { target: string; text: string }[] {
  const found: { target: string; text: string }[] = []
  const marks = raw.matchAll(/\/agents\/([^/\\\s"'?]+)\/tasks/g)
  for (const mark of marks) {
    const start = Math.max(0, mark.index - 800)
    const window = raw.slice(start, mark.index + 4000)
    const text = readTaskText(window)
    if (text) found.push({ target: mark[1], text })
  }
  return found
}

function readTaskText(source: string): string {
  const plain = source.match(/"text"\s*:\s*"((?:\\.|[^"\\])*)"/)
  if (plain) return decodeTaskText(plain[1])
  const escaped = source.match(/\\"text\\"\s*:\s*\\"((?:\\.|[^"\\])*)\\"/)
  return escaped ? decodeTaskText(escaped[1]) : ''
}

function decodeTaskText(value: string): string {
  try {
    return JSON.parse(`"${value}"`).trim()
  } catch {
    return value.trim()
  }
}

function messageText(message: ShownMessage): string {
  return message.parts.filter((part) => part.kind === 'text' && part.text).map((part) => part.text ?? '').join('\n').trim()
}

function relayOf(message: ShownMessage): Relay | undefined {
  if (message.role !== 'user') return undefined
  const text = messageText(message)
  return text ? relays.value.get(`${message.agentId}\u0000${text}`) : undefined
}

function place(message: ShownMessage): 'user' | 'relay' | 'assistant' {
  if (relayOf(message)) return 'relay'
  return message.role === 'user' ? 'user' : 'assistant'
}

function showIdentity(message: ShownMessage): boolean {
  return focusAgent.value === 'group' || place(message) === 'relay'
}

function messageClock(ms: number): string {
  const date = new Date(ms)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  const clock = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date)
  const sameDay = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
  if (sameDay) return clock
  const day = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
  return `${day} ${clock}`
}

function messageStamp(ms: number): string {
  const date = new Date(ms)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString()
}

function showTimeNode(index: number): boolean {
  const message = messages.value[index]
  if (!message) return false
  if (index === 0) return true
  return messageClock(message.createdAt) !== messageClock(messages.value[index - 1].createdAt)
}

function identity(message: ShownMessage): { mark: string; name: string; tag: string; toward: string } {
  const relay = relayOf(message)
  if (relay) {
    return {
      mark: relay.fromName.slice(0, 1) || '·',
      name: relay.fromName,
      tag: relay.fromTag === relay.fromName ? '' : relay.fromTag,
      toward: message.agentTag || message.agentName,
    }
  }
  if (message.role === 'user') {
    return { mark: '你', name: '你', tag: '', toward: message.agentTag || message.agentName }
  }
  return {
    mark: message.agentName.slice(0, 1) || '·',
    name: message.agentName,
    tag: message.agentTag === message.agentName ? '' : message.agentTag,
    toward: '',
  }
}

function byTime(left: ShownMessage, right: ShownMessage): number {
  return left.createdAt - right.createdAt || left.agentId - right.agentId || left.seq - right.seq
}

async function loadOlder() {
  if (focusAgent.value === 'group') return
  const el = threadEl.value
  const oldest = messages.value[0]
  const seat = currentSeat.value
  if (!el || !oldest || !seat || !hasMore.value || loadingOlder.value) return
  const number = issueView.value.id
  const request = threadRequest
  loadingOlder.value = true
  const beforeHeight = el.scrollHeight
  try {
    const page = await fetchAgentConversations(number, seat.agentId, oldest.seq)
    if (request !== threadRequest) return
    const full = await Promise.all(page.messages.map((item) => stamp(number, seat.agentId, seat.name, seat.tag, item.seq)))
    if (request !== threadRequest) return
    messages.value = [...full, ...messages.value]
    hasMore.value = page.hasMore
    await nextTick()
    el.scrollTop = el.scrollHeight - beforeHeight
  } catch {
    sendError.value = '更早的消息没有读到。'
  } finally {
    loadingOlder.value = false
  }
}

function onThreadScroll() {
  const el = threadEl.value
  if (!el) return
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48
  if (el.scrollTop < 24) void loadOlder()
}

async function send() {
  const text = draft.value.trim()
  const seat = currentSeat.value
  if (!text || sending.value || replying.value || !seat) return
  const number = issueView.value.id
  sending.value = true
  sendError.value = ''
  try {
    const sent = await sendAgentConversation(number, seat.agentId, text)
    draft.value = ''
    const user = { ...sent.user, agentId: seat.agentId, agentName: seat.name, agentTag: seat.tag }
    const assistant = { ...sent.assistant, agentId: seat.agentId, agentName: seat.name, agentTag: seat.tag }
    messages.value = messages.value.filter((message) => message.agentId !== seat.agentId || (message.seq !== user.seq && message.seq !== assistant.seq))
    messages.value.push(user, assistant)
    stickToBottom = true
    await nextTick()
    scrollThreadToEnd()
    if (assistant.status === 'running') watchStream(number, seat.agentId, assistant.seq)
  } catch (error) {
    sendError.value = error instanceof Error && error.message ? error.message : '消息没有送出去。'
  } finally {
    sending.value = false
  }
}

let draftComposing = false

function onDraftCompositionStart() {
  draftComposing = true
}

function onDraftCompositionEnd() {
  window.setTimeout(() => {
    draftComposing = false
  }, 0)
}

function onDraftKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  if (event.isComposing || event.keyCode === 229 || draftComposing) return
  event.preventDefault()
  void send()
}

function watchStream(number: string, agentId: number, seq: number) {
  const key = `${agentId}:${seq}`
  streams.get(key)?.close()
  const source = new EventSource(`/api/issues/${encodeURIComponent(number)}/agents/${agentId}/conversations/${seq}/stream`)
  streams.set(key, source)
  const current = () => {
    const index = messages.value.findIndex((message) => message.agentId === agentId && message.seq === seq)
    const message = index < 0 ? undefined : messages.value[index]
    return { index, message }
  }
  const put = (index: number, message: ShownMessage) => {
    messages.value[index] = message
    if (stickToBottom) void nextTick(scrollThreadToEnd)
  }
  const apply = (kind: string) => (event: MessageEvent) => {
    const data = JSON.parse(event.data) as ConversationMessage & ConversationPart & { status?: ConversationMessage['status'] }
    const { index, message } = current()
    if (index < 0 || !message) return
    if (kind === 'snapshot') {
      put(index, { ...(data as ConversationMessage), agentId, agentName: message.agentName, agentTag: message.agentTag })
      return
    }
    if (kind === 'done') {
      put(index, { ...message, status: data.status === 'failed' ? 'failed' : 'done' })
      source.close()
      if (streams.get(key) === source) streams.delete(key)
      return
    }
    const parts = message.parts.slice()
    if (kind === 'text' || kind === 'thinking') {
      const last = parts[parts.length - 1]
      if (last && last.kind === kind) parts[parts.length - 1] = { ...last, text: `${last.text || ''}${data.text || ''}` }
      else parts.push({ kind, text: data.text || '' })
    } else if (kind === 'tool') {
      const found = parts.findIndex((part) => part.kind === 'tool' && part.id === data.id)
      if (found >= 0) parts[found] = { ...data, kind: 'tool' }
      else parts.push({ ...data, kind: 'tool' })
    } else if (kind === 'file') {
      if (!parts.some((part) => part.kind === 'file' && part.path === data.path)) {
        parts.push({ kind: 'file', name: data.name, path: data.path })
      }
    }
    put(index, { ...message, parts })
  }
  source.addEventListener('snapshot', apply('snapshot'))
  source.addEventListener('text', apply('text'))
  source.addEventListener('thinking', apply('thinking'))
  source.addEventListener('tool', apply('tool'))
  source.addEventListener('file', apply('file'))
  source.addEventListener('done', apply('done'))
  source.onerror = () => {
    const { message } = current()
    if (!message || message.status !== 'running') {
      source.close()
      if (streams.get(key) === source) streams.delete(key)
      return
    }
    void fetchAgentConversation(number, agentId, seq).then((fresh) => {
      if (streams.get(key) !== source) return
      const { index, message } = current()
      if (index >= 0 && message) messages.value[index] = { ...fresh, agentId, agentName: message.agentName, agentTag: message.agentTag }
      if (fresh.status !== 'running') {
        source.close()
        if (streams.get(key) === source) streams.delete(key)
      }
    }).catch(() => {})
  }
}

function closeStream() {
  for (const source of streams.values()) source.close()
  streams.clear()
}

function scrollThreadToEnd() {
  const el = threadEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

async function openAttachment(path: string | undefined, agentId: number) {
  if (!path) return
  const number = issueView.value.id
  fileError.value = ''
  fileView.value = { name: path.split('/').pop() || path, path, text: '', binary: false }
  try {
    const file = await fetchConversationFile(number, path, agentId)
    if (fileView.value?.path !== path) return
    fileView.value = file
  } catch (error) {
    fileError.value = error instanceof Error && error.message ? error.message : '附件没有打开。'
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  issueTimer = window.setInterval(() => void pollIssue(), 12000)
  rosterTimer = window.setInterval(() => void pollRoster(), 6000)
  if (goalText.value) {
    void resumeGoal(goalText.value)
    return
  }
  if (pane.value === 'goal') void refreshOpenGoal()
})
onUnmounted(() => {
  closeStream()
  stopGoalPoll()
  window.clearInterval(issueTimer)
  window.clearInterval(rosterTimer)
  window.clearTimeout(localAgentTimer)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <section class="detail" aria-labelledby="task-detail-title">
    <header class="detail-head">
      <div class="detail-title">
        <button class="back" type="button" @click="emit('close')">返回列表</button>
        <span class="code">#{{ task.code }}</span>
        <button type="button" class="local-agent" @click="copyLocalAgentPrompt">
          {{ localAgentCopied ? '已复制' : '本地Agent继续' }}
        </button>
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
        <button type="button" role="tab" :aria-selected="pane === 'goal'" :class="{ on: pane === 'goal' }" @click="pane = 'goal'">
          目标
        </button>
        <button type="button" role="tab" :aria-selected="pane === 'agent'" :class="{ on: pane === 'agent' }" @click="pane = 'agent'">
          Agent 会话
        </button>
      </div>
    </header>

    <section v-if="showHitl" class="hitl-panel" :class="{ folded: hitlCollapsed }" aria-label="待确认">
      <template v-if="hitlCollapsed">
        <p class="hitl-kicker">待确认</p>
        <p class="hitl-bar">manager 停在这里，需要你的决定。</p>
        <button type="button" class="hitl-toggle" @click="hitlCollapsed = false">展开</button>
      </template>
      <template v-else>
        <div class="hitl-head">
          <div>
            <p class="hitl-kicker">待确认</p>
            <h3>交给你确认</h3>
          </div>
          <button type="button" class="hitl-toggle" @click="hitlCollapsed = true">收起</button>
        </div>
        <p class="hitl-lead">写下你的决定；确认并继续会摘掉 ai/hitl，交回 manager。</p>
        <p v-if="hitlReason" class="hitl-reason">{{ hitlReason }}</p>
        <p v-else-if="latestNote" class="hitl-reason">{{ latestNote.body }}</p>
        <p v-else class="hitl-empty">这张 issue 上还没有评论。决定写在下面。</p>
        <label class="hitl-field">
          <span>你的决定</span>
          <textarea v-model="hitlDraft" rows="2" placeholder="按提案继续" />
        </label>
        <p v-if="hitlError" class="goal-error">{{ hitlError }}</p>
        <div class="hitl-actions">
          <button type="button" class="hitl-go" :disabled="hitlBusy" @click="submitHitl">确认并继续</button>
          <button type="button" class="hitl-stop" :disabled="hitlBusy" @click="dismissHitl">结束确认</button>
        </div>
      </template>
    </section>

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
              <time :datetime="note.createdAt">{{ note.time }}</time>
            </div>
            <div class="issue-body" v-html="note.html" />
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
                <time :datetime="note.createdAt">{{ note.time }}</time>
              </div>
              <div class="issue-body" v-html="note.html" />
            </li>
          </template>
          <li v-for="note in trailingNotes" :key="note.id" class="comment">
            <div class="who">
              <span class="avatar">{{ note.author.slice(0, 1) || '·' }}</span>
              <strong>{{ note.author }}</strong>
              <time :datetime="note.createdAt">{{ note.time }}</time>
            </div>
            <div class="issue-body" v-html="note.html" />
          </li>
        </ol>
      </section>
      </template>
    </div>

    <div v-else-if="pane === 'goal'" class="goal-pane">
      <p class="goal-kicker">目标模式</p>
      <h3 class="goal-title">委托 Agent 开发</h3>
      <p class="goal-lead">写下这张 issue 要做到的结果。留空，就做到测试通过。manager 按 issue 和 MR 的实际状态调度已配置的 Agent，做完后交给你确认。</p>
      <template v-if="!goalText">
        <label class="goal-field">
          <span>目标</span>
          <textarea v-model="goalDraft" rows="5" placeholder="做到测试通过" />
        </label>
        <p v-if="goalError" class="goal-error">{{ goalError }}</p>
        <button type="button" class="goal-start" :disabled="goalBusy" @click="commitGoal">委托开发</button>
      </template>
      <template v-else>
        <div class="goal-card">
          <h3>目标</h3>
          <p>{{ goalText }}</p>
        </div>
        <ol class="goal-phases" aria-label="目标进度">
          <li :class="{ done: goalPhase === 'turn' || goalPhase === 'confirm' }"><span>1</span>初始化</li>
          <li :class="{ on: goalPhase === 'turn', done: goalPhase === 'confirm' }"><span>2</span>轮转</li>
          <li :class="{ on: goalPhase === 'confirm' }"><span>3</span>待确认</li>
        </ol>
        <p class="goal-status">{{ goalNote }}</p>
        <p v-if="goalError" class="goal-error">{{ goalError }}</p>
        <button type="button" class="goal-stop" @click="clearGoal">结束委托</button>
      </template>
    </div>

    <div v-else class="session-pane" :class="{ full: sessionFull }">
      <div class="session-bar">
      <p v-if="sessionState === 'loading'" class="empty">正在读取会话。</p>
      <p v-else-if="sessionState === 'error'" class="empty">会话没有读到。</p>
      <nav v-else class="agent-switch" aria-label="Agent">
        <button type="button" :class="{ on: focusAgent === 'group' }" @click="selectAgent('group')">群聊</button>
        <button
          v-for="seat in orderedSeats"
          :key="seat.agentId"
          type="button"
          :class="{ on: focusAgent === seat.agentId }"
          @click="selectAgent(seat.agentId)"
        >
          <span>{{ seat.name }}</span>
          <em v-if="seat.tag">{{ seat.tag }}</em>
        </button>
      </nav>
      <button type="button" class="session-full" @click="sessionFull = !sessionFull">
        {{ sessionFull ? '退出全屏' : '全屏' }}
      </button>
      </div>
      <template v-if="sessionState === 'ready'">
      <p v-if="seats.length === 0" class="empty">这个项目还没有 Agent。</p>
      <div v-else-if="focusAgent !== 'group' && !currentSeat?.assigned" class="session-mask">
        <p>{{ currentSeat?.name }} 还没有自己的会话。</p>
        <p v-if="createError" class="create-error">{{ createError }}</p>
        <button type="button" :disabled="creating" @click="createSession">
          {{ creating ? '正在创建' : '创建会话' }}
        </button>
      </div>
      <div v-else-if="focusAgent !== 'group' && currentSeat && !currentSeat.alive" class="session-alert">
        <div class="session-copy">
          <p>绑定的 Worker 不存活，会话已中断。</p>
          <p class="hint">请联系管理员处理。</p>
        </div>
      </div>
      <div v-else class="session-live">
        <p v-if="threadState === 'loading'" class="empty">正在读取对话。</p>
        <p v-else-if="threadState === 'error'" class="empty">对话没有读到。</p>
        <ol v-else ref="threadEl" class="thread" @scroll="onThreadScroll">
          <li v-if="hasMore" class="older">
            <button type="button" class="fold" :disabled="loadingOlder" @click="loadOlder">
              {{ loadingOlder ? '正在读取更早的消息' : '更早的消息' }}
            </button>
          </li>
          <template v-for="(message, index) in messages" :key="`${message.agentId}-${message.seq}`">
          <li v-if="showTimeNode(index)" class="time-node">
            <time :datetime="messageStamp(message.createdAt)">{{ messageClock(message.createdAt) }}</time>
          </li>
          <li :class="place(message)">
            <div class="bubble">
              <div class="speaker" :class="{ bare: !showIdentity(message) }">
                <template v-if="showIdentity(message)">
                  <span class="avatar">{{ identity(message).mark }}</span>
                  <strong>{{ identity(message).name }}</strong>
                  <em v-if="identity(message).tag">{{ identity(message).tag }}</em>
                  <span v-if="identity(message).toward" class="toward">发给 {{ identity(message).toward }}</span>
                </template>
                <time class="when" :datetime="messageStamp(message.createdAt)">{{ messageClock(message.createdAt) }}</time>
              </div>
              <template v-for="(part, index) in message.parts" :key="`${message.seq}-${index}`">
                <details v-if="part.kind === 'thinking'" class="thinking" :open="message.status === 'running'">
                  <summary>思考</summary>
                  <p>{{ part.text }}</p>
                </details>
                <details v-else-if="part.kind === 'tool'" class="tool" :open="part.status != null && part.status !== 'done'">
                  <summary>
                    <span>{{ part.name || '工具' }}</span>
                    <em>{{ part.status === 'done' ? '完成' : '进行中' }}</em>
                  </summary>
                  <pre v-if="part.arguments">{{ part.arguments }}</pre>
                  <pre v-if="part.observation">{{ part.observation }}</pre>
                </details>
                <button v-else-if="part.kind === 'file'" type="button" class="file-ref" @click="openAttachment(part.path, message.agentId)">
                  {{ part.name || part.path }}
                </button>
                <div v-else-if="part.text" class="issue-body" v-html="renderMarkdown(part.text)" />
              </template>
              <p v-if="message.role === 'assistant' && message.status === 'running'" class="hint">正在回复</p>
              <p v-else-if="message.status === 'failed'" class="create-error">这轮没有完成。</p>
            </div>
          </li>
          </template>
          <li v-if="messages.length === 0" class="thread-empty">
            <p class="empty">还没有消息。</p>
          </li>
        </ol>
        <p v-if="focusAgent === 'group'" class="group-note">选择一个 Agent 再发送。群聊按时间把各自的会话铺在一起。</p>
        <form v-else class="reply" @submit.prevent="send">
          <textarea
            v-model="draft"
            rows="3"
            placeholder="发给这个 Agent"
            :disabled="sending || replying"
            @compositionstart="onDraftCompositionStart"
            @compositionend="onDraftCompositionEnd"
            @keydown="onDraftKeydown"
          />
          <p v-if="sendError" class="create-error">{{ sendError }}</p>
          <button type="submit" :disabled="sending || replying || !draft.trim()">
            {{ sending ? '正在发送' : '发送' }}
          </button>
        </form>
        <section v-if="fileView" class="file-view" role="dialog" aria-label="附件">
          <header>
            <strong>{{ fileView.name }}</strong>
            <button type="button" @click="fileView = null">关闭</button>
          </header>
          <p v-if="fileError" class="create-error">{{ fileError }}</p>
          <p v-else-if="fileView.binary" class="empty">这个附件不能按文本打开。</p>
          <pre v-else>{{ fileView.text }}</pre>
        </section>
      </div>
      </template>
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

.detail-title .local-agent {
  margin-left: auto;
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
  overflow-anchor: none;
  overscroll-behavior: contain;
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
  max-width: 100%;
  min-width: 0;
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
  max-width: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
}

.thread > li {
  min-width: 0;
  max-width: 100%;
}

.comment,
.merged {
  content-visibility: auto;
  contain-intrinsic-size: auto 96px;
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

.session-bar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin: 0 0 16px;
}

.session-bar > .empty {
  flex: 1;
  margin: 0;
}

.agent-switch {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 6px;
  min-width: 0;
  margin: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.agent-switch::-webkit-scrollbar {
  display: none;
}

.agent-switch button {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.agent-switch button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.agent-switch em {
  color: var(--muted);
  font-style: normal;
  font-size: 12px;
}

.agent-switch button.on em {
  color: var(--accent);
}

.session-full {
  flex: none;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.session-pane.full {
  position: fixed;
  inset: 12px;
  z-index: 40;
  overflow: visible;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  box-shadow: 0 18px 50px rgba(40, 32, 20, 0.18);
}

.session-pane.full::before {
  content: '';
  position: fixed;
  inset: -12px;
  z-index: -1;
  background: rgba(28, 25, 20, 0.32);
}

.speaker {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  color: var(--ink);
  font-size: 13px;
}

.speaker strong {
  font-weight: 650;
}

.speaker em,
.speaker .toward {
  font-style: normal;
  font-size: 12px;
}

.speaker em {
  border-radius: 999px;
  padding: 1px 7px;
  background: white;
  color: var(--accent);
  font-weight: 650;
}

.speaker .toward,
.speaker .when {
  color: var(--muted);
}

.speaker .when {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.speaker.bare {
  margin-bottom: 4px;
}

.time-node {
  display: flex;
  justify-content: center;
}

.time-node time {
  border-radius: 999px;
  padding: 2px 10px;
  background: #f3efe6;
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.thread li.user .speaker {
  justify-content: flex-end;
}

.thread li.user .speaker .when {
  margin-left: 8px;
}

.group-note {
  margin: 0 0 6px;
  color: var(--muted);
  font-size: 12px;
}

.group-note {
  margin: 0 0 16px;
}

.session-pane > .empty {
  margin: 0;
  padding: 4px 0 0;
}

.thread-empty {
  display: block;
  margin: 0;
  padding: 0;
  text-align: left;
}

.session-pane {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 16px 22px 0;
}

.session-mask,
.session-alert {
  display: grid;
  flex: 1;
  place-items: center;
  align-content: center;
  gap: 16px;
  padding: 32px 0;
  text-align: center;
}

.session-mask {
  background: color-mix(in srgb, var(--surface) 88%, #1a1f1c);
}

.session-copy {
  display: grid;
  gap: 6px;
  justify-items: center;
}

.session-mask > p,
.session-copy p,
.create-error {
  margin: 0;
  max-width: 28em;
  line-height: 1.5;
}

.session-alert .session-copy > p:first-child {
  color: var(--danger, #9a3412);
  font-weight: 650;
}

.hint {
  color: var(--muted);
}

.create-error {
  color: var(--danger, #9a3412);
  font-weight: 650;
}

.session-mask button:disabled {
  opacity: 0.6;
  cursor: default;
}

.session-mask button {
  border: 0;
  border-radius: 999px;
  padding: 8px 16px;
  background: var(--accent);
  color: white;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
}

.session-live {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.session-live > .thread {
  flex: 1;
  min-height: 0;
  overflow: auto;
  align-content: start;
  padding: 0 0 12px;
}

.thread li.user {
  display: flex;
  justify-content: flex-end;
}

.thread li.user .bubble {
  background: var(--accent-soft);
}

.older {
  display: flex;
  justify-content: center;
}

.thinking,
.tool {
  margin-top: 8px;
  min-width: 0;
}

.thinking summary,
.tool summary {
  cursor: pointer;
  color: var(--muted);
  font-size: 12px;
  font-weight: 650;
}

.tool summary {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.tool summary::-webkit-details-marker {
  display: none;
}

.tool summary::before {
  content: '';
  flex: none;
  align-self: center;
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid var(--muted);
}

.tool[open] summary::before {
  border-top: 5px solid var(--muted);
  border-right: 4px solid transparent;
  border-bottom: 0;
  border-left: 4px solid transparent;
}

.tool summary span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool summary em {
  flex: none;
  font-style: normal;
  font-weight: 500;
}

.thinking p,
.tool pre {
  max-width: 100%;
  max-height: 180px;
  overflow: auto;
  margin: 6px 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
}

.file-ref {
  display: inline-flex;
  margin-top: 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 10px;
  background: white;
  color: var(--accent);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.file-view {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}

.file-view header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px;
  border-bottom: 1px solid var(--line);
}

.file-view header button,
.file-view header strong {
  font: inherit;
}

.file-view header button {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
}

.file-view pre {
  flex: 1;
  min-height: 0;
  overflow: auto;
  margin: 0;
  padding: 16px 22px 28px;
  white-space: pre-wrap;
  line-height: 1.5;
}

.file-view .create-error,
.file-view .empty {
  padding: 16px 22px;
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
  box-sizing: border-box;
  width: fit-content;
  min-width: 0;
  max-width: min(720px, 100%);
  padding: 10px 12px;
  border-radius: 14px;
  background: #f6f3ec;
}

.thread li.assistant .bubble {
  width: min(720px, 100%);
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
  margin: 0 -22px;
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

.hitl-panel {
  flex-shrink: 0;
  margin: 0;
  padding: 12px 22px 12px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--accent) 8%, var(--surface));
  max-height: 42vh;
  overflow-y: auto;
}

.hitl-panel.folded {
  display: flex;
  align-items: center;
  gap: 10px;
  max-height: none;
  overflow: visible;
  padding: 10px 22px;
}

.hitl-panel.folded .hitl-kicker {
  margin: 0;
}

.hitl-bar {
  margin: 0;
  flex: 1;
  font-size: 13px;
}

.hitl-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.hitl-head .hitl-kicker {
  margin: 0 0 4px;
}

.hitl-head h3 {
  margin: 0;
}

.hitl-toggle {
  flex-shrink: 0;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 12px;
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.hitl-kicker {
  margin: 0 0 4px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 650;
}

.hitl-panel h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.hitl-lead,
.hitl-empty {
  margin: 0 0 6px;
  color: var(--muted, #5c6570);
  font-size: 12.5px;
}

.hitl-reason {
  max-height: 132px;
  overflow: auto;
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--surface);
  white-space: pre-wrap;
  font-size: 12.5px;
  line-height: 1.45;
}

.hitl-field {
  display: grid;
  gap: 6px;
  margin-bottom: 10px;
}

.hitl-field span {
  font-size: 13px;
  font-weight: 650;
}

.hitl-field textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  resize: vertical;
}

.hitl-actions {
  display: flex;
  gap: 8px;
}

.hitl-go,
.hitl-stop {
  border-radius: 999px;
  padding: 8px 14px;
}

.hitl-go {
  background: var(--accent);
  color: white;
}

.hitl-stop {
  background: transparent;
  color: var(--ink);
  border: 1px solid var(--line);
}

.hitl-go:disabled,
.hitl-stop:disabled {
  opacity: 0.45;
  cursor: default;
}

.goal-pane {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 18px 22px 28px;
}

.goal-kicker {
  margin: 0 0 6px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 650;
}

.goal-title {
  margin: 0 0 8px;
  font-size: 20px;
  line-height: 1.35;
}

.goal-lead {
  max-width: 40em;
  margin: 0 0 18px;
  color: var(--muted);
}

.goal-field {
  display: grid;
  gap: 8px;
  max-width: 40em;
}

.goal-field span {
  font-size: 13px;
  font-weight: 650;
}

.goal-field textarea {
  width: 100%;
  min-height: 120px;
  resize: vertical;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  color: var(--ink);
  font: inherit;
}

.goal-start,
.goal-stop {
  margin-top: 14px;
  border-radius: 999px;
  padding: 8px 16px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.goal-start {
  border: 0;
  background: var(--accent);
  color: #fff;
  font-weight: 650;
}

.goal-card {
  max-width: 40em;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.goal-card p {
  margin: 0;
  white-space: pre-wrap;
}

.goal-phases {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.goal-phases li {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 12px 0 6px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
  color: var(--muted);
  font-size: 13px;
}

.goal-phases li span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 999px;
  background: #fff;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.goal-phases li.done {
  color: var(--ink);
  background: #fff;
}

.goal-phases li.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.goal-error {
  margin: 12px 0 0;
  color: #b42318;
}

.goal-status {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.goal-stop {
  border: 1px solid var(--line);
  background: transparent;
  color: var(--ink);
}

.issue-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.spec-entry,
.gitlab,
.local-agent {
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

.gitlab,
.local-agent {
  border: 1px solid var(--line);
  color: var(--accent);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  background: transparent;
  cursor: pointer;
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
