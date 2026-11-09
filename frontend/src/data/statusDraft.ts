import { ref } from 'vue'
import type { Task } from '@/data/tasks'

export const pendingIssueId = ref<string | null>(null)

export const editingOwnerId = ref<number | null>(null)

let ownerEditorSerial = 0

export function nextOwnerEditorId(): number {
  ownerEditorSerial += 1
  return ownerEditorSerial
}

export function applyTask(task: Task): Task {
  if (task.owners) return task
  return { ...task, owners: task.owner ? [task.owner] : [] }
}
