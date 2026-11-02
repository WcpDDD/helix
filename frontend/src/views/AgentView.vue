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
import { agentTagGroups } from '@/data/agentTags'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const agents = ref<AgentRecord[]>([])
const catalog = ref<SkillEntry[]>([])
const selectedId = ref<string | 'new' | null>(null)
const name = ref('')
const body = ref('')
const skills = ref<string[]>([])
const tag = ref<string | null>(null)
const scope = ref<'global' | 'task'>('task')
const access = ref<string[]>(['reporter', 'developer', 'maintainer'])

const accessRoles = ['reporter', 'developer', 'maintainer']

const layers = [
  { id: 'global' as const, label: '全局智能体' },
  { id: 'task' as const, label: '任务智能体' },
]
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
const grouped = computed(() =>
  layers.map((layer) => ({
    ...layer,
    agents: agents.value.filter((agent) => (agent.scope || (agent.tag ? 'task' : 'global')) === layer.id),
  })),
)

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
    if (next[0]) select(next[0].name)
  } catch (error: unknown) {
    phase.value = 'error'
    notice.value = error instanceof Error && error.name === 'IssueReadAuthError' ? '登录后才能定义 agent。' : 'agent 没有读到。'
  }
}

function select(id: string | 'new') {
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
    tag.value = null
    scope.value = 'task'
    access.value = [...accessRoles]
    return
  }
  const agent = agents.value.find((item) => item.name === id)
  name.value = agent?.name ?? ''
  body.value = agent?.body ?? ''
  skills.value = [...(agent?.skills ?? [])]
  scope.value = agent?.scope === 'global' ? 'global' : 'task'
  tag.value = scope.value === 'global' ? null : (agent?.tag ?? null)
  access.value = agent?.access?.length ? [...agent.access] : [...accessRoles]
}

function tagHolder(role: string): string | null {
  const owner = agents.value.find((agent) => agent.tag === role && agent.name !== selectedId.value)
  return owner?.name ?? null
}

function chooseScope(next: 'global' | 'task') {
  scope.value = next
  if (next === 'global') tag.value = null
  saved.value = false
}

function toggleAccess(role: string) {
  const next = access.value.includes(role) ? access.value.filter((item) => item !== role) : [...access.value, role]
  if (next.length === 0) return
  access.value = next
  saved.value = false
}

function chooseTag(next: string) {
  if (tagHolder(next)) return
  tag.value = tag.value === next ? null : next
  saved.value = false
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
  const draft = {
    name: name.value.trim(),
    body: body.value,
    skills: skills.value,
    tag: scope.value === 'global' ? null : tag.value,
    scope: scope.value,
    access: access.value,
  }
  try {
    const savedAgent = selectedId.value === 'new' ? await createAgent(draft) : await updateAgent(selectedId.value, draft)
    const previous = selectedId.value
    agents.value = [...agents.value.filter((item) => item.name !== previous && item.name !== savedAgent.name), savedAgent].sort((a, b) =>
      a.name.localeCompare(b.name, 'zh'),
    )
    select(savedAgent.name)
    saved.value = true
  } catch (error: unknown) {
    saveError.value = error instanceof Error && error.name !== 'IssueReadAuthError' ? error.message : 'agent 没有保存。'
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (selectedId.value == null || selectedId.value === 'new') {
    selectedId.value = agents.value[0]?.name ?? null
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
    agents.value = agents.value.filter((item) => item.name !== id)
    const next = agents.value[0]
    if (next) select(next.name)
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
      <p>每个 agent 存在工作目录的 agents/&lt;名字&gt;/agent.md。全局智能体用在首页，任务智能体用在任务里，并按 GitLab 权限决定谁能看见。</p>
    </header>

    <p v-if="phase === 'loading'" class="note">正在读取 agent。</p>
    <p v-else-if="phase === 'error'" class="note">{{ notice }}</p>
    <div v-else class="board">
      <nav class="agent-list" aria-label="agents">
        <button type="button" class="create" :class="{ on: selectedId === 'new' }" @click="select('new')">新建</button>
        <section v-for="layer in grouped" :key="layer.id" class="layer">
          <h2>{{ layer.label }}</h2>
          <p v-if="layer.agents.length === 0" class="layer-empty">还没有。</p>
          <button
            v-for="agent in layer.agents"
            :key="agent.name"
            type="button"
            :class="{ on: agent.name === selectedId }"
            :title="agent.name"
            @click="select(agent.name)"
          >
            <span class="agent-name">{{ agent.name }}</span>
            <span v-if="layer.id === 'task' && agent.tag" class="agent-tags">{{ agent.tag }}</span>
          </button>
        </section>
      </nav>

      <section v-if="selectedId != null" class="editor">
        <label>
          名称
          <input v-model="name" type="text" autocomplete="off" placeholder="给这个 agent 起个名字" @input="saved = false" />
        </label>
        <div class="tags">
          <span>层面</span>
          <div class="tag-choices">
            <button type="button" :class="{ on: scope === 'global' }" @click="chooseScope('global')">全局</button>
            <button type="button" :class="{ on: scope === 'task' }" @click="chooseScope('task')">任务</button>
          </div>
          <span>谁可以用</span>
          <div class="tag-choices">
            <button
              v-for="role in accessRoles"
              :key="role"
              type="button"
              :class="{ on: access.includes(role) }"
              @click="toggleAccess(role)"
            >
              {{ role }}
            </button>
          </div>
        </div>
        <div v-if="scope === 'task'" class="tags">
          <span>角色</span>
          <div v-for="group in agentTagGroups" :key="group.id" class="tag-group" role="group" :aria-label="group.label">
            <span class="tag-layer">{{ group.label }}</span>
            <div class="tag-choices">
              <button
                v-for="item in group.tags"
                :key="item.id"
                type="button"
                :class="{ on: tag === item.id }"
                :aria-pressed="tag === item.id"
                :disabled="tag !== item.id && tagHolder(item.id) != null"
                :title="tagHolder(item.id) ? `${item.id} 已经是 ${tagHolder(item.id)}` : item.hint"
                @click="chooseTag(item.id)"
              >
                {{ item.id }}
              </button>
            </div>
          </div>
        </div>
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

.layer {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.layer h2,
.layer-empty {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
}

.layer-empty {
  font-weight: 400;
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
  display: flex;
  flex: none;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  overflow: hidden;
  border-radius: 12px;
  font-size: 14px;
  line-height: 36px;
  text-align: left;
}

.agent-name,
.agent-tags {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.agent-name {
  flex: 1;
}

.agent-tags {
  flex: none;
}

.agent-tags {
  color: var(--muted);
  font-size: 12px;
}

.agent-list button.on .agent-tags {
  color: var(--accent);
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

.tags {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  color: var(--muted);
  font-size: 13px;
}

.tag-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tag-layer {
  font-size: 12px;
}

.tag-choices {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-choices button {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.tag-choices button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.tag-choices button:disabled {
  opacity: 0.45;
  cursor: default;
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
