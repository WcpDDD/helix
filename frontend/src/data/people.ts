import { ref } from 'vue'
import type { Task } from '@/data/tasks'

export const directory = ref<string[]>([])

export function rememberPeople(tasks: Task[]) {
  const names = new Set<string>()
  for (const task of tasks) {
    add(names, task.issue.author)
    add(names, task.owner)
    for (const issue of task.issues ?? []) {
      add(names, issue.author)
      add(names, issue.owner)
    }
  }
  directory.value = [...names].sort((a, b) => a.localeCompare(b, 'zh'))
}

function add(names: Set<string>, value: string | null | undefined) {
  const name = value?.trim()
  if (name) names.add(name)
}
