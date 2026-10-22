export interface ProposalKindDraft {
  id: 'bug' | 'tech' | 'feature'
  label: string
  fields: string[]
}

export const proposalKinds: ProposalKindDraft[] = [
  {
    id: 'bug',
    label: 'bug',
    fields: ['复现步骤', '期望行为', '实际行为', '环境', '影响面'],
  },
  {
    id: 'tech',
    label: '技术变更',
    fields: ['背景', '备选方案', '决策框架', '取舍', '影响面', '验证方式'],
  },
  {
    id: 'feature',
    label: 'feature',
    fields: ['背景', '核心用户故事', '业务交互流程', '做什么', '怎么做', '预期效果', '交互边界'],
  },
]
