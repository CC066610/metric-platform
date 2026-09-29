<script setup>
defineProps({
  /** Payload from /api/metrics/ingestion. */
  status: { type: Object, default: null },
})

// The route is what makes the write-path work visible: an operator can see that
// a 1000-point batch took COPY while a single-point ping took the batch path.
const ROUTE = {
  copy: { label: 'COPY FROM STDIN', className: 'copy' },
  batch: { label: '批量 INSERT', className: 'batch' },
}

function routeLabel(route) {
  return ROUTE[route]?.label ?? '尚无请求'
}

function routeClass(route) {
  return ROUTE[route]?.className ?? 'idle'
}
</script>

<template>
  <div class="panel" v-if="status">
    <h2>写入路径</h2>
    <div class="row">
      <span class="key">最近一次</span>
      <span class="badge" :class="routeClass(status.route)">{{ routeLabel(status.route) }}</span>
    </div>
    <div class="row">
      <span class="key">该批行数</span>
      <span class="val mono">{{ status.rows ?? '—' }}</span>
    </div>
    <div class="row">
      <span class="key">切换阈值</span>
      <span class="val mono">≥ {{ status.copyMinBatchSize }} 行</span>
    </div>
    <div class="row">
      <span class="key">覆盖开关</span>
      <span class="val mono" :class="{ forced: status.forcedPath !== 'auto' }">
        {{ status.forcedPath }}
      </span>
    </div>
    <p class="hint">
      阈值来自实测交叉点：少于 100 行时 COPY 反而更慢。覆盖开关可在不重启代码的情况下强制切换路径。
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
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 0;
  font-size: 13px;
}
.key {
  color: #8b949e;
}
.val {
  color: #e6edf3;
}
.forced {
  color: #d29922;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.badge {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.badge.copy {
  background: rgba(88, 166, 255, 0.15);
  color: #58a6ff;
}
.badge.batch {
  background: rgba(139, 148, 158, 0.15);
  color: #8b949e;
}
.badge.idle {
  background: rgba(110, 118, 129, 0.15);
  color: #6e7681;
}
.hint {
  margin: 10px 0 0;
  color: #6e7681;
  font-size: 12px;
  line-height: 1.5;
}
</style>
