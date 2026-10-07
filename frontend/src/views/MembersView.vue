<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useProjectStore } from '@/stores/projects'
import { createMemberLabel, fetchMembers, saveMemberRoles, syncMembers, type MemberBoard } from '@/api/client'
import { roleTone } from '@/data/roles'

interface Member {
  login: string
  name: string
  teamIds: string[]
}

interface Team {
  id: string
  name: string
}

const projects = useProjectStore()
const teamId = ref<string | null>(null)
const roleId = ref<string | '未设置' | null>(null)
const query = ref('')
const members = ref<Member[]>([])
const teams = ref<Team[]>([])
const labels = ref<string[]>([])
const roleByLogin = ref<Record<string, string[]>>({})
const ready = ref(false)
const loadError = ref('')
const syncing = ref(false)
const syncError = ref('')
const rolesReady = ref(false)
const rolesError = ref('')
const savingLogin = ref<string | null>(null)
const draft = ref('')
const adding = ref(false)
const editor = ref<{ login: string; name: string; top: number; left: number } | null>(null)

const teamById = computed(() => new Map(teams.value.map((team) => [team.id, team])))
const teamCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const member of members.value) {
    for (const id of member.teamIds) counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  return counts
})

watch(
  () => projects.currentId,
  async (id) => {
    if (id == null) return
    ready.value = false
    loadError.value = ''
    editor.value = null
    try {
      applyBoard(await fetchMembers())
    } catch {
      loadError.value = '成员没有读到。'
    } finally {
      ready.value = true
    }
  },
  { immediate: true },
)

function applyBoard(board: MemberBoard) {
  members.value = board.members.map((member) => ({
    login: member.login,
    name: member.name,
    teamIds: member.teamIds,
  }))
  teams.value = board.teams
  labels.value = board.labels
  const next: Record<string, string[]> = {}
  for (const member of board.members) next[member.login] = member.roles
  roleByLogin.value = next
  rolesReady.value = true
  if (teamId.value && !board.teams.some((team) => team.id === teamId.value)) teamId.value = null
}

async function sync() {
  if (syncing.value) return
  syncing.value = true
  syncError.value = ''
  editor.value = null
  try {
    applyBoard(await syncMembers())
  } catch {
    syncError.value = '成员没有同步。'
  } finally {
    syncing.value = false
  }
}

const visibleMembers = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return members.value
    .filter((member) => {
      if (teamId.value && !member.teamIds.includes(teamId.value)) return false
      const assigned = rolesOf(member.login)
      if (roleId.value === '未设置' && assigned.length > 0) return false
      if (roleId.value && roleId.value !== '未设置' && !assigned.includes(roleId.value)) return false
      if (!keyword) return true
      return member.name.toLowerCase().includes(keyword) || member.login.toLowerCase().includes(keyword)
    })
    .sort((a, b) => a.name.localeCompare(b.name, 'zh'))
})

function rolesOf(login: string): string[] {
  return roleByLogin.value[login] ?? []
}

function countRole(role: string): number {
  return members.value.filter((member) => rolesOf(member.login).includes(role)).length
}

const unsetCount = computed(() => members.value.filter((member) => rolesOf(member.login).length === 0).length)

function teamsOf(member: Member): Team[] {
  return member.teamIds.flatMap((id) => {
    const team = teamById.value.get(id)
    return team ? [team] : []
  })
}

function pick(id: string | null) {
  teamId.value = id
}

function pickRole(id: string | '未设置' | null) {
  roleId.value = id
}

function initial(name: string): string {
  const text = name.trim()
  if (!text) return '?'
  const char = [...text][0] ?? '?'
  return /[a-z]/i.test(char) ? char.toUpperCase() : char
}

function openEditor(member: Member, event: MouseEvent) {
  const target = event.currentTarget
  if (!(target instanceof HTMLElement)) return
  const rect = target.getBoundingClientRect()
  const width = 240
  const height = 360
  const left = Math.min(Math.max(8, rect.left), window.innerWidth - width - 8)
  const below = rect.bottom + 6
  const top = below + height > window.innerHeight ? Math.max(8, rect.top - height - 6) : below
  editor.value = { login: member.login, name: member.name, top, left }
}

