import type { TaskStage, TaskStatus } from '@/data/tasks'

export interface Move {
  to: TaskStatus
  label: string
  tone: 'forward' | 'back' | 'stop'
  needsReason: boolean
  needsOwners: boolean
  disabledReason: string | null
}

const MOVES: Record<TaskStatus, Omit<Move, 'disabledReason'>[]> = {
  提案中: [
    { to: '实现中', label: '采纳，进入实现', tone: 'forward', needsReason: false, needsOwners: true },
    { to: '审阅中', label: '采纳，直接审阅', tone: 'forward', needsReason: false, needsOwners: true },
    { to: '已驳回', label: '驳回', tone: 'stop', needsReason: true, needsOwners: false },
    { to: '已关闭', label: '放弃', tone: 'stop', needsReason: true, needsOwners: false },
  ],
  已驳回: [],
  实现中: [
    { to: '审阅中', label: '提交审阅', tone: 'forward', needsReason: false, needsOwners: true },
    { to: '已关闭', label: '放弃', tone: 'stop', needsReason: true, needsOwners: false },
  ],
  审阅中: [
    { to: '可上线', label: '审阅通过', tone: 'forward', needsReason: false, needsOwners: false },
    { to: '实现中', label: '打回实现', tone: 'back', needsReason: true, needsOwners: false },
    { to: '已关闭', label: '放弃', tone: 'stop', needsReason: true, needsOwners: false },
  ],
  可上线: [{ to: '待上线池', label: '进入待上线池', tone: 'forward', needsReason: false, needsOwners: false }],
  待上线池: [{ to: '已上线', label: '确认上线', tone: 'forward', needsReason: false, needsOwners: false }],
  已上线: [],
  已关闭: [],
}

export function movesFor(status: TaskStatus, locked: boolean): Move[] {
  return MOVES[status].map((move) => ({
    ...move,
    disabledReason:
      locked && status === '提案中' && (move.to === '实现中' || move.to === '审阅中')
        ? '前置还没到可上线'
        : null,
  }))
}

export function stageFor(status: TaskStatus, current: TaskStage): TaskStage {
  if (status === '已关闭') return current
  if (status === '提案中' || status === '已驳回') return '提案'
  if (status === '实现中' || status === '审阅中') return '实现'
  return '上线'
}
