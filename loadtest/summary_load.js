// k6 부하테스트 — 웹 레이어 기준선 (LLM 없는 빠른 엔드포인트)
// 목적: Tomcat 스레드 · Hikari 풀 · DuckDB 집계의 한계를 LLM 천장에 안 가려진 채 측정.
// 실행:  k6 run loadtest/summary_load.js
// 환경:  BASE_URL 기본 http://localhost:8080  (k6 run -e BASE_URL=... 로 변경)
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
  // VU(가상유저)를 단계적으로 올려 p95가 꺾이는 지점을 본다.
  stages: [
    { duration: "20s", target: 10 },
    { duration: "40s", target: 30 },
    { duration: "40s", target: 60 },
    { duration: "20s", target: 0 },
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],          // 에러율 1% 미만이어야 통과
    http_req_duration: ["p(95)<800"],         // p95 800ms 목표(기준선 잡고 조정)
  },
};

export default function () {
  const r = http.get(`${BASE}/api/analytics/summary`);
  check(r, {
    "status 200": (x) => x.status === 200,
    "has totals": (x) => x.body && x.body.includes("totals"),
  });
  sleep(0.5); // 유저 think-time
}