async function toggleRole(role: string) {
  const current = editor.value
  if (!current || savingLogin.value) return
  const previous = rolesOf(current.login)
  const next = labels.value.filter((item) => (item === role ? !previous.includes(item) : previous.includes(item)))
  roleByLogin.value = { ...roleByLogin.value, [current.login]: next }
  savingLogin.value = current.login
  rolesError.value = ''
  try {
    const saved = await saveMemberRoles(current.login, current.name, next)
    roleByLogin.value = { ...roleByLogin.value, [current.login]: saved.roles }
  } catch {
    roleByLogin.value = { ...roleByLogin.value, [current.login]: previous }
    rolesError.value = '角色没有保存。'
  } finally {
    savingLogin.value = null
  }
}

async function addLabel() {
  const name = draft.value.trim()
  if (!name || adding.value) return
  adding.value = true
  rolesError.value = ''
  try {
    labels.value = await createMemberLabel(name)
    draft.value = ''
  } catch {
    rolesError.value = '标签没有加上。'
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <main class="members">
    <header class="head">
      <div>
        <h1>成员</h1>
        <p>团队跟着 GitLab 同步，角色留在 Helix。</p>
      </div>
      <div class="tools">
        <button type="button" class="sync" :disabled="syncing" @click="sync">
          {{ syncing ? '正在同步' : '同步成员' }}
        </button>
        <input v-model="query" class="search" type="search" placeholder="搜索姓名或登录名" aria-label="搜索成员" />
      </div>
    </header>

    <div class="teams" role="tablist" aria-label="按团队筛选">
      <button type="button" role="tab" :aria-selected="teamId === null" :class="{ on: teamId === null }" @click="pick(null)">
        全部
        <span>{{ members.length }}</span>
      </button>
      <button
        v-for="team in teams"
        :key="team.id"
        type="button"
        role="tab"
        :aria-selected="teamId === team.id"
        :class="{ on: teamId === team.id }"
        @click="pick(team.id)"
      >
        {{ team.name }}
        <span>{{ teamCounts.get(team.id) ?? 0 }}</span>
      </button>
    </div>

    <div class="roles" role="tablist" aria-label="按角色筛选">
      <span class="roles-label">角色</span>
      <button type="button" role="tab" :aria-selected="roleId === null" :class="{ on: roleId === null }" @click="pickRole(null)">
        全部
      </button>
      <button type="button" role="tab" :aria-selected="roleId === '未设置'" :class="{ on: roleId === '未设置' }" @click="pickRole('未设置')">
        未设置
        <span>{{ unsetCount }}</span>
      </button>
      <button
        v-for="role in labels"
        :key="role"
        type="button"
        role="tab"
        :aria-selected="roleId === role"
        :class="[roleTone(role), { on: roleId === role }]"
        @click="pickRole(role)"
      >
        {{ role }}
        <span>{{ countRole(role) }}</span>
      </button>
    </div>
    <p v-if="!ready && !loadError" class="empty">正在读取成员。</p>
    <p v-else-if="loadError" class="empty">{{ loadError }}</p>
    <p v-else-if="syncError" class="empty">{{ syncError }}</p>
    <p v-else-if="rolesError" class="empty">{{ rolesError }}</p>

    <div v-if="visibleMembers.length" class="table-wrap">
      <table>
        <colgroup>
          <col class="col-person" />
          <col class="col-role" />
          <col />
        </colgroup>
        <thead>
          <tr>
            <th>成员</th>
            <th>角色</th>
            <th>所属团队</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="member in visibleMembers" :key="member.login">
            <td>
              <div class="person">
                <span class="avatar" aria-hidden="true">{{ initial(member.name) }}</span>
                <span class="who">
                  <strong>{{ member.name }}</strong>
                  <span v-if="member.login !== member.name" class="login">{{ member.login }}</span>
                </span>
              </div>
            </td>
            <td>
              <div class="chips">
                <button
                  v-for="role in rolesOf(member.login)"
                  :key="role"
                  type="button"
                  class="role-chip"
                  :class="[roleTone(role), { on: roleId === role }]"
                  @click="pickRole(role)"
                >
                  {{ role }}
                </button>
                <span v-if="rolesOf(member.login).length === 0" class="unset">未设置</span>
                <button v-if="rolesReady" type="button" class="set-role" :class="{ on: editor?.login === member.login }" @click="openEditor(member, $event)">
                  设置
                </button>
              </div>
            </td>
            <td>
              <div class="chips">
                <button
                  v-for="team in teamsOf(member)"
                  :key="team.id"
                  type="button"
                  class="chip"
                  :class="{ on: teamId === team.id }"
                  @click="pick(team.id)"
                >
                  {{ team.name }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <ul v-if="visibleMembers.length" class="member-cards">
      <li v-for="member in visibleMembers" :key="member.login">
        <div class="person">
          <span class="avatar" aria-hidden="true">{{ initial(member.name) }}</span>
          <span class="who">
            <strong>{{ member.name }}</strong>
            <span v-if="member.login !== member.name" class="login">{{ member.login }}</span>
          </span>
        </div>
        <div class="chips">
          <button
            v-for="role in rolesOf(member.login)"
            :key="role"
            type="button"
            class="role-chip"
            :class="[roleTone(role), { on: roleId === role }]"
            @click="pickRole(role)"
          >
            {{ role }}
          </button>
          <span v-if="rolesOf(member.login).length === 0" class="unset">未设置</span>
          <button v-if="rolesReady" type="button" class="set-role" :class="{ on: editor?.login === member.login }" @click="openEditor(member, $event)">
            设置
          </button>
        </div>
        <div class="chips">
          <button
            v-for="team in teamsOf(member)"
            :key="team.id"
            type="button"
            class="chip"
            :class="{ on: teamId === team.id }"
            @click="pick(team.id)"
          >
            {{ team.name }}
          </button>
        </div>
      </li>
    </ul>

    <p v-else-if="ready && members.length === 0" class="empty">还没有从 GitLab 同步成员。</p>
    <p v-else-if="ready" class="empty">没有匹配的成员。</p>

    <Teleport to="body">
      <div v-if="editor" class="role-backdrop" @click="editor = null">
        <div
          class="role-panel"
          role="dialog"
          aria-label="设置角色"
          :style="{ top: `${editor.top}px`, left: `${editor.left}px` }"
          @click.stop
        >
          <p>{{ editor.name }}</p>
          <div class="role-choices">
            <button
              v-for="role in labels"
              :key="role"
              type="button"
              class="role-choice"
              :class="[roleTone(role), { on: rolesOf(editor.login).includes(role) }]"
              :disabled="savingLogin === editor.login"
              @click="toggleRole(role)"
            >
              {{ role }}
            </button>
          </div>
          <form class="new-label" @submit.prevent="addLabel">
            <input v-model="draft" maxlength="20" placeholder="新标签" aria-label="新标签" />
            <button type="submit" :disabled="!draft.trim() || adding">新增</button>
          </form>
        </div>
      </div>
    </Teleport>
  </main>
</template>

<style scoped>
.head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.head p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.search {
  width: min(280px, 100%);
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink);
}

.sync {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--surface);
  color: var(--accent);
  cursor: pointer;
}

.sync:disabled {
  cursor: progress;
  opacity: 0.6;
}

.teams,
.roles {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.roles-label {
  color: var(--muted);
  font-size: 13px;
}

.teams button,
.roles button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--surface);
  color: var(--muted);
  cursor: pointer;
}

