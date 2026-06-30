"use client";
import { useEffect, useState } from "react";
import { api, type CaseRecord, type CaseReport } from "@/lib/api";
import CarImage from "@/components/CarImage";
import PartImage from "@/components/PartImage";
import Markdown from "@/components/Markdown";

const num = (v: unknown) => Number(v) || 0;
const won = (n: number) => Math.round(Number(n) || 0).toLocaleString("ko-KR") + "원";
const PAGE = 8;

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

/** 윈도형 페이지 번호 (현재 ±2 + 처음/끝). -1 = 생략(…) */
function pageWindow(cur: number, last: number): number[] {
  if (last <= 7) return Array.from({ length: last }, (_, i) => i);
  const s = new Set<number>([0, last - 1, cur, cur - 1, cur + 1, cur - 2, cur + 2]);
  const arr = [...s].filter((p) => p >= 0 && p < last).sort((a, b) => a - b);
  const out: number[] = [];
  for (let i = 0; i < arr.length; i++) {
    if (i > 0 && arr[i] - arr[i - 1] > 1) out.push(-1);
    out.push(arr[i]);
  }
  return out;
}

export default function CaseTriagePanel({ onNavigate, initialModel, initialCaseId }:
  { onNavigate?: (id: string, payload?: string) => void; initialModel?: string; initialCaseId?: string }) {
  const [carModels, setCarModels] = useState<string[]>([]);
  const [model, setModel] = useState(initialModel || "");
  const [component, setComponent] = useState("");
  const [sort, setSort] = useState<"priority" | "model">("priority");
  const [cases, setCases] = useState<CaseRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const [resolvedCount, setResolvedCount] = useState(0);
  const [showResolved, setShowResolved] = useState(false);
  const [resolvedList, setResolvedList] = useState<{ caseNumber: string; note: string; resolvedAt: string }[]>([]);
  const [selected, setSelected] = useState<CaseRecord | null>(null);

  // 선택 케이스 상세 = 적재된 접수번호 리포트(스냅샷)
  const [report, setReport] = useState<CaseReport | "loading" | null>(null);
  const [noteText, setNoteText] = useState("");
  const [noteSaving, setNoteSaving] = useState(false);
  const [noteSaved, setNoteSaved] = useState(false);

  useEffect(() => {
    api.summary().then((s) => setCarModels((s.byModel || []).map((m) => String(m[0])))).catch(() => {});
    refreshResolvedCount();
    load(0); /* eslint-disable-next-line */
  }, []);

  // 리포트 등에서 특정 케이스로 넘어오면 그 케이스 상세 자동 오픈 (현재 페이지에 없으면 단건 조회)
  useEffect(() => {
    if (!initialCaseId || selected) return;
    const c = cases.find((x) => String(x[0]) === initialCaseId);
    if (c) { openCase(c); return; }
    api.caseById(initialCaseId).then((r) => {
      if (Array.isArray(r.case) && r.case.length) openCase(r.case as CaseRecord);
    }).catch(() => {});
    /* eslint-disable-next-line */
  }, [cases, initialCaseId]);

  function refreshResolvedCount() {
    api.resolvedCases().then((r) => setResolvedCount((r.resolved || []).length)).catch(() => {});
  }

  async function load(p: number, sortOverride?: "priority" | "model") {
    setLoading(true); setErr("");
    try {
      const r = await api.cases(model || undefined, component || undefined, p * PAGE, PAGE, sortOverride ?? sort);
      setCases(r.cases || []); setTotal(r.total || 0); setPage(p);
      if (r.error) setErr(r.error);
    } catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  async function resolve(id: string) {
    try { await api.resolveCase(id); } catch {}
    refreshResolvedCount();
    // 해결된 항목이 빠지면 현재 페이지가 비는 경우 한 칸 앞으로
    const lastPage = Math.max(0, Math.ceil((total - 1) / PAGE) - 1);
    await load(Math.min(page, lastPage));
  }

  function toggleResolved() {
    const next = !showResolved; setShowResolved(next);
    if (next) api.resolvedCases().then((r) => setResolvedList(r.resolved || [])).catch(() => {});
  }
  async function restore(id: string) {
    try { await api.unresolveCase(id); } catch {}
    api.resolvedCases().then((r) => { setResolvedList(r.resolved || []); setResolvedCount((r.resolved || []).length); }).catch(() => {});
    load(page);
  }

  function openCase(c: CaseRecord) {
    setSelected(c); setReport("loading"); setNoteText(""); setNoteSaved(false);
    api.caseReport(String(c[0]))
      .then((r) => { setReport(r); setNoteText(r.note || ""); })
      .catch((e) => setReport({ caseNumber: String(c[0]), error: String(e) } as CaseReport));
  }
  function regenReport(id: string) {
    setReport("loading"); setNoteSaved(false);
    api.caseReport(id, undefined, true)
      .then((r) => { setReport(r); setNoteText(r.note || ""); })
      .catch((e) => setReport({ caseNumber: id, error: String(e) } as CaseReport));
  }
  async function saveNote(id: string) {
    setNoteSaving(true); setNoteSaved(false);
    try { const r = await api.saveCaseNote(id, noteText); setReport(r); setNoteSaved(true); }
    catch {} finally { setNoteSaving(false); }
  }

  // ───────── 접수번호 리포트 (적재 스냅샷: AI 진단 + 정비사 메모 + 견적 + 점검) ─────────
  if (selected) {
    const c = selected, id = String(c[0]), mdl = String(c[2]);
    const accent = num(c[10]) > 0 || num(c[7]) > 0 ? "var(--danger)" : num(c[6]) > 0 ? "var(--warn)" : "var(--border)";
    const rep = report !== "loading" && report ? report : null;
    const est = rep?.estimate;
    const thisChk = (rep?.checklistThis?.additional || []).map((a) => String(a[0]));
    const carChk = rep?.checklistCar?.additional || [];
    return (
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <button className="ghost" style={{ fontSize: 12 }} onClick={() => setSelected(null)}>← 케이스 큐</button>
          <div className="row" style={{ gap: 6 }}>
            <button className="ghost" style={{ fontSize: 12 }} onClick={() => regenReport(id)} disabled={report === "loading"} title="진단·견적·점검을 새로 생성해 적재본 갱신">↻ 재생성</button>
            {onNavigate && <button className="ghost" style={{ fontSize: 12 }} onClick={() => onNavigate("report", mdl)}>차종 카테고리 →</button>}
            <button className="btn" style={{ fontSize: 12 }} onClick={() => { resolve(id); setSelected(null); }}>해결 처리</button>
          </div>
        </div>

        {/* 클라이언트 차량 케이스 헤더 */}
        <div className="car-hero" style={{ marginTop: 12, borderLeft: `4px solid ${accent}` }}>
          <CarImage model={mdl} height={140} rounded={false} />
          <div className="car-hero-overlay">
            <div className="kicker" style={{ color: "#cfe0ff" }}>접수번호 진단 리포트</div>
            <h2>{mdl} <span style={{ fontSize: 14, fontWeight: 400, opacity: .85 }}>접수 #{id}</span></h2>
            <p>{c[4]}년 · {c[1]} · 부위: {String(c[3])}</p>
          </div>
        </div>
        <div className="row" style={{ marginTop: 10, justifyContent: "space-between", alignItems: "center" }}>
          <Badges c={c} />
          {rep?.generatedAt && <span className="muted" style={{ fontSize: 12 }}>적재 {rep.generatedAt.slice(0, 16).replace("T", " ")}{rep.cached ? " · 캐시" : " · 방금"}</span>}
        </div>
        <div className="snip" style={{ marginTop: 10, fontStyle: "italic" }}>접수 내용: {rep?.summary || String(c[5])}</div>

        {report === "loading" && <div className="muted" style={{ fontSize: 13, marginTop: 14 }}>리포트 불러오는 중(최초 1회 생성 시 진단에 수십 초)…</div>}
        {report === null && <div className="err" style={{ marginTop: 14 }}>리포트를 불러오지 못했습니다.</div>}
        {rep?.error && <div className="err" style={{ marginTop: 14 }}>리포트 생성 실패: {rep.error}
          <div className="hint" style={{ marginTop: 6 }}>404면 백엔드가 새 코드로 재시작 안 된 것(/api/agent/case-report). 500이면 메시지를 알려줘.</div></div>}

        {rep && !rep.error && (<>
          {/* AI 진단 — 매뉴얼 근거(RAG) */}
          <div className="label" style={{ marginTop: 16 }}>AI 진단 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(매뉴얼 근거)</span></div>
          {rep.diagnosis ? <div className="answer"><Markdown text={rep.diagnosis} /></div> : <div className="muted" style={{ fontSize: 13 }}>—</div>}
          {rep.sources && rep.sources.length > 0 && <div className="hint" style={{ marginTop: 4 }}>근거: {rep.sources.join(" · ")}</div>}

          {/* 정비사 메모 — 사용자 작성, 문서화·적재 */}
          <div className="label" style={{ marginTop: 18 }}>정비사 메모 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(작성 시 리포트에 함께 적재)</span></div>
          <textarea value={noteText} onChange={(e) => { setNoteText(e.target.value); setNoteSaved(false); }}
            placeholder="점검 소견·작업 내용·고객 안내 등을 적으면 이 접수번호 리포트에 저장됩니다."
            style={{ width: "100%", minHeight: 90, resize: "vertical", padding: "10px 12px", fontSize: 13.5, lineHeight: 1.5, borderRadius: 8, border: "1px solid var(--border)", background: "var(--surface)", color: "var(--text)" }} />
          <div className="row" style={{ justifyContent: "flex-end", gap: 8, marginTop: 6 }}>
            {noteSaved && <span className="muted" style={{ fontSize: 12, color: "var(--ok, var(--accent))" }}>저장됨 ✓</span>}
            <button className="btn" style={{ fontSize: 12 }} onClick={() => saveNote(id)} disabled={noteSaving}>{noteSaving ? "저장 중…" : "메모 저장"}</button>
          </div>

          {/* 필요 부품 + 이미지 */}
          <div className="label" style={{ marginTop: 18 }}>필요 부품</div>
          {!est || est.items.length === 0 ? <div className="muted" style={{ fontSize: 13 }}>해당 부품을 찾지 못했어요(부위 신호 약함).</div>
            : est.items.map((it, i) => (
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
          {est && est.items.length > 0 && (
            <div style={{ marginTop: 10, padding: "10px 12px", background: "var(--surface-2)", borderRadius: 8, fontSize: 13 }}>
              <div className="row" style={{ justifyContent: "space-between" }}><span className="muted">부품계</span><span>{won(est.partsTotal)}</span></div>
              <div className="row" style={{ justifyContent: "space-between" }}><span className="muted">공임계</span><span>{won(est.laborTotal)}</span></div>
              <div className="row" style={{ justifyContent: "space-between" }}><span className="muted">부가세(10%)</span><span>{won(est.vat ?? 0)}</span></div>
              <div className="row" style={{ justifyContent: "space-between", fontWeight: 700, borderTop: "1px solid var(--border)", paddingTop: 4, marginTop: 4 }}><span>합계</span><span>{won(est.total ?? est.grandTotal)}</span></div>
              <div className="hint" style={{ marginTop: 4 }}>샘플 단가(예시) — 실제 청구액 아님. 운영은 현대모비스 부품가·표준공임 연동.</div>
            </div>
          )}

          {/* 이 건 점검 항목 */}
          <div className="label" style={{ marginTop: 18 }}>이 건 점검 항목 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(부위: {String(c[3]).slice(0, 40)})</span></div>
          {thisChk.length === 0 ? <div className="muted" style={{ fontSize: 13 }}>매핑된 점검 항목 없음</div>
            : thisChk.map((it, i) => <div key={i} className="chk" style={{ fontSize: 13.5, padding: "3px 0" }}>{it}</div>)}

          {/* 차종 추가 점검 (참고) */}
          <div className="label" style={{ marginTop: 18 }}>차종 추가 점검 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(참고 · {mdl} 전반 빈도순)</span></div>
          {carChk.length === 0 ? <div className="muted" style={{ fontSize: 13 }}>—</div>
            : carChk.slice(0, 6).map((r, i) => (
              <div key={i} className="row" style={{ justifyContent: "space-between", fontSize: 13, padding: "2px 0" }}>
                <span className="chk">{r[0]}</span><span className="badge" style={{ marginLeft: 0 }}>{r[1]}건</span>
              </div>
            ))}
        </>)}
      </div>
    );
  }

  // ───────── 케이스 큐 (우선순위 정렬 + 페이지네이션 + 해결 영속) ─────────
  const lastPage = Math.max(1, Math.ceil(total / PAGE));
  const win = pageWindow(page, lastPage);
  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ margin: 0 }}>케이스 트리아지 <span className="muted" style={{ fontSize: 13 }}>· 우선순위 큐</span></h2>
        <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
          <select value={sort} onChange={(e) => { const s = e.target.value as "priority" | "model"; setSort(s); load(0, s); }} title="정렬 기준">
            <option value="priority">우선순위순</option>
            <option value="model">차종별 · 우선순위순</option>
          </select>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            <option value="">전체 차종</option>
            {carModels.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <input type="text" placeholder="부위 키워드" value={component} onChange={(e) => setComponent(e.target.value)} onKeyDown={(e) => e.key === "Enter" && load(0)} style={{ width: 160 }} />
          <button className="btn" onClick={() => load(0)} disabled={loading}>{loading ? "조회 중…" : "조회"}</button>
        </div>
      </div>
      <div className="hint">
        <b>우선순위 = 사망×100 + 부상×10 + 화재×5 + 사고×3 + 최신성</b> (높을수록 시급 · 최신성=최근 접수일수록 가산, 반감 180일). {sort === "model" ? "차종별로 묶어 그 안에서 우선순위순" : "우선순위순으로"} 정렬 · 총 <b>{total.toLocaleString("ko-KR")}건</b> · 페이지 {page + 1}/{lastPage}. 카드를 누르면 접수번호 리포트로, "해결"하면 큐에서 사라집니다(서버 저장).
        {resolvedCount > 0 && <> · <a onClick={toggleResolved} style={{ cursor: "pointer" }}>해결 내역 {resolvedCount}건 {showResolved ? "닫기" : "보기"}</a></>}
      </div>

      {showResolved && (
        <div style={{ marginTop: 8, padding: "8px 10px", background: "var(--surface-2)", borderRadius: 8, fontSize: 13 }}>
          {resolvedList.length === 0 ? <span className="muted">없음</span> : resolvedList.map((r) => (
            <div key={r.caseNumber} className="row" style={{ justifyContent: "space-between", padding: "3px 0" }}>
              <span>접수 #{r.caseNumber} <span className="muted" style={{ fontSize: 11 }}>{(r.resolvedAt || "").slice(0, 16).replace("T", " ")}</span></span>
              <button className="ghost" style={{ fontSize: 12 }} onClick={() => restore(r.caseNumber)}>되돌리기</button>
            </div>
          ))}
        </div>
      )}

      {err && <div className="err">{err}</div>}
      {!loading && cases.length === 0 && <div className="empty"><div className="empty-ic"><svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5" /></svg></div><div>대기 중인 케이스가 없습니다(모두 해결됨 또는 조건 없음).</div></div>}

      <div style={{ marginTop: 12, display: "flex", flexDirection: "column", gap: 10 }}>
        {cases.map((c, i) => {
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
                  <div className="snip" style={{ marginTop: 4 }}>{String(c[5]).slice(0, 140)}…</div>
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

      {/* 페이지네이션 1 · 2 · 3 … */}
      {total > PAGE && (
        <div className="row" style={{ gap: 4, justifyContent: "center", marginTop: 16, flexWrap: "wrap" }}>
          <button className="ghost" style={{ fontSize: 13 }} disabled={page === 0 || loading} onClick={() => load(page - 1)}>‹ 이전</button>
          {win.map((p, i) => p === -1
            ? <span key={`g${i}`} className="muted" style={{ padding: "0 4px" }}>…</span>
            : <button key={p} className={p === page ? "btn" : "ghost"} style={{ fontSize: 13, minWidth: 34 }} disabled={loading} onClick={() => load(p)}>{p + 1}</button>)}
          <button className="ghost" style={{ fontSize: 13 }} disabled={page >= lastPage - 1 || loading} onClick={() => load(page + 1)}>다음 ›</button>
        </div>
      )}
    </div>
  );
}
