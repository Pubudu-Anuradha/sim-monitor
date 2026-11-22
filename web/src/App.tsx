import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, keys, type Sim } from './api/client'
import { useSimEvents } from './api/useSimEvents'
import { HistoryDrawer } from './components/HistoryDrawer'
import { PairingDialog } from './components/PairingDialog'
import { SimTable } from './components/SimTable'
import styles from './App.module.css'

export default function App() {
  const connected = useSimEvents()
  const sims = useQuery({ queryKey: keys.sims, queryFn: api.sims })
  const settings = useQuery({ queryKey: keys.settings, queryFn: api.settings, staleTime: Infinity })
  const [openId, setOpenId] = useState<string | null>(null)
  const [pairing, setPairing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // State changes arrive over SSE; these only report request errors.
  const check = useMutation({
    mutationFn: (sim: Sim) => api.check(sim.sim_id),
    onError: (e) => setError(e.message),
  })
  const checkAll = useMutation({ mutationFn: api.checkAll, onError: (e) => setError(e.message) })

  const open = sims.data?.find((s) => s.sim_id === openId) ?? null
  const anyCheckable = sims.data?.some((s) => s.phone_online && s.checkable && s.status !== 'checking')

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1>SIM Monitor</h1>
        <span className={`${styles.live} ${connected ? styles.on : ''}`} title="Live updates from the server">
          {connected ? 'Live' : 'Reconnecting…'}
        </span>
        <div className={styles.spacer} />
        <button type="button" onClick={() => setPairing(true)}>
          Pair phone
        </button>
        <button
          type="button"
          className={styles.primary}
          disabled={!anyCheckable || checkAll.isPending}
          onClick={() => {
            setError(null)
            checkAll.mutate()
          }}
        >
          Check all
        </button>
      </header>

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button type="button" onClick={() => setError(null)} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}

      {sims.isPending || settings.isPending ? (
        <p className={styles.muted}>Loading…</p>
      ) : sims.isError || settings.isError ? (
        <p className={styles.error}>Can't reach the server: {(sims.error ?? settings.error)?.message}</p>
      ) : (
        <SimTable
          sims={sims.data}
          settings={settings.data}
          onCheck={(sim) => {
            setError(null)
            check.mutate(sim)
          }}
          onOpen={(sim) => setOpenId(sim.sim_id)}
        />
      )}

      {settings.data && (
        <p className={styles.legend}>
          Highlighted: balance under Rs {settings.data.low_balance}, or expiry within{' '}
          {settings.data.expiry_warn_days} days. Values are from the last successful check.
        </p>
      )}

      {open && <HistoryDrawer sim={open} onClose={() => setOpenId(null)} />}
      {pairing && <PairingDialog onClose={() => setPairing(false)} />}
    </div>
  )
}
