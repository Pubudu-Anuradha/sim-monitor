// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Reading, Settings, Sim } from '../api/client'
import { SimRow } from './SimRow'

const settings: Settings = { low_balance: 50, expiry_warn_days: 3 }

const reading: Reading = {
  id: 1, ts: new Date().toISOString(), balance: 1234.56, expiry: '2099-12-13', data_mb: 2048,
  data_expiry: null, buckets: [], raw: '[USSD] ...', parsed_ok: true, error: null,
}

function sim(over: Partial<Sim> = {}): Sim {
  return {
    sim_id: 'p1:1', phone_id: 'p1', phone_name: 'Test phone', phone_online: true, slot: 0,
    carrier: 'hutch', carrier_label: 'Hutch', carrier_name: 'Hutch', number: null, label: 'Work',
    status: 'ok', checkable: true, last: reading, last_ok: reading, ...over,
  }
}

function renderRow(s: Sim, onCheck = vi.fn(), onOpen = vi.fn()) {
  render(<table><tbody><SimRow sim={s} settings={settings} onCheck={onCheck} onOpen={onOpen} /></tbody></table>)
  return { onCheck, onOpen }
}

afterEach(cleanup)

describe('SimRow', () => {
  it('shows values and checks without opening the drawer', () => {
    const { onCheck, onOpen } = renderRow(sim())
    expect(screen.getByText('Rs 1,234.56')).toBeTruthy()
    expect(screen.getByText('2.00 GB')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Check' }))
    expect(onCheck).toHaveBeenCalledOnce()
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('greys out offline phones but keeps last-known values', () => {
    renderRow(sim({ phone_online: false }))
    expect(screen.getByText('Phone offline')).toBeTruthy()
    expect(screen.getByText('Rs 1,234.56')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Check' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows parse failure with the last good values', () => {
    const failed = { ...reading, id: 2, parsed_ok: false, balance: null, raw: '[USSD] Sorry' }
    renderRow(sim({ status: 'parse_failed', last: failed }))
    expect(screen.getByText('Parse failed')).toBeTruthy()
    expect(screen.getByText('Rs 1,234.56')).toBeTruthy()
  })

  it('disables Check while checking', () => {
    renderRow(sim({ status: 'checking' }))
    expect(screen.getByText('Checking…')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Check' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('highlights low balance', () => {
    renderRow(sim({ last_ok: { ...reading, balance: 5 } }))
    expect(screen.getByTitle('Low balance').textContent).toBe('Rs 5.00')
  })
})
