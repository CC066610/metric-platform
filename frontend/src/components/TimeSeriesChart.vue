<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
// Tree-shaken ECharts: importing the package index pulls every chart type and
// component, which tripled the bundle for one line chart. Registering only what
// this component draws keeps the payload proportional to what it renders.
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  CanvasRenderer,
])

const props = defineProps({
  /** Buckets from /api/metrics/query: { bucket, avg, max, min, count }. */
  series: { type: Array, default: () => [] },
  metricName: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  /** Requested window in hours, used to tell "sparse data" from "small range". */
  requestedHours: { type: Number, default: 0 },
})

const container = ref(null)
let chart = null

/**
 * The window the returned buckets actually cover.
 *
 * A chart drawn from sparse data looks identical to one drawn from a short
 * range. Printing the real span is what distinguishes them: if the data only
 * reaches back a few hours, the label says so instead of leaving the reader to
 * guess why the curve looks thin.
 */
const observedSpan = computed(() => {
  if (props.series.length === 0) {
    return null
  }
  const first = new Date(props.series[0].bucket)
  const last = new Date(props.series[props.series.length - 1].bucket)
  const hours = (last.getTime() - first.getTime()) / 3_600_000
  return { first, last, hours }
})

/** True when the data covers materially less than the requested window. */
const underCovered = computed(() => {
  if (observedSpan.value === null || props.requestedHours <= 0) {
    return false
  }
  return observedSpan.value.hours < props.requestedHours * 0.9
})

function shortStamp(date) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function render() {
  if (chart === null) {
    return
  }
  // ECharts takes [timestamp, value] pairs for a time axis. Sorting is the
  // backend's job; the array arrives ordered because the SQL has ORDER BY.
  const timestamps = props.series.map((bucket) => new Date(bucket.bucket).getTime())
  const bandBase = props.series.map((bucket) => bucket.min)
  const bandWidth = props.series.map((bucket) => bucket.max - bucket.min)
  const averages = props.series.map((bucket) => bucket.avg)

  const hasSpread = props.series.some((bucket) => bucket.max - bucket.min > 1e-9)

  chart.setOption({
    backgroundColor: 'transparent',
    title: {
      text: props.metricName === '' ? '选择一个指标' : props.metricName,
      subtext: hasSpread
        ? '折线为桶内平均，阴影带为桶内最大到最小的区间'
        : '桶内只有一个采样点，因此最大/最小与平均值重合（提高采集频率可见区间）',
      subtextStyle: { color: '#6e7681', fontSize: 11 },
      left: 8,
      top: 4,
      textStyle: { color: '#e6edf3', fontSize: 14, fontWeight: 500 },
    },
    grid: { left: 56, right: 24, top: 62, bottom: 56 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(22,27,34,0.95)',
      borderColor: '#30363d',
      textStyle: { color: '#e6edf3' },
      // A stacked series reports its own raw value, which for the band's upper
      // half is max minus min, not max. Reading the values back out of the
      // source array by timestamp is the only way to show the real maximum.
      formatter: (params) => {
        const first = Array.isArray(params) ? params[0] : params
        if (first === undefined) {
          return ''
        }
        const at = new Date(first.value[0])
        const bucket = props.series.find(
          (entry) => new Date(entry.bucket).getTime() === at.getTime(),
        )
        if (bucket === undefined) {
          return ''
        }
        const pad = (value) => String(value).padStart(2, '0')
        const stamp = `${pad(at.getMonth() + 1)}-${pad(at.getDate())} `
          + `${pad(at.getHours())}:${pad(at.getMinutes())}`
        return [
          `<b>${stamp}</b>`,
          `最大 &nbsp;<b>${bucket.max.toFixed(2)}</b>`,
          `平均 &nbsp;<b>${bucket.avg.toFixed(2)}</b>`,
          `最小 &nbsp;<b>${bucket.min.toFixed(2)}</b>`,
          `采样 &nbsp;${bucket.count} 点`,
        ].join('<br/>')
      },
    },
    legend: {
      right: 12,
      top: 4,
      data: ['平均'],
      textStyle: { color: '#8b949e' },
    },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: '#30363d' } },
      axisLabel: { color: '#8b949e' },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { color: '#8b949e' },
      splitLine: { lineStyle: { color: '#21262d' } },
    },
    // The max/min envelope is a shaded band rather than two more lines: as
    // lines it was hidden behind the average whenever the two coincided, and
    // a band reads as a range even when it collapses to zero width.
    //
    // Both halves need a time axis bound to the same data, because ECharts
    // would otherwise let the second hidden series extend the axis and leave
    // empty space on the right.
    series: [
      {
        name: '最小',
        type: 'line',
        data: timestamps.map((time, index) => [time, bandBase[index]]),
        stack: 'envelope',
        lineStyle: { opacity: 0 },
        areaStyle: { opacity: 0 },
        symbol: 'none',
        silent: true,
        z: 1,
      },
      {
        name: '最大',
        type: 'line',
        data: timestamps.map((time, index) => [time, bandWidth[index]]),
        stack: 'envelope',
        lineStyle: { opacity: 0 },
        areaStyle: { color: 'rgba(88, 166, 255, 0.22)' },
        symbol: 'none',
        silent: true,
        z: 1,
      },
      {
        name: '平均',
        type: 'line',
        data: timestamps.map((time, index) => [time, averages[index]]),
        showSymbol: false,
        smooth: true,
        lineStyle: { width: 2, color: '#58a6ff' },
        itemStyle: { color: '#58a6ff' },
        z: 3,
      },
    ],
    dataZoom: [
      { type: 'inside', throttle: 50 },
      { type: 'slider', height: 18, bottom: 8, borderColor: '#30363d' },
    ],
  }, { notMerge: true })
}

onMounted(() => {
  chart = echarts.init(container.value, null, { renderer: 'canvas' })
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (chart !== null) {
    chart.dispose()
    chart = null
  }
})

function resize() {
  if (chart !== null) {
    chart.resize()
  }
}

watch(() => [props.series, props.metricName], render, { deep: true })
</script>

<template>
  <div class="chart-wrap">
    <div ref="container" class="chart"></div>
    <p v-if="observedSpan" class="span" :class="{ warn: underCovered }">
      数据自 {{ shortStamp(observedSpan.first) }} 至 {{ shortStamp(observedSpan.last) }}，
      共 {{ observedSpan.hours.toFixed(1) }} 小时 · {{ series.length }} 个聚合桶
      <template v-if="underCovered">
        —— 少于所选范围，曲线上点稀疏是因为数据本身不够长，不是丢点
      </template>
    </p>
    <div v-if="series.length === 0 && !loading" class="chart-empty">
      该时间范围内没有数据。先运行 <code>python scripts/seed_dashboard.py</code>。
    </div>
  </div>
</template>

<style scoped>
.chart-wrap {
  position: relative;
  height: 400px;
}
.chart {
  width: 100%;
  height: 360px;
}
.span {
  margin: 0;
  padding: 0 8px;
  font-size: 12px;
  color: #8b949e;
}
.span.warn {
  color: #d29922;
}
.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8b949e;
  font-size: 13px;
}
</style>
