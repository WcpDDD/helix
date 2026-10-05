<script setup lang="ts">
import { useProjectStore } from '@/stores/projects'

const store = useProjectStore()
</script>

<template>
  <main class="page">
    <p class="eyebrow">研发进度</p>
    <h1>正在推进的项目</h1>
    <p class="lede">人和 AI 看同一条进度线。目标由人定，进展写回前必须经过确认。</p>

    <p v-if="store.apiStatus === 'down'" class="note">
      后端没有响应。在 backend 目录执行 <code>./mvnw spring-boot:run</code> 后刷新页面。
    </p>

    <ul v-else class="cards">
      <li v-for="project in store.projects" :key="project.id" class="card">
        <header>
          <h2>{{ project.name }}</h2>
          <span class="pill" :class="{ pending: project.status !== '进行中' }">{{ project.status }}</span>
        </header>
        <p>{{ project.goal }}</p>
        <div class="meter" role="meter" :aria-valuenow="project.progress" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: `${project.progress}%` }" />
        </div>
        <div class="meta">
          <span>{{ project.owner }}</span>
          <span>{{ project.progress }}%</span>
        </div>
      </li>
    </ul>
  </main>
</template>
