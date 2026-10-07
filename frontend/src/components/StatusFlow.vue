<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { directory } from '@/data/people'
import { pendingIssueId, rememberOwners } from '@/data/statusDraft'
import { taskTransitionKey } from '@/data/taskTransition'
import { movesFor, stageFor, type Move } from '@/data/transitions'
import { statusTone, type TaskStage, type TaskStatus } from '@/data/tasks'

const props = defineProps<{
  issueId: string
  status: TaskStatus
  stage: TaskStage
  owners: string[]
  locked: boolean
  variant: 'row' | 'panel'
}>()

const pending = ref<Move | null>(null)
const reason = ref('')
const picked = ref<string[]>([])
const query = ref('')
const othersOpen = ref(false)
const saving = ref(false)
const notice = ref('')
const settle = inject(taskTransitionKey)

const filteredPeople = computed(() => {
  const text = query.value.trim().toLowerCase()
  if (!text) return directory.value
  return directory.value.filter((name) => name.toLowerCase().includes(text))
})
const canConfirm = computed(() => {
  if (!pending.value) return false
  if (pending.value.needsReason && !reason.value.trim()) return false
  if (pending.value.needsOwners && picked.value.length === 0) return false
  return !saving.value
})

const moves = computed(() => movesFor(props.status, props.locked))
const forward = computed(() => moves.value.filter((move) => move.tone === 'forward'))
const rest = computed(() => moves.value.filter((move) => move.tone !== 'forward'))
const primary = computed(() => forward.value.find((move) => !move.disabledReason) ?? forward.value[0] ?? null)
const rowRest = computed(() => moves.value.filter((move) => move !== primary.value))

watch(
  () => props.status,
  () => {
    pending.value = null
    reason.value = ''
    picked.value = []
    query.value = ''
    notice.value = ''
  },
)

watch(pendingIssueId, (id) => {
  if (id !== props.issueId) {
    pending.value = null
    othersOpen.value = false
  }
})

function pick(move: Move) {
  if (move.disabledReason) return
  pendingIssueId.value = props.issueId
  pending.value = move
  reason.value = ''
  picked.value = move.needsOwners ? [...props.owners] : []
  query.value = ''
  othersOpen.value = false
}

function toggleOwner(name: string) {
  picked.value = picked.value.includes(name) ? picked.value.filter((item) => item !== name) : [...picked.value, name]
}

function cancel() {
  pending.value = null
  reason.value = ''
  picked.value = []
  query.value = ''
  if (pendingIssueId.value === props.issueId) pendingIssueId.value = null
}

async function confirm() {
  const move = pending.value
  if (!move || !canConfirm.value || !settle) return
  saving.value = true
  notice.value = ''
  const stage = stageFor(move.to, props.stage)
  try {
    const result = await settle(props.issueId, move.to, stage)
    if (move.needsOwners && result.synced) rememberOwners(props.issueId, [...picked.value])
    if (result.synced) {
      cancel()
      return
    }
    notice.value = '主 issue 没有接受这次流转，任务已按 issue 上的状态对齐。'
  }
  catch {
    notice.value = '主 issue 没有改成，请再试一次。'
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="flow" :class="variant" @click.stop>
    <p v-if="moves.length === 0" class="terminal">终态</p>
    <form v-else-if="pending" class="confirm" @submit.prevent="confirm">
      <p class="confirm-title">{{ pending.label }}</p>
      <p class="confirm-path">
        <span class="state" :class="statusTone(status)">{{ status }}</span>
        <span aria-hidden="true">→</span>
        <span class="state" :class="statusTone(pending.to)">{{ pending.to }}</span>
      </p>
      <label v-if="pending.needsReason" class="reason">
        原因
        <textarea v-model="reason" rows="2" placeholder="写明原因" />
      </label>
      <div v-if="pending.needsOwners" class="owners">
        <span class="owners-label">负责人 <span v-if="picked.length > 0">已选 {{ picked.length }} 人</span></span>
        <div v-if="picked.length > 0" class="picked">
          <button v-for="name in picked" :key="name" type="button" class="chip" @click="toggleOwner(name)">
            {{ name }}
            <span aria-hidden="true">×</span>
          </button>
        </div>
        <input v-model="query" type="search" placeholder="搜索姓名" />
        <div class="member-list">
          <button
            v-for="name in filteredPeople"
            :key="name"
            type="button"
            class="member"
            :class="{ on: picked.includes(name) }"
            @click="toggleOwner(name)"
          >
            <span class="avatar" aria-hidden="true">{{ name.slice(0, 1) }}</span>
            {{ name }}
          </button>
          <p v-if="filteredPeople.length === 0" class="empty">没有匹配的人</p>
        </div>
      </div>
      <p v-if="notice" class="notice">{{ notice }}</p>
      <div class="confirm-actions">
        <button type="button" class="quiet" @click="cancel">取消</button>
        <button type="submit" class="go" :disabled="!canConfirm">确认{{ pending.label }}</button>
      </div>
    </form>
    <div v-else class="choices">
      <template v-if="variant === 'panel'">
        <button
          v-for="move in forward"
          :key="move.label"
          type="button"
          class="choice forward"
          :disabled="!!move.disabledReason"
          :title="move.disabledReason || undefined"
          @click="pick(move)"
        >
          {{ move.label }}
        </button>
        <button
          v-for="move in rest"
          :key="move.label"
          type="button"
          class="choice"
          :class="move.tone"
          @click="pick(move)"
        >
          {{ move.label }}
        </button>
      </template>
      <template v-else>
        <button
          v-if="primary"
          type="button"
          class="choice forward"
          :disabled="!!primary.disabledReason"
          :title="primary.disabledReason || undefined"
          @click="pick(primary)"
        >
          {{ primary.label }}
        </button>
        <button v-if="rowRest.length > 0" type="button" class="quiet" @click="othersOpen = !othersOpen">
          {{ othersOpen ? '收起' : '其他' }}
        </button>
        <div v-if="othersOpen" class="others">
          <button
            v-for="move in rowRest"
            :key="move.label"
            type="button"
            class="choice"
            :class="move.tone"
            :disabled="!!move.disabledReason"
            :title="move.disabledReason || undefined"
            @click="pick(move)"
          >
            {{ move.label }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.flow.row {
  max-width: 100%;
}

.terminal {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.choices,
.others,
.confirm-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.panel .choices {
  align-items: stretch;
}

.choice,
.quiet,
.go {
  border: 0;
  border-radius: 999px;
  padding: 5px 10px;
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}

.choice:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.forward,
.go {
  background: var(--accent);
  color: white;
}

.back {
  background: var(--warn-soft);
  color: var(--warn);
}

.stop {
  background: var(--danger-soft);
  color: var(--danger);
}

.quiet {
  background: transparent;
  color: var(--muted);
}

.others {
  flex-basis: 100%;
}

.confirm {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fbf9f4;
}

.confirm-title {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
}

.confirm-path {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
}

.reason {
  display: grid;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
}

textarea,
input {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: white;
  color: var(--ink);
  font: inherit;
  resize: vertical;
}

.owners {
  display: grid;
  gap: 6px;
}

.owners-label,
.empty,
.notice {
  color: var(--muted);
  font-size: 12px;
}

.notice {
  margin: 0;
  color: var(--warn);
}

.owners-label span {
  color: var(--accent);
}

.picked,
.member-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.member-list {
  max-height: 160px;
  overflow: auto;
}

.chip,
.member {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px 8px;
  background: white;
  color: inherit;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.member.on,
.chip {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent);
}

.avatar {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: white;
  font-size: 11px;
}

.empty {
  margin: 0;
}

.state {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
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
</style>
