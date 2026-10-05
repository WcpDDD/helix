import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchHealth, fetchProjects, type Project } from '@/api/client'

export const useProjectStore = defineStore('projects', () => {
  const projects = ref<Project[]>([])
  const apiStatus = ref<'loading' | 'ok' | 'down'>('loading')

  async function load() {
    apiStatus.value = 'loading'
    try {
      await fetchHealth()
      projects.value = await fetchProjects()
      apiStatus.value = 'ok'
    } catch {
      projects.value = []
      apiStatus.value = 'down'
    }
  }

  return { projects, apiStatus, load }
})
