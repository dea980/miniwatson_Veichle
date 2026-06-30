// k6 부하테스트 — RAG 전체 경로 (LLM 포함, 느림 → 낮은 동시성)
// 목적: /ask 의 end-to-end 지연·처리량. Ollama LLM 천장이 지배적이라 VU 낮게.
// 실행:  k6 run loadtest/rag_ask_load.js
// 주의:  Ollama가 단일 인스턴스라 VU를 올려도 큐잉됨(선형 가속 X). 노트북 RAM도 고려해 낮게.
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const QUESTIONS = [
  "안전벨트 경고등은 언제 울리나요?",
  "후방 카메라가 안 나올 때 점검 항목은?",
  "프리텐셔너 안전띠 취급 시 주의사항은?",
];

export const options = {
  // LLM-bound라 동시 5명 정도로 충분(그 이상은 Ollama 큐만 늘어남).
  scenarios: {
    rag: { executor: "constant-vus", vus: 5, duration: "2m" },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<30000"],  // LLM 생성이라 초 단위. 기준선 잡고 조정.
  },
};

export default function () {
  const q = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  const r = http.post(`${BASE}/api/rag/ask`,
    JSON.stringify({ question: q, namespace: "vehicle" }),
    { headers: { "Content-Type": "application/json" }, timeout: "60s" });
  check(r, { "status 200": (x) => x.status === 200 });
}
