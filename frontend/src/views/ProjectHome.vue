<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import VirtualThread from '@/components/VirtualThread.vue'
import {
  fetchHomeAgent,
  resetHomeAgent,
  sendHomeAgent,
  uploadHomeFile,
  type HomeAgent,
  type HomeAgentFile,
  type HomeAgentMessage,
  type HomeAgentTool,
} from '@/api/client'
import { renderMarkdown } from '@/markdown'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const project = computed(() => projects.current?.name || '当前项目')
const view = ref<HomeAgent | null>(null)
const draft = ref('')
const files = ref<HomeAgentFile[]>([])
const error = ref('')
const sending = ref(false)
const uploading = ref(false)
const resetting = ref(false)
const fileEl = ref<HTMLInputElement | null>(null)
let composing = false
const stickToBottom = ref(true)
let stream: EventSource | null = null
let live: HomeAgentMessage | null = null
let frame = 0
let ticker = 0
const toolStarted = new Map<string, number>()
const now = ref(Date.now())

const running = computed(() => view.value?.messages.some((item) => item.status === 'running') ?? false)
const runningMessage = computed(() =>
  [...(view.value?.messages ?? [])].reverse().find((item) => item.role === 'assistant' && item.status === 'running'),
)
const runningLabel = computed(() => (runningMessage.value ? subject(runningMessage.value) : ''))
const runningElapsed = computed(() => (runningMessage.value ? elapsed(runningMessage.value) : ''))
const locked = computed(() => sending.value || running.value || view.value?.preparing === true || uploading.value)
const canSend = computed(() => !locked.value && (draft.value.trim().length > 0 || files.value.length > 0))

function subject(item: HomeAgentMessage) {
  return runningTool(item)?.name ?? ''
}

function runningTool(item: HomeAgentMessage) {
  return [...(item.tools ?? [])].reverse().find((entry) => entry.status === 'running' && entry.name && entry.name !== 'delegate')
}

function remember(id: string, at: number) {
  if (!toolStarted.has(id)) toolStarted.set(id, at)
}

function note(item: HomeAgentMessage, at: number) {
  for (const tool of item.tools ?? []) {
    if (tool.status === 'running') remember(tool.id, at)
  }
}

