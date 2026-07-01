"use client";
import { useEffect, useState } from "react";
import { api, type Analytics, type Models } from "@/lib/api";
import Markdown from "@/components/Markdown";
import Donut from "@/components/Donut";
import TrendChart from "@/components/TrendChart";

const won = (n: number) => Math.round(Number(n) || 0).toLocaleString("ko-KR") + "원";
const num = (v: unknown) => Number(v) || 0;

function Bars({ rows, unit = "", money = false }: { rows: [string, number][]; unit?: string; money?: boolean }) {
  const max = Math.max(1, ...rows.map((r) => num(r[1])));
  return (
    <div className="bars">
      {rows.map((r, i) => (
        <div className="bar-row" key={i}>
          <span className="bar-label">{String(r[0]).slice(0, 20)}</span>
          <span className="bar-track"><span className={`bar-fill s${Math.min(i, 5)}`} style={{ width: `${(num(r[1]) / max) * 100}%` }} /></span>
          <span className="bar-val" style={{ width: money ? 110 : 54 }}>{money ? won(num(r[1])) : num(r[1]) + unit}</span>
        </div>
      ))}
    </div>
  );
}

export default function AnalyticsPanel() {
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const [res, setRes] = useState<Analytics | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const [insight, setInsight] = useState("");
  const [insLoading, setInsLoading] = useState(false);
  // 시계열 추세 (연/월/일)
  const [by, setBy] = useState<"year" | "month" | "day">("year");
  const [recallTrend, setRecallTrend] = useState<[string, number][]>([]);
  const [complaintTrend, setComplaintTrend] = useState<[string, number][]>([]);

  useEffect(() => { api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {}); }, []);

  async function loadTrends(g = by) {
    try {
      // 추세는 전 차종(플릿) 집계 — 여기 model 은 LLM 모델이라 차종 필터로 넘기면 안 됨(버그였음).
      const [rc, cp] = await Promise.all([
        api.trend("recalls", g),
        api.trend("complaints", g),
      ]);
      setRecallTrend(rc.trend || []); setComplaintTrend(cp.trend || []);
    } catch { /* 무시 */ }
  }

  async function load() {
    setLoading(true); setErr(""); setInsight("");   // 데이터 바뀌면 이전 인사이트 비움
    try { setRes(await api.analytics(model || undefined)); }   // 집계(차트) — 빠름
    catch (e) { setErr(String(e)); } finally { setLoading(false); }
    loadTrends();
  }

  // AI 인사이트는 *요청 시에만* 생성(느린 LLM 호출이라 자동 X)
  async function genInsight() {
    setInsLoading(true); setInsight("");
    try { const r = await api.analyticsInsight(model || undefined); setInsight(r.insight); }
    catch { setInsight("(인사이트 생성 실패)"); } finally { setInsLoading(false); }
  }
  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);
  // 그래뉼래리티 바꾸면 추세만 다시
  useEffect(() => { if (res) loadTrends(by); /* eslint-disable-next-line */ }, [by]);

  const t = res?.totals;

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2 style={{ margin: 0 }}>플릿 분석 대시보드</h2>
        <div className="row" style={{ gap: 6 }}>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <button className="btn" onClick={load} disabled={loading}>{loading ? "분석 중…" : "새로고침"}</button>
        </div>
      </div>
      <div className="hint">리콜·불만·부품 데이터를 집계(결정적 SQL)하고, AI가 운영 인사이트를 서술합니다.</div>

      {err && <div className="err">{err}</div>}
      {loading && !res && <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>데이터 집계 중…</div></div>}

      {res && (
        <>
          {/* KPI */}
          <div className="cards" style={{ marginTop: 14 }}>
            <div className="stat"><div className="v">{num(t?.recalls)}</div><div className="l">리콜</div></div>
            <div className="stat"><div className="v">{num(t?.complaints)}</div><div className="l">불만</div></div>
            <div className={`stat ${num(t?.fires) > 0 ? "danger" : ""}`}><div className="v">{num(t?.fires)}</div><div className="l">화재</div></div>
            <div className={`stat ${num(t?.injuries) > 0 ? "warn" : ""}`}><div className="v">{num(t?.injuries)}</div><div className="l">부상</div></div>
            <div className="stat"><div className="v">{num(t?.crashes)}</div><div className="l">사고</div></div>
          </div>

          {/* 부품 수요 / 워런티 비용 프록시 */}
          <div className="label">부품 수요 | 예상 워런티 비용 (결함 신호 × 단가)</div>
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr><th>부품</th><th>부위</th><th className="right">수요(신호)</th><th className="right">단가</th><th className="right">예상 비용</th></tr></thead>
              <tbody>
                {res.partsDemand.map((r, i) => (
                  <tr key={i}>
                    <td>{r[0]}</td><td className="muted">{r[1]}</td>
                    <td className="right">{num(r[2])}</td><td className="right">{won(num(r[3]))}</td>
                    <td className="right"><b>{won(num(r[4]))}</b></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="hint">수요=불만에 해당 부위가 등장한 횟수(프록시). 예상비용=수요×(단가+공임). 정확 청구액 아닌 운영 우선순위용.</div>

          {/* 추세 분석 — 연/월/일 그래뉼래리티 (분석가용) */}
          <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline", marginTop: 10 }}>
            <div className="label" style={{ margin: 0 }}>추세 분석 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>· 전 차종</span></div>
            <div className="row" style={{ gap: 4 }}>
              {(["year", "month", "day"] as const).map((g) => (
                <button key={g} className={by === g ? "btn" : "ghost"} style={{ fontSize: 12, padding: "4px 10px" }} onClick={() => setBy(g)}>
                  {g === "year" ? "연도별" : g === "month" ? "월별" : "일별"}
                </button>
              ))}
            </div>
          </div>
          {(recallTrend.length > 0 || complaintTrend.length > 0)
            ? <TrendChart unit="건" series={[
                { name: "리콜", color: "#002c5f", data: recallTrend },
                { name: "불만", color: "#d97706", data: complaintTrend },
              ]} />
            : <div className="muted" style={{ fontSize: 13 }}>추세 데이터 없음</div>}

          {/* 결함 부위 */}
          {res.recallTopComponents?.length > 0 && (<><div className="label">리콜 주요 부위</div><Donut rows={res.recallTopComponents} unit="건" /></>)}
          {res.complaintTopComponents?.length > 0 && (<><div className="label">불만 주요 부위</div><Donut rows={res.complaintTopComponents} unit="건" /></>)}
          {res.complaintByModel?.length > 0 && (<><div className="label">차종별 불만</div><Bars rows={res.complaintByModel} unit="건" /></>)}

          {/* 안전 핫스팟 */}
          {res.safetyHotspots?.length > 0 && (
            <>
              <div className="label">안전 핫스팟 (차종별 화재·부상·사고)</div>
              <div style={{ overflowX: "auto" }}>
                <table>
                  <thead><tr><th>차종</th><th className="right">화재</th><th className="right">부상</th><th className="right">사고</th></tr></thead>
                  <tbody>
                    {res.safetyHotspots.map((r, i) => (
                      <tr key={i}>
                        <td>{r[0]}</td>
                        <td className="right">{num(r[1]) > 0 ? <span className="pill bad">{num(r[1])}</span> : 0}</td>
                        <td className="right">{num(r[2]) > 0 ? <span className="pill warn">{num(r[2])}</span> : 0}</td>
                        <td className="right">{num(r[3])}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {/* AI 인사이트 — 요청 시에만 생성(버튼) */}
          <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline", marginTop: 10 }}>
            <div className="label" style={{ margin: 0 }}>AI 운영 인사이트</div>
            {!insLoading && <button className="btn" style={{ fontSize: 12 }} onClick={genInsight}>{insight ? "다시 생성" : "AI 인사이트 생성"}</button>}
          </div>
          {insLoading
            ? <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>집계를 바탕으로 AI가 인사이트를 작성 중…</div></div>
            : insight
              ? <div className="answer"><Markdown text={insight} /></div>
              : <div className="muted" style={{ fontSize: 13, marginTop: 6 }}>버튼을 누르면 위 집계를 근거로 AI가 운영 인사이트를 서술합니다(느린 LLM 호출이라 요청 시에만).</div>}
        </>
      )}
    </div>
  );
}
