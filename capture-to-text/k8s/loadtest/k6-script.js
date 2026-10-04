import http from 'k6/http';
import { check } from 'k6';

// Uploaded under this filename on purpose: every load-test row in Mongo has
// sourceFilename "loadtest.png", so they're trivially findable/deletable.
const image = open('/scripts/loadtest.png', 'b');

// The Service's cluster-internal DNS name. Traffic from inside the cluster to
// this name is what goes through kube-proxy -- `kubectl port-forward` would
// bypass it and pin everything to a single pod.
const BASE = __ENV.BASE_URL || 'http://capture-api:8080';

const DURATION = __ENV.DURATION || '90s';
const BROWSE_RATE = parseInt(__ENV.BROWSE_RATE || '20'); // GET  /api/captures, per second
const UPLOAD_RATE = parseInt(__ENV.UPLOAD_RATE || '4');  // POST /api/captures, per second

export const options = {
  // A fresh TCP connection per request. With keep-alive, k6 would reuse a few
  // connections, kube-proxy would pick a backend once per connection, and the
  // whole test would pile onto one or two pods.
  noConnectionReuse: true,

  scenarios: {
    // Cheap read path: exercises the LB and the API pods, not the OCR pipeline.
    browse: {
      executor: 'constant-arrival-rate', exec: 'browse',
      rate: BROWSE_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 20, maxVUs: 200,
    },
    // Expensive write path: MinIO put + Mongo insert + Kafka publish, then a
    // CPU-heavy OCR job on a worker. Load here shows up as consumer lag.
    upload: {
      executor: 'constant-arrival-rate', exec: 'upload',
      rate: UPLOAD_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 10, maxVUs: 100,
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<500'],
  },
};

// Keycloak on the host (docker-compose), reached from inside the cluster.
const KEYCLOAK = __ENV.KEYCLOAK_URL || 'http://host.minikube.internal:8180';

// Runs once before the scenarios: log in as the "loadtest" user with the password grant
// (enabled only on the capture-to-text-loadtest client; the browser uses the redirect flow).
// The token lives 5 minutes, longer than the default 90s run.
export function setup() {
  const res = http.post(`${KEYCLOAK}/realms/capture-to-text/protocol/openid-connect/token`, {
    grant_type: 'password',
    client_id: 'capture-to-text-loadtest',
    username: __ENV.LOADTEST_USER || 'loadtest',
    password: __ENV.LOADTEST_PASSWORD || 'loadtest',
  });
  if (!check(res, { 'login 200': (r) => r.status === 200 })) {
    throw new Error(`Keycloak login failed: ${res.status} ${res.body}`);
  }
  return { headers: { Authorization: `Bearer ${res.json('access_token')}` } };
}

export function browse(auth) {
  const res = http.get(`${BASE}/api/captures?size=5`, { headers: auth.headers });
  check(res, { 'browse 200': (r) => r.status === 200 });
}

export function upload(auth) {
  const res = http.post(`${BASE}/api/captures`, {
    image: http.file(image, 'loadtest.png', 'image/png'),
  }, { headers: auth.headers });
  // 202 Accepted: the API only promises the job was queued, not that OCR is done.
  check(res, { 'upload 202': (r) => r.status === 202 });
}
