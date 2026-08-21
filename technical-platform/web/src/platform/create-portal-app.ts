import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '../styles.css'
import { createPortalRouter } from '../router'
import { usePortalSessionStore } from '../session'
import PortalRuntimeRoot from './PortalRuntimeRoot.vue'
import type { PortalDefinition } from './portal-config'
import { recordRuntimeError } from './runtime-error-state'

export function createPortalApp(portal: PortalDefinition) {
  const app = createApp(PortalRuntimeRoot)
  const pinia = createPinia()
  app.use(pinia)
  const session = usePortalSessionStore(pinia)
  const router = createPortalRouter(portal, session)
  app.config.errorHandler = recordRuntimeError
  app.use(router)
  app.mount('#app')
}
