<script setup lang="ts" generic="T">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    items: T[]
    itemKey: (item: T) => string
    estimate?: number
    overscan?: number
    gap?: number
    pinned?: boolean
  }>(),
  {
    estimate: 128,
    overscan: 6,
    gap: 12,
    pinned: false,
  },
)

const emit = defineEmits<{
  scroll: [event: Event]
}>()

const scroller = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewport = ref(0)
const heightVersion = ref(0)
const heights = new Map<string, number>()
const rowNodes = new Map<string, Element>()
const rowObservers = new Map<string, ResizeObserver>()
let frame = 0

const layout = computed(() => {
  heightVersion.value
  const tops: number[] = []
  let total = 0
  for (const item of props.items) {
    tops.push(total)
    total += heights.get(props.itemKey(item)) ?? props.estimate
  }
  return { tops, total }
})

const visible = computed(() => {
  const { tops } = layout.value
  const items = props.items
  if (!items.length) return []
  const startY = Math.max(0, scrollTop.value - props.overscan * props.estimate)
  const endY = scrollTop.value + viewport.value + props.overscan * props.estimate
  let low = 0
  let high = tops.length
  while (low < high) {
    const mid = (low + high) >> 1
    if ((tops[mid] ?? 0) < startY) low = mid + 1
    else high = mid
  }
  const start = Math.max(0, low - 1)
  const rows: { item: T; index: number; key: string; top: number }[] = []
  for (let index = start; index < items.length; index++) {
    const top = tops[index]
    const item = items[index]
    if (top === undefined || item === undefined || top > endY) break
    rows.push({ item, index, key: props.itemKey(item), top })
  }
  return rows
})

function pinBottom() {
  const el = scroller.value
  if (!el || !props.pinned) return
  el.scrollTop = el.scrollHeight
}

function applyHeight(key: string, next: number) {
  const prev = heights.get(key) ?? props.estimate
  if (Math.abs(prev - next) < 1) return
  const index = props.items.findIndex((item) => props.itemKey(item) === key)
  const top = index < 0 ? 0 : layout.value.tops[index] ?? 0
  heights.set(key, next)
  heightVersion.value++
  const el = scroller.value
  if (!el) return
  if (props.pinned) {
    if (frame) return
    frame = requestAnimationFrame(() => {
      frame = 0
      pinBottom()
    })
    return
  }
  if (top < el.scrollTop) el.scrollTop += next - prev
}

function bindRow(key: string, el: Element | null) {
  if (rowNodes.get(key) === el) return
  rowObservers.get(key)?.disconnect()
  rowObservers.delete(key)
  if (!el) {
    rowNodes.delete(key)
    return
  }
  rowNodes.set(key, el)
  const node = el as HTMLElement
  const observer = new ResizeObserver(() => applyHeight(key, node.offsetHeight))
  observer.observe(node)
  rowObservers.set(key, observer)
}

function measureViewport() {
  viewport.value = scroller.value?.clientHeight ?? 0
}

function onScroll(event: Event) {
  scrollTop.value = (event.target as HTMLElement).scrollTop
  emit('scroll', event)
}

let viewportObserver: ResizeObserver | null = null

onMounted(() => {
  measureViewport()
  if (!scroller.value) return
  viewportObserver = new ResizeObserver(measureViewport)
  viewportObserver.observe(scroller.value)
  pinBottom()
})

watch(
  () => props.items.map((item) => props.itemKey(item)).join('\u0000'),
  (signature) => {
    const live = new Set(signature ? signature.split('\u0000') : [])
    for (const [key, observer] of rowObservers) {
      if (live.has(key)) continue
      observer.disconnect()
      rowObservers.delete(key)
      rowNodes.delete(key)
      heights.delete(key)
    }
    heightVersion.value++
    if (props.pinned) requestAnimationFrame(pinBottom)
  },
)

onUnmounted(() => {
  viewportObserver?.disconnect()
  for (const observer of rowObservers.values()) observer.disconnect()
  if (frame) cancelAnimationFrame(frame)
})

defineExpose({
  get el() {
    return scroller.value
  },
})
</script>

<template>
  <div
    ref="scroller"
    class="virtual-thread"
    :style="{ '--virtual-gap': `${gap}px` }"
    @scroll="onScroll"
  >
    <div v-if="$slots.before" class="virtual-before">
      <slot name="before" />
    </div>
    <div class="virtual-space" :style="{ height: `${layout.total}px` }">
      <div
        v-for="row in visible"
        :key="row.key"
        class="virtual-row"
        :style="{ transform: `translateY(${row.top}px)` }"
        :ref="(el) => bindRow(row.key, el as Element | null)"
      >
        <slot :item="row.item" :index="row.index" />
      </div>
    </div>
    <slot name="after" />
  </div>
</template>

<style scoped>
.virtual-thread {
  display: block;
  overflow: auto;
  min-height: 0;
}

.virtual-space {
  position: relative;
  width: 100%;
}

.virtual-row {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  padding-bottom: var(--virtual-gap);
}
</style>
