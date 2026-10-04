import { useRef, useState } from 'react';
import { createPayment } from '../api.js';

export default function PayPanel({ capture, onError }) {
  const [amount, setAmount] = useState(
    capture.suggestedAmountCents != null ? (capture.suggestedAmountCents / 100).toFixed(2) : ''
  );
  const [currency, setCurrency] = useState('usd');
  const [description, setDescription] = useState(`Bill: ${capture.sourceFilename || 'captured image'}`);
  const [busy, setBusy] = useState(false);

  // One Idempotency-Key per payment attempt: a double click or a retried request reuses it,
  // so the server creates the payment once. Editing the bill makes it a new attempt.
  // A ref, not state: changing it must not re-render, and it must survive re-renders.
  const payKey = useRef(null);

  function edit(setter) {
    return e => {
      setter(e.target.value);
      payKey.current = null;
    };
  }

  async function handlePay() {
    // Converted to whole cents once, here; the server never sees a decimal amount.
    const amountCents = Math.round(parseFloat(amount) * 100);
    if (!Number.isFinite(amountCents) || amountCents < 50) {
      onError('Enter an amount of at least 0.50.');
      return;
    }
    payKey.current = payKey.current || crypto.randomUUID();
    setBusy(true);
    try {
      // Retries with backoff inside createPayment, always with this same key.
      const payment = await createPayment(payKey.current, {
        captureId: capture.id,
        amountCents,
        currency,
        description,
      });
      if (payment.status === 'FAILED') {
        // A definite failure is final for this key (a retry would just replay it), so the
        // next click must be a new attempt with a new key.
        payKey.current = null;
        throw new Error(`Payment failed: ${payment.failureReason || 'rejected by Stripe'}. Try again.`);
      }
      window.location.href = payment.checkoutUrl; // Stripe's hosted page collects the card
    } catch (err) {
      onError(err.message);
      setBusy(false);
    }
  }

  return (
    <section id="payPanel">
      <h2>Pay this bill</h2>
      <div className="row">
        <input id="payAmount" type="number" min="0.50" step="0.01" placeholder="Amount" aria-label="Amount"
               value={amount} onChange={edit(setAmount)} />
        <select aria-label="Currency" value={currency} onChange={edit(setCurrency)}>
          <option value="usd">USD</option>
          <option value="myr">MYR</option>
        </select>
        <input id="payDescription" placeholder="What is this bill for?" aria-label="Description"
               value={description} onChange={edit(setDescription)} />
        <button onClick={handlePay} disabled={busy}>Pay with Stripe</button>
      </div>
      <p className="hint">
        Amount detected from the image; check it before paying.
        Test mode: card 4242 4242 4242 4242, any future expiry, any CVC.
      </p>
    </section>
  );
}
