<script setup>
import { onMounted, ref, watch } from 'vue'
import Dashboard from './components/Dashboard.vue'
import LoginPanel from './components/LoginPanel.vue'
import { fetchMetricNames } from './api'
import { credentials } from './auth'

/**
 * Whether a credential has been proven against the API.
 *
 * Deliberately not the same as "a credential string exists": a mistyped
 * password must not flash the dashboard before the first request comes back
 * 401, so this flips only after a real read has succeeded.
 */
const signedIn = ref(false)

// A credential restored from sessionStorage is unverified, so re-check it on
// load rather than either trusting it or forcing a fresh login every reload.
const checking = ref(false)

onMounted(async () => {
  if (!credentials.value) {
    return
  }
  checking.value = true
  try {
    await fetchMetricNames()
    signedIn.value = true
  } catch {
    // The API client has already cleared the credential; the login panel stays.
  } finally {
    checking.value = false
  }
})

// A 401 anywhere in the dashboard clears the shared credential, and this is what
// puts the login panel back without a reload.
watch(credentials, (value) => {
  if (!value) {
    signedIn.value = false
  }
})
</script>

<template>
  <p v-if="checking" class="checking">正在校验登录状态…</p>
  <Dashboard v-else-if="signedIn" />
  <LoginPanel v-else @signed-in="signedIn = true" />
</template>

<style scoped>
.checking {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  margin: 0;
  color: #8b949e;
  font-size: 13px;
}
</style>
