let currentController = new AbortController()

export function rotateNavigationAbortSignal(): AbortSignal {
  currentController.abort('route changed')
  currentController = new AbortController()
  return currentController.signal
}

export function currentNavigationSignal(): AbortSignal {
  return currentController.signal
}
