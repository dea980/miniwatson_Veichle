"use client";
import { useEffect, useState } from "react";
import { api, type CaseRecord, type EstimateResult } from "@/lib/api";
import CarImage from "@/components/CarImage";
import PartImage from "@/components/PartImage";
import Markdown from "@/components/Markdown";

const num = (v: unknown) => Number(v) || 0;
const won = (n: number) => Math.round(Number(n) || 0).toLocaleString("ko-KR") + "원";
const RKEY = "mw-resolved-cases";

function Badges({ c }: { c: CaseRecord }) {
  const fire = num(c[7]), crash = num(c[8]), inj = num(c[9]), dea = num(c[10]);
  if (num(c[6]) === 0) return null;
  return (
    <div className="row" style={{ gap: 4, flexWrap: "wrap" }}>
      {dea > 0 && <span className="pill bad">사망 {dea}</span>}
      {inj > 0 && <span className="pill warn">부상 {inj}</span>}
      {fire > 0 && <span className="pill bad">화재</span>}
      {crash > 0 && <span className="pill warn">사고</span>}
    </div>
  );
}

export default function CaseTriagePanel({ onNavigate, initialModel, initialCaseId }:
  { onNavigate?: (id: string, payload?: string) => void; initialModel?: string; initialCaseId?: string }) {
  const [carModels, setCarModels] = useState<string[]>([]);
  const [model, setModel] = useState(initialModel || "");
  const [component, setComponent] = useState("");
  const [cases, setCases] = useState<CaseRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const [resolved, setResolved] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<CaseRecord | null>(null);

  // 선택 케이스 상세
  const [parts, setParts] = useState<EstimateResult | "loading" | null>(null);
  const [chkItems, setChkItems] = useState<string[]>([]);
  const [carChk, setCarChk] = useState<[string, number, string][]>([]);
  const [caseDiag, setCaseDiag] = useState("");        // AI 진단 코멘트(RAG)
  const [diagBusy, setDiagBusy] = useState(false);

  useEffect(() => {
    api.summary().then((s) => setCarModels((s.byModel || []).map((m) => String(m[0])))).catch(() => {});
    try { setResolved(new Set(JSON.parse(localStorage.getItem(RKEY) || "[]"))); } catch {}
    load(); /* eslint-disable-next-line */
  }, []);

  // 리포트 등에서 특정 케이스로 넘어오면 그 케이스 상세 자동 오픈
  useEffect(() => {
    if (initialCaseId && cases.length && !selected) {
      const c = cases.find((x) => String(x[0]) === initialCaseId);
      if (c) openCase(c);
    }
    /* eslint-disable-next-line */
  }, [cases, initialCaseId]);

  async function load() {
    setLoading(true); setErr("");
    try { const r = await api.cases(model || undefined, component || undefined); setCases(r.cases || []); if (r.error) setErr(r.error); }
    catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  function resolve(id: string) {
    const n = new Set(resolved); n.add(id); setResolved(n);
    localStorage.setItem(RKEY, JSON.stringify([...n]));
  }
  function clearResolved() { setResolved(new Set()); localStorage.removeItem(RKEY); }

  function openCase(c: CaseRecord) {
    setSelected(c); setParts("loading"); setChkItems([]); setCarChk([]); setCaseDiag(""); setDiagBusy(true);
    const comp = String(c[3]), mdl = String(c[2]);
    api.estimate(comp, mdl).then(setParts).catch(() => setParts(null));
    api.checklist(mdl, comp).then((k) => setChkItems((k.additional || []).map((a) => String(a[0])))).catch(() => {});
    api.checklist(mdl).then((k) => setCarChk(k.additional || [])).catch(() => {});
    // AI 진단 코멘트 — 이 건의 증상·부위로 매뉴얼 근거(RAG) 한국어 진단
    const q = `${mdl} 차량, 증상: "${String(c[5]).slice(0, 200)}" (결함 부위: ${comp}). `
      + `정비사 관점에서 추정 원인과 점검·조치를 한국어로 3~4문장 간결히. 매뉴얼 근거에 충실하고 지어내지 말 것.`;
    api.ask(q, "vehicle").then((r) => setCaseDiag(r.answer || "(근거 부족)")).catch(() => setCaseDiag("")).finally(() => setDiagBusy(false));
  }

  const queue = cases.filter((c) => !resolved.has(String(c[0]))).slice(0, 5);

  // ───────── 상세 페이지 ─────────
  if (selected) {
    const c = selected, id = String(c[0]), mdl = String(c[2]);
    const accent = num(c[10]) > 0 || num(c[7]) > 0 ? "var(--danger)" : num(c[6]) > 0 ? "var(--warn)" : "var(--border)";
    return (
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <button className="ghost" style={{ fontSize: 12 }} onClick={() => setSelected(null)}>← 케이스 큐</button>
          <div className="row" style={{ gap: 6 }}>
            {onNavigate && <button className="ghost" style={{ fontSize: 12 }} onClick={() => onNavigate("report", mdl)}>차종 종합 리포트 →</button>}
            <button className="btn" style={{ fontSize: 12 }} onClick={() => { resolve(id); setSelected(null); }}>해결 처리</button>
          </div>
        </div>

        {/* 클라이언트 차량 케이스 헤더 */}
        <div className="car-hero" style={{ marginTop: 12, borderLeft: `4px solid ${accent}` }}>
          <CarImage model={mdl} height={140} rounded={false} />
          <div className="car-hero-overlay">
            <div className="kicker" style={{ color: "#cfe0ff" }}>차량 케이스 진단</div>
            <h2>{mdl} <span style={{ fontSize: 14, fontWeight: 400, opacity: .85 }}>접수 #{id}</span></h2>
            <p>{c[4]}년 · {c[1]} · 부위: {String(c[3])}</p>
          </div>
        </div>
        <div style={{ marginTop: 10 }}><Badges c={c} /></div>
        <div className="snip" style={{ marginTop: 10, fontStyle: "italic" }}>접수 내용: {String(c[5])}</div>

        {/* AI 진단 코멘트 — 매뉴얼 근거(RAG) */}
        <div className="label" style={{ marginTop: 16 }}>AI 진단 코멘트 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(매뉴얼 근거)</span></div>
        {diagBusy ? <div className="muted" style={{ fontSize: 13 }}>이 건을 진단하는 중…</div>
          : caseDiag ? <div className="answer"><Markdown text={caseDiag} /></div>
          : <div className="muted" style={{ fontSize: 13 }}>—</div>}

        {/* 필요 부품 + 이미지 */}
        <div className="label" style={{ marginTop: 18 }}>필요 부품</div>
        {parts === "loading" && <div className="muted" style={{ fontSize: 13 }}>부품·견적 산정 중…</div>}
        {parts && parts !== "loading" && parts.items.length === 0 && <div className="muted" style={{ fontSize: 13 }}>해당 부품을 찾지 못했어요(부위 신호 약함).</div>}
        {parts && parts !== "loading" && parts.items.map((it, i) => (
          <div key={i} className="row" style={{ gap: 12, alignItems: "center", padding: "10px 0", borderBottom: "1px solid var(--border)" }}>
            <PartImage part={it.part} height={72} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 600 }}>{it.part}</div>
              <div className="muted" style={{ fontSize: 12 }}>{it.component} · 공임 {it.laborHours}h</div>
            </div>
            <div style={{ textAlign: "right" }}>
              <div style={{ fontWeight: 600 }}>{won(it.lineTotal)}</div>
              <div className="muted" style={{ fontSize: 11 }}>부품 {won(it.unitPrice)} + 공임 {won(it.laborCost)}</div>
            </div>
          </div>
        ))}

        {/* 견적서 합계 */}
        {parts && parts !== "loading" && parts.items.length > 0 && (
          <div style={{ marginTop: 10, padding: "10px 12px", background: "var(--surface-2)", borderRadius: 8, fontSize: 13 }}>
            <div className="row" style={{ justifyContent: "space-between" }}><span className="muted">부품계</span><span>{won(parts.partsTotal)}</span></div>
            <div className="row" style={{ justifyContent: "space-between" }}><span className="muted">공임계</span><span>{won(parts.laborTotal)}</span></div>
            <div className="row" style={{ justifyContent: "space-between" }}><span className="muted">부가세(10%)</span><span>{won(parts.vat ?? 0)}</span></div>
            <div className="row" style={{ justifyContent: "space-between", fontWeight: 700, borderTop: "1px solid var(--border)", paddingTop: 4, marginTop: 4 }}><span>합계</span><span>{won(parts.total ?? parts.grandTotal)}</span></div>
            <div className="hint" style={{ marginTop: 4 }}>샘플 단가(예시) — 실제 청구액 아님. 운영은 현대모비스 부품가·표준공임 연동.</div>
          </div>
        )}

        {/* 이 건 점검 항목 */}
        <div className="label" style={{ marginTop: 18 }}>이 건 점검 항목 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(부위: {String(c[3]).slice(0, 40)})</span></div>
        {chkItems.length === 0 ? <div className="muted" style={{ fontSize: 13 }}>매핑된 점검 항목 없음</div>
          : chkItems.map((it, i) => <div key={i} style={{ fontSize: 13.5, padding: "3px 0" }}><span style={{ color: "var(--warn)" }}>☐</span> {it}</div>)}

        {/* 차종 추가 점검 (참고) */}
        <div className="label" style={{ marginTop: 18 }}>차종 추가 점검 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(참고 · {mdl} 전반 빈도순)</span></div>
        {carChk.length === 0 ? <div className="muted" style={{ fontSize: 13 }}>—</div>
          : carChk.slice(0, 6).map((r, i) => (
            <div key={i} className="row" style={{ justifyContent: "space-between", fontSize: 13, padding: "2px 0" }}>
              <span>☐ {r[0]}</span><span className="badge" style={{ marginLeft: 0 }}>{r[1]}건</span>
            </div>
          ))}
      </div>
    );
  }

  // ───────── 작업 큐 (상위 5, 해결 시 제거) ─────────
  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ margin: 0 }}>케이스 트리아지 <span className="muted" style={{ fontSize: 13 }}>· 작업 큐</span></h2>
        <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            <option value="">전체 차종</option>
            {carModels.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <input type="text" placeholder="부위 키워드" value={component} onChange={(e) => setComponent(e.target.value)} onKeyDown={(e) => e.key === "Enter" && load()} style={{ width: 160 }} />
          <button className="btn" onClick={load} disabled={loading}>{loading ? "조회 중…" : "조회"}</button>
        </div>
      </div>
      <div className="hint">심각도 우선순위 <b>상위 5건</b>만 보여줍니다. 카드를 누르면 차량 케이스 진단 페이지로, "해결"하면 큐에서 사라집니다. {resolved.size > 0 && <a onClick={clearResolved} style={{ cursor: "pointer" }}>해결 {resolved.size}건 초기화</a>}</div>

      {err && <div className="err">{err}</div>}
      {!loading && queue.length === 0 && <div className="empty"><div className="empty-ic"><svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5" /></svg></div><div>대기 중인 케이스가 없습니다(모두 해결됨 또는 조건 없음).</div></div>}

      <div style={{ marginTop: 12, display: "flex", flexDirection: "column", gap: 10 }}>
        {queue.map((c, i) => {
          const id = String(c[0]);
          const accent = num(c[10]) > 0 || num(c[7]) > 0 ? "var(--danger)" : num(c[6]) > 0 ? "var(--warn)" : "var(--border)";
          return (
            <div key={i} style={{ border: "1px solid var(--border)", borderLeft: `4px solid ${accent}`, borderRadius: 10, padding: "12px 14px", cursor: "pointer" }} onClick={() => openCase(c)}>
              <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", gap: 10 }}>
                <span style={{ width: 64, flexShrink: 0 }}><CarImage model={String(c[2])} height={40} /></span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="muted" style={{ fontSize: 12 }}><span className="badge" style={{ marginLeft: 0 }}>우선순위 {num(c[6])}</span> {c[2]} · 접수 #{id} · {c[4]}년</div>
                  <div style={{ fontWeight: 600, fontSize: 13.5, marginTop: 2 }}>{String(c[3])}</div>
                  <div style={{ marginTop: 4 }}><Badges c={c} /></div>
                  <div className="snip" style={{ marginTop: 4 }}>{String(c[5]).slice(0, 110)}…</div>
                </div>
                <div className="row" style={{ gap: 6, whiteSpace: "nowrap" }}>
                  <span className="muted" style={{ fontSize: 12 }}>진단 보기 →</span>
                  <button className="ghost" style={{ fontSize: 12 }} onClick={(e) => { e.stopPropagation(); resolve(id); }}>해결</button>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
