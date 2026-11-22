import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { keys, type Sim } from './client'

/**
 * Live updates over SSE. The server pushes a full `Sim` whenever anything about it changes
 * (check started/finished, phone online/offline), so the cache is patched in place.
 * Returns whether the stream is connected.
 */
export function useSimEvents(): boolean {
  const qc = useQueryClient()
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    const es = new EventSource('/api/events')

    es.addEventListener('hello', () => {
      setConnected(true)
      // Anything may have changed while disconnected.
      void qc.invalidateQueries({ queryKey: keys.sims })
    })
    es.addEventListener('sim', (e) => {
      const sim = JSON.parse((e as MessageEvent<string>).data) as Sim
      qc.setQueryData<Sim[]>(keys.sims, (old) => {
        if (!old) return old
        const i = old.findIndex((s) => s.sim_id === sim.sim_id)
        if (i === -1) {
          void qc.invalidateQueries({ queryKey: keys.sims }) // new SIM: refetch for server ordering
          return old
        }
        const next = old.slice()
        next[i] = sim
        return next
      })
      void qc.invalidateQueries({ queryKey: keys.history(sim.sim_id) })
    })
    es.addEventListener('sms', (e) => {
      const { sim_id } = JSON.parse((e as MessageEvent<string>).data) as { sim_id: string }
      void qc.invalidateQueries({ queryKey: keys.history(sim_id) })
    })
    es.onerror = () => setConnected(false) // EventSource reconnects by itself

    return () => es.close()
  }, [qc])

  return connected
}
