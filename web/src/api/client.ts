import type { components } from './schema'

// Types come from the server's OpenAPI schema (`npm run gen:api`), so API changes break the build.
export type Sim = components['schemas']['Sim']
export type Reading = components['schemas']['Reading']
export type History = components['schemas']['History']
export type SmsEntry = components['schemas']['SmsEntry']
export type Settings = components['schemas']['Settings']
export type Pairing = components['schemas']['Pairing']
export type SimStatus = Sim['status']

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!res.ok) {
    let detail = res.statusText
    try {
      detail = (await res.json()).detail ?? detail
    } catch {
      // not JSON
    }
    throw new ApiError(res.status, detail)
  }
  return res.json() as Promise<T>
}

export const api = {
  sims: () => request<Sim[]>('/api/sims'),
  settings: () => request<Settings>('/api/settings'),
  history: (simId: string) => request<History>(`/api/sims/${encodeURIComponent(simId)}/history`),
  pairing: () => request<Pairing>('/api/pairing'),
  check: (simId: string) =>
    request<{ result: string }>(`/api/check/${encodeURIComponent(simId)}`, { method: 'POST' }),
  checkAll: () => request<{ started: string[] }>('/api/check-all', { method: 'POST' }),
  updateSim: (simId: string, body: { label: string | null; number: string | null }) =>
    request<Sim>(`/api/sims/${encodeURIComponent(simId)}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
}

export const keys = {
  sims: ['sims'] as const,
  settings: ['settings'] as const,
  history: (simId: string) => ['history', simId] as const,
}
