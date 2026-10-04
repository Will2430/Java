import Keycloak from 'keycloak-js';

// Login happens on Keycloak's own page (OIDC authorization code flow + PKCE). This app never
// sees a password: it gets back a short-lived signed access token (a JWT) and sends it to the
// API as "Authorization: Bearer <token>". The API checks the signature and reads the user id
// from it, so the token is the only proof of identity the server accepts.
const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
  realm: 'capture-to-text',
  clientId: 'capture-to-text-web',
});

// Resolves once logged in. With login-required, a visitor without a session is redirected to
// Keycloak and comes back to this same URL (query string included, e.g. Stripe's ?paymentId=).
export function initAuth() {
  return keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });
}

// Access tokens live 5 minutes. Refresh it if it expires within 30s, using the refresh token,
// so a long session never sends an expired one.
export async function getAccessToken() {
  try {
    await keycloak.updateToken(30);
  } catch {
    await login(); // the refresh token expired too: log in again
  }
  return keycloak.token;
}

export function login() {
  return keycloak.login();
}

export function logout() {
  return keycloak.logout({ redirectUri: window.location.origin });
}

export function currentUsername() {
  return keycloak.tokenParsed?.preferred_username;
}
