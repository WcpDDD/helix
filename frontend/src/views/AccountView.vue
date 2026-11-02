<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useLdapAccountStore } from '@/stores/ldapAccount'

const account = useLdapAccountStore()
const uid = ref(account.uid)
const password = ref('')
const error = ref('')
const notice = ref('')

const set = computed(() => account.uid.length > 0)

const platforms = [
  { name: 'SkyWalking', where: '公有云', host: 'skywalking-prod.oalite.com' },
]

onMounted(async () => {
  try {
    await account.load()
    uid.value = account.uid
  }
  catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'LDAP 账号没有读到。'
  }
})

async function submit() {
  error.value = ''
  notice.value = ''
  const name = uid.value.trim()
  if (!name) {
    error.value = '先写 LDAP 用户名。'
    return
  }
  if (!password.value) {
    error.value = '先写 LDAP 密码。'
    return
  }
  try {
    await account.save(name, password.value)
    uid.value = account.uid
    password.value = ''
    notice.value = '已记下。密码不会再显示在页面上。'
  }
  catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'LDAP 账号没有记下。'
  }
}

async function wipe() {
  error.value = ''
  notice.value = ''
  try {
    await account.clear()
    uid.value = ''
    password.value = ''
    notice.value = '已清除。'
  }
  catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'LDAP 账号没有清除。'
  }
}
</script>

<template>
  <main class="account-page">
    <header class="head">
      <h1>账号</h1>
      <p>记下你的 LDAP 账号。</p>
    </header>

    <section class="card">
      <p class="state" :class="{ on: set }">{{ set ? `已设置 · ${account.uid}` : '还没设置' }}</p>
      <form @submit.prevent="submit">
        <label>
          <span>LDAP 用户名</span>
          <input v-model="uid" name="uid" autocomplete="username" placeholder="目录里的 uid" />
        </label>
        <label>
          <span>LDAP 密码</span>
          <input v-model="password" name="password" type="password" autocomplete="current-password" placeholder="只在保存时用一次" />
        </label>
        <p v-if="error" class="error">{{ error }}</p>
        <p v-else-if="notice" class="ok">{{ notice }}</p>
        <div class="actions">
          <button type="submit">保存</button>
          <button v-if="set" type="button" class="quiet" @click="wipe">清除</button>
        </div>
      </form>
    </section>

    <section class="card platforms">
      <h2>会用到这份账号的平台</h2>
      <ul>
        <li v-for="item in platforms" :key="item.host">
          <strong>{{ item.name }}</strong>
          <span>{{ item.where }}</span>
          <code>{{ item.host }}</code>
        </li>
      </ul>
      <p class="hint">每个平台各自校验。这里只保存这一份账号，不会变成一张全公司通用的票据。</p>
    </section>
  </main>
</template>

<style scoped>
.account-page {
  width: min(640px, 100%);
}

.head h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 650;
}

.head p {
  margin: 8px 0 0;
  color: var(--muted);
}

.card {
  margin-top: 24px;
  padding: 22px 22px 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.state {
  display: inline-flex;
  margin: 0 0 18px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 13px;
}

.state.on {
  background: var(--accent-soft);
  color: var(--accent);
}

label {
  display: grid;
  gap: 6px;
  margin-bottom: 14px;
}

label span {
  font-size: 13px;
  color: var(--muted);
}

input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--paper);
  color: var(--ink);
}

input:focus {
  outline: 2px solid var(--accent-soft);
  border-color: var(--accent);
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

button {
  padding: 8px 16px;
  border: 0;
  border-radius: 999px;
  background: var(--accent);
  color: white;
  cursor: pointer;
}

.quiet {
  background: transparent;
  color: var(--muted);
}

.error,
.ok {
  margin: 0 0 12px;
  font-size: 14px;
}

.error {
  color: var(--danger);
}

.ok {
  color: var(--accent);
}

.platforms h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}

.platforms ul {
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
}

.platforms li {
  display: grid;
  grid-template-columns: 120px 72px 1fr;
  gap: 12px;
  align-items: baseline;
  padding: 10px 0;
  border-top: 1px solid var(--line);
}

.platforms code {
  color: var(--muted);
  font-size: 13px;
}

.hint {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 13px;
}
</style>
