import { ref } from 'vue'
import { defineStore } from 'pinia'
import { clearLdapAccount, fetchLdapAccount, saveLdapAccount } from '@/api/client'

export const useLdapAccountStore = defineStore('ldapAccount', () => {
  const uid = ref('')

  async function load() {
    const account = await fetchLdapAccount()
    uid.value = account.uid ?? ''
  }

  async function save(next: string, password: string) {
    const account = await saveLdapAccount(next, password)
    uid.value = account.uid ?? ''
  }

  async function clear() {
    await clearLdapAccount()
    uid.value = ''
  }

  function reset() {
    uid.value = ''
  }

  return { uid, load, save, clear, reset }
})
