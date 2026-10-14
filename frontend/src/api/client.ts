import type { TaskTransitionResult } from '@/data/taskTransition'
import type { Task, TaskStage, TaskStatus } from '@/data/tasks'

export interface Project {
  id: number
  name: string
  workspace: {
    host: string
    path: string
  }
}

export interface Health {
  status: string
  service: string
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}`)
  }
  return response.json() as Promise<T>
}

export function fetchHealth(): Promise<Health> {
  return getJson<Health>('/api/health')
}

export function fetchProjects(): Promise<Project[]> {
  return getJson<Project[]>('/api/projects')
}

export function fetchCurrentProject(): Promise<Project> {
  return getJson<Project>('/api/projects/current')
}

export interface WorkCommit {
  sha: string
  subject: string
  author: string
  at: string
}

export interface WorkSnapshot {
  branch: string
  directory: string
  push: string
  pushError: string
  commits: WorkCommit[]
  ahead: number
  behind: number
  dirty: boolean
}

export async function fetchProjectWork(): Promise<WorkSnapshot> {
  return workRequest('/api/projects/current/work', 'GET')
}

export function pullProjectWork(): Promise<WorkSnapshot> {
  return workRequest('/api/projects/current/work/pull', 'POST')
}

export function pushProjectWork(): Promise<WorkSnapshot> {
  return workRequest('/api/projects/current/work/push', 'POST')
}

async function workRequest(path: string, method: string): Promise<WorkSnapshot> {
  const response = await fetch(path, { method })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const detail = body && typeof body.detail === 'string' ? body.detail : ''
    throw new Error(detail || '工作目录没有准备好。')
  }
  return response.json() as Promise<WorkSnapshot>
}

export async function selectProject(id: number): Promise<Project> {
  const response = await fetch('/api/projects/current', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const detail = body && typeof body.detail === 'string' ? body.detail : ''
    throw new Error(detail || '项目没有切换。')
  }
  return response.json() as Promise<Project>
}

export async function createProject(name: string, path: string): Promise<Project> {
  const response = await fetch('/api/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, path }),
  })
  if (response.status === 409) {
    throw new Error('这个工程已经挂在别的项目上。')
  }
  if (response.status === 404) {
    throw new Error('GitLab 上没有这个工程。')
  }
  if (response.status === 401) {
    throw new Error('需要重新登录。')
  }
  if (!response.ok) {
    throw new Error('项目没有建起来。')
  }
  return response.json() as Promise<Project>
}

export interface GitlabProject {
  path: string
  name: string
  webUrl: string
}

export function fetchGitlabProjects(query: string): Promise<GitlabProject[]> {
  const search = query.trim()
  const path = search ? `/api/gitlab/projects?q=${encodeURIComponent(search)}` : '/api/gitlab/projects'
  return getJson<GitlabProject[]>(path)
}

export async function saveProject(id: number, name: string, path: string): Promise<Project> {
  const response = await fetch(`/api/projects/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, path }),
  })
  if (response.status === 409) {
    throw new Error('这块看板已经有 issue，工程先留在现在这个。')
  }
  if (response.status === 404) {
    throw new Error('GitLab 上没有这个工程。')
  }
  if (response.status === 401) {
    throw new Error('需要重新登录。')
  }
  if (!response.ok) {
    throw new Error('工程没有保存。')
  }
  return response.json() as Promise<Project>
}

export async function fetchTasks(): Promise<Task[]> {
  const response = await fetch('/api/tasks')
  if (response.status === 401) {
    throw new Error('需要重新登录。')
  }
  if (!response.ok) {
    throw new Error(`/api/tasks returned ${response.status}`)
  }
  return response.json() as Promise<Task[]>
}

export interface SkillEntry {
  name: string
  path: string
  files: string[]
}

export interface SkillFile {
  path: string
  markdown: string
}

export function fetchSkills(): Promise<SkillEntry[]> {
  return fetchIssueJson<SkillEntry[]>('/api/skills')
}

export function fetchSkillFile(path: string): Promise<SkillFile> {
  return fetchIssueJson<SkillFile>(`/api/skills/file?path=${encodeURIComponent(path)}`)
}

export interface AgentRecord {
  id: number
  name: string
  body: string
  skills: string[]
}

export interface AgentDraft {
  name: string
  body: string
  skills: string[]
}

export function fetchAgents(): Promise<AgentRecord[]> {
  return fetchIssueJson<AgentRecord[]>('/api/agents')
}

export interface WorkerRecord {
  id: string
  name: string
  alive: boolean
  lastHeartbeat: string
}

export function fetchWorkers(): Promise<WorkerRecord[]> {
  return getJson<WorkerRecord[]>('/api/workers')
}

export async function createAgent(draft: AgentDraft): Promise<AgentRecord> {
  return writeAgent('/api/agents', 'POST', draft)
}

