import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api, keys, type Reading, type Sim } from '../api/client'
import { formatBalance, formatData, formatDate, formatDateTime, simTitle } from '../lib/format'
import { StatusBadge } from './StatusBadge'
import styles from './HistoryDrawer.module.css'

type Props = { sim: Sim; onClose: () => void }

export function HistoryDrawer({ sim, onClose }: Props) {
  const history = useQuery({ queryKey: keys.history(sim.sim_id), queryFn: () => api.history(sim.sim_id) })
  const [tab, setTab] = useState<'readings' | 'sms'>('readings')

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <aside
        className={styles.drawer}
        role="dialog"
        aria-label={`History for ${simTitle(sim)}`}
        onClick={(e) => e.stopPropagation()}
      >
        <header className={styles.header}>
          <div>
            <h2>{simTitle(sim)}</h2>
            <div className={styles.sub}>
              {sim.carrier_label} · {sim.phone_name} · slot {(sim.slot ?? 0) + 1}
            </div>
          </div>
          <StatusBadge sim={sim} />
          <button type="button" className={styles.close} onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>

        <SimDetails key={sim.sim_id} sim={sim} />

        <div className={styles.tabs} role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'readings'} onClick={() => setTab('readings')}>
            Readings {history.data && `(${history.data.readings.length})`}
          </button>
          <button type="button" role="tab" aria-selected={tab === 'sms'} onClick={() => setTab('sms')}>
            Carrier texts {history.data && `(${history.data.sms.length})`}
          </button>
        </div>

        <div className={styles.body}>
          {history.isPending && <p className={styles.sub}>Loading…</p>}
          {history.isError && <p className={styles.error}>{history.error.message}</p>}
          {history.data && tab === 'readings' && (
            <ReadingList readings={history.data.readings} />
          )}
          {history.data && tab === 'sms' && (
            <ul className={styles.list}>
              {history.data.sms.length === 0 && <li className={styles.sub}>No carrier texts yet.</li>}
              {history.data.sms.map((s) => (
                <li key={s.id} className={styles.item}>
                  <div className={styles.meta}>
                    <strong>{s.sender}</strong> · {formatDateTime(s.ts)}
                  </div>
                  <div className={styles.raw}>{s.body}</div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>
    </div>
  )
}

function SimDetails({ sim }: { sim: Sim }) {
  const qc = useQueryClient()
  const [label, setLabel] = useState(sim.label ?? '')
  const [number, setNumber] = useState(sim.number ?? '')
  const save = useMutation({
    mutationFn: () => api.updateSim(sim.sim_id, { label: label || null, number: number || null }),
    onSuccess: (updated) =>
      qc.setQueryData<Sim[]>(keys.sims, (old) => old?.map((s) => (s.sim_id === updated.sim_id ? updated : s))),
  })
  const dirty = label !== (sim.label ?? '') || number !== (sim.number ?? '')

  return (
    <form
      className={styles.details}
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate()
      }}
    >
      <label>
        Label
        <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. Work, Mum's phone" />
      </label>
      <label>
        Number
        <input value={number} onChange={(e) => setNumber(e.target.value)} placeholder="07x xxx xxxx" inputMode="tel" />
      </label>
      <button type="submit" disabled={!dirty || save.isPending}>
        {save.isPending ? 'Saving…' : 'Save'}
      </button>
      {save.isError && <span className={styles.error}>{save.error.message}</span>}
    </form>
  )
}

function ReadingList({ readings }: { readings: Reading[] }) {
  if (readings.length === 0) return <p className={styles.sub}>Not checked yet.</p>
  return (
    <ul className={styles.list}>
      {readings.map((r) => (
        <li key={r.id} className={styles.item}>
          <div className={styles.meta}>
            {formatDateTime(r.ts)}
            {r.parsed_ok ? '' : r.error ? ` · failed: ${r.error}` : ' · reply not understood'}
          </div>
          {r.parsed_ok && (
            <div className={styles.values}>
              <span>{formatBalance(r.balance)}</span>
              {r.expiry && <span>until {formatDate(r.expiry)}</span>}
              <span>{formatData(r.data_mb)} data</span>
              {r.data_expiry && <span>data until {formatDate(r.data_expiry)}</span>}
            </div>
          )}
          {r.buckets.length > 0 && (
            <ul className={styles.buckets}>
              {r.buckets.map((b, i) => (
                <li key={i}>{b.text}</li>
              ))}
            </ul>
          )}
          {r.raw && (
            <details>
              <summary>Raw reply</summary>
              <pre className={styles.raw}>{r.raw}</pre>
            </details>
          )}
        </li>
      ))}
    </ul>
  )
}
