import type { Settings, Sim } from '../api/client'
import {
  expiresSoon,
  formatAgo,
  formatBalance,
  formatData,
  formatDate,
  isLowBalance,
  shownReading,
  simTitle,
} from '../lib/format'
import { badgeState } from '../lib/status'
import { StatusBadge } from './StatusBadge'
import styles from './SimRow.module.css'

type Props = {
  sim: Sim
  settings: Settings
  onCheck: (sim: Sim) => void
  onOpen: (sim: Sim) => void
}

export function SimRow({ sim, settings, onCheck, onOpen }: Props) {
  const r = shownReading(sim)
  const state = badgeState(sim)
  const low = isLowBalance(r, settings)
  const mainSoon = expiresSoon(r?.expiry, settings)
  const dataSoon = expiresSoon(r?.data_expiry, settings)
  const canCheck = sim.phone_online && sim.checkable && sim.status !== 'checking'
  const checkTitle = !sim.phone_online
    ? 'Phone offline'
    : !sim.checkable
      ? 'No check defined for this carrier'
      : undefined

  return (
    <tr
      className={`${styles.row} ${state === 'offline' ? styles.offline : ''}`}
      onClick={() => onOpen(sim)}
      data-testid={`sim-${sim.sim_id}`}
    >
      <td>
        <div className={styles.title}>{simTitle(sim)}</div>
        <div className={styles.sub}>
          {sim.phone_name} · slot {(sim.slot ?? 0) + 1}
          {sim.label && sim.number ? ` · ${sim.number}` : ''}
        </div>
      </td>
      <td>
        <span className={`${styles.carrier} ${styles[sim.carrier] ?? ''}`}>{sim.carrier_label}</span>
      </td>
      <td className={`${styles.num} ${low ? styles.warn : ''}`} title={low ? 'Low balance' : undefined}>
        {formatBalance(r?.balance)}
      </td>
      <td className={styles.num}>
        <div>{formatData(r?.data_mb)}</div>
        {r?.data_expiry && (
          <div className={`${styles.sub} ${dataSoon ? styles.warn : ''}`}>
            until {formatDate(r.data_expiry)}
          </div>
        )}
      </td>
      <td className={mainSoon ? styles.warn : undefined} title={mainSoon ? 'Expires soon' : undefined}>
        {formatDate(r?.expiry)}
      </td>
      <td className={styles.sub} title={sim.last?.ts}>
        {formatAgo(sim.last?.ts)}
      </td>
      <td>
        <StatusBadge sim={sim} />
      </td>
      <td className={styles.actions}>
        <button
          type="button"
          disabled={!canCheck}
          title={checkTitle}
          onClick={(e) => {
            e.stopPropagation()
            onCheck(sim)
          }}
        >
          Check
        </button>
      </td>
    </tr>
  )
}
