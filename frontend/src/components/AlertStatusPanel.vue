<script setup>
defineProps({
  /** Rows from /api/alerts/status. */
  rules: { type: Array, default: () => [] },
})

// The three lifecycle positions, mapped to a label and a colour. `pending` is
// shown distinctly on purpose: it is the state that exists only because a
// single spike is not worth notifying anyone about.
const STATUS = {
  ok: { label: '正常', className: 'ok' },
  pending: { label: '待确认', className: 'pending' },
  firing: { label: '告警中', className: 'firing' },
}

function describe(rule) {
  const bound = rule.sigmaMultiplier
    ? `均值 + ${rule.sigmaMultiplier}σ`
    : `${rule.operator === 'gt' ? '>' : '<'} ${rule.threshold}`
  return bound
}

function status(rule) {
  return STATUS[rule.status] ?? STATUS.ok
}
</script>

<template>
  <div class="panel">
    <h2>告警规则状态</h2>
    <table v-if="rules.length > 0">
      <thead>
        <tr>
          <th>指标</th>
          <th>判定</th>
          <th>状态</th>
          <th class="num">连续</th>
          <th class="num">最新值</th>
          <th class="num">最近告警</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="rule in rules" :key="rule.id">
          <td class="mono">{{ rule.metricName }}</td>
          <td class="mono dim">{{ describe(rule) }}</td>
          <td>
            <span class="badge" :class="status(rule).className">{{ status(rule).label }}</span>
          </td>
          <td class="num mono">
            {{ rule.consecutive }}<span class="dim">/{{ rule.minConsecutive }}</span>
          </td>
          <td class="num mono">{{ rule.lastValue === null ? '—' : rule.lastValue.toFixed(1) }}</td>
          <td class="num mono dim">
            {{ rule.lastFiredAt ? new Date(rule.lastFiredAt).toLocaleTimeString() : '从未' }}
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">还没有配置任何规则。</p>
    <p class="hint">
      “连续”是当前连续超阈值的次数，达到“判定”要求的次数才会真正告警。
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
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
th {
  text-align: left;
  color: #8b949e;
  font-weight: 500;
  padding: 6px 8px;
  border-bottom: 1px solid #30363d;
}
td {
  padding: 7px 8px;
  border-bottom: 1px solid #21262d;
  color: #e6edf3;
}
tr:last-child td {
  border-bottom: none;
}
.num {
  text-align: right;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.dim {
  color: #8b949e;
}
.badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.badge.ok {
  background: rgba(63, 185, 80, 0.15);
  color: #3fb950;
}
.badge.pending {
  background: rgba(210, 153, 34, 0.15);
  color: #d29922;
}
.badge.firing {
  background: rgba(248, 81, 73, 0.15);
  color: #f85149;
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