export async function updateAgent(id: number, draft: AgentDraft): Promise<AgentRecord> {
  return writeAgent(`/api/agents/${id}`, 'PUT', draft)
}

export async function deleteAgent(id: number): Promise<void> {
  const response = await fetch(`/api/agents/${id}`, { method: 'DELETE' })
  if (response.status === 401) {
    const error = new Error('login')
    error.name = 'IssueReadAuthError'
    throw error
  }
  if (!response.ok) {
    throw new Error(`/api/agents/${id} returned ${response.status}`)
  }
}

async function writeAgent(path: string, method: string, draft: AgentDraft): Promise<AgentRecord> {
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(draft),
  })
  if (response.status === 401) {
    const error = new Error('login')
    error.name = 'IssueReadAuthError'
    throw error
  }
  if (response.status === 409) {
    throw new Error('已经有同名 agent。')
  }
  if (response.status === 400) {
    throw new Error('名称或 skill 引用没有通过。')
  }
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}`)
  }
  return response.json() as Promise<AgentRecord>
}

export interface TaskSpecFile {
  path: string
  markdown: string
}

export interface TaskSpecCheckout {
  branch: string
  commit: string
  files: TaskSpecFile[]
}

export async function fetchTaskSpec(code: string): Promise<TaskSpecCheckout> {
  return fetchIssueJson<TaskSpecCheckout>(`/api/tasks/${encodeURIComponent(code)}/spec`)
}

export interface MemberRoleAssignment {
  login: string
  roles: string[]
}

export interface MemberRolePage {
  labels: string[]
  members: MemberRoleAssignment[]
}

export interface MemberBoard {
  teams: { id: string; name: string }[]
  members: { login: string; name: string; teamIds: string[]; roles: string[] }[]
  labels: string[]
}

export function fetchMembers(): Promise<MemberBoard> {
  return getJson<MemberBoard>('/api/members')
}

export async function syncMembers(): Promise<MemberBoard> {
  const response = await fetch('/api/members/sync', { method: 'POST' })
  if (!response.ok) {
    throw new Error(`${response.status}`)
  }
  return response.json() as Promise<MemberBoard>
}

export function fetchMemberRoles(): Promise<MemberRolePage> {
  return getJson<MemberRolePage>('/api/members/roles')
}

export async function saveMemberRoles(login: string, name: string, roles: string[]): Promise<MemberRoleAssignment> {
  const response = await fetch(`/api/members/${encodeURIComponent(login)}/roles`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, roles }),
  })
  if (!response.ok) {
    throw new Error(`${response.status}`)
  }
  return response.json() as Promise<MemberRoleAssignment>
}

export async function createMemberLabel(name: string): Promise<string[]> {
  const response = await fetch('/api/members/labels', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  if (!response.ok) {
    throw new Error(`${response.status}`)
  }
  return response.json() as Promise<string[]>
}

export async function transitionTask(code: string, status: TaskStatus, stage: TaskStage): Promise<TaskTransitionResult> {
  const response = await fetch(`/api/tasks/${encodeURIComponent(code)}/status`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, stage }),
  })
  if (response.status !== 200 && response.status !== 409) {
    throw new Error(`${response.status}`)
  }
  const body = (await response.json()) as { status: TaskStatus; stage: TaskStage }
  return { status: body.status, stage: body.stage, synced: response.status === 200 }
}

export async function fetchIssueBody(number: string): Promise<string> {
  const payload = await fetchIssueJson<{ body: string }>(`/api/issues/${encodeURIComponent(number)}/body`)
  return payload.body ?? ''
}

export interface IssueNote {
  id: number
  author: string
  createdAt: string
  body: string
}

export async function fetchIssueNotes(number: string): Promise<IssueNote[]> {
  const payload = await fetchIssueJson<{ notes: IssueNote[] }>(
    `/api/issues/${encodeURIComponent(number)}/notes`,
  )
  return payload.notes ?? []
}

async function fetchIssueJson<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (response.status === 401) {
    const error = new Error('login')
    error.name = 'IssueReadAuthError'
    throw error
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const detail = body && typeof body.detail === 'string' ? body.detail : ''
    const error = new Error(detail || `${path} returned ${response.status}`)
    if (detail.includes('还在准备')) error.name = 'WorkspacePreparing'
    throw error
  }
  return response.json() as Promise<T>
}

export interface GitlabUser {
  id: number
  username: string
  name: string
  avatarUrl: string | null
}

export async function fetchSession(): Promise<GitlabUser | null> {
  const response = await fetch('/api/auth/me')
  if (response.status === 401) {
    return null
  }
  if (!response.ok) {
    throw new Error(`/api/auth/me returned ${response.status}`)
  }
  return response.json() as Promise<GitlabUser>
}

export async function logoutSession(): Promise<void> {
  const response = await fetch('/api/auth/logout', { method: 'POST' })
  if (!response.ok && response.status !== 204) {
    throw new Error(`/api/auth/logout returned ${response.status}`)
  }
}
