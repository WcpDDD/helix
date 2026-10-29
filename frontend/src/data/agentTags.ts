export const agentTagGroups = [
  {
    id: 'coordinate',
    label: '调度',
    tags: [
      { id: 'manager', hint: '按 issue、MR、流程规范、产物和上一个 agent 的结果做流转。定期唤起；issue 未结束、无人机协同、没有 agent 在做时才运行' },
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
      { id: 'developer', hint: '先写技术方案。冒烟用例落盘后再实现' },
      { id: 'reviewer', hint: '对照方案、spec、实现和冒烟用例。不改东西，不执行冒烟' },
    ],
  },
  {
    id: 'test',
    label: '测试',
    tags: [{ id: 'tester', hint: '先写冒烟用例。审阅通过后跑冒烟，再写最终用例并做最终测试' }],
  },
] as const

export const agentTags = agentTagGroups.flatMap((group) => group.tags.map((tag) => tag.id))

export type AgentTag = (typeof agentTags)[number]
