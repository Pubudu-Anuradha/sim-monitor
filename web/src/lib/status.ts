import type { Sim, SimStatus } from '../api/client'

export type BadgeState = SimStatus | 'offline'

/** What a row shows: offline wins unless a check is still finishing. */
export function badgeState(sim: Sim): BadgeState {
  if (!sim.phone_online && sim.status !== 'checking') return 'offline'
  return sim.status
}
