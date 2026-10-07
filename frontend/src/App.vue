<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const projects = useProjectStore()
const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const switchEl = ref<HTMLElement | null>(null)

async function signOut() {
  menuOpen.value = false
  await session.logout()
  projects.ready = false
  await router.replace({ name: 'login' })
}

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

async function pickProject(id: number) {
  menuOpen.value = false
  if (id === projects.currentId) return
  await projects.use(id)
}

function onPointerDown(event: PointerEvent) {
  if (!switchEl.value?.contains(event.target as Node)) menuOpen.value = false
}

function onKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape') menuOpen.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown)
  document.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onPointerDown)
  document.removeEventListener('keydown', onKeyDown)
})
</script>

<template>
  <RouterView v-if="route.name === 'login'" />
  <div v-else class="shell">
    <header class="topbar">
      <RouterLink class="brand" to="/">
        <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true">
          <path
            d="M4 3c4 3 4 5 0 8s-4 5 0 8"
            fill="none"
            stroke="#0f6e56"
            stroke-width="1.6"
            stroke-linecap="round"
          />
          <path
            d="M18 3c-4 3-4 5 0 8s4 5 0 8"
            fill="none"
            stroke="#1a1f1c"
            stroke-width="1.6"
            stroke-linecap="round"
          />
        </svg>
        Helix
      </RouterLink>
      <nav class="nav">
        <RouterLink to="/">任务</RouterLink>
        <RouterLink to="/project">项目</RouterLink>
        <RouterLink to="/members">成员</RouterLink>
        <RouterLink to="/collab">协同</RouterLink>
      </nav>
      <div v-if="session.user" class="bar-end">
        <div v-if="projects.current" ref="switchEl" class="project-switch">
          <button
            type="button"
            class="project-current"
            aria-haspopup="listbox"
            :aria-expanded="menuOpen"
            @click="toggleMenu"
          >
            {{ projects.current.name }}
          </button>
          <ul v-if="menuOpen" class="project-menu" role="listbox">
            <li v-for="item in projects.projects" :key="item.id">
              <button
                type="button"
                role="option"
                :aria-selected="item.id === projects.currentId"
                :class="{ on: item.id === projects.currentId }"
                @click="pickProject(item.id)"
              >
                {{ item.name }}
              </button>
            </li>
          </ul>
        </div>
        <span class="bar-rule" aria-hidden="true"></span>
        <div class="account">
          <img v-if="session.user.avatarUrl" class="avatar" :src="session.user.avatarUrl" alt="" />
          <span class="who">{{ session.user.name }}</span>
          <button type="button" class="text-button" @click="signOut">退出</button>
        </div>
      </div>
    </header>
    <RouterView />
  </div>
</template>
