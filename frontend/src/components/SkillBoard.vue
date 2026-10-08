<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchSkillFile, fetchSkills, type SkillEntry, type SkillFile } from '@/api/client'
import { renderMarkdown } from '@/markdown'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const skills = ref<SkillEntry[]>([])
const selected = ref('')
const filePath = ref('')
const file = ref<SkillFile | null>(null)
const phase = ref<'loading' | 'ready' | 'empty' | 'error'>('loading')
const filePhase = ref<'loading' | 'ready' | 'error'>('loading')
const notice = ref('')

const current = computed(() => skills.value.find((skill) => skill.path === selected.value) ?? null)
const html = computed(() => (file.value ? renderMarkdown(skillBody(file.value.markdown)) : ''))

watch(
  () => projects.currentId,
  () => {
    void load()
  },
  { immediate: true },
)

async function load() {
  phase.value = 'loading'
  notice.value = ''
  skills.value = []
  selected.value = ''
  file.value = null
  try {
    const next = await fetchSkills()
    skills.value = next
    if (next.length === 0) {
      phase.value = 'empty'
      notice.value = 'skills 目录里还没有规范。'
      return
    }
    phase.value = 'ready'
    await openSkill(next[0])
  } catch (error: unknown) {
    phase.value = 'error'
    notice.value = error instanceof Error && error.name === 'IssueReadAuthError' ? '登录后才能读取 skills。' : 'skills 没有读到。'
  }
}

async function openSkill(skill: SkillEntry) {
  selected.value = skill.path
  const entry = skill.files.find((item) => item.endsWith('/SKILL.md')) ?? skill.files[0]
  if (entry) await openFile(entry)
}

async function openFile(path: string) {
  filePath.value = path
  filePhase.value = 'loading'
  file.value = null
  try {
    const next = await fetchSkillFile(path)
    if (filePath.value !== path) return
    file.value = next
    filePhase.value = 'ready'
  } catch {
    if (filePath.value !== path) return
    filePhase.value = 'error'
  }
}

function fileLabel(skill: SkillEntry, path: string): string {
  const prefix = skill.path.slice(0, skill.path.lastIndexOf('/') + 1)
  return path.startsWith(prefix) ? path.slice(prefix.length) : path
}

function skillBody(markdown: string): string {
  const text = markdown.replace(/^\uFEFF/, '')
  if (!text.startsWith('---')) return text
  const end = text.indexOf('\n---', 3)
  if (end < 0) return text
  return text.slice(end + 4).replace(/^\s+/, '')
}
</script>

<template>
  <p v-if="phase === 'loading'" class="note">正在读取 skills。</p>
  <p v-else-if="phase !== 'ready'" class="note">{{ notice }}</p>
  <div v-else class="board">
    <nav class="skill-list" aria-label="skills">
      <button
        v-for="skill in skills"
        :key="skill.path"
        type="button"
        :class="{ on: skill.path === selected }"
        :title="skill.name"
        @click="openSkill(skill)"
      >
        {{ skill.name }}
      </button>
    </nav>
    <section v-if="current" class="reader">
      <div class="files">
        <button
          v-for="path in current.files"
          :key="path"
          type="button"
          :class="{ on: path === filePath }"
          @click="openFile(path)"
        >
          {{ fileLabel(current, path) }}
        </button>
      </div>
      <p v-if="filePhase === 'loading'" class="note">正在读取这份规范。</p>
      <p v-else-if="filePhase === 'error'" class="note">这份规范没有读到。</p>
      <article v-else-if="file" class="doc">
        <p class="path">{{ file.path }}</p>
        <div class="md" v-html="html" />
      </article>
    </section>
  </div>
</template>

<style scoped>
.note {
  margin: 18px 0 0;
  color: var(--muted);
  font-size: 14px;
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

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  min-height: 0;
  margin: 0;
  overflow: auto;
}

.skill-list button,
.files button {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.skill-list button {
  flex: none;
  height: 36px;
  padding: 0 12px;
  overflow: hidden;
  font-size: 14px;
  line-height: 36px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.skill-list button.on,
.files button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.reader {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 16px 18px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.files {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
}

.files button {
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 26px;
  white-space: nowrap;
}

.path {
  margin: 14px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.doc {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  margin: 1.1em 0 0.4em;
  line-height: 1.3;
  font-weight: 650;
}

.md :deep(h1) {
  font-size: 22px;
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

.md :deep(code) {
  font-size: 0.92em;
}

.md :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.md :deep(th),
.md :deep(td) {
  padding: 6px 8px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}

@media (max-width: 800px) {
  .board {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
  }

  .skill-list {
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
  }
}
</style>
