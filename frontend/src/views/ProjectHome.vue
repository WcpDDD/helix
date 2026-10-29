<script setup lang="ts">
import { computed } from 'vue'
import { useProjectStore } from '@/stores/projects'

const projects = useProjectStore()
const project = computed(() => projects.current?.name || '当前项目')
</script>

<template>
  <main class="home">
    <header class="head">
      <h1>{{ project }}</h1>
    </header>
    <section class="thread" aria-label="接入">
      <p>还没有。</p>
    </section>
    <form class="composer" @submit.prevent>
      <label class="field">
        <span class="sr">问题</span>
        <textarea rows="2" placeholder="写下要处理的问题" disabled />
      </label>
      <button type="button" disabled>还没接上</button>
    </form>
  </main>
</template>

<style scoped>
.home {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: min(720px, 100%);
  min-height: 0;
  margin: 0 auto;
}

.head {
  flex: none;
}

h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.thread {
  display: grid;
  flex: 1;
  place-items: center;
  min-height: 0;
  margin: 28px 0 16px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--surface);
  color: var(--muted);
}

.thread p {
  margin: 0;
}

.composer {
  display: grid;
  flex: none;
  gap: 10px;
  margin-bottom: 8px;
}

.field {
  display: block;
}

.sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

textarea {
  width: 100%;
  resize: none;
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 14px 16px;
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  line-height: 1.5;
}

.composer button {
  justify-self: end;
  border: 0;
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--line);
  color: var(--muted);
}
</style>