.teams button.on,
.roles button.on {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent);
}

.roles button.frontend.on {
  background: var(--accent-soft);
  color: var(--accent);
}

.roles button.backend.on {
  background: #e7eef8;
  color: #2451a3;
}

.roles button.qa.on {
  background: var(--warn-soft);
  color: var(--warn);
}

.roles button.product.on {
  background: #e7f6ee;
  color: #0b7a45;
}

.roles button.architect.on,
.roles button.custom.on {
  background: #eeeae3;
  color: var(--ink);
}

.teams span,
.roles span {
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

table {
  width: 100%;
  min-width: 880px;
  border-collapse: collapse;
  table-layout: fixed;
}

.col-person {
  width: 220px;
}

.col-role {
  width: 196px;
}

th,
td {
  padding: 14px 16px;
  text-align: left;
  vertical-align: middle;
  border-bottom: 1px solid var(--line);
}

th {
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  background: #f7f4ee;
}

tbody tr:last-child td {
  border-bottom: 0;
}

.person {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 13px;
}

.who {
  min-width: 0;
}

.who strong {
  display: block;
  overflow: hidden;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.login,
.unset {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.unset {
  display: inline;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.chip,
.role-chip,
.set-role {
  border: 0;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  cursor: pointer;
}

.chip {
  background: #eeeae3;
  color: var(--muted);
}

.chip.on {
  background: var(--accent-soft);
  color: var(--accent);
}

.role-chip.frontend,
.set-role.frontend {
  background: var(--accent-soft);
  color: var(--accent);
}

.role-chip.backend {
  background: #e7eef8;
  color: #2451a3;
}

.role-chip.qa {
  background: var(--warn-soft);
  color: var(--warn);
}

.role-chip.product {
  background: #e7f6ee;
  color: #0b7a45;
}

.role-chip.architect,
.role-chip.custom {
  background: #eeeae3;
  color: var(--ink);
}

.role-chip.on {
  box-shadow: inset 0 0 0 1px currentColor;
}

.set-role {
  border: 1px dashed var(--line);
  background: transparent;
  color: var(--muted);
}

.set-role.on {
  border-style: solid;
  border-color: var(--accent);
  color: var(--accent);
}

.member-cards {
  display: none;
}

.empty {
  margin: 0 0 16px;
  color: var(--muted);
}

@media (max-width: 800px) {
  .head {
    flex-direction: column;
    align-items: stretch;
  }

  .tools,
  .search {
    width: 100%;
  }

  .teams,
  .roles {
    flex-wrap: nowrap;
    overflow: auto;
    scrollbar-width: none;
  }

  .teams::-webkit-scrollbar,
  .roles::-webkit-scrollbar {
    display: none;
  }

  .teams button,
  .roles button,
  .roles-label {
    flex: none;
  }

  .table-wrap {
    display: none;
  }

  .member-cards {
    display: grid;
    gap: 12px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .member-cards li {
    display: grid;
    gap: 12px;
    min-width: 0;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--surface);
  }
}
</style>

<style>
.role-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
}

.role-panel {
  position: fixed;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 240px;
  max-height: min(360px, calc(100vh - 16px));
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  box-shadow: 0 12px 32px rgb(26 31 28 / 12%);
}

.role-panel p {
  margin: 0;
  font-weight: 650;
}

.role-choices {
  display: grid;
  gap: 8px;
  min-height: 0;
  overflow: auto;
}

.role-choice {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--surface);
  color: var(--muted);
  text-align: left;
  cursor: pointer;
}

.role-choice.on.frontend {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent);
}

.role-choice.on.backend {
  border-color: transparent;
  background: #e7eef8;
  color: #2451a3;
}

.role-choice.on.qa {
  border-color: transparent;
  background: var(--warn-soft);
  color: var(--warn);
}

.role-choice.on.product {
  border-color: transparent;
  background: #e7f6ee;
  color: #0b7a45;
}

.role-choice.on.architect,
.role-choice.on.custom {
  border-color: transparent;
  background: #eeeae3;
  color: var(--ink);
}

.new-label {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.new-label input {
  min-width: 0;
  flex: 1;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
  color: var(--ink);
}

.new-label button {
  flex: none;
  border: 0;
  border-radius: 999px;
  padding: 6px 10px;
  background: var(--accent-soft);
  color: var(--accent);
  cursor: pointer;
}

.new-label button:disabled {
  cursor: default;
  opacity: 0.45;
}

.role-choice:disabled {
  cursor: progress;
}
</style>
