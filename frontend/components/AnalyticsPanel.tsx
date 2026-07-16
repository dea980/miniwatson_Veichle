"use client";
import { useEffect, useState } from "react";
import { api, koModel, type Analytics, type Models, type Briefing } from "@/lib/api";
import Markdown from "@/components/Markdown";
import Donut from "@/components/Donut";
import TrendChart from "@/components/TrendChart";
import Select from "@/components/Select";
import USStateMap from "./USStateMap";

const won = (n: number) => Math.round(Number(n) || 0).toLocaleString("ko-KR") + "원";
const num = (v: unknown) => Number(v) || 0;

// drill-down 레벨 — 각 레벨이 답하는 질문(thinking line)이 다르다.
const LEVEL_META = {
  overview: { label: "개요", q: "무엇이 이상한가" },
  recall: { label: "리콜", q: "규제 리스크는" },
  safety: { label: "안전", q: "누가 다치나" },
  parts: { label: "부품·워런티", q: "돈이 어디로" },
  geo: { label: "지역", q: "어디서 터지나" },
} as const;

//trend META
const TREND_META: Record<Level, { title: string; note: string}> = {
overview: { title: "추세 분석", note: "전 차종, 리콜/불만" },
  recall:   { title: "리콜 추세", note: "규제성 안전" },
  safety:   { title: "안전 추세", note: "화재/부상/사고 — 볼륨 아닌 위해도" },
  parts:    { title: "수요 추세", note: "불만 건수 프록시(청구액 아님)" },
  geo:      { title: "불만 추세", note: "전 지역 합계(주별 분해는 아래 표)" },
};
type Level = keyof typeof LEVEL_META;

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

