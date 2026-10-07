<script setup lang="ts">
withDefaults(
  defineProps<{
    creator?: string
    owners?: string[]
    part?: 'all' | 'creator' | 'owners'
  }>(),
  { owners: () => [], part: 'all' },
)
</script>

<template>
  <div class="people">
    <div v-if="part !== 'owners'" class="line">
      <span v-if="part === 'all'" class="k">创建人</span>
      <span class="who">
        <span class="avatar" aria-hidden="true">{{ creator?.slice(0, 1) }}</span>
        {{ creator }}
      </span>
    </div>
    <div v-if="part !== 'creator'" class="line">
      <span v-if="part === 'all'" class="k">负责人</span>
      <span v-if="owners.length === 0" class="empty">还没有</span>
      <span v-else class="group">
        <span v-for="name in owners" :key="name" class="who">
          <span class="avatar" aria-hidden="true">{{ name.slice(0, 1) }}</span>
          {{ name }}
        </span>
      </span>
    </div>
  </div>
</template>

<style scoped>
.people {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.k,
.empty {
  color: var(--muted);
  font-size: 12px;
}

.group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.who {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
}

.avatar {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}
</style>
