<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const authError = computed(() => {
  const code = route.query.auth_error
  if (code === 'not_configured') {
    return '还没有配置 GitLab 应用。设置 GITLAB_CLIENT_ID 和 GITLAB_CLIENT_SECRET 后再登录。'
  }
  if (code === 'denied') {
    return 'GitLab 没有完成授权。'
  }
  if (code === 'exchange') {
    return 'GitLab 登录没有完成，请再试一次。'
  }
  return ''
})
</script>

<template>
  <main class="gate">
    <section class="gate-card">
      <div class="gate-brand">
        <svg width="28" height="28" viewBox="0 0 22 22" aria-hidden="true">
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
        <span>Helix</span>
      </div>
      <h1>登录</h1>
      <p>用 GitLab 账号进入任务看板。</p>
      <p v-if="authError" class="gate-error">{{ authError }}</p>
      <a class="gate-button" href="/api/auth/gitlab">用 GitLab 登录</a>
    </section>
  </main>
</template>

<style scoped>
.gate {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.gate-card {
  width: min(420px, 100%);
  padding: 36px 32px 32px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--surface);
}

.gate-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 650;
  letter-spacing: 0.04em;
}

.gate-brand svg {
  display: block;
}

h1 {
  margin: 28px 0 8px;
  font-size: 32px;
  font-weight: 650;
}

p {
  margin: 0;
  color: var(--muted);
}

.gate-error {
  margin-top: 16px;
  color: var(--danger);
}

.gate-button {
  display: block;
  margin-top: 28px;
  padding: 12px 16px;
  border-radius: 999px;
  background: var(--accent);
  color: #fffcf7;
  text-align: center;
}
</style>
