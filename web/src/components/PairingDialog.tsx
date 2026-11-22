import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import styles from './PairingDialog.module.css'

export function PairingDialog({ onClose }: { onClose: () => void }) {
  const pairing = useQuery({ queryKey: ['pairing'], queryFn: api.pairing, staleTime: Infinity })
  const [showToken, setShowToken] = useState(false)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div className={styles.dialog} role="dialog" aria-label="Pair a phone" onClick={(e) => e.stopPropagation()}>
        <h2>Pair a phone</h2>
        <p className={styles.sub}>
          In the SIM Monitor app, open Settings → Pair and scan this code. It holds the server address, the shared
          token and the server certificate fingerprint, so keep it private.
        </p>
        <img className={styles.qr} src="/api/pairing.svg" alt="Pairing QR code" width={240} height={240} />
        {pairing.data && (
          <dl className={styles.fields}>
            <dt>Server</dt>
            <dd>
              {pairing.data.host}:{pairing.data.port}
            </dd>
            <dt>Certificate SHA-256</dt>
            <dd className={styles.mono}>{pairing.data.ca_sha256.match(/.{1,4}/g)?.join(' ')}</dd>
            <dt>Token</dt>
            <dd className={styles.mono}>
              {showToken ? pairing.data.token : '•'.repeat(12)}{' '}
              <button type="button" onClick={() => setShowToken((v) => !v)}>
                {showToken ? 'Hide' : 'Show'}
              </button>
            </dd>
          </dl>
        )}
        <div className={styles.actions}>
          <button type="button" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>
  )
}