function elapsed(item: HomeAgentMessage) {
  const tool = runningTool(item)
  const marked = tool ? toolStarted.get(tool.id) : undefined
  const start = marked ?? Date.parse(item.at)
  if (!Number.isFinite(start)) return ''
  const total = Math.max(0, Math.floor((now.value - start) / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const clock = `${minutes}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : clock
}

function tick(active: boolean) {
  if (active) {
    if (ticker) return
    now.value = Date.now()
    ticker = window.setInterval(() => {
      now.value = Date.now()
    }, 1000)
    return
  }
  if (!ticker) return
  window.clearInterval(ticker)
  ticker = 0
}

function shownTools(item: HomeAgentMessage) {
  return (item.tools ?? []).filter((entry) => entry.arguments || entry.observation)
}

function shown(agent: HomeAgent): HomeAgent {
  return {
    ...agent,
    messages: (agent.messages ?? []).map((item) => ({
      ...item,
      text: item.text ?? '',
      reasoning: item.reasoning ?? '',
      tools: item.tools ?? [],
      files: item.files ?? [],
    })),
    sessions: agent.sessions ?? [],
  }
}

watch(running, (active) => tick(active), { immediate: true })

watch(
  () => projects.currentId,
  () => {
    closeStream()
    view.value = null
    files.value = []
    void load()
  },
  { immediate: true },
)

onUnmounted(() => {
  closeStream()
  tick(false)
})

function homeKey(item: HomeAgentMessage) {
  return item.id
}

async function load() {
  stickToBottom.value = true
  try {
    const agent = shown(await fetchHomeAgent())
    const loadedAt = Date.now()
    for (const item of agent.messages) {
      const origin = Date.parse(item.at)
      note(item, Number.isFinite(origin) ? origin : loadedAt)
    }
    view.value = agent
    error.value = ''
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '首页没有读到。'
  }
  if (running.value && view.value) follow(view.value.sessionId)
}

function closeStream() {
  stream?.close()
  stream = null
  live = null
  if (frame) {
    cancelAnimationFrame(frame)
    frame = 0
  }
}

function follow(sessionId: string) {
  closeStream()
  const source = new EventSource(`/api/home/agent/stream?session=${encodeURIComponent(sessionId)}`)
  stream = source
  const apply = (kind: string) => (event: MessageEvent) => {
    if (stream !== source || view.value?.sessionId !== sessionId) return
    const data = JSON.parse(event.data) as {
      text?: string
      reasoning?: string
      status?: string
      tools?: HomeAgentTool[]
      id?: string
      name?: string
      arguments?: string
      observation?: string
    }
    const message = ensureDraft()
    if (!message) return
    if (kind === 'snapshot' || kind === 'done') {
      message.text = data.text ?? ''
      message.reasoning = data.reasoning ?? ''
      message.status = data.status || (kind === 'done' ? 'done' : message.status)
      message.tools = data.tools ?? []
      const origin = Date.parse(message.at)
      note(message, Number.isFinite(origin) ? origin : Date.now())
    } else if (kind === 'text') {
      message.text += data.text || ''
    } else if (kind === 'thinking') {
      message.reasoning += data.text || ''
    } else if (kind === 'tool' && data.id) {
      const tools = message.tools.slice()
      const index = tools.findIndex((tool) => tool.id === data.id)
      const tool = {
        id: data.id,
        name: data.name || '工具',
        arguments: data.arguments || '',
        observation: data.observation || '',
        status: data.status || 'done',
      }
      if (index >= 0) tools[index] = tool
      else tools.push(tool)
      message.tools = tools
      if (tool.status === 'running') remember(tool.id, Date.now())
    }
    paint()
    if (kind === 'done') {
      source.close()
      if (stream === source) stream = null
      const current = view.value?.sessionId
      void fetchHomeAgent(current).then((agent) => {
        if (!view.value || view.value.sessionId !== agent.sessionId) return
        view.value = { ...view.value, sessions: agent.sessions ?? view.value.sessions }
      }).catch(() => {})
    }
  }
  source.addEventListener('snapshot', apply('snapshot'))
  source.addEventListener('text', apply('text'))
  source.addEventListener('thinking', apply('thinking'))
  source.addEventListener('tool', apply('tool'))
  source.addEventListener('done', apply('done'))
}

function ensureDraft() {
  if (live) return live
  const messages = view.value?.messages
  const last = messages && messages.length ? messages[messages.length - 1] : undefined
  if (!last || last.role !== 'assistant') return null
  live = { ...last, text: last.text, reasoning: last.reasoning, tools: last.tools.slice() }
  return live
}

function paint() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    const next = live
    live = null
    if (!next || !view.value) return
    const messages = view.value.messages.slice()
    const index = messages.findIndex((item) => item.id === next.id)
    if (index < 0) return
    messages[index] = next
    view.value = { ...view.value, messages }
  })
}

async function submit() {
  const text = draft.value.trim()
  if (!canSend.value) return
  const attached = files.value.slice()
  stickToBottom.value = true
  sending.value = true
  error.value = ''
  try {
    view.value = shown(await sendHomeAgent(text, attached))
    draft.value = ''
    files.value = []
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '没有发出去。'
  } finally {
    sending.value = false
  }
  if (running.value && view.value) follow(view.value.sessionId)
}

async function openSession(id: string) {
  if (!view.value || view.value.sessionId === id || locked.value) return
  stickToBottom.value = true
  closeStream()
  error.value = ''
  try {
    view.value = shown(await fetchHomeAgent(id))
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '会话没有打开。'
  }
}

async function reset() {
  if (locked.value || resetting.value) return
  stickToBottom.value = true
  closeStream()
  resetting.value = true
  error.value = ''
  try {
    view.value = shown(await resetHomeAgent())
    draft.value = ''
    files.value = []
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '会话没有换。'
  } finally {
    resetting.value = false
  }
}

async function pick(event: Event) {
  const input = event.target as HTMLInputElement
  const chosen = Array.from(input.files ?? [])
  input.value = ''
  if (!chosen.length || locked.value) return
  uploading.value = true
  error.value = ''
  try {
    for (const file of chosen) {
      files.value = [...files.value, await uploadHomeFile(file)]
    }
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '附件没有传上去。'
  } finally {
    uploading.value = false
  }
}

function removeFile(url: string) {
  files.value = files.value.filter((file) => file.url !== url)
}

function onCompositionStart() {
  composing = true
}

function onCompositionEnd() {
  window.setTimeout(() => {
    composing = false
  }, 0)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  if (event.isComposing || event.keyCode === 229 || composing) return
  event.preventDefault()
  void submit()
}

function onThreadScroll(event: Event) {
  const thread = event.target as HTMLElement
  stickToBottom.value = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 48
}

function clock(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function picture(name: string) {
  return /\.(png|jpe?g|gif|webp)$/i.test(name)
}

function html(text: string) {
  return renderMarkdown(text)
}
</script>

<template>
  <main class="home">
    <header class="head">
      <div class="title">
        <h1>{{ project }}</h1>
        <p v-if="view" class="who">{{ view.username }}</p>
      </div>
      <nav v-if="view && view.sessions.length" class="history" aria-label="历史会话">
        <button
          v-for="item in view.sessions"
          :key="item.id"
          type="button"
          :class="{ on: item.current }"
          :disabled="locked && !item.current"
          @click="openSession(item.id)"
        >
          {{ item.title }}
        </button>
      </nav>
      <button type="button" class="quiet" :disabled="locked || resetting || !view" @click="reset">新会话</button>
    </header>
    <section v-if="!view || view.preparing || !view.messages.length" class="thread" aria-label="对话">
      <p v-if="!view" class="empty">正在读取</p>
      <p v-else-if="view.preparing" class="empty">主分支还在准备。</p>
      <p v-else class="empty">还没有。</p>
    </section>
    <VirtualThread
      v-else
      class="thread"
      aria-label="对话"
      :items="view.messages"
      :item-key="homeKey"
      :gap="8"
      :pinned="stickToBottom"
      @scroll="onThreadScroll"
    >
      <template #default="{ item }">
      <article class="turn" :class="item.role">
        <div class="bubble">
          <header>
            <strong>{{ item.role === 'user' ? '你' : 'Helix' }}</strong>
            <time :datetime="item.at">{{ clock(item.at) }}</time>
          </header>
          <ul v-if="item.files?.length" class="files">
            <li v-for="file in item.files" :key="file.url">
              <a :href="file.url" target="_blank" rel="noopener noreferrer">{{ file.name }}</a>
              <img v-if="picture(file.name)" :src="file.url" :alt="file.name" />
            </li>
          </ul>
          <p v-if="item.role === 'user' && item.text" class="plain">{{ item.text }}</p>
          <details v-if="item.reasoning" class="fold" :open="item.status === 'running' && !item.text">
            <summary>思考</summary>
            <p>{{ item.reasoning }}</p>
          </details>
          <details v-for="tool in shownTools(item)" :key="tool.id" class="fold tool" :open="tool.status === 'running'">
            <summary>
              <span>{{ tool.name }}</span>
              <em>{{ tool.status === 'running' ? '进行中' : '完成' }}</em>
            </summary>
            <pre v-if="tool.arguments">{{ tool.arguments }}</pre>
            <pre v-if="tool.observation">{{ tool.observation }}</pre>
          </details>
          <p v-if="item.role === 'assistant' && item.status === 'running'" class="wait">正在执行{{ subject(item) ? ` ${subject(item)}` : '' }} <span class="elapsed">{{ elapsed(item) }}</span></p>
          <div v-if="item.role === 'assistant' && item.text" class="md" v-html="html(item.text)" />
          <p v-else-if="item.status === 'failed' && !item.text" class="wait">这轮没有完成。</p>
        </div>
      </article>
      </template>
    </VirtualThread>
    <form class="composer" @submit.prevent="submit">
      <ul v-if="files.length" class="pending">
        <li v-for="file in files" :key="file.url">
          <span>{{ file.name }}</span>
          <button type="button" @click="removeFile(file.url)">移除</button>
        </li>
      </ul>
      <label class="field">
        <span class="sr">问题</span>
        <textarea
          v-model="draft"
          rows="2"
          placeholder="写下要处理的问题"
          :disabled="locked"
          @compositionstart="onCompositionStart"
          @compositionend="onCompositionEnd"
          @keydown="onKeydown"
        />
      </label>
      <div class="bar">
        <button type="button" class="quiet" :disabled="locked" @click="fileEl?.click()">{{ uploading ? '正在上传' : '附件' }}</button>
        <input ref="fileEl" class="sr" type="file" multiple @change="pick" />
        <p class="hint">Enter 发送，Shift+Enter 换行</p>
        <button type="submit" :disabled="!canSend">{{ running ? `正在执行${runningLabel ? ` ${runningLabel}` : ''} ${runningElapsed}` : '发送' }}</button>
      </div>
      <p v-if="error" class="error">{{ error }}</p>
    </form>
  </main>
</template>

<style scoped>
.home {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  min-height: 0;
}

.head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.title {
  display: flex;
  flex: none;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.who {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  white-space: nowrap;
}

.history {
  display: flex;
  flex: 1;
  gap: 8px;
  min-width: 0;
  overflow: auto;
}

.history button {
  flex: none;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 10px;
  background: var(--surface);
  color: var(--muted);
  font-size: 13px;
}

.history button.on {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--ink);
}

.thread {
  flex: 1;
  min-height: 0;
  margin: 10px 0 8px;
  overflow: auto;
  padding: 8px 2px;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.empty {
  display: grid;
  height: 100%;
  margin: 0;
  place-items: center;
  color: var(--muted);
}

.turn {
  display: flex;
  width: 100%;
}

.turn + .turn {
  margin-top: 8px;
}

.turn.user {
  justify-content: flex-end;
}

.bubble {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 8px 12px;
  border-radius: 12px;
}

.turn.user .bubble {
  width: min(100%, 720px);
  background: var(--accent-soft);
}

.turn.assistant .bubble {
  border: 1px solid var(--line);
  background: var(--paper);
}

.bubble header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.bubble strong {
  font-size: 13px;
}

.bubble time,
.hint,
.wait {
  color: var(--muted);
  font-size: 12px;
}

.plain,
.fold p,
.wait {
  margin: 0;
  white-space: pre-wrap;
}

.elapsed {
  font-variant-numeric: tabular-nums;
}

.files,
.pending {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.files a,
.pending li,
.quiet,
.composer button {
  border-radius: 999px;
}

.files li {
  display: grid;
  gap: 6px;
}

.files a,
.pending li {
  padding: 4px 10px;
  background: var(--surface);
  color: var(--ink);
  font-size: 13px;
}

.files img {
  max-width: 240px;
  border-radius: 12px;
}

.fold {
  border-top: 1px solid var(--line);
  padding-top: 6px;
}

.fold summary {
  display: flex;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
  cursor: pointer;
}

.fold em {
  font-style: normal;
}

.fold pre {
  margin: 6px 0 0;
  overflow: auto;
  white-space: pre-wrap;
  color: var(--muted);
  font-size: 12px;
}

.md :deep(p),
.md :deep(ul),
.md :deep(ol),
.md :deep(pre),
.md :deep(blockquote) {
  margin: 0 0 8px;
}

.md :deep(p:last-child),
.md :deep(ul:last-child),
.md :deep(ol:last-child),
.md :deep(pre:last-child) {
  margin-bottom: 0;
}

.md :deep(code) {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--surface);
  font-size: 0.92em;
}

.md :deep(pre) {
  overflow: auto;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface);
}

.md :deep(pre code) {
  padding: 0;
  background: transparent;
}

.md :deep(a) {
  color: var(--accent);
}

.composer {
  display: grid;
  flex: none;
  gap: 6px;
}

.field {
  display: block;
}

.sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

textarea {
  width: 100%;
  resize: none;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 10px 12px;
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  line-height: 1.5;
}

textarea:disabled {
  color: var(--muted);
}

.bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hint {
  margin: 0;
}

.bar .hint {
  margin-right: auto;
}

.quiet,
.composer button {
  border: 0;
  padding: 6px 12px;
  background: var(--line);
  color: var(--ink);
}

@media (max-width: 720px) {
  .head {
    flex-wrap: wrap;
  }

  .history {
    order: 3;
    flex-basis: 100%;
  }
}

.composer button[type='submit'] {
  background: var(--ink);
  color: var(--surface);
}

.quiet:disabled,
.composer button:disabled {
  color: var(--muted);
}

.pending button {
  border: 0;
  margin-left: 8px;
  padding: 0;
  background: transparent;
  color: var(--muted);
}

.error {
  margin: 0;
  color: var(--danger);
  font-size: 13px;
}
</style>
