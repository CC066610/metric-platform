import { ref } from 'vue'

/**
 * The operator credential, shared as a reactive value.
 *
 * Kept in Vue state rather than only in storage so the dashboard can react to
 * losing it: when a request comes back 401 the API client clears the value and
 * the login panel replaces the dashboard, with no navigation and no reload.
 *
 * It lives in sessionStorage, so it dies with the tab. That does not protect it
 * from script running on this origin, which is the reason the dashboard loads no
 * third-party or inline scripts.
 */
const STORAGE_KEY = 'metric-platform.credentials'

function readStored() {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    // Storage is unavailable in some privacy modes. The dashboard then works for
    // as long as the tab lives, but the credential does not survive a reload.
    return null
  }
}

export const credentials = ref(readStored())

function write(value) {
  try {
    if (value === null) {
      sessionStorage.removeItem(STORAGE_KEY)
    } else {
      sessionStorage.setItem(STORAGE_KEY, value)
    }
  } catch {
    // Deliberately ignored: the in-memory value is what the app reads.
  }
}

/**
 * @returns {string|null} value for the Authorization header, or null when signed out
 */
export function authorizationHeader() {
  return credentials.value ? `Basic ${credentials.value}` : null
}

/**
 * @param {string} username operator name
 * @param {string} password operator password
 */
export function signIn(username, password) {
  credentials.value = btoa(`${username}:${password}`)
  write(credentials.value)
}

export function signOut() {
  credentials.value = null
  write(null)
}
