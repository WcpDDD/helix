<script setup lang="ts">
import StatusFlow from '@/components/StatusFlow.vue'
import type { Task } from '@/data/tasks'

defineProps<{
  task: Task
  variant: 'row' | 'panel'
}>()
</script>

<template>
  <StatusFlow
    :issue-id="task.code"
    :status="task.status"
    :stage="task.stage"
    :owners="task.owners ?? []"
    :locked="false"
    :variant="variant"
  />
</template>
