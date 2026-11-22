import type { Reading, Settings, Sim } from '../api/client'

const money = new Intl.NumberFormat('en-LK', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function formatBalance(v: number | null | undefined): string {
  return v == null ? '—' : `Rs ${money.format(v)}`
}

export function formatData(mb: number | null | undefined): string {
  if (mb == null) return '—'
  if (mb >= 1024) return `${(mb / 1024).toFixed(mb >= 10240 ? 1 : 2)} GB`
  return `${mb >= 100 ? Math.round(mb) : mb.toFixed(2)} MB`
}

/** ISO date (yyyy-mm-dd) → "13 Dec 2026". */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-GB', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'never'
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return `${Math.floor(s / 86400)} d ago`
}

/** Whole days from today (local) until an ISO date; negative when past. */
export function daysUntil(iso: string, today = new Date()): number {
  const [y, m, d] = iso.split('-').map(Number)
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((new Date(y, m - 1, d).getTime() - start.getTime()) / 86_400_000)
}

export function expiresSoon(iso: string | null | undefined, settings: Settings, today?: Date) {
  return iso != null && daysUntil(iso, today) <= settings.expiry_warn_days
}

export function isLowBalance(r: Reading | null | undefined, settings: Settings): boolean {
  return r?.balance != null && r.balance < settings.low_balance
}

/** The reading to show in the table: the latest successful one, whatever happened since. */
export function shownReading(sim: Sim): Reading | null {
  return sim.last_ok ?? null
}

export function simTitle(sim: Sim): string {
  return sim.label || sim.number || `${sim.carrier_label} · slot ${(sim.slot ?? 0) + 1}`
}
