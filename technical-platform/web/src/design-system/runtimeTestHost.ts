import {
  createRenderer,
  defineComponent,
  h,
  markRaw,
  nextTick,
  reactive,
  type Component,
  type VNodeChild,
} from 'vue'

type RuntimeNode = RuntimeElement | RuntimeText | RuntimeComment
export type RuntimeSlots = Record<string, () => VNodeChild>

type RuntimeEventHandler = (event: unknown) => void

class RuntimeText {
  readonly kind = 'text'
  parent: RuntimeElement | null = null

  constructor(public value: string) {}
}

class RuntimeComment {
  readonly kind = 'comment'
  parent: RuntimeElement | null = null

  constructor(public value: string) {}
}

interface RuntimeDocumentState {
  activeElement: RuntimeElement | null
}

const runtimeDocument: RuntimeDocumentState = { activeElement: null }
const ssrContextKey = Symbol.for('v-scx')

export class RuntimeElement {
  readonly kind = 'element'
  readonly props = new Map<string, unknown>()
  readonly children: RuntimeNode[] = []
  parent: RuntimeElement | null = null
  directText = ''
  connected = true

  constructor(public readonly tag: string) {
    markRaw(this)
  }

  get tagName(): string {
    return this.tag.toUpperCase()
  }

  get isConnected(): boolean {
    return this.connected
  }

  get textContent(): string {
    const childText = this.children.map((child) => nodeText(child)).join('')
    return `${this.directText}${childText}`
  }

  get tabIndex(): number {
    const explicit = this.props.get('tabindex') ?? this.props.get('tabIndex')
    if (typeof explicit === 'number') return explicit
    if (typeof explicit === 'string' && explicit.trim() !== '') return Number(explicit)
    return this.isNativeFocusable() ? 0 : -1
  }

  hasAttribute(name: string): boolean {
    const value = this.props.get(name)
    return value !== undefined && value !== null && value !== false
  }

  getAttribute(name: string): string | null {
    const value = this.props.get(name)
    if (value === undefined || value === null || value === false) return null
    if (value === true) return ''
    return String(value)
  }

  getAttributeNames(): string[] {
    return Array.from(this.props.keys()).filter((name) => this.hasAttribute(name))
  }

  focus(_options?: FocusOptions): void {
    runtimeDocument.activeElement = this
  }

  querySelectorAll<T extends Element = Element>(_selectors: string): T[] {
    return collectElements(this)
      .filter((element) => element !== this && element.isFocusable()) as unknown as T[]
  }

  private isNativeFocusable(): boolean {
    if (this.hasAttribute('disabled')) return false
    if (this.tag === 'a') return this.hasAttribute('href')
    return ['button', 'input', 'select', 'textarea'].includes(this.tag)
  }

  private isFocusable(): boolean {
    return !this.hasAttribute('disabled') && this.tabIndex >= 0
  }
}

function nodeText(node: RuntimeNode): string {
  if (node.kind === 'element') return node.textContent
  return node.value
}

function collectElements(root: RuntimeElement): RuntimeElement[] {
  const elements: RuntimeElement[] = [root]
  for (const child of root.children) {
    if (child.kind === 'element') elements.push(...collectElements(child))
  }
  return elements
}

function connectNode(node: RuntimeNode, connected: boolean): void {
  if (node.kind !== 'element') return
  node.connected = connected
  for (const child of node.children) connectNode(child, connected)
}

function insertNode(child: RuntimeNode, parent: RuntimeElement, anchor: RuntimeNode | null = null): void {
  if (child.parent) {
    const oldIndex = child.parent.children.indexOf(child)
    if (oldIndex >= 0) child.parent.children.splice(oldIndex, 1)
  }
  child.parent = parent
  connectNode(child, parent.connected)
  const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1
  if (anchorIndex >= 0) parent.children.splice(anchorIndex, 0, child)
  else parent.children.push(child)
}

function removeNode(child: RuntimeNode): void {
  const parent = child.parent
  if (parent) {
    const index = parent.children.indexOf(child)
    if (index >= 0) parent.children.splice(index, 1)
  }
  child.parent = null
  connectNode(child, false)
}

