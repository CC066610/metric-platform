/**
 * Thin API client. Every call throws on a non-2xx response so the caller can
 * surface one error path instead of checking status codes at each site.
 */

async function request(path) {
  const response = await fetch(path, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new Error(`${response.status} ${response.statusText} ${body}`.trim())
  }
  return response.json()
}

export function fetchMetricNames() {
  return request('/api/metrics/names')
}

export function fetchPoints() {
  return request('/api/metrics/count')
}

/**
 * Aggregated series for one metric.
 *
 * @param {string} name metric name
 * @param {string} from inclusive ISO-8601 start
 * @param {string} to exclusive ISO-8601 end
 * @returns {Promise<Array>} buckets of avg, max, min, count
 */
export function fetchSeries(name, from, to) {
  const query = new URLSearchParams({ name, from, to })
  return request(`/api/metrics/query?${query}`)
}

export function fetchAlertStatus() {
  return request('/api/alerts/status')
}

export function fetchAlertEvents(limit = 20) {
  return request(`/api/alerts/events?limit=${limit}`)
}

export function fetchIngestionStatus() {
  return request('/api/metrics/ingestion')
}
