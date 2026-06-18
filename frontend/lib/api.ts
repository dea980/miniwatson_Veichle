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
};
export type Stats = {
  totalCalls: number; avgLatencyMs: number; totalPii: number; totalDocs: number;
  byModel: { model: string; calls: number; avgMs: number }[];
  bySourceType: { sourceType: string; docs: number; chunks: number }[];
  feedback: { feedback: string; count: number }[];
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
  ask: (question: string, namespace: string, model?: string) =>
    jpost<AskResult>("/api/rag/ask", { question, namespace, model }),
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

  // 차종 종합 진단서 (리콜 + 불만 + 매뉴얼 종합)
  report: (car: string, namespace: string, model?: string) =>
    jpost<ReportResult>("/api/agent/report", { car, namespace, model }),

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

  // Governance
  logs: () => jget<QueryLog[]>("/api/governance/logs"),
  stats: () => jget<Stats>("/api/governance/stats"),
  feedback: (id: number, value: string) =>
    jpost<unknown>("/api/governance/feedback", { id: String(id), value }),
};
