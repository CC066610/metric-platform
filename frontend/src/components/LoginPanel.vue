<script setup>
import { ref } from 'vue'
import { fetchMetricNames } from '../api'
import { signIn, signOut } from '../auth'

const emit = defineEmits(['signed-in'])

const username = ref('dashboard')
const password = ref('')
const failure = ref(null)
const busy = ref(false)

/**
 * Verify the credential before showing the dashboard.
 *
 * A mistyped password should produce one clear message on this form, not a
 * dashboard full of individually failing panels. Making a real read the proof
 * means this check cannot drift away from what the dashboard actually needs.
 */
async function submit() {
  if (busy.value) {
    return
  }
  busy.value = true
  failure.value = null
  try {
    signIn(username.value, password.value)
    await fetchMetricNames()
    emit('signed-in')
  } catch (error) {
    signOut()
    failure.value = error.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="gate">
    <form class="panel" @submit.prevent="submit">
      <h1>指标监控看板</h1>
      <p class="hint">
        读取指标和管理告警规则需要运维账号。采集器用的是另一套 API key，不从这里登录。
      </p>

      <label>
        <span>用户名</span>
        <input v-model="username" autocomplete="username" />
      </label>

      <label>
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" autofocus />
      </label>

      <p v-if="failure" class="error">{{ failure }}</p>

      <button type="submit" :disabled="busy">{{ busy ? '校验中…' : '登录' }}</button>

      <p class="note">
        密码由后端生成时会在启动日志里打印一次；设置
        <code>DASHBOARD_USER</code> / <code>DASHBOARD_PASSWORD</code> 可固定下来。
      </p>
    </form>
  </div>
</template>

<style scoped>
.gate {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
}
.panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  max-width: 380px;
  background: #161b22;
  border: 1px solid #30363d;
  border-radius: 8px;
  padding: 24px;
}
h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #e6edf3;
}
.hint {
  margin: 0;
  color: #8b949e;
  font-size: 12px;
  line-height: 1.6;
}
label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: #8b949e;
}
input {
  background: #0d1117;
  color: #e6edf3;
  border: 1px solid #30363d;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
}
input:focus {
  outline: none;
  border-color: #1f6feb;
}
button {
  background: #1f6feb;
  color: #ffffff;
  border: none;
  border-radius: 6px;
  padding: 9px 14px;
  font-size: 13px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
  cursor: default;
}
.error {
  margin: 0;
  background: rgba(248, 81, 73, 0.12);
  color: #f85149;
  border: 1px solid rgba(248, 81, 73, 0.3);
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
}
.note {
  margin: 0;
  color: #6e7681;
  font-size: 11px;
  line-height: 1.6;
}
code {
  background: #21262d;
  padding: 1px 5px;
  border-radius: 4px;
}
</style>
