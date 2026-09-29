<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import TimeSeriesChart from './components/TimeSeriesChart.vue'
import AlertStatusPanel from './components/AlertStatusPanel.vue'
import AlertEventList from './components/AlertEventList.vue'
import IngestionStatusCard from './components/IngestionStatusCard.vue'
import {
  fetchAlertEvents,
  fetchAlertStatus,
  fetchIngestionStatus,
  fetchMetricNames,
  fetchPoints,
  fetchSeries,
} from './api'
import { usePolling } from './usePolling'

// Range presets. The bucket width is not sent: the backend derives it from the
// span so that every view returns a comparable number of points.
const RANGES = [
  { key: '1h', label: '1 小时', ms: 60 * 60 * 1000 },
  { key: '6h', label: '6 小时', ms: 6 * 60 * 60 * 1000 },
  { key: '24h', label: '24 小时', ms: 24 * 60 * 60 * 1000 },
  { key: '7d', label: '7 天', ms: 7 * 24 * 60 * 60 * 1000 },
]

const selectedMetric = ref('')
const selectedRange = ref('6h')
const lastUpdated = ref(null)

function window() {
  const range = RANGES.find((entry) => entry.key === selectedRange.value) ?? RANGES[1]
  const to = new Date()
  const from = new Date(to.getTime() - range.ms)
  return { from: from.toISOString(), to: to.toISOString() }
}

const metrics = usePolling(fetchMetricNames, 30000)
const points = usePolling(fetchPoints, 10000)
const alerts = usePolling(fetchAlertStatus, 5000)
const events = usePolling(() => fetchAlertEvents(20), 5000)
const ingestion = usePolling(fetchIngestionStatus, 10000)

const series = ref([])
const seriesError = ref(null)
const seriesLoading = ref(false)

async function loadSeries() {
  if (selectedMetric.value === '') {
    series.value = []
    return
  }
  seriesLoading.value = true
  try {
    const { from, to } = window()
    series.value = await fetchSeries(selectedMetric.value, from, to)
    seriesError.value = null
    lastUpdated.value = new Date()
  } catch (failure) {
    // Hold the previous series: one failed poll should annotate, not blank out.
    seriesError.value = failure.message
  } finally {
    seriesLoading.value = false
  }
}

// Pick a default metric as soon as the list arrives, but never override a
// choice the user already made.
watch(
  () => metrics.data.value,
  (list) => {
    if (selectedMetric.value === '' && Array.isArray(list) && list.length > 0) {
      selectedMetric.value = list[0].name
    }
  },
  { immediate: true },
)

watch([selectedMetric, selectedRange], loadSeries, { immediate: true })

// Poll the series on the same cadence as the alert state, so the chart and the
// rule panel never disagree about what just happened. The interval is cleared
// on teardown; a polling timer that outlives its component keeps fetching and
// writing into a ref nobody renders.
const seriesTimer = setInterval(loadSeries, 5000)
onBeforeUnmount(() => clearInterval(seriesTimer))

const totalPoints = computed(() => points.data.value?.points ?? null)

const metricOptions = computed(() =>
  Array.isArray(metrics.data.value) ? metrics.data.value : [],
)

const chartTitle = computed(() => selectedMetric.value || '选择一个指标')

const rangeLabel = computed(
  () => RANGES.find((entry) => entry.key === selectedRange.value)?.label ?? '',
)

// Passed to the chart so it can tell "sparse data" apart from "short range".
const requestedHours = computed(
  () => (RANGES.find((entry) => entry.key === selectedRange.value)?.ms ?? 0) / 3_600_000,
)

function bucketCount() {
  return series.value.length
}
</script>

