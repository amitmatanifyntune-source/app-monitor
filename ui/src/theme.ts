// Dark by default, as in Grafana; the choice is kept per browser.
const KEY = 'monitor.theme'
export type Theme = 'dark' | 'light'

export function theme(): Theme {
  try { return localStorage.getItem(KEY) === 'light' ? 'light' : 'dark' } catch { return 'dark' }
}

export function applyTheme(t: Theme = theme()) {
  document.documentElement.dataset.theme = t
  try { localStorage.setItem(KEY, t) } catch { /* private mode: this page only */ }
}
