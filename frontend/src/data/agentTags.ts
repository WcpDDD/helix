export const agentTagGroups = [
  {
    id: 'global',
    label: '全局',
    tags: [
      { id: 'manager', hint: '协调各阶段需要谁参与，只给建议，不推动状态' },
      { id: 'arch', hint: '拆解中大型任务，管理并行契约，并审契约破坏' },
    ],
  },
  {
    id: 'proposal',
    label: '提案',
    tags: [
      { id: 'proposal', hint: '起草提案' },
      { id: 'proposal-review', hint: '审阅提案' },
    ],
  },
  {
    id: 'develop',
    label: '开发',
    tags: [
      { id: 'developer', hint: '实现' },
      { id: 'reviewer', hint: '审阅实现' },
    ],
  },
  {
    id: 'test',
    label: '测试',
    tags: [{ id: 'tester', hint: '执行测试' }],
  },
] as const

export const agentTags = agentTagGroups.flatMap((group) => group.tags.map((tag) => tag.id))

export type AgentTag = (typeof agentTags)[number]
