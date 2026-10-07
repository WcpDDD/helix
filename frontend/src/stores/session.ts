import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchSession, logoutSession, type GitlabUser } from '@/api/client'

export const useSessionStore = defineStore('session', () => {
  const user = ref<GitlabUser | null>(null)
  const ready = ref(false)

  async function load() {
    try {
      user.value = await fetchSession()
    } catch {
      user.value = null
    } finally {
      ready.value = true
    }
  }

  async function logout() {
    await logoutSession()
    user.value = null
  }

  return { user, ready, load, logout }
})
