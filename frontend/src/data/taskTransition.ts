import type { InjectionKey } from 'vue'
import type { TaskStage, TaskStatus } from '@/data/tasks'

export interface TaskTransitionResult {
  status: TaskStatus
  stage: TaskStage
  synced: boolean
}

export const taskTransitionKey: InjectionKey<
  (code: string, status: TaskStatus, stage: TaskStage) => Promise<TaskTransitionResult>
> = Symbol('taskTransition')
