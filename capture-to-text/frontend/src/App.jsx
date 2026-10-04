import { useCallback, useEffect, useRef, useState } from 'react';
import { pollCaptureUntilDone, uploadCapture } from './api.js';
import { currentUsername, logout } from './auth.js';
import UploadZone from './components/UploadZone.jsx';
import OutputPanel from './components/OutputPanel.jsx';
import PaymentResult from './components/PaymentResult.jsx';
import PayPanel from './components/PayPanel.jsx';
import HistoryList from './components/HistoryList.jsx';

export default function App() {
  const [status, setStatus] = useState({ message: '', isError: false });
  const [capture, setCapture] = useState(null);
  const [previewUrl, setPreviewUrl] = useState(null);
  // Bumping this makes HistoryList fetch again.
  const [historyVersion, setHistoryVersion] = useState(0);
  // The upload currently being polled. A newer upload aborts it, so a slow
  // older result can't land on top of the newer one.
  const uploadRef = useRef(null);

  const showStatus = useCallback((message, isError = false) => setStatus({ message, isError }), []);
  const refreshHistory = useCallback(() => setHistoryVersion(v => v + 1), []);

  // Blob URLs hold the image in memory until revoked.
  useEffect(() => () => previewUrl && URL.revokeObjectURL(previewUrl), [previewUrl]);

  const handleFile = useCallback(async file => {
    if (!file.type.startsWith('image/')) {
      showStatus('Please provide an image file.', true);
      return;
    }
    uploadRef.current?.abort();
    const controller = new AbortController();
    uploadRef.current = controller;

    setPreviewUrl(URL.createObjectURL(file));
    setCapture(null);
    showStatus('Uploading...');

    try {
      const pending = await uploadCapture(file, controller.signal);
      showStatus('Processing (OCR running in the background)...');
      refreshHistory();

      const done = await pollCaptureUntilDone(pending.id, controller.signal);
      if (done.status === 'FAILED') {
        throw new Error(done.errorMessage || 'OCR failed.');
      }
      setCapture(done);
      const confidence = done.ocrConfidence != null ? ` (confidence: ${done.ocrConfidence.toFixed(1)}%)` : '';
      showStatus(`Done${confidence}`);
      refreshHistory();
    } catch (err) {
      if (controller.signal.aborted) return; // superseded by a newer upload
      showStatus(err.message, true);
    }
  }, [showStatus, refreshHistory]);

  const handleHistorySelect = useCallback((selected, label) => {
    setCapture(selected);
    showStatus(`Loaded capture from ${label}`);
  }, [showStatus]);

  return (
    <>
      <header className="topbar">
        <h1>Capture to Text</h1>
        <span>
          Signed in as <strong>{currentUsername()}</strong> <button onClick={logout}>Log out</button>
        </span>
      </header>
      <p>Drop an image, pick a file, or paste (Ctrl/Cmd+V) a screenshot from your clipboard.</p>

      <UploadZone onFile={handleFile} />
      {previewUrl && <img id="preview" src={previewUrl} alt="preview" />}

      <div id="status" className={status.isError ? 'error' : ''}>{status.message}</div>

      <OutputPanel text={capture?.extractedText || ''} />

      <PaymentResult />

      {/* key: a different capture remounts the panel, which resets its form and Idempotency-Key. */}
      {capture?.status === 'DONE' && (
        <PayPanel key={capture.id} capture={capture} onError={message => showStatus(message, true)} />
      )}

      <HistoryList version={historyVersion} onSelect={handleHistorySelect} />
    </>
  );
}
