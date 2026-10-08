<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  createAgent,
  deleteAgent,
  fetchAgents,
  fetchSkills,
  updateAgent,
  type AgentRecord,
  type SkillEntry,
} from '@/api/client'
import { renderMarkdown } from '@/markdown'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const agents = ref<AgentRecord[]>([])
const catalog = ref<SkillEntry[]>([])
const selectedId = ref<number | 'new' | null>(null)
const name = ref('')
const body = ref('')
const skills = ref<string[]>([])
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const notice = ref('')
const saving = ref(false)
const saveError = ref('')
const saved = ref(false)
const confirmDelete = ref(false)
const preview = ref(false)
const pickerOpen = ref(false)
const pickerEl = ref<HTMLElement | null>(null)

const html = computed(() => renderMarkdown(body.value))
const available = computed(() => catalog.value.filter((skill) => !skills.value.includes(skill.path)))

watch(
  () => projects.currentId,
  () => {
    void load()
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onPointerDown)
})

function onPointerDown(event: PointerEvent) {
  if (!pickerEl.value?.contains(event.target as Node)) pickerOpen.value = false
}

async function load() {
  phase.value = 'loading'
  notice.value = ''
  selectedId.value = null
  agents.value = []
  catalog.value = []
  try {
    const [next, listed] = await Promise.all([fetchAgents(), fetchSkills().catch(() => [] as SkillEntry[])])
    agents.value = next
    catalog.value = listed
    phase.value = 'ready'
    if (next[0]) select(next[0].id)
  } catch (error: unknown) {
    phase.value = 'error'
    notice.value = error instanceof Error && error.name === 'IssueReadAuthError' ? '登录后才能定义 agent。' : 'agent 没有读到。'
  }
}

function select(id: number | 'new') {
  pickerOpen.value = false
  confirmDelete.value = false
  saved.value = false
  saveError.value = ''
  preview.value = false
  selectedId.value = id
  if (id === 'new') {
    name.value = ''
    body.value = ''
    skills.value = []
    return
  }
  const agent = agents.value.find((item) => item.id === id)
  name.value = agent?.name ?? ''
  body.value = agent?.body ?? ''
  skills.value = [...(agent?.skills ?? [])]
}

function addSkill(path: string) {
  if (!skills.value.includes(path)) skills.value = [...skills.value, path]
  pickerOpen.value = false
  saved.value = false
}

function removeSkill(path: string) {
  skills.value = skills.value.filter((item) => item !== path)
  saved.value = false
}

function skillLabel(path: string): string {
  const prefix = 'skills/'
  const suffix = '/SKILL.md'
  if (path.startsWith(prefix) && path.endsWith(suffix)) {
    return path.slice(prefix.length, -suffix.length)
  }
  return path
}

