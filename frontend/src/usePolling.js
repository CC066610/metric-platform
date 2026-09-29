import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Run an async loader immediately and then on a fixed interval.
 *
 * Polling rather than a push channel is a deliberate choice at this scale: the
 * backend refresh cadence is measured in seconds, so a socket would add
 * lifecycle and reconnect handling for no visible benefit.
 *
 * @param {Function} loader async function returning the value to expose
 * @param {number} intervalMs polling interval
 * @returns {{data: import('vue').Ref, error: import('vue').Ref<string|null>, loading: import('vue').Ref<boolean>, refresh: Function}}
 */
export function usePolling(loader, intervalMs = 5000) {
  const data = ref(null)
  const error = ref(null)
  const loading = ref(false)
  let timer = null

  async function refresh() {
    loading.value = true
    try {
      data.value = await loader()
      error.value = null
    } catch (failure) {
      // Keep the previous value on screen: a single failed poll should not blank
      // the dashboard, it should annotate it.
      error.value = failure.message
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    refresh()
    timer = setInterval(refresh, intervalMs)
  })

  onBeforeUnmount(() => {
    if (timer !== null) {
      clearInterval(timer)
    }
  })

  return { data, error, loading, refresh }
}
