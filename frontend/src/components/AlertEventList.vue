<script setup>
defineProps({
  /** Rows from /api/alerts/events, newest first. */
  events: { type: Array, default: () => [] },
})

const KIND = {
  FIRING: { label: '触发', className: 'firing' },
  RESOLVED: { label: '恢复', className: 'resolved' },
}

function kind(event) {
  return KIND[event.kind] ?? KIND.FIRING
}
</script>

<template>
  <div class="panel">
    <h2>告警历史</h2>
    <ul v-if="events.length > 0" class="list">
      <li v-for="event in events" :key="`${event.ruleId}-${event.at}`">
        <span class="badge" :class="kind(event).className">{{ kind(event).label }}</span>
        <span class="mono metric">{{ event.metricName }}</span>
        <span class="mono value">{{ event.observed.toFixed(1) }}</span>
        <span class="time">{{ new Date(event.at).toLocaleString() }}</span>
      </li>
    </ul>
    <p v-else class="empty">还没有产生任何告警。</p>
    <p class="hint">
      触发和恢复都会记一条，所以能看出一次故障持续了多久。
    </p>
  </div>
</template>

<style scoped>
.panel {
  background: #161b22;
  border: 1px solid #30363d;
  border-radius: 8px;
  padding: 14px 16px;
}
h2 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #e6edf3;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
}
li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid #21262d;
  font-size: 13px;
}
li:last-child {
  border-bottom: none;
}
.badge {
  flex: none;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.badge.firing {
  background: rgba(248, 81, 73, 0.15);
  color: #f85149;
}
.badge.resolved {
  background: rgba(63, 185, 80, 0.15);
  color: #3fb950;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #e6edf3;
}
.metric {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.value {
  color: #d29922;
}
.time {
  color: #8b949e;
  font-size: 12px;
}
.empty {
  color: #8b949e;
  font-size: 13px;
  margin: 8px 0;
}
.hint {
  margin: 10px 0 0;
  color: #6e7681;
  font-size: 12px;
}
</style>