const renderer = createRenderer<RuntimeNode, RuntimeElement>({
  patchProp(element, key, _previousValue, nextValue) {
    if (nextValue === undefined || nextValue === null || nextValue === false) element.props.delete(key)
    else element.props.set(key, nextValue)
  },
  insert: insertNode,
  remove: removeNode,
  createElement: (tag) => new RuntimeElement(tag),
  createText: (text) => new RuntimeText(text),
  createComment: (text) => new RuntimeComment(text),
  setText(node, text) {
    if (node.kind === 'element') node.directText = text
    else node.value = text
  },
  setElementText(element, text) {
    element.children.splice(0, element.children.length)
    element.directText = text
  },
  parentNode: (node) => node.parent,
  nextSibling(node) {
    const parent = node.parent
    if (!parent) return null
    const index = parent.children.indexOf(node)
    return index >= 0 ? parent.children[index + 1] ?? null : null
  },
  setScopeId(element, id) {
    element.props.set(id, '')
  },
  insertStaticContent(content, parent, anchor) {
    const node = new RuntimeText(content)
    insertNode(node, parent, anchor)
    return [node, node]
  },
})

export interface RuntimeMount {
  root: RuntimeElement
  setProps: (nextProps: Record<string, unknown>) => Promise<void>
  unmount: () => void
}

export function installRuntimeHost(): void {
  runtimeDocument.activeElement = null
  Object.defineProperty(globalThis, 'HTMLElement', {
    configurable: true,
    value: RuntimeElement,
  })
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: runtimeDocument,
  })
}

export function mountRuntime(
  component: Component,
  initialProps: Record<string, unknown> = {},
  slots: RuntimeSlots = {},
): RuntimeMount {
  installRuntimeHost()
  const root = new RuntimeElement('test-root')
  const props = reactive<Record<string, unknown>>({ ...initialProps })
  const Host = defineComponent({
    setup: () => () => h(component, props, slots),
  })
  const app = renderer.createApp(Host)
  app.provide(ssrContextKey, { modules: new Set<string>() })
  app.mount(root)

  return {
    root,
    async setProps(nextProps) {
      Object.assign(props, nextProps)
      await settleRuntime()
    },
    unmount() {
      app.unmount()
    },
  }
}

export async function settleRuntime(): Promise<void> {
  await nextTick()
  await nextTick()
}

export function runtimeActiveElement(): RuntimeElement | null {
  return runtimeDocument.activeElement
}

export function setRuntimeActiveElement(element: RuntimeElement | null): void {
  runtimeDocument.activeElement = element
}

export function createExternalFocusable(id: string): RuntimeElement {
  const element = new RuntimeElement('button')
  element.props.set('id', id)
  return element
}

export function findRuntimeElement(
  root: RuntimeElement,
  predicate: (element: RuntimeElement) => boolean,
): RuntimeElement {
  const found = collectElements(root).find(predicate)
  if (!found) throw new Error('runtime element not found')
  return found
}

export function findRuntimeElements(
  root: RuntimeElement,
  predicate: (element: RuntimeElement) => boolean,
): RuntimeElement[] {
  return collectElements(root).filter(predicate)
}

export function findByClass(root: RuntimeElement, className: string): RuntimeElement {
  return findRuntimeElement(root, (element) => {
    const classes = String(element.props.get('class') ?? '').split(/\s+/)
    return classes.includes(className)
  })
}

export function findByTag(root: RuntimeElement, tag: string): RuntimeElement {
  return findRuntimeElement(root, (element) => element.tag === tag)
}

export function findAllByTag(root: RuntimeElement, tag: string): RuntimeElement[] {
  return findRuntimeElements(root, (element) => element.tag === tag)
}

export function triggerRuntimeEvent(element: RuntimeElement, eventName: string, event: unknown): void {
  const normalized = `on${eventName.charAt(0).toUpperCase()}${eventName.slice(1)}`
  const handler = element.props.get(normalized)
  if (typeof handler === 'function') {
    ;(handler as RuntimeEventHandler)(event)
    return
  }
  if (Array.isArray(handler)) {
    for (const item of handler) {
      if (typeof item === 'function') (item as RuntimeEventHandler)(event)
    }
  }
}

export function runtimeKeyEvent(key: string, shiftKey = false): {
  event: KeyboardEvent
  wasPrevented: () => boolean
} {
  let prevented = false
  const event = {
    key,
    shiftKey,
    preventDefault: () => {
      prevented = true
    },
  } as unknown as KeyboardEvent
  return { event, wasPrevented: () => prevented }
}
