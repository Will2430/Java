import { useEffect, useState } from 'react';
import { listCaptures } from '../api.js';

function snippetOf(capture) {
  if (capture.status === 'PENDING') return '(processing...)';
  return (capture.extractedText || '').replace(/\s+/g, ' ').trim() || '(no text found)';
}

function metaOf(capture) {
  const statusSuffix = capture.status !== 'DONE' ? ` · ${capture.status}` : '';
  return new Date(capture.createdAt).toLocaleString() + statusSuffix;
}

// `version` changes whenever the parent wants a fresh list.
export default function HistoryList({ version, onSelect }) {
  const [captures, setCaptures] = useState([]);

  useEffect(() => {
    let stale = false; // ignore a response that arrives after a newer fetch started
    listCaptures()
      .then(page => { if (!stale) setCaptures(page.content || []); })
      .catch(err => console.error('Failed to load history', err));
    return () => { stale = true; };
  }, [version]);

  return (
    <section>
      <h2>History</h2>
      <ul id="history">
        {captures.map(capture => (
          <li key={capture.id} onClick={() => onSelect(capture, metaOf(capture))}>
            <span className="snippet">{snippetOf(capture)}</span>
            <span className="meta">{metaOf(capture)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
