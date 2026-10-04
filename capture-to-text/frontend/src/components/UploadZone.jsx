import { useEffect, useRef, useState } from 'react';

export default function UploadZone({ onFile }) {
  const inputRef = useRef(null);
  const [dragOver, setDragOver] = useState(false);

  // Paste works anywhere on the page, so the listener goes on document, not this element.
  // The cleanup removes it again; without it every re-run would stack another listener.
  useEffect(() => {
    function handlePaste(e) {
      for (const item of e.clipboardData?.items ?? []) {
        if (item.type.startsWith('image/')) {
          onFile(item.getAsFile());
          break;
        }
      }
    }
    document.addEventListener('paste', handlePaste);
    return () => document.removeEventListener('paste', handlePaste);
  }, [onFile]);

  function handleDragOver(e) {
    e.preventDefault(); // without this the browser opens the dropped file itself
    setDragOver(true);
  }

  function handleDrop(e) {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) onFile(file);
  }

  function handleChange(e) {
    const file = e.target.files[0];
    if (file) onFile(file);
    e.target.value = ''; // so picking the same file again still fires onChange
  }

  return (
    <div
      className={`drop-zone${dragOver ? ' dragover' : ''}`}
      onClick={() => inputRef.current.click()}
      onDragEnter={handleDragOver}
      onDragOver={handleDragOver}
      onDragLeave={() => setDragOver(false)}
      onDrop={handleDrop}
    >
      <p>Drag &amp; drop an image here, or click to choose a file</p>
      <input ref={inputRef} type="file" accept="image/*" onChange={handleChange} />
    </div>
  );
}
