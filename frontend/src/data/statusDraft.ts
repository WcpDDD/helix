import { reactive, ref } from 'vue'
import type { Task } from '@/data/tasks'

const owners = reactive<Record<string, string[]>>({})

export const pendingIssueId = ref<string | null>(null)

export function rememberOwners(id: string, names: string[]) {
  owners[id] = names
}

export function applyTask(task: Task): Task {
  const next = owners[task.code] ?? (task.owner ? [task.owner] : [])
  return { ...task, owners: next }
}
