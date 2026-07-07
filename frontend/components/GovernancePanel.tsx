"use client";
import { useEffect, useState } from "react";
import { api, type QueryLog, type Stats } from "@/lib/api";

export default function GovernancePanel() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [logs, setLogs] = useState<QueryLog[]>([]);
  const [err, setErr] = useState("");
  // PII 마스킹 before/after 데모
  const [piiText, setPiiText] = useState("");
  const [piiBusy, setPiiBusy] = useState(false);
  const [piiResult, setPiiResult] = useState<{ original: string; masked: string; count: number } | null>(null);

  async function runMask() {
    if (!piiText.trim() || piiBusy) return;
    setPiiBusy(true);
    try { setPiiResult(await api.maskPreview(piiText)); } catch {} finally { setPiiBusy(false); }
  }

  async function refresh() {
    setErr("");
    try {
      const [s, l] = await Promise.all([api.stats(), api.logs()]);
      setStats(s); setLogs([...l].reverse());
    } catch (e) { setErr(String(e)); }
  }
  useEffect(() => { refresh(); }, []);

  return (
    <>
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>거버넌스 현황</h2>
          <button className="ghost" onClick={refresh}>새로고침</button>
        </div>
        {err && <div className="err">{err}</div>}
        {!stats && !err && (
          <div className="empty"><div className="empty-ic"><svg viewBox="0 0 24 24"><path d="M12 3l8 3v6c0 5-3.4 7.7-8 9-4.6-1.3-8-4-8-9V6z" /></svg></div><div>아직 호출 기록이 없습니다. 다른 탭에서 질문하면 모든 LLM 호출이 여기 감사 로그로 쌓입니다.</div></div>
        )}
        {stats && (
          <div className="cards" style={{ marginTop: 14 }}>
            <div className="stat"><div className="v">{stats.totalCalls}</div><div className="l">총 호출</div></div>
            <div className="stat"><div className="v">{stats.avgLatencyMs} ms</div><div className="l">평균 지연</div></div>
            <div className={`stat ${stats.totalPii > 0 ? "warn" : ""}`}><div className="v">{stats.totalPii}</div><div className="l">개인정보 마스킹</div></div>
            <div className="stat"><div className="v">{stats.totalDocs}</div><div className="l">문서 수</div></div>
          </div>
        )}
        {stats && stats.byModel?.length > 0 && (() => {
          const maxCalls = Math.max(1, ...stats.byModel.map((m) => m.calls));
          return (
            <>
              <div className="label">모델별 호출</div>
              <div className="bars">
                {stats.byModel.map((m, i) => (
                  <div className="bar-row" key={i}>
                    <span className="bar-label">{m.model}</span>
                    <span className="bar-track"><span className={`bar-fill s${Math.min(i, 5)}`} style={{ width: `${(m.calls / maxCalls) * 100}%` }} /></span>
                    <span className="bar-val" style={{ width: 96 }}>{m.calls}회, {m.avgMs}ms</span>
                  </div>
                ))}
              </div>
            </>
          );
        })()}
      </div>

      {/* PII 마스킹 before/after — H-Chat류 게이트웨이의 핵심을 눈으로. 원문은 저장 안 됨(마스킹이 저장 전 단계). */}
      <div className="card">
        <h2>PII 마스킹 테스트 <span className="muted" style={{ fontWeight: 400, fontSize: 12 }}>(before/after — 원문은 저장되지 않습니다)</span></h2>
        <div className="row" style={{ alignItems: "stretch" }}>
          <textarea className="grow" value={piiText} onChange={(e) => setPiiText(e.target.value)}
            placeholder={"예: 고객 김철수 연락처는 010-1234-5678, 이메일 kim@example.com 카드번호 1234-5678-9012-3456"}
            style={{ minHeight: 64, resize: "vertical", padding: "9px 11px", fontSize: 13.5, border: "1px solid var(--border-strong)", background: "var(--surface)", color: "var(--text)", borderRadius: 3 }} />
          <button className="btn" style={{ alignSelf: "flex-end" }} onClick={runMask} disabled={!piiText.trim() || piiBusy}>{piiBusy ? "…" : "마스킹"}</button>
        </div>
        {piiResult && (
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginTop: 12 }}>
            <div>
              <div className="label" style={{ margin: "0 0 6px" }}>Before <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(원문 — LLM/저장소로 안 나감)</span></div>
              <div className="answer" style={{ marginTop: 0, borderLeftColor: "var(--danger)", whiteSpace: "pre-wrap", fontSize: 13.5 }}>{piiResult.original}</div>
            </div>
            <div>
              <div className="label" style={{ margin: "0 0 6px" }}>After <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(마스킹 {piiResult.count}건 — 이것만 밖으로)</span></div>
              <div className="answer" style={{ marginTop: 0, borderLeftColor: "var(--ok)", whiteSpace: "pre-wrap", fontSize: 13.5 }}>{piiResult.masked}</div>
            </div>
          </div>
        )}
      </div>

      <div className="card">
        <h2>감사 로그</h2>
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr><th>ID</th><th>질문</th><th>모델</th><th className="right">지연(ms)</th><th>PII</th><th>시각</th></tr>
            </thead>
            <tbody>
              {logs.map((l) => (
                <tr key={l.id}>
                  <td>{l.id}</td>
                  <td title={l.question}>{(l.question || "").slice(0, 60)}</td>
                  <td><span className="badge">{l.model}</span></td>
                  <td className="right">{l.latencyMs}</td>
                  <td>{l.piiCount && l.piiCount > 0 ? <span className="pill warn">PII {l.piiCount}</span> : <span className="muted">–</span>}</td>
                  <td className="muted">{l.createdAt}</td>
                </tr>
              ))}
              {logs.length === 0 && <tr><td colSpan={6} className="muted">로그 없음</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
