<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { saveOwners } from '@/api/client'
import { directory, loadDirectory, type DirectoryPerson } from '@/data/people'
import { editingOwnerId, nextOwnerEditorId } from '@/data/statusDraft'

const props = withDefaults(
  defineProps<{
    code: string
    owners: string[]
    labeled?: boolean
  }>(),
  { labeled: false },
)

const emit = defineEmits<{ saved: [owners: string[]] }>()

const editorId = nextOwnerEditorId()
const root = ref<HTMLElement | null>(null)
const picked = ref<string[]>([])
const query = ref('')
const saving = ref(false)
const notice = ref('')
const panelBox = ref({ top: 0, left: 0, maxHeight: 420, above: false })

const open = computed(() => editingOwnerId.value === editorId)
const panelStyle = computed(() => {
  const box = panelBox.value
  const edge = box.above
    ? { bottom: `${window.innerHeight - box.top}px` }
    : { top: `${box.top}px` }
  return { left: `${box.left}px`, maxHeight: `${box.maxHeight}px`, ...edge }
})
const filteredPeople = computed(() => {
  const text = query.value.trim().toLowerCase()
  if (!text) return directory.value
  return directory.value.filter((person) => person.name.toLowerCase().includes(text) || person.login.toLowerCase().includes(text))
})

watch(directory, () => {
  if (!open.value) return
  picked.value = [...new Set(picked.value.map(personId))]
})

function personId(owner: string): string {
  return directory.value.find((person) => person.login === owner || person.name === owner)?.login ?? owner
}

function personName(id: string): string {
  return directory.value.find((person) => person.login === id)?.name ?? id
}

function layout() {
  const el = root.value
  if (!el || !open.value) return
  const rect = el.getBoundingClientRect()
  const width = 320
  const margin = 12
  const gap = 6
  let left = rect.left
  if (left + width > window.innerWidth - margin) left = Math.max(margin, window.innerWidth - width - margin)
  const spaceBelow = window.innerHeight - rect.bottom - margin - gap
  const spaceAbove = rect.top - margin - gap
  const above = spaceBelow < 280 && spaceAbove > spaceBelow
  panelBox.value = {
    top: above ? rect.top - gap : rect.bottom + gap,
    left,
    maxHeight: Math.max(220, Math.min(480, above ? spaceAbove : spaceBelow)),
    above,
  }
}

function onMove() {
  if (open.value) layout()
}

function start() {
  editingOwnerId.value = editorId
  picked.value = [...new Set(props.owners.map(personId))]
  query.value = ''
  notice.value = ''
  void loadDirectory()
  void nextTick(layout)
}

watch(open, (value) => {
  if (value) void nextTick(layout)
})

onMounted(() => {
  window.addEventListener('resize', onMove)
  window.addEventListener('scroll', onMove, true)
})

onUnmounted(() => {
  window.removeEventListener('resize', onMove)
  window.removeEventListener('scroll', onMove, true)
})

function cancel() {
  if (editingOwnerId.value === editorId) editingOwnerId.value = null
  query.value = ''
  notice.value = ''
}

function toggleOwner(person: DirectoryPerson) {
  picked.value = picked.value.includes(person.login)
    ? picked.value.filter((item) => item !== person.login)
    : [...picked.value, person.login]
}

async function save() {
  if (saving.value) return
  saving.value = true
  notice.value = ''
  try {
    const saved = await saveOwners(props.code, picked.value.map(personName))
    emit('saved', saved)
    cancel()
  } catch (error) {
    notice.value = error instanceof Error ? error.message : '负责人没有保存。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div ref="root" class="owner-edit" :class="{ open }" @click.stop @keydown.enter.stop>
    <div class="view">
      <span v-if="labeled" class="k">负责人</span>
      <span v-if="owners.length === 0" class="empty">还没有</span>
      <span v-else class="group">
        <span v-for="name in owners" :key="name" class="who">
          <span class="avatar" aria-hidden="true">{{ name.slice(0, 1) }}</span>
          {{ name }}
        </span>
      </span>
      <button type="button" class="edit" @click="open ? cancel() : start()">{{ open ? '收起' : '编辑' }}</button>
    </div>
    <Teleport to="body">
    <form v-if="open" class="panel" :style="panelStyle" @click.stop @keydown.enter.stop @submit.prevent="save">
      <span class="k">负责人 <span v-if="picked.length > 0">已选 {{ picked.length }} 人</span></span>
      <div v-if="picked.length > 0" class="picked">
        <button v-for="id in picked" :key="id" type="button" class="chip" @click="picked = picked.filter((item) => item !== id)">
          {{ personName(id) }}
          <span aria-hidden="true">×</span>
        </button>
      </div>
      <input v-model="query" type="search" placeholder="搜索姓名或登录名" />
      <div class="member-list">
        <button
          v-for="person in filteredPeople"
          :key="person.login"
          type="button"
          class="member"
          :class="{ on: picked.includes(person.login) }"
          @click="toggleOwner(person)"
        >
          <span class="avatar" aria-hidden="true">{{ person.name.slice(0, 1) }}</span>
          <span>{{ person.name }}</span>
          <span v-if="person.login !== person.name" class="login">{{ person.login }}</span>
        </button>
        <p v-if="filteredPeople.length === 0" class="empty">没有匹配的人</p>
      </div>
      <p class="hint">不选任何人，保存后就是还没有负责人。</p>
      <p v-if="notice" class="notice">{{ notice }}</p>
      <div class="actions">
        <button type="button" class="quiet" :disabled="saving" @click="cancel">取消</button>
        <button type="submit" class="go" :disabled="saving">保存</button>
      </div>
    </form>
    </Teleport>
  </div>
</template>

<style scoped>
.owner-edit {
  min-width: 0;
}

.view,
.group,
.picked,
.member-list,
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.view {
  min-width: 0;
}

.k,
.empty,
.hint,
.login {
  color: var(--muted);
  font-size: 12px;
}

.k span {
  color: var(--accent);
}

.who,
.chip,
.member {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
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

.edit,
.quiet,
.go,
.chip,
.member {
  border: 1px solid var(--line);
  border-radius: 999px;
  background: white;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.edit,
.quiet,
.go {
  padding: 2px 8px;
  font-size: 12px;
}

.edit {
  color: var(--accent);
}

.panel {
  position: fixed;
  z-index: 30;
  display: grid;
  gap: 8px;
  width: min(320px, calc(100vw - 24px));
  box-sizing: border-box;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: white;
  box-shadow: 0 12px 32px rgba(26, 31, 28, 0.16);
}

.panel > * {
  min-width: 0;
}

.member-list {
  min-height: 0;
  max-height: 220px;
  align-content: flex-start;
  overflow: auto;
}

.chip,
.member {
  max-width: 100%;
  padding: 3px 8px;
  font-size: 12px;
}

.member.on,
.chip {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent);
}

input {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: white;
  color: var(--ink);
  font: inherit;
}

.hint,
.notice {
  margin: 0;
}

.notice {
  color: var(--warn);
  font-size: 12px;
}

.go {
  border-color: transparent;
  background: var(--accent);
  color: white;
}

button:disabled {
  cursor: default;
  opacity: 0.6;
}
</style>
