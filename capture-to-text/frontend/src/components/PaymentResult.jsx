import { useEffect, useState } from 'react';
import { getPayment, sleep } from '../api.js';

const MAX_ATTEMPTS = 20;

// Read once, on first render. The initializer must stay pure (no replaceState here):
// StrictMode calls it twice in dev, and the second call would find the params gone.
function readReturnParams() {
  const params = new URLSearchParams(window.location.search);
  return { id: params.get('paymentId'), cancelled: params.has('cancelled') };
}

// Stripe redirects back to /?paymentId=... The redirect alone proves nothing (anyone can
// open that URL), so we ask our server, which only trusts the signed webhook.
export default function PaymentResult() {
  const [{ id, cancelled }] = useState(readReturnParams);
  const [result, setResult] = useState({ text: 'Confirming payment with Stripe...', kind: '' });

  useEffect(() => {
    if (!id) return;
    history.replaceState(null, '', '/');
    if (cancelled) {
      setResult({ text: 'Checkout cancelled. Nothing was charged.', kind: '' });
      return;
    }

    const controller = new AbortController();
    (async () => {
      for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        try {
          const payment = await getPayment(id, controller.signal);
          if (payment.status !== 'PENDING' && payment.status !== 'CREATED') {
            const amount = `${(payment.amountCents / 100).toFixed(2)} ${payment.currency.toUpperCase()}`;
            setResult({
              text: `Payment ${payment.status.toLowerCase()}: ${amount} for "${payment.description}"`,
              kind: payment.status === 'SUCCEEDED' ? 'ok' : 'error',
            });
            return;
          }
          await sleep(1500, controller.signal);
        } catch {
          if (controller.signal.aborted) return;
          await sleep(1500, controller.signal).catch(() => {});
        }
      }
      if (!controller.signal.aborted) {
        setResult({ text: 'Still waiting for Stripe to confirm the payment. Check back shortly.', kind: '' });
      }
    })();
    return () => controller.abort();
  }, [id, cancelled]);

  if (!id) return null;
  return <section id="paymentResult" className={result.kind}>{result.text}</section>;
}
