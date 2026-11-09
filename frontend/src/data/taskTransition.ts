import type { InjectionKey } from 'vue'
import type { TaskStage, TaskStatus } from '@/data/tasks'

export interface TaskTransitionResult {
  status: TaskStatus
  stage: TaskStage
  synced: boolean
  releaseOn: string | null
}

export const taskTransitionKey: InjectionKey<
  (
    code: string,
    status: TaskStatus,
    stage: TaskStage,
    releaseOn?: string | null,
    owners?: string[],
  ) => Promise<TaskTransitionResult>
> = Symbol('taskTransition')
