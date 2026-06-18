"use client";
import { useEffect, useState } from "react";
import { api, type QueryLog, type Stats } from "@/lib/api";

export default function GovernancePanel() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [logs, setLogs] = useState<QueryLog[]>([]);
  const [err, setErr] = useState("");

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
        {stats && (
          <div className="cards" style={{ marginTop: 14 }}>
            <div className="stat"><div className="v">{stats.totalCalls}</div><div className="l">총 호출</div></div>
            <div className="stat"><div className="v">{stats.avgLatencyMs} ms</div><div className="l">평균 지연</div></div>
            <div className="stat"><div className="v">{stats.totalPii}</div><div className="l">개인정보 마스킹</div></div>
            <div className="stat"><div className="v">{stats.totalDocs}</div><div className="l">문서 수</div></div>
          </div>
        )}
        {stats && stats.byModel?.length > 0 && (
          <>
            <div className="label">모델별</div>
            {stats.byModel.map((m, i) => <div key={i} className="muted">{m.model}: {m.calls} calls, {m.avgMs} ms avg</div>)}
          </>
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
                  <td>{l.piiCount && l.piiCount > 0 ? `🔒 ${l.piiCount}` : "–"}</td>
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
