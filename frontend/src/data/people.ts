import { ref } from 'vue'
import { fetchMembers } from '@/api/client'
import type { Task } from '@/data/tasks'

export interface DirectoryPerson {
  login: string
  name: string
}

export const directory = ref<DirectoryPerson[]>([])

let members: DirectoryPerson[] = []
let fromTasks: string[] = []
let generation = 0

export function loadDirectory(): Promise<void> {
  const ticket = ++generation
  return fetchMembers()
    .then((board) => {
      if (ticket !== generation) return
      members = board.members
        .filter((member) => member.login)
        .map((member) => ({
          login: member.login,
          name: member.name?.trim() || member.login,
        }))
      rebuild()
    })
    .catch(() => {
      if (ticket === generation) rebuild()
    })
}

export function rememberPeople(tasks: Task[]) {
  const names = new Set<string>()
  for (const task of tasks) {
    add(names, task.issue.author)
    add(names, task.owner)
    for (const owner of task.owners ?? []) add(names, owner)
    for (const issue of task.issues ?? []) {
      add(names, issue.author)
      add(names, issue.owner)
    }
  }
  fromTasks = [...names]
  rebuild()
}

function rebuild() {
  const byLogin = new Map<string, DirectoryPerson>()
  for (const person of members) byLogin.set(person.login, person)
  for (const name of fromTasks) {
    const known = [...byLogin.values()].some((person) => person.login === name || person.name === name)
    if (!known) byLogin.set(name, { login: name, name })
  }
  directory.value = [...byLogin.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh') || a.login.localeCompare(b.login))
}

function add(names: Set<string>, value: string | null | undefined) {
  const name = value?.trim()
  if (name) names.add(name)
}
