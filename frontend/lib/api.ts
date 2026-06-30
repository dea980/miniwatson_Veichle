// 백엔드 API 헬퍼. next.config.js rewrites가 /api/* 를 Spring(8080)으로 프록시한다.

export type Source = { id?: number; title: string; summary: string; url: string; namespace?: string };
export type AskResult = { answer: string; sources: Source[]; logId?: number };
export type DocItem = { title: string; chunks: number; namespace: string; url: string; ids: number[] };
export type Models = { default: string; available: string[] };
export type QueryLog = {
  id: number; question: string; model: string; latencyMs: number;
  piiCount?: number; sources?: string; createdAt?: string; feedback?: string;
};
export type AgentStep = { tool: string; action: string; result: string; detail: string };
export type AgentResult = {
  answer: string; tool: string; trace: AgentStep[];
  sources: Source[]; sql?: unknown; rows?: unknown; logId?: number;
};
export type ReportResult = {
  car: string;
  inspection: [string, string, string, string][];   // category, item, result, code
  recallTotal: number; recallTopComponents: [string, number][];
  complaintTotal: number; complaintTopComponents: [string, number][];
  fires: number; injuries: number;
  manualNotes: string; sources: string[]; report: string;
  generatedAt?: string; cached?: boolean;   // 적재(생성일)·캐시 여부
};
export type DiagnoseResult = {
  caption: string; ocr: string; diagnosis: string; problem: string; sources: string[];
};
export type EstimateItem = {
  part: string; component: string; unitPrice: number; laborHours: number; laborCost: number; lineTotal: number;
};
export type EstimateResult = {
  car: string; problem: string; laborRate: number; items: EstimateItem[];
  partsTotal: number; laborTotal: number; grandTotal: number; note: string;
  supplyAmount?: number; vat?: number; total?: number; sample?: boolean;   // 견적서: 공급가액·부가세(10%)·합계
};
export type Stats = {
  totalCalls: number; avgLatencyMs: number; totalPii: number; totalDocs: number;
  byModel: { model: string; calls: number; avgMs: number }[];
  bySourceType: { sourceType: string; docs: number; chunks: number }[];
  feedback: { feedback: string; count: number }[];
};

export type Analytics = {
  totals: { recalls: number; complaints: number; fires: number; injuries: number; crashes: number };
  recallByYear: [string, number][];
  complaintByYear: [string, number][];
  recallTopComponents: [string, number][];
  complaintTopComponents: [string, number][];
  complaintByModel: [string, number][];
  safetyHotspots: [string, number, number, number][];        // model, fires, injuries, crashes
  partsDemand: [string, string, number, number, number][];   // part, component, demand, unitPrice, estCost
  insight?: string;   // 분리됨 — analyticsInsight()로 별도 로드
};

export type Summary = {
  totals: { recalls: number; complaints: number; fires: number; injuries: number };
  recentRecalls: [string, string, string, string, string][];      // id(campaign), date, model, component, summary
  recentComplaints: [string, string, string, string, string][];   // id(odiNumber), date, model, components, summary
  byModel: [string, number, number][];                    // model, complaints, recalls
};
// id, date, components, year, summary, priority, fire(0/1), crash(0/1), injuries, deaths
export type VehicleRecord = [string, string, string, string, string, number, number, number, number, number];
// id, date, model, components, year, summary, priority, fire, crash, injuries, deaths
export type CaseRecord = [string, string, string, string, string, string, number, number, number, number, number];

export type CaseChecklist = { model?: string; common: [string, string][]; additional: [string, number, string][] };
export type CaseReport = {
  caseNumber: string; model?: string; component?: string; year?: string; date?: string;
  summary?: string; priority?: number; fire?: number; crash?: number; injuries?: number; deaths?: number;
  diagnosis?: string; sources?: string[];
  estimate?: EstimateResult;
  checklistThis?: CaseChecklist; checklistCar?: CaseChecklist;
  note?: string; generatedAt?: string; cached?: boolean; error?: string;
};

export type RecallDetail = {
  campaign?: string; date?: string; model?: string; year?: string; component?: string;
  summary?: string; consequence?: string; remedy?: string; parkIt?: string; parkOutside?: string;
};

export type Maintenance = {
  id: number; model: string; caseNumber?: string; title: string;
  scheduledDate: string; status: string; technician?: string; note?: string; createdAt?: string;
};

