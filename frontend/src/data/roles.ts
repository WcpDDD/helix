const tones: Record<string, string> = {
  前端: 'frontend',
  后端: 'backend',
  测试: 'qa',
  产品: 'product',
  架构师: 'architect',
}

export function roleTone(role: string): string {
  return tones[role] ?? 'custom'
}
