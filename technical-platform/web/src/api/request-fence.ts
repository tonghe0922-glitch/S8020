export interface RequestFence {
  next: () => number
  isCurrent: (requestId: number) => boolean
}

export function createRequestFence(): RequestFence {
  let current = 0
  return {
    next() {
      current += 1
      return current
    },
    isCurrent(requestId) {
      return requestId === current
    },
  }
}
