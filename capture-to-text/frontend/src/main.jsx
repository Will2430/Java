import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { initAuth } from './auth.js';
import './index.css';

const root = createRoot(document.getElementById('root'));

// Log in before rendering anything, so every component can assume a token exists.
initAuth()
  .then(() => root.render(
    <StrictMode>
      <App />
    </StrictMode>
  ))
  .catch(() => root.render(<p className="error">Could not reach the login server (Keycloak). Is it running?</p>));
