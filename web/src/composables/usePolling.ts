import { onMounted, onUnmounted } from 'vue'

/**
 * Run `fn` once on mount, then every `intervalMs` while the component is mounted - skipping ticks while
 * the tab is hidden and catching up as soon as it is visible again. One call in flight at a time.
 */
export function usePolling(fn: () => Promise<unknown>, intervalMs: number) {
  let timer: ReturnType<typeof setInterval> | undefined
  let running = false

  async function tick(force = false) {
    if (running || (!force && document.visibilityState === 'hidden')) return
    running = true
    try {
      await fn()
    } finally {
      running = false
    }
  }

  const onVisible = () => {
    if (document.visibilityState === 'visible') void tick()
  }

  onMounted(() => {
    void tick(true)
    timer = setInterval(() => void tick(), intervalMs)
    document.addEventListener('visibilitychange', onVisible)
  })
  onUnmounted(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
  })

  return { refresh: () => tick(true) }
}
