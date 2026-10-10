<script setup lang="ts">
import SkillBoard from '@/components/SkillBoard.vue'
</script>

<template>
  <main class="skill-page">
    <header class="head">
      <h1>Skill</h1>
      <p>当前 workspace 的 skills 目录。</p>
    </header>
    <SkillBoard />
  </main>
</template>

<style scoped>
.skill-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.head {
  flex: none;
}

.head h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.head p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 14px;
}

@media (max-width: 800px) {
  .head h1 {
    font-size: 22px;
  }
}
</style>
