import type { Settings, Sim } from '../api/client'
import { SimRow } from './SimRow'
import styles from './SimTable.module.css'

type Props = {
  sims: Sim[]
  settings: Settings
  onCheck: (sim: Sim) => void
  onOpen: (sim: Sim) => void
}

export function SimTable({ sims, settings, onCheck, onOpen }: Props) {
  if (sims.length === 0) {
    return (
      <div className={styles.empty}>
        No SIMs yet. Pair a phone with the SIM Monitor app; its SIMs appear here once it connects.
      </div>
    )
  }
  return (
    <div className={styles.wrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>SIM</th>
            <th>Carrier</th>
            <th className={styles.num}>Balance</th>
            <th className={styles.num}>Data</th>
            <th>Expiry</th>
            <th>Last checked</th>
            <th>Status</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {sims.map((sim) => (
            <SimRow key={sim.sim_id} sim={sim} settings={settings} onCheck={onCheck} onOpen={onOpen} />
          ))}
        </tbody>
      </table>
    </div>
  )
}
