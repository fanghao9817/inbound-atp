import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { FulfillmentCenter, Sku } from '@/api/types'

/** SKUs and fulfillment centers change rarely; load them once per session. */
export const useCatalogStore = defineStore('catalog', () => {
  const skus = ref<Sku[]>([])
  const fcs = ref<FulfillmentCenter[]>([])
  const loaded = ref(false)
  const error = ref<string | null>(null)

  async function load() {
    if (loaded.value) return
    try {
      ;[skus.value, fcs.value] = await Promise.all([api.skus(), api.fulfillmentCenters()])
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    }
  }

  return { skus, fcs, loaded, error, load }
})
