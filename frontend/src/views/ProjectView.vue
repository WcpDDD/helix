<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { fetchGitlabProjects, saveProject, type GitlabProject } from '@/api/client'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const drafting = ref(false)
const name = ref('')
const path = ref('')
const query = ref('')
const choices = ref<GitlabProject[]>([])
const ready = ref(false)
const loadError = ref('')
const listError = ref('')
const saveError = ref('')
const saved = ref(false)
const saving = ref(false)
const listing = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | undefined

watch(
  () => projects.current,
  (current) => {
    if (drafting.value || !current) return
    name.value = current.name
    path.value = current.workspace.path
    saved.value = false
    saveError.value = ''
  },
  { immediate: true },
)

onMounted(async () => {
  ready.value = projects.ready
  if (!projects.ready) {
    try {
      await projects.load()
    } catch {
      loadError.value = '项目没有读到。'
    }
  }
  ready.value = true
  if (!projects.current && !loadError.value && projects.projects.length === 0) {
    loadError.value = '项目没有读到。'
  }
  await loadChoices('')
})

onUnmounted(() => {
  if (searchTimer) clearTimeout(searchTimer)
})

function scheduleSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    loadChoices(query.value)
  }, 250)
}

async function loadChoices(search: string) {
  listing.value = true
  listError.value = ''
  try {
    choices.value = await fetchGitlabProjects(search)
  } catch {
    choices.value = []
    listError.value = 'GitLab 工程没有读到。'
  } finally {
    listing.value = false
  }
}

function pick(choice: GitlabProject) {
  path.value = choice.path
  if (!name.value.trim()) name.value = choice.name
  saved.value = false
  saveError.value = ''
}

function startDraft() {
  drafting.value = true
  name.value = ''
  path.value = ''
  saved.value = false
  saveError.value = ''
}

async function openProject(id: number) {
  drafting.value = false
  saveError.value = ''
  saved.value = false
  if (id === projects.currentId) {
    const current = projects.current
    if (current) {
      name.value = current.name
      path.value = current.workspace.path
    }
    return
  }
  try {
    await projects.use(id)
  } catch {
    saveError.value = '项目没有切换。'
  }
}

async function save() {
  if (saving.value || !path.value.trim()) return
  saving.value = true
  saveError.value = ''
  saved.value = false
  try {
    if (drafting.value || !projects.current) {
      const created = await projects.add(name.value.trim(), path.value.trim())
      drafting.value = false
      name.value = created.name
      path.value = created.workspace.path
    } else {
      const savedProject = await saveProject(projects.current.id, name.value.trim(), path.value.trim())
      projects.replace(savedProject)
      name.value = savedProject.name
      path.value = savedProject.workspace.path
    }
    saved.value = true
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '工程没有保存。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="project">
    <header class="head">
      <h1>管理项目</h1>
      <p>指定一个 GitLab 工程作为这个项目的 workspace。issue、规格和契约都从这里读。</p>
    </header>

    <p v-if="!ready" class="note">正在读取项目。</p>
    <p v-else-if="loadError" class="note">{{ loadError }}</p>
    <form v-else class="form" @submit.prevent="save">
      <div class="roster">
        <button
          v-for="item in projects.projects"
          :key="item.id"
          type="button"
          :class="{ on: !drafting && item.id === projects.currentId }"
          @click="openProject(item.id)"
        >
          {{ item.name }}
        </button>
        <button type="button" :class="{ on: drafting }" @click="startDraft">新建</button>
      </div>
      <label>
        名称
        <input v-model="name" type="text" autocomplete="off" />
      </label>
      <label>
        GitLab 工程
        <input v-model="path" type="text" autocomplete="off" spellcheck="false" placeholder="群组/工程" />
      </label>
      <p v-if="!drafting && projects.current" class="current">
        当前 workspace
        <a
          :href="`https://${projects.current.workspace.host}/${projects.current.workspace.path}`"
          target="_blank"
          rel="noreferrer"
        >
          {{ projects.current.workspace.host }}/{{ projects.current.workspace.path }}
        </a>
      </p>
      <label>
        从我参与的工程里选
        <input v-model="query" type="search" placeholder="搜索工程" @input="scheduleSearch" />
      </label>
      <p v-if="listing" class="note">正在读取 GitLab 工程。</p>
      <p v-else-if="listError" class="note">{{ listError }}</p>
      <ul v-else class="choices">
        <li v-for="choice in choices" :key="choice.path">
          <button type="button" :class="{ on: choice.path === path }" @click="pick(choice)">
            <span>{{ choice.path }}</span>
            <small>{{ choice.name }}</small>
          </button>
        </li>
      </ul>
      <div class="actions">
        <button type="submit" class="save" :disabled="saving || !path.trim()">
          {{ saving ? '正在保存' : drafting ? '建立项目' : '保存工程' }}
        </button>
        <p v-if="saveError" class="note">{{ saveError }}</p>
        <p v-else-if="saved" class="note ok">工程已保存。</p>
      </div>
    </form>
  </main>
</template>

<style scoped>
.head h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.head p,
.note,
.current {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 640px;
  margin-top: 22px;
}

.roster {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.roster button {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}

.roster button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--muted);
}

input {
  width: 100%;
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink);
}

.current a {
  color: var(--accent);
}

.choices {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.choices button {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 4px 12px;
  width: 100%;
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.choices button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.choices small {
  color: var(--muted);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.save {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--surface);
  color: var(--accent);
  cursor: pointer;
}

.save:disabled {
  cursor: progress;
  opacity: 0.6;
}

.note.ok {
  color: var(--accent);
}

@media (max-width: 800px) {
  .head h1 {
    font-size: 22px;
  }
}
</style>
