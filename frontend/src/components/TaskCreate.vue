<script setup lang="ts">
import { computed, ref } from 'vue'
import { createTask, uploadTaskFile } from '@/api/client'
import { proposalKinds } from '@/data/proposalDraft'

const emit = defineEmits<{
  close: []
  created: [code: string]
}>()

interface DraftFile {
  id: number
  name: string
  markdown: string
  error: string
  pending: boolean
}

const title = ref('')
const kind = ref<'bug' | 'tech' | 'feature'>('feature')
const values = ref<Record<string, string>>({})
const files = ref<DraftFile[]>([])
const busy = ref(false)
const error = ref('')
let fileId = 0

const fields = computed(() => proposalKinds.find((item) => item.id === kind.value)?.fields ?? [])

function text(name: string): string {
  return values.value[name] ?? ''
}

function setText(name: string, value: string) {
  values.value = { ...values.value, [name]: value }
}

function removeFile(id: number) {
  files.value = files.value.filter((file) => file.id !== id)
}

async function addFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = [...(input.files ?? [])]
  input.value = ''
  for (const file of selected) {
    const id = ++fileId
    files.value = [...files.value, { id, name: file.name, markdown: '', error: '', pending: true }]
    try {
      const uploaded = await uploadTaskFile(file)
      files.value = files.value.map((item) =>
        item.id === id ? { ...item, name: uploaded.name || file.name, markdown: uploaded.markdown, pending: false } : item,
      )
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : '附件没有传上去。'
      files.value = files.value.map((item) => (item.id === id ? { ...item, error: message, pending: false } : item))
    }
  }
}

async function submit(delegate: boolean) {
  error.value = ''
  const missing = fields.value.filter((name) => text(name).trim() === '')
  if (!title.value.trim()) {
    error.value = '要写标题。'
    return
  }
  if (missing.length > 0) {
    error.value = `还要写：${missing.join('、')}。`
    return
  }
  if (files.value.some((file) => file.pending)) {
    error.value = '附件还在上传。'
    return
  }
  if (files.value.some((file) => file.error || !file.markdown)) {
    error.value = '还有附件没传上去。'
    return
  }
  busy.value = true
  try {
    const filled: Record<string, string> = {}
    for (const name of fields.value) filled[name] = text(name).trim()
    const created = await createTask({
      title: title.value.trim(),
      kind: kind.value,
      fields: filled,
      attachments: files.value.map((file) => file.markdown),
      delegate,
    })
    emit('created', created.code)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '任务没有建起来。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <form class="sheet" @submit.prevent="submit(false)">
      <header>
        <h2>创建任务</h2>
        <button type="button" class="ghost" @click="emit('close')">关闭</button>
      </header>
      <div class="body">
        <label>
          标题
          <input v-model="title" type="text" maxlength="200" autocomplete="off" />
        </label>
        <div class="kinds" role="radiogroup" aria-label="类型">
          <label v-for="item in proposalKinds" :key="item.id">
            <input v-model="kind" type="radio" name="kind" :value="item.id" />
            {{ item.label }}
          </label>
        </div>
        <label v-for="name in fields" :key="name">
          {{ name }}
          <textarea :value="text(name)" rows="3" @input="setText(name, ($event.target as HTMLTextAreaElement).value)" />
        </label>
        <div class="files">
          <div class="file-head">
            <span>参考附件</span>
            <label class="pick">
              添加附件
              <input type="file" multiple @change="addFiles" />
            </label>
          </div>
          <ul v-if="files.length">
            <li v-for="file in files" :key="file.id">
              <span class="file-name">{{ file.name }}</span>
              <span v-if="file.pending" class="hint">正在上传</span>
              <span v-else-if="file.error" class="error">{{ file.error }}</span>
              <button type="button" class="ghost" @click="removeFile(file.id)">移除</button>
            </li>
          </ul>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
      </div>
      <div class="actions">
        <button type="submit" :disabled="busy">原文保存</button>
        <button type="button" class="delegate" :disabled="busy" @click="submit(true)">委托 proposal</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(26 31 28 / 28%);
}

.sheet {
  display: flex;
  flex-direction: column;
  width: min(640px, 100%);
  max-height: min(820px, calc(100vh - 48px));
  overflow: hidden;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.body {
  display: grid;
  gap: 14px;
  min-height: 0;
  overflow: auto;
  padding: 14px 0;
}

header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

h2 {
  margin: 0;
  font-size: 20px;
}

label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 13px;
}

input,
textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
  color: var(--ink);
  background: var(--paper);
  font: inherit;
}

.kinds {
  display: flex;
  gap: 14px;
}

.kinds label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink);
}

.kinds input {
  width: auto;
}

.actions {
  display: flex;
  flex: none;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.files {
  display: grid;
  gap: 8px;
}

.file-head,
.files li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.files ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.file-name {
  min-width: 0;
  overflow: hidden;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hint {
  color: var(--muted);
  font-size: 12px;
}

.pick {
  position: relative;
  overflow: hidden;
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--paper);
  color: var(--ink);
  cursor: pointer;
}

.pick input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

button {
  border: 0;
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--accent);
  color: white;
  cursor: pointer;
}

button:disabled {
  opacity: 0.6;
  cursor: default;
}

.delegate {
  background: var(--ink);
}

.ghost {
  background: transparent;
  color: var(--muted);
}

.error {
  margin: 0;
  color: var(--danger);
}
</style>
