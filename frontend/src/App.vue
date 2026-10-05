<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useProjectStore } from '@/stores/projects'

const store = useProjectStore()

const statusLabel = computed(() => {
  if (store.apiStatus === 'ok') return '后端已连接'
  if (store.apiStatus === 'down') return '后端未启动'
  return '正在连接'
})

onMounted(() => {
  store.load()
})
</script>

<template>
  <div class="shell">
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
        <RouterLink to="/">进度</RouterLink>
        <RouterLink to="/collab">协同</RouterLink>
      </nav>
      <span class="status" :class="store.apiStatus">{{ statusLabel }}</span>
    </header>
    <RouterView />
  </div>
</template>