async function jget<T>(url: string): Promise<T> {
  const r = await fetch(url, { cache: "no-store" });
  if (!r.ok) throw new Error(`${r.status} ${await r.text().catch(() => "")}`.slice(0, 200));
  return r.json();
}
async function jpost<T>(url: string, body?: unknown): Promise<T> {
  const r = await fetch(url, {
    method: "POST",
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(`${r.status} ${await r.text().catch(() => "")}`.slice(0, 200));
  return r.json();
}

export const api = {
  // RAG
  // car(차종 부분일치)·year(연식)·powertrain 으로 검색 범위를 좁힐 수 있음(백엔드 메타 1차 필터)
  ask: (question: string, namespace: string, model?: string, title?: string,
        car?: string, year?: number, powertrain?: string) =>
    jpost<AskResult>("/api/rag/ask", { question, namespace, model, title, car, year, powertrain }),
  models: () => jget<Models>("/api/rag/models"),

  // Data / KB
  documents: (namespace?: string) =>
    jget<DocItem[]>(`/api/data/documents${namespace ? `?namespace=${encodeURIComponent(namespace)}` : ""}`),
  count: (namespace?: string) =>
    jget<{ count: number }>(`/api/data/count${namespace ? `?namespace=${encodeURIComponent(namespace)}` : ""}`),
  deleteDocument: (title: string, namespace: string) =>
    fetch(`/api/data/documents?title=${encodeURIComponent(title)}&namespace=${encodeURIComponent(namespace)}`, { method: "DELETE" }),
  summarize: (id: number) => jpost<{ summary: string }>(`/api/data/summarize/${id}`),
  ingestFile: (file: File, namespace: string) => {
    const isImage = file.type.startsWith("image/");
    const form = new FormData();
    form.append("namespace", namespace);
    form.append(isImage ? "image" : "file", file);
    return fetch(isImage ? "/api/multimodal/ingest" : "/api/data/ingest-file", { method: "POST", body: form })
      .then(async (r) => { if (!r.ok) throw new Error(`${r.status} ${(await r.text()).slice(0, 200)}`); return r.json(); });
  },

  // Tabular text-to-SQL
  tabularLoad: (table: string, path: string) =>
    jpost<{ table: string; schema: string }>(`/api/tabular/load?table=${encodeURIComponent(table)}&path=${encodeURIComponent(path)}`),
  tabularUpload: (file: File, table: string, headerRow = 0) => {
    const form = new FormData();
    form.append("file", file); form.append("table", table); form.append("headerRow", String(headerRow));
    return fetch("/api/tabular/upload", { method: "POST", body: form })
      .then(async (r) => { if (!r.ok) throw new Error(`${r.status} ${(await r.text()).slice(0, 200)}`); return r.json() as Promise<{ table: string; file: string; schema: string }>; });
  },
  tabularAsk: (table: string, question: string) =>
    jpost<{ sql?: string; columns: string[]; rows: (string | number | null)[][] }>("/api/tabular/ask", { table, question }),

  // Agent (Agentic Search: RAG + 리콜 SQL 툴콜)
  agentAsk: (question: string, namespace: string, model?: string) =>
    jpost<AgentResult>("/api/agent/ask", { question, namespace, model }),

  // 차종 종합 진단서 (리콜 + 불만 + 매뉴얼 종합) — 적재 캐시 우선, force=true면 재생성
  report: (car: string, namespace: string, model?: string, force?: boolean) =>
    jpost<ReportResult>("/api/agent/report", { car, namespace, model, force: force ? "true" : "false" }),
  // 적재된 리포트 목록 (차종·생성일·모델)
  savedReports: () =>
    jget<{ reports: { key: string; model: string; generatedAt: string }[] }>("/api/agent/reports"),
  // 접수번호별 리포트 (AI 진단+견적+점검 스냅샷, 적재 캐시) — force=true면 재생성
  caseReport: (id: string, model?: string, force?: boolean) => {
    const p = new URLSearchParams({ id });
    if (model) p.set("model", model);
    if (force) p.set("force", "true");
    return jget<CaseReport>(`/api/agent/case-report?${p.toString()}`);
  },
  // 정비사 메모 저장(문서화·적재)
  saveCaseNote: (id: string, note: string, model?: string) =>
    jpost<CaseReport>("/api/agent/case-report/note", { id, note, model }),

  // 이미지 진단 (경고등/부품 사진 → Vision+OCR+RAG)
  diagnoseImage: (image: File, namespace: string, model?: string) => {
    const form = new FormData();
    form.append("image", image);
    form.append("namespace", namespace);
    if (model) form.append("model", model);
    return fetch("/api/agent/diagnose-image", { method: "POST", body: form })
      .then(async (r) => { if (!r.ok) throw new Error(`${r.status} ${(await r.text()).slice(0, 200)}`); return r.json() as Promise<DiagnoseResult>; });
  },

  // 필요 부품 명세 (+ 참고 견적)
  estimate: (problem: string, car: string, model?: string) =>
    jpost<EstimateResult>("/api/agent/estimate", { problem, car, model }),

  // 분석 대시보드 (플릿 집계 + LLM 인사이트)
  analytics: (model?: string) =>
    jget<Analytics>(`/api/analytics/overview${model ? `?model=${encodeURIComponent(model)}` : ""}`),
  analyticsInsight: (model?: string) =>
    jget<{ insight: string }>(`/api/analytics/insight${model ? `?model=${encodeURIComponent(model)}` : ""}`),
  // 홈 대시보드용 경량 요약 (총계 + 최근 리콜/불만)
  summary: () => jget<Summary>("/api/analytics/summary"),
  // 드릴다운: 특정 차종의 개별 차량 기록(불만)
  vehicles: (model: string) =>
    jget<{ model: string; vehicles: VehicleRecord[] }>(`/api/analytics/vehicles?model=${encodeURIComponent(model)}`),
  // 시계열 추세 (연/월/일 + 차종)
  trend: (table: "recalls" | "complaints", by: "year" | "month" | "day", model?: string) =>
    jget<{ trend: [string, number][]; error?: string }>(
      `/api/analytics/trend?table=${table}&by=${by}${model ? `&model=${encodeURIComponent(model)}` : ""}`),
  // 점검 체크리스트: component 주면 건별(그 부위만), 없으면 차종 집계
  checklist: (model: string, component?: string) =>
    jget<{ model: string; common: [string, string][]; additional: [string, number, string][]; error?: string }>(
      `/api/analytics/checklist?model=${encodeURIComponent(model)}${component ? `&component=${encodeURIComponent(component)}` : ""}`),
  // 케이스 우선순위 트리아지(전 차종, 필터 + 페이지네이션 + 해결제외)
  cases: (model?: string, component?: string, offset = 0, limit = 50, sort: "priority" | "model" = "priority") => {
    const p = new URLSearchParams();
    if (model) p.set("model", model);
    if (component) p.set("component", component);
    p.set("offset", String(offset));
    p.set("limit", String(limit));
    p.set("sort", sort);
    return jget<{ cases: CaseRecord[]; total: number; offset: number; limit: number; error?: string }>(
      `/api/analytics/cases?${p.toString()}`);
  },
  // 케이스 해결 처리(영속) / 취소 / 목록
  resolveCase: (id: string, note?: string) =>
    jpost<{ resolved: boolean; id: string }>("/api/analytics/resolve", { id, note }),
  unresolveCase: (id: string) =>
    fetch(`/api/analytics/resolve/${encodeURIComponent(id)}`, { method: "DELETE" }).then((r) => r.json()),
  resolvedCases: () =>
    jget<{ resolved: { caseNumber: string; note: string; resolvedAt: string }[] }>("/api/analytics/resolved"),
  // 단일 리콜 상세 (캠페인번호)
  recall: (id: string) =>
    jget<RecallDetail>(`/api/analytics/recall?id=${encodeURIComponent(id)}`),
  // 단일 케이스(접수번호) 상세
  caseById: (id: string) =>
    jget<{ case: CaseRecord | []; error?: string }>(`/api/analytics/case?id=${encodeURIComponent(id)}`),
  // 불만 접수 내용 한국어 요약 (처음 1회만 LLM, 이후 캐시)
  caseSummary: (id: string, model?: string) =>
    jget<{ caseNumber: string; fullText?: string; gist?: string; cached?: boolean; generatedAt?: string; error?: string }>(
      `/api/agent/case-summary?id=${encodeURIComponent(id)}${model ? `&model=${encodeURIComponent(model)}` : ""}`),

  // 정비 스케줄 (캘린더, 백엔드 JPA 영속)
  maintenanceList: () => jget<Maintenance[]>("/api/maintenance"),
  maintenanceCreate: (m: Partial<Maintenance>) => jpost<Maintenance>("/api/maintenance", m),
  maintenanceStatus: (id: number, value: string) =>
    fetch(`/api/maintenance/${id}/status?value=${encodeURIComponent(value)}`, { method: "PUT" }).then((r) => r.json()),
  maintenanceDelete: (id: number) =>
    fetch(`/api/maintenance/${id}`, { method: "DELETE" }).then((r) => r.json()),

  // Governance
  logs: () => jget<QueryLog[]>("/api/governance/logs"),
  stats: () => jget<Stats>("/api/governance/stats"),
  feedback: (id: number, value: string) =>
    jpost<unknown>("/api/governance/feedback", { id: String(id), value }),
};