// 기간 토글 — 전체/최근 1년·1개월·1주. 대시보드 전체(KPI·표·카드·추세)를 이 기간으로 스코프.
function GranToggle({ by, setBy }: { by: "all" | "year" | "month" | "week"; setBy: (g: "all" | "year" | "month" | "week") => void }) {
  const LABELS: Record<string, string> = { all: "전체", year: "최근 1년", month: "최근 1개월", week: "최근 1주" };
  return (
    <div className="row" style={{ gap: 4 }}>
      {(["all", "year", "month", "week"] as const).map((g) => (
        <button key={g} className={by === g ? "btn" : "ghost"} style={{ fontSize: 12, padding: "4px 10px" }} onClick={() => setBy(g)}>
          {LABELS[g]}
        </button>
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
  // drill-down 레벨 (L0 개요 → L1 딥다이브)
  const [level, setLevel] = useState<Level>("overview");
  // 기간 필터 (전체/최근 1년·1개월·1주) — 대시보드 전체 스코프
  const [by, setBy] = useState<"all" | "year" | "month" | "week">("all");
  type Series = { name: string; color: string; data: [string, number][] };
  const [trends, setTrends] = useState<Series[]>([]);
  // 주간 품질 브리핑 — 캐시 히트면 즉시, 최초 생성만 LLM 수십 초
  const [brief, setBrief] = useState<Briefing | "loading" | null>(null);

  function loadBriefing(force = false) {
    setBrief("loading");
    api.briefing(force).then((b) => setBrief(b.error ? null : b)).catch(() => setBrief(null));
  }

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
    loadBriefing();
  }, []);

  async function loadTrends(g = by, lv = level) {
    const H = "#002c5f", O = "#d97706", R = "#dc2626", W = "#f59e0b";
    try {
      let s: Series[] = [];
      if (lv === "overview") {
        const [rc, cp] = await Promise.all([api.trend("recalls", g), api.trend("complaints", g)]);
        s = [{ name: "리콜", color: H, data: rc.trend || [] }, { name: "불만", color: O, data: cp.trend || [] }];
      } else if (lv === "recall") {
        const rc = await api.trend("recalls", g);
        s = [{ name: "리콜", color: H, data: rc.trend || [] }];
      } else if (lv === "safety") {
        const [fi, inj, cr] = await Promise.all([
          api.trend("complaints", g, undefined, "fire"),
          api.trend("complaints", g, undefined, "injury"),
          api.trend("complaints", g, undefined, "crash"),
        ]);
        s = [{ name: "화재", color: R, data: fi.trend || [] }, { name: "부상", color: W, data: inj.trend || [] }, { name: "사고", color: H, data: cr.trend || [] }];
      } else {
        const cp = await api.trend("complaints", g);
        s = [{ name: lv === "parts" ? "불만(수요 프록시)" : "불만", color: O, data: cp.trend || [] }];
      }
      setTrends(s);
    } catch { /* 무시 */ }
  }

  async function load() {
    setLoading(true); setErr(""); setInsight("");   // 데이터 바뀌면 이전 인사이트 비움
    try { setRes(await api.analytics(model || undefined, by)); }   // 집계(차트) — 기간 스코프, 빠름
    catch (e) { setErr(String(e)); } finally { setLoading(false); }
    loadTrends();
  }

  // AI 인사이트는 *요청 시에만* 생성(느린 LLM 호출이라 자동 X)
  async function genInsight() {
    setInsLoading(true); setInsight("");
    try { const r = await api.analyticsInsight(model || undefined, level, by); setInsight(r.insight); }   // 현재 탭(레벨)·그래뉼래리티 기준
    catch { setInsight("(인사이트 생성 실패)"); } finally { setInsLoading(false); }
  }
  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);
  // 탭(레벨) OR 기간 바뀌면 이전 인사이트는 비운다 — 화면과 어긋난 서술을 안 남긴다
  useEffect(() => { setInsight(""); }, [level, by]);
  // 기간 바뀌면 전체 재집계(KPI·표·카드·추세 모두 그 기간 기준)
  useEffect(() => { if (res) load(); /* eslint-disable-next-line */ }, [by]);
  // 레벨만 바뀌면 추세만 다시(집계는 한 페이로드라 재요청 불필요)
  useEffect(() => { if (res) loadTrends(by, level); /* eslint-disable-next-line */ }, [level]);

  const t = res?.totals;
  // 3렌즈 요약(개요) — 기존 집계에서 파생. 볼륨·심각도·비용이 서로 다른 대상을 가리킨다.
  const lensVolume = res?.complaintByModel?.[0];
  const lensSeverity = res ? [...(res.safetyHotspots || [])].sort((a, b) => num(b[2]) - num(a[2]))[0] : undefined;
  const lensCost = res?.partsDemand?.[0];

  return (
    <>
    {/* 주간 품질 브리핑 — 집계는 결정적 SQL, 서술만 LLM(주간 키 캐시) */}
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2 style={{ margin: 0 }}>주간 품질 브리핑</h2>
        <div className="row" style={{ gap: 8 }}>
          {brief && brief !== "loading" && brief.generatedAt &&
            <span className="muted" style={{ fontSize: 12 }}>{brief.cached ? "캐시" : "방금 생성"} | {brief.generatedAt.slice(0, 16).replace("T", " ")}</span>}
          <button className="ghost" style={{ fontSize: 12 }} onClick={() => loadBriefing(true)} disabled={brief === "loading"}>↻ 재생성</button>
        </div>
      </div>
      {brief === "loading" && <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>브리핑 준비 중(최초 1회 생성 시 수십 초)…</div>}
      {brief === null && <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>브리핑을 불러오지 못했습니다.</div>}
      {brief && brief !== "loading" && (
        <>
          <div className="hint" style={{ marginTop: 4 }}>데이터 기준 주간: {brief.from} ~ {brief.to} (접수일 최신 7일)</div>
          <div className="cards" style={{ marginTop: 12 }}>
            <div className="stat"><div className="v">{num(brief.complaints)}</div><div className="l">신규 불만</div></div>
            <div className="stat"><div className="v">{num(brief.recalls)}</div><div className="l">신규 리콜</div></div>
            <div className={`stat ${num(brief.deaths) > 0 ? "danger" : ""}`}><div className="v">{num(brief.deaths)}</div><div className="l">사망</div></div>
            <div className={`stat ${num(brief.fires) > 0 ? "danger" : num(brief.injuries) > 0 ? "warn" : ""}`}><div className="v">{num(brief.fires)}</div><div className="l">화재</div></div>
          </div>
          <div className="row" style={{ gap: 6, marginTop: 10, flexWrap: "wrap" }}>
            {(brief.topModels || []).map((m, i) => <span key={i} className="badge" style={{ marginLeft: 0 }}>{koModel(String(m[0]))} {num(m[1])}건</span>)}
            {(brief.topComponents || []).map((c, i) => <span key={i} className="badge" style={{ marginLeft: 0 }}>{String(c[0]).slice(0, 24)} {num(c[1])}건</span>)}
          </div>
          {brief.narrative
            ? <div className="answer" style={{ marginTop: 12 }}><Markdown text={brief.narrative} /></div>
            : <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>서술 없음 — 위 집계를 참고하세요.</div>}
        </>
      )}
    </div>

    <div className="card">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2 style={{ margin: 0 }}>플릿 분석 대시보드</h2>
        <div className="row" style={{ gap: 6 }}>
          <Select value={model} onChange={setModel} options={models?.available || []} title="인사이트 생성 LLM" />
          <button className="btn" onClick={load} disabled={loading}>{loading ? "분석 중…" : "새로고침"}</button>
        </div>
      </div>
      <div className="hint">리콜·불만·부품 데이터를 집계(결정적 SQL)하고, AI가 운영 인사이트를 서술합니다.</div>

      {err && <div className="err">{err}</div>}
      {loading && !res && <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>데이터 집계 중…</div></div>}

      {res && (
        <>
          {/* KPI — 전 레벨 공통 컨텍스트 */}
          <div className="cards" style={{ marginTop: 14 }}>
            <div className="stat"><div className="v">{num(t?.recalls)}</div><div className="l">리콜</div></div>
            <div className="stat"><div className="v">{num(t?.complaints)}</div><div className="l">불만</div></div>
            <div className={`stat ${num(t?.fires) > 0 ? "danger" : ""}`}><div className="v">{num(t?.fires)}</div><div className="l">화재</div></div>
            <div className={`stat ${num(t?.injuries) > 0 ? "warn" : ""}`}><div className="v">{num(t?.injuries)}</div><div className="l">부상</div></div>
            <div className="stat"><div className="v">{num(t?.crashes)}</div><div className="l">사고</div></div>
          </div>

          {/* 레벨 서브탭 (drill-down): L0 개요 → L1 딥다이브 */}
          <div className="row" style={{ gap: 4, marginTop: 14, flexWrap: "wrap" }}>
            {(Object.keys(LEVEL_META) as Level[]).map((k) => (
              <button key={k} className={level === k ? "btn" : "ghost"}
                style={{ fontSize: 12, padding: "5px 12px" }} onClick={() => setLevel(k)}>
                {LEVEL_META[k].label}
              </button>
            ))}
          </div>
          <div className="hint" style={{ marginTop: 6 }}>
            <b>{LEVEL_META[level].label}</b> — 이 레벨이 답하는 질문: <b>{LEVEL_META[level].q}</b>
          </div>

          {/* 공용 추세 — 레벨별 지표(개요:리콜+불만 / 안전:화재·부상·사고 / 부품·지역:불만). 5탭 전부 자동 적용 */}
          <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline", marginTop: 12 }}>
            <div className="label" style={{ margin: 0 }}>{TREND_META[level].title}
              <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}> | {TREND_META[level].note}</span>
            </div>
            <GranToggle by={by} setBy={setBy} />
          </div>
          {trends.some((x) => x.data.length > 0)
            ? <TrendChart unit="건" series={trends} />
            : <div className="muted" style={{ fontSize: 13 }}>추세 데이터 없음</div>}

          {/* ── L0 개요: 추세 + 차종별 불만 ── */}
          {level === "overview" && (
            <>
              {/* 3렌즈 요약 — 볼륨·심각도·비용이 서로 다른 대상을 가리킨다(핵심 인사이트) */}
              <div className="label" style={{ marginTop: 8 }}>3렌즈 요약 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>| 볼륨·심각도·비용</span></div>
              <div className="lens-cards">
                <div className="lens-card">
                  <div className="lens-k">볼륨 · 가장 많은 불만</div>
                  <div className="lens-v">{koModel(String(lensVolume?.[0] ?? "-"))}</div>
                  <div className="lens-s">불만 {num(lensVolume?.[1]).toLocaleString("ko-KR")}건</div>
                </div>
                <div className="lens-card">
                  <div className="lens-k">심각도 · 가장 위험</div>
                  <div className="lens-v">{koModel(String(lensSeverity?.[0] ?? "-"))}</div>
                  <div className="lens-s">부상 {num(lensSeverity?.[2])} · 사고 {num(lensSeverity?.[3])}</div>
                </div>
                <div className="lens-card">
                  <div className="lens-k">비용 · 워런티 최대</div>
                  <div className="lens-v">{String(lensCost?.[0] ?? "-")}</div>
                  <div className="lens-s">{won(num(lensCost?.[4]))}</div>
                </div>
              </div>
              {res.complaintByModel?.length > 0 && (<><div className="label">차종별 불만</div><Bars rows={res.complaintByModel} unit="건" /></>)}
            </>
          )}

          {/* ── L1 리콜: 규제 리스크 ── */}
          {level === "recall" && (
            <>
              {res.recallTopComponents?.length > 0 && (<><div className="label">리콜 주요 부위</div><Donut rows={res.recallTopComponents} unit="건" /></>)}
              {res.recallByModel?.length > 0 && (
                <>
                  <div className="label">차종별 리콜 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>| 주차권고 = 화재위험</span></div>
                  <div style={{ overflowX: "auto" }}>
                    <table>
                      <thead><tr><th>차종</th><th className="right">리콜</th><th className="right">주차권고</th></tr></thead>
                      <tbody>
                        {res.recallByModel.map((r, i) => (
                          <tr key={i}>
                            <td>{koModel(String(r[0]))}</td>
                            <td className="right">{num(r[1])}</td>
                            <td className="right">{num(r[2]) > 0 ? <span className="pill bad">{num(r[2])}</span> : 0}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
              {!res.recallTopComponents?.length && !res.recallByModel?.length &&
                <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>리콜 데이터 없음</div>}
            </>
          )}

          {/* ── L1 안전: 누가 다치나 ── */}
          {level === "safety" && (
            <>
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
              {res.complaintTopComponents?.length > 0 && (<><div className="label">불만 주요 부위</div><Donut rows={res.complaintTopComponents} unit="건" /></>)}
            </>
          )}

          {/* ── L1 부품·워런티: 돈이 어디로 ── */}
          {level === "parts" && (
            <>
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
            </>
          )}

          {/* ── L1 지역: 어디서 터지나 ── */}
          {level === "geo" && res.complaintsByState?.length > 0 && (
            <>
              <div className="label">지역 핫스팟 (주별 불만·화재·부상)</div>
              <USStateMap data={res.complaintsByState} />
              <div style={{ overflowX: "auto" }}>
                <table>
                  <thead><tr><th>주(State)</th><th className="right">불만</th><th className="right">화재</th><th className="right">부상</th></tr></thead>
                  <tbody>
                    {res.complaintsByState.slice(0, 10).map((r, i) => (
                      <tr key={i}>
                        <td>{r[0]}</td>
                        <td className="right">{num(r[1])}</td>
                        <td className="right">{num(r[2]) > 0 ? <span className="pill bad">{num(r[2])}</span> : 0}</td>
                        <td className="right">{num(r[3]) > 0 ? <span className="pill warn">{num(r[3])}</span> : 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {/* AI 인사이트 — 전 레벨 공통(집계 기반, 요청 시 생성) */}
          <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline", marginTop: 10 }}>
            <div className="label" style={{ margin: 0 }}>AI 운영 인사이트 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>| {LEVEL_META[level].label} · {({ all: "전체", year: "최근 1년", month: "최근 1개월", week: "최근 1주" } as const)[by]} 기준</span></div>
            {!insLoading && <button className="btn" style={{ fontSize: 12 }} onClick={genInsight}>{insight ? "다시 생성" : "AI 인사이트 생성"}</button>}
          </div>
          {insLoading
            ? <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>집계를 바탕으로 AI가 인사이트를 작성 중…</div></div>
            : insight
              ? <div className="answer"><Markdown text={insight} /></div>
              : <div className="muted" style={{ fontSize: 13, marginTop: 6 }}>버튼을 누르면 <b>{LEVEL_META[level].label}</b> 탭의 집계(+연간·계절 시간축)를 근거로 AI가 인사이트를 서술합니다. 탭마다 결과가 달라집니다.</div>}
        </>
      )}
    </div>
    </>
  );
}
