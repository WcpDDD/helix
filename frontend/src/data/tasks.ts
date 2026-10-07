export type TaskStage = '提案' | '实现' | '上线'

export type TaskStatus =
  | '提案中'
  | '已驳回'
  | '实现中'
  | '审阅中'
  | '可上线'
  | '待上线池'
  | '已上线'
  | '已关闭'

export type ProposalKind = 'BUG' | '技术变更' | '小型 feature' | '大型 feature'

export type MergeRequestState = '打开' | '已合并' | '已关闭'

export interface MergeRequest {
  iid: string
  title: string
  state: MergeRequestState
  url: string
}

export interface TestEnvironment {
  name: string
  url: string
}

export interface IssueInfo {
  url: string
  kind: ProposalKind
  author: string
  allowedScope: string
  blastRadius: string
  review: string
  version: string | null
  conclusion: string
  mergeRequests: MergeRequest[]
  environments: TestEnvironment[]
}

export interface TaskIssue {
  id: string
  url: string
  title: string
  status: TaskStatus
  stage: TaskStage
  owner: string
  kind: ProposalKind
  author: string
  allowedScope: string
  blastRadius: string
  review: string
  version: string | null
  conclusion: string
  mergeRequests: MergeRequest[]
  environments: TestEnvironment[]
}

export interface IssueEdge {
  id: string
  from: string
  to: string
  needsContract: boolean
  contractRef: string | null
}

export interface Task {
  code: string
  description: string
  owner: string
  owners?: string[]
  status: TaskStatus
  stage: TaskStage
  startedAt: string
  expectedEndAt: string
  finishedAt: string | null
  issue: IssueInfo
  issues: TaskIssue[]
  edges: IssueEdge[]
}

export const stageFilters = ['全部', '提案', '实现', '上线', '已上线', '已关闭'] as const

export type StageFilter = (typeof stageFilters)[number]

export function statusTone(status: TaskStatus): string {
  if (status === '已驳回' || status === '已关闭') return 'muted'
  if (status === '已上线') return 'done'
  if (status === '审阅中' || status === '待上线池') return 'wait'
  if (status === '提案中') return 'proposal'
  return 'active'
}

const readyStatuses: TaskStatus[] = ['可上线', '待上线池', '已上线']

export function issueLayers(issues: TaskIssue[], edges: IssueEdge[]): TaskIssue[][] {
  const indegree = new Map(issues.map((issue) => [issue.id, 0]))
  const outgoing = new Map(issues.map((issue) => [issue.id, [] as string[]]))
  for (const edge of edges) {
    if (!indegree.has(edge.from) || !indegree.has(edge.to)) continue
    indegree.set(edge.to, (indegree.get(edge.to) ?? 0) + 1)
    outgoing.get(edge.from)?.push(edge.to)
  }
  const byId = new Map(issues.map((issue) => [issue.id, issue]))
  const layers: TaskIssue[][] = []
  let ready = issues.filter((issue) => indegree.get(issue.id) === 0).map((issue) => issue.id)
  const seen = new Set<string>()
  while (ready.length > 0) {
    layers.push(ready.map((id) => byId.get(id)!))
    const next: string[] = []
    for (const id of ready) {
      seen.add(id)
      for (const to of outgoing.get(id) ?? []) {
        indegree.set(to, (indegree.get(to) ?? 1) - 1)
        if (indegree.get(to) === 0) next.push(to)
      }
    }
    ready = next
  }
  const rest = issues.filter((issue) => !seen.has(issue.id))
  if (rest.length > 0) layers.push(rest)
  return layers
}

export function issueUnlocked(issue: TaskIssue, issues: TaskIssue[], edges: IssueEdge[]): boolean {
  const incoming = edges.filter((edge) => edge.to === issue.id)
  if (incoming.length === 0) return true
  const byId = new Map(issues.map((item) => [item.id, item]))
  return incoming.every((edge) => {
    const predecessor = byId.get(edge.from)
    return predecessor != null && readyStatuses.includes(predecessor.status)
  })
}