async function save() {
  if (saving.value || selectedId.value == null) return
  saving.value = true
  saveError.value = ''
  saved.value = false
  const draft = { name: name.value.trim(), body: body.value, skills: skills.value }
  try {
    const savedAgent = selectedId.value === 'new' ? await createAgent(draft) : await updateAgent(selectedId.value, draft)
    agents.value = [...agents.value.filter((item) => item.id !== savedAgent.id), savedAgent].sort((a, b) =>
      a.name.localeCompare(b.name, 'zh'),
    )
    select(savedAgent.id)
    saved.value = true
  } catch (error: unknown) {
    saveError.value = error instanceof Error && error.name !== 'IssueReadAuthError' ? error.message : 'agent 没有保存。'
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (selectedId.value == null || selectedId.value === 'new') {
    selectedId.value = agents.value[0]?.id ?? null
    if (selectedId.value != null) select(selectedId.value)
    return
  }
  if (!confirmDelete.value) {
    confirmDelete.value = true
    return
  }
  const id = selectedId.value
  try {
    await deleteAgent(id)
    agents.value = agents.value.filter((item) => item.id !== id)
    const next = agents.value[0]
    if (next) select(next.id)
    else selectedId.value = null
  } catch {
    saveError.value = 'agent 没有删除。'
    confirmDelete.value = false
  }
}
</script>

<template>
  <main class="agent-page">
    <header class="head">
      <h1>Agent</h1>
      <p>每个 agent 有一份 agent.md，并引用指定的 skill。</p>
    </header>

    <p v-if="phase === 'loading'" class="note">正在读取 agent。</p>
    <p v-else-if="phase === 'error'" class="note">{{ notice }}</p>
    <div v-else class="board">
      <nav class="agent-list" aria-label="agents">
        <button type="button" class="create" :class="{ on: selectedId === 'new' }" @click="select('new')">新建</button>
        <button
          v-for="agent in agents"
          :key="agent.id"
          type="button"
          :class="{ on: agent.id === selectedId }"
          :title="agent.name"
          @click="select(agent.id)"
        >
          {{ agent.name }}
        </button>
      </nav>

      <section v-if="selectedId != null" class="editor">
        <label>
          名称
          <input v-model="name" type="text" autocomplete="off" placeholder="给这个 agent 起个名字" @input="saved = false" />
        </label>
        <div ref="pickerEl" class="refs">
          <div class="refs-head">
            <span>引用的 skill</span>
            <button type="button" class="add" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">添加 skill</button>
          </div>
          <ul v-if="pickerOpen" class="picker">
            <li v-if="available.length === 0">skills 目录里没有可添加的规范。</li>
            <li v-for="skill in available" :key="skill.path">
              <button type="button" @click="addSkill(skill.path)">{{ skill.name }}</button>
            </li>
          </ul>
          <p v-if="skills.length === 0" class="empty">还没有引用 skill。</p>
          <div v-else class="chips">
            <span v-for="path in skills" :key="path" class="chip">
              {{ skillLabel(path) }}
              <button type="button" :aria-label="`移除 ${skillLabel(path)}`" @click="removeSkill(path)">×</button>
            </span>
          </div>
        </div>
        <div class="file-head">
          <span>agent.md</span>
          <div class="modes">
            <button type="button" :class="{ on: !preview }" @click="preview = false">编写</button>
            <button type="button" :class="{ on: preview }" @click="preview = true">预览</button>
          </div>
        </div>
        <textarea
          v-if="!preview"
          v-model="body"
          spellcheck="false"
          placeholder="写下这个 agent 怎么工作、什么时候用、不要做什么。"
          @input="saved = false"
        />
        <article v-else class="preview md" v-html="html" />
        <div class="actions">
          <button type="button" class="save" :disabled="saving || !name.trim()" @click="save">
            {{ saving ? '正在保存' : '保存' }}
          </button>
          <button type="button" class="remove" @click="remove">{{ confirmDelete ? '确认删除' : '删除' }}</button>
          <p v-if="saveError" class="note">{{ saveError }}</p>
          <p v-else-if="saved" class="note ok">已保存。</p>
        </div>
      </section>
      <section v-else class="editor wait">
        <p class="note">还没有 agent。</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.head {
  flex: none;
}

.head h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.head p,
.note {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.note.ok {
  color: var(--accent);
}

.board {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 16px;
  flex: 1;
  min-width: 0;
  min-height: 0;
  margin-top: 18px;
}

.agent-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  min-height: 0;
  margin: 0;
  overflow: auto;
}

.agent-list button,
.modes button,
.add,
.remove {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}

.agent-list button {
  flex: none;
  height: 36px;
  padding: 0 12px;
  overflow: hidden;
  border-radius: 12px;
  font-size: 14px;
  line-height: 36px;
  text-align: left;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.agent-list button.on,
.modes button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  padding: 16px 18px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.editor.wait {
  justify-content: center;
}

label {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 6px;
  color: var(--muted);
  font-size: 13px;
}

input,
textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--paper);
  color: var(--ink);
  font: inherit;
}

input {
  height: 40px;
  padding: 0 12px;
  font-size: 15px;
}

.refs {
  position: relative;
  flex: none;
}

.refs-head,
.file-head,
.actions,
.modes {
  display: flex;
  align-items: center;
  gap: 8px;
}

.refs-head,
.file-head {
  justify-content: space-between;
  color: var(--muted);
  font-size: 13px;
}

.add,
.modes button,
.remove {
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 13px;
}

.picker {
  position: absolute;
  z-index: 5;
  top: 32px;
  right: 0;
  width: min(280px, 100%);
  max-height: 240px;
  margin: 0;
  padding: 6px;
  overflow: auto;
  list-style: none;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
}

.picker li {
  color: var(--muted);
  font-size: 13px;
}

.picker button {
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.picker button:hover {
  background: var(--accent-soft);
}

.empty {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px 0 10px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 13px;
}

.chip button {
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--accent);
  font-size: 14px;
  line-height: 18px;
  cursor: pointer;
}

.file-head {
  flex: none;
}

textarea,
.preview {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

textarea {
  padding: 12px 14px;
  resize: none;
  font-size: 14px;
  line-height: 1.6;
}

.preview {
  padding: 4px 2px 8px;
}

.actions {
  flex: none;
}

.save {
  height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.save:disabled {
  cursor: progress;
  opacity: 0.6;
}

.remove {
  color: var(--danger);
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  margin: 1em 0 0.4em;
  line-height: 1.3;
  font-weight: 650;
}

.md :deep(p),
.md :deep(ul),
.md :deep(ol),
.md :deep(pre) {
  margin: 0.6em 0;
}

.md :deep(pre) {
  overflow: auto;
  padding: 12px;
  border-radius: 12px;
  background: var(--paper);
}

@media (max-width: 800px) {
  .head h1 {
    font-size: 22px;
  }

  .board {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
  }

  .agent-list {
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
  }
}
</style>
