import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { createProject, fetchCurrentProject, fetchProjectAccess, fetchProjectWork, fetchProjects, pullProjectWork, pushProjectWork, selectProject, type Project, type WorkSnapshot } from '@/api/client'

export const useProjectStore = defineStore('projects', () => {
  const projects = ref<Project[]>([])
  const currentId = ref<number | null>(null)
  const ready = ref(false)
  const current = computed(() => projects.value.find((item) => item.id === currentId.value) ?? null)
  const access = ref('')
  const work = ref<WorkSnapshot | null>(null)
  const workError = ref('')
  const workLoading = ref(false)
  let refreshing = false

  async function refreshWork() {
    if (refreshing) return
    refreshing = true
    if (work.value == null) workLoading.value = true
    try {
      work.value = await fetchProjectWork()
      workError.value = ''
    } catch (error) {
      workError.value = error instanceof Error ? error.message : '工作目录没有读到。'
    } finally {
      workLoading.value = false
      refreshing = false
    }
  }

  async function pullWork() {
    work.value = await pullProjectWork()
    workError.value = ''
  }

  async function pushWork() {
    work.value = await pushProjectWork()
    workError.value = ''
  }

  async function loadAccess() {
    try {
      access.value = (await fetchProjectAccess()).access || ''
    } catch {
      access.value = ''
    }
  }

  async function load() {
    try {
      const [listed, active] = await Promise.all([fetchProjects(), fetchCurrentProject()])
      projects.value = listed
      currentId.value = active.id
      if (!listed.some((item) => item.id === active.id)) {
        projects.value = [...listed, active]
      }
      await loadAccess()
    } catch {
      projects.value = []
      currentId.value = null
      access.value = ''
    } finally {
      ready.value = true
    }
  }

  async function use(id: number) {
    work.value = null
    access.value = ''
    const project = await selectProject(id)
    currentId.value = project.id
    const index = projects.value.findIndex((item) => item.id === project.id)
    if (index >= 0) projects.value[index] = project
    else projects.value = [...projects.value, project]
    void refreshWork()
    void loadAccess()
    return project
  }

  async function add(name: string, path: string) {
    work.value = null
    const project = await createProject(name, path)
    if (!projects.value.some((item) => item.id === project.id)) {
      projects.value = [...projects.value, project]
    }
    currentId.value = project.id
    void refreshWork()
    void loadAccess()
    return project
  }

  function replace(project: Project) {
    const index = projects.value.findIndex((item) => item.id === project.id)
    if (index >= 0) projects.value[index] = project
    if (currentId.value === project.id) currentId.value = project.id
  }

  return { projects, currentId, current, access, ready, work, workError, workLoading, load, refreshWork, pullWork, pushWork, use, add, replace }
})
