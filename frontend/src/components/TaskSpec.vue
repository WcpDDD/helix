<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchTaskSpec, type TaskSpecCheckout } from '@/api/client'
import type { Task } from '@/data/tasks'
import { renderMarkdown } from '@/markdown'

const props = defineProps<{
  task: Task
  workspace?: string
}>()

const cloneSteps = ['对齐引用', '浅克隆指定分支', '读取 openspec']
const phase = ref<'clone' | 'ready' | 'empty' | 'error'>('clone')
const notice = ref('')
const cloneAt = ref(1)
const checkout = ref<TaskSpecCheckout>({ branch: '', commit: '', files: [] })
const selectedPath = ref('')
const docEl = ref<HTMLElement | null>(null)

const workspaceName = computed(() => props.workspace || '')
const current = computed(() => checkout.value.files.find((file) => file.path === selectedPath.value) ?? checkout.value.files[0])
const html = computed(() => (current.value ? renderMarkdown(current.value.markdown) : ''))

watch(
  () => props.task.code,
  (code) => {
    void load(code)
  },
  { immediate: true },
)

async function load(code: string) {
  phase.value = 'clone'
  notice.value = ''
  cloneAt.value = 1
  checkout.value = { branch: '', commit: '', files: [] }
  selectedPath.value = ''
  try {
    const next = await fetchTaskSpec(code)
    if (code !== props.task.code) return
    checkout.value = next
    selectedPath.value = next.files[0]?.path ?? ''
    phase.value = next.files.length > 0 ? 'ready' : 'empty'
    if (next.files.length === 0) notice.value = '这张任务还没有规格。'
  } catch (error: unknown) {
    if (code !== props.task.code) return
    phase.value = 'error'
    notice.value = error instanceof Error && error.name === 'IssueReadAuthError' ? '登录后才能读取规格。' : '规格没有读到。'
  }
}

function fileLabel(path: string): string {
  const parts = path.split('/')
  return parts.slice(-2).join('/')
}

function open(path: string) {
  selectedPath.value = path
  docEl.value?.scrollTo({ top: 0 })
}
</script>

<template>
  <div class="spec">
    <div v-if="phase === 'clone'" class="clone" role="status" aria-live="polite">
      <p class="clone-k">正在浅克隆</p>
      <p v-if="workspaceName" class="clone-repo">{{ workspaceName }} · 只取该分支最新提交</p>
      <ol class="clone-steps">
        <li v-for="(label, index) in cloneSteps" :key="label" :class="{ on: index === cloneAt, done: index < cloneAt }">
          <span class="mark" aria-hidden="true" />
          {{ label }}
        </li>
      </ol>
    </div>

    <p v-else-if="phase === 'empty' || phase === 'error'" class="wait">{{ notice }}</p>

    <template v-else>
      <div class="meta">
        <span class="branch">{{ checkout.branch }}</span>
        <span>{{ checkout.commit }}</span>
        <span>{{ workspaceName }}</span>
        <span>{{ checkout.files.length }} 个文件</span>
      </div>

      <div class="board">
        <nav class="index" aria-label="openspec 文件">
          <button
            v-for="file in checkout.files"
            :key="file.path"
            type="button"
            :class="{ on: file.path === current?.path }"
            :title="file.path"
            @click="open(file.path)"
          >
            {{ fileLabel(file.path) }}
          </button>
        </nav>

        <article v-if="current" ref="docEl" class="doc">
          <p class="path">{{ current.path }}</p>
          <div class="md" v-html="html" />
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.spec {
  container-type: inline-size;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.clone {
  padding: 8px 0 12px;
}

.wait {
  margin: 12px 0 0;
  color: var(--muted);
}

.clone-k {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.clone-branch {
  margin: 4px 0 0;
  font-size: 22px;
  line-height: 1.3;
  font-weight: 650;
  letter-spacing: -0.02em;
  overflow-wrap: anywhere;
}

.clone-repo {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.clone-steps {
  display: grid;
  gap: 8px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.clone-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  color: var(--muted);
  font-size: 13px;
}

.clone-steps li.on {
  border-color: var(--accent);
  color: var(--ink);
  background: var(--accent-soft);
}

.clone-steps li.done {
  color: var(--ink);
}

.mark {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--line);
  flex: none;
}

li.on .mark,
li.done .mark {
  background: var(--accent);
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: var(--muted);
  font-size: 12px;
}

.branch {
  color: var(--ink);
  font-weight: 650;
}

.board {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  flex: 1;
}

.index {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  min-height: 40px;
  margin: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
}

.index::-webkit-scrollbar {
  display: none;
}

.index button {
  flex: none;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}

.index button.on {
  border-color: var(--accent);
  background: #f3faf7;
}

.doc {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  scrollbar-width: none;
}

.doc::-webkit-scrollbar {
  display: none;
}

.path {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.md {
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4) {
  margin: 18px 0 8px;
  line-height: 1.35;
  font-weight: 650;
}

.md :deep(h2) {
  font-size: 17px;
}

.md :deep(h3) {
  font-size: 15px;
}

.md :deep(h4) {
  font-size: 14px;
}

.md :deep(h2:first-child),
.md :deep(h3:first-child) {
  margin-top: 0;
}

.md :deep(p),
.md :deep(ul),
.md :deep(ol),
.md :deep(pre),
.md :deep(blockquote) {
  margin: 8px 0;
}

.md :deep(ul),
.md :deep(ol) {
  padding-left: 1.3em;
}

.md :deep(li + li) {
  margin-top: 4px;
}

.md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em;
}

.md :deep(a) {
  color: var(--accent);
}

.md :deep(input[type='checkbox']) {
  margin-right: 6px;
}

@container (min-width: 680px) {
  .clone-branch {
    font-size: 28px;
  }

  .clone-steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .board {
    display: grid;
    grid-template-columns: minmax(180px, 280px) minmax(0, 1fr);
    gap: 22px;
    align-items: start;
    justify-items: stretch;
  }

  .index {
    flex-direction: column;
    align-items: stretch;
    justify-self: stretch;
    width: 100%;
    min-height: 0;
    overflow: visible;
  }

  .index button {
    border-radius: 10px;
    text-align: left;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .md :deep(h2) {
    font-size: 18px;
  }
}
</style>