<template>
  <div class="page">
    <header class="head">
      <div class="title">
        <h1>指标监控看板</h1>
        <p class="sub">
          每 5 秒轮询一次 · 当前范围 {{ rangeLabel }} · 返回 {{ bucketCount() }} 个聚合桶
        </p>
      </div>
      <div class="head-right">
        <span v-if="totalPoints !== null" class="points">
          累计 <b>{{ totalPoints.toLocaleString() }}</b> 个数据点
        </span>
        <span v-if="lastUpdated" class="stamp">
          更新于 {{ lastUpdated.toLocaleTimeString() }}
        </span>
      </div>
    </header>

    <section class="toolbar">
      <label class="field">
        <span>指标</span>
        <select v-model="selectedMetric">
          <option v-for="metric in metricOptions" :key="metric.name" :value="metric.name">
            {{ metric.name }}（{{ metric.points.toLocaleString() }} 点）
          </option>
        </select>
      </label>

      <div class="field">
        <span>时间范围</span>
        <div class="segmented">
          <button
            v-for="range in RANGES"
            :key="range.key"
            type="button"
            :class="{ active: selectedRange === range.key }"
            @click="selectedRange = range.key"
          >
            {{ range.label }}
          </button>
        </div>
      </div>

      <button class="refresh" type="button" :disabled="seriesLoading" @click="loadSeries">
        {{ seriesLoading ? '加载中…' : '立即刷新' }}
      </button>
    </section>

    <p v-if="seriesError" class="banner error">
      图表数据加载失败：{{ seriesError }}（保留上一次的结果）
    </p>

    <section class="card chart-card">
      <TimeSeriesChart
        :series="series"
        :metric-name="chartTitle"
        :loading="seriesLoading"
        :requested-hours="requestedHours"
      />
    </section>

    <section class="grid">
      <AlertStatusPanel :rules="alerts.data.value ?? []" />
      <div class="stack">
        <IngestionStatusCard :status="ingestion.data.value" />
        <AlertEventList :events="events.data.value ?? []" />
      </div>
    </section>

    <p v-if="alerts.error.value" class="banner error">
      告警状态加载失败：{{ alerts.error.value }}
    </p>

    <footer class="foot">
      <span>后端 <code>/api/metrics/query</code> 负责分桶聚合，前端只负责画。</span>
      <span>桶宽由后端按时间跨度自动选择（60s / 300s / 3600s）。</span>
    </footer>
  </div>
</template>

<style scoped>
.page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px 24px 40px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 16px;
}
h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #e6edf3;
}
.sub {
  margin: 4px 0 0;
  color: #8b949e;
  font-size: 12px;
}
.head-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  font-size: 12px;
  color: #8b949e;
}
.points b {
  color: #58a6ff;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.toolbar {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: #8b949e;
}
select {
  background: #0d1117;
  color: #e6edf3;
  border: 1px solid #30363d;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 13px;
  min-width: 260px;
}
.segmented {
  display: inline-flex;
  border: 1px solid #30363d;
  border-radius: 6px;
  overflow: hidden;
}
.segmented button {
  background: #0d1117;
  color: #8b949e;
  border: none;
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
  border-right: 1px solid #30363d;
}
.segmented button:last-child {
  border-right: none;
}
.segmented button.active {
  background: #1f6feb;
  color: #ffffff;
}
.refresh {
  background: #21262d;
  color: #e6edf3;
  border: 1px solid #30363d;
  border-radius: 6px;
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
  margin-left: auto;
}
.refresh:disabled {
  opacity: 0.6;
  cursor: default;
}
.card {
  background: #161b22;
  border: 1px solid #30363d;
  border-radius: 8px;
}
.chart-card {
  padding: 8px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: 16px;
  align-items: start;
}
.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.banner {
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 13px;
  margin: 0 0 12px;
}
.banner.error {
  background: rgba(248, 81, 73, 0.12);
  color: #f85149;
  border: 1px solid rgba(248, 81, 73, 0.3);
}
.foot {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 22px;
  color: #6e7681;
  font-size: 12px;
}
code {
  background: #21262d;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 11px;
}
@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .refresh {
    margin-left: 0;
  }
}
</style>
