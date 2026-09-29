// Load test with two parallel scenarios:
//   submit:  arrival rate ramps 0 -> 100 submissions/s over 30s, then holds 100/s for 60s. Each
//            submission is one EMAIL notification to cust-1001 (always succeeds). Throughput and
//            accept latency come from here, independent of how fast deliveries complete.
//   sampler: 5 VUs that each submit one notification and poll its status until COMPLETED, to
//            measure time-to-sent under that load.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < k6/load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = 'billing-demo-key-7f3a9c2e5b8d4f1a';

const acceptLatency = new Trend('accept_latency', true);
const timeToSent = new Trend('time_to_sent', true);
const sentTimeouts = new Counter('time_to_sent_timeouts');

export const options = {
  scenarios: {
    submit: {
      executor: 'ramping-arrival-rate',
      exec: 'submitOnly',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '30s', target: 100 },
        { duration: '60s', target: 100 },
      ],
    },
    sampler: {
      executor: 'constant-vus',
      exec: 'sampleTimeToSent',
      vus: 5,
      duration: '90s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function submit() {
  const key = `k6-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
  const body = JSON.stringify({
    sourceSystem: 'billing',
    eventId: key,
    type: 'TRANSACTIONAL',
    severity: 'MEDIUM',
    priority: 'NORMAL',
    recipients: ['cust-1001'],
    channels: ['EMAIL'],
    subject: 'Load test',
    body: 'Load test message',
  });
  const res = http.post(`${BASE_URL}/api/v1/notifications`, body, {
    headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY, 'Idempotency-Key': key },
    tags: { name: 'submit' },
  });
  acceptLatency.add(res.timings.duration);
  check(res, { 'accepted (202)': (r) => r.status === 202 });
  return res;
}

export function submitOnly() {
  submit();
}

export function sampleTimeToSent() {
  const res = submit();
  if (res.status !== 202) {
    return;
  }
  const id = res.json('id');
  const start = Date.now();
  const deadline = start + 60000;
  while (Date.now() < deadline) {
    const status = http.get(`${BASE_URL}/api/v1/notifications/${id}`, {
      headers: { 'X-API-Key': API_KEY },
      tags: { name: 'status' },
    });
    if (status.status === 200 && status.json('status') === 'COMPLETED') {
      timeToSent.add(Date.now() - start);
      return;
    }
    sleep(0.05);
  }
  sentTimeouts.add(1);
}
