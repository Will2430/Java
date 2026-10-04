import { useEffect, useState } from 'react';

export default function OutputPanel({ text }) {
  const [confirm, setConfirm] = useState('');

  useEffect(() => {
    if (!confirm) return;
    const timer = setTimeout(() => setConfirm(''), 1500);
    return () => clearTimeout(timer);
  }, [confirm]);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(text);
      setConfirm('Copied!');
    } catch {
      setConfirm('Copy failed');
    }
  }

  return (
    <>
      <textarea id="output" placeholder="Extracted text will appear here..." readOnly value={text} />
      <div className="row">
        <button onClick={handleCopy} disabled={!text}>Copy to clipboard</button>
        <span>{confirm}</span>
      </div>
    </>
  );
}
