import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { createProject, fetchCurrentProject, fetchProjects, selectProject, type Project } from '@/api/client'

export const useProjectStore = defineStore('projects', () => {
  const projects = ref<Project[]>([])
  const currentId = ref<number | null>(null)
  const ready = ref(false)
  const current = computed(() => projects.value.find((item) => item.id === currentId.value) ?? null)

  async function load() {
    try {
      const [listed, active] = await Promise.all([fetchProjects(), fetchCurrentProject()])
      projects.value = listed
      currentId.value = active.id
      if (!listed.some((item) => item.id === active.id)) {
        projects.value = [...listed, active]
      }
    } catch {
      projects.value = []
      currentId.value = null
    } finally {
      ready.value = true
    }
  }

  async function use(id: number) {
    const project = await selectProject(id)
    currentId.value = project.id
    const index = projects.value.findIndex((item) => item.id === project.id)
    if (index >= 0) projects.value[index] = project
    else projects.value = [...projects.value, project]
    return project
  }

  async function add(name: string, path: string) {
    const project = await createProject(name, path)
    if (!projects.value.some((item) => item.id === project.id)) {
      projects.value = [...projects.value, project]
    }
    currentId.value = project.id
    return project
  }

  function replace(project: Project) {
    const index = projects.value.findIndex((item) => item.id === project.id)
    if (index >= 0) projects.value[index] = project
    if (currentId.value === project.id) currentId.value = project.id
  }

  return { projects, currentId, current, ready, load, use, add, replace }
})
