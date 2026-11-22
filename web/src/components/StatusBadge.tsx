import type { Sim } from '../api/client'
import { badgeState } from '../lib/status'
import styles from './StatusBadge.module.css'

const TEXT = {
  idle: 'Not checked',
  checking: 'Checking…',
  ok: 'OK',
  parse_failed: 'Parse failed',
  error: 'Failed',
  offline: 'Phone offline',
} as const

export function StatusBadge({ sim }: { sim: Sim }) {
  const state = badgeState(sim)
  const detail =
    state === 'error' || state === 'parse_failed'
      ? (sim.last?.error ?? 'Reply could not be read; raw text kept in history')
      : undefined
  return (
    <span className={`${styles.badge} ${styles[state]}`} title={detail} data-state={state}>
      {state === 'checking' && <span className={styles.spinner} aria-hidden />}
      {TEXT[state]}
    </span>
  )
}
