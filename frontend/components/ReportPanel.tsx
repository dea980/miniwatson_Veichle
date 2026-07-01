"use client";
import { useEffect, useState } from "react";
import { api, koModel, cleanText, severityPct, type ReportResult, type Models, type CaseRecord, type EstimateResult } from "@/lib/api";
import Markdown from "@/components/Markdown";
import CarImage from "@/components/CarImage";
import Donut from "@/components/Donut";

const num = (v: unknown) => Number(v) || 0;
const won = (n: number) => Math.round(Number(n) || 0).toLocaleString("ko-KR") + "원";
const NS = "vehicle";   // 자동차 도메인 고정 (사용자에게 노출하지 않음)

// 점검 결과 → 의미색 (양호=초록, 정비·교환·판금·흠집 등은 주황·빨강 계열)
const INS_COLOR: Record<string, string> = {
  "양호": "#1a7f37", "교환": "#c01825", "판금": "#b45309", "판금/용접": "#b45309",
  "부식": "#92400e", "흠집": "#a16207", "손상": "#c2410c", "요철": "#9a6700", "정비필요": "#d97706",
};

function Bars({ rows }: { rows: [string, number][] }) {
  const max = Math.max(1, ...rows.map((r) => Number(r[1]) || 0));
  return (
    <div className="bars">
      {rows.map((r, i) => (
        <div className="bar-row" key={i}>
          <span className="bar-label">{String(r[0]).slice(0, 18)}</span>
          <span className="bar-track">
            <span className={`bar-fill s${Math.min(i, 5)}`} style={{ width: `${(Number(r[1]) / max) * 100}%` }} />
          </span>
          <span className="bar-val">{r[1]}건</span>
        </div>
      ))}
    </div>
  );
}

// 점검 결과 → 상태 배지 색 (양호=초록, 교환=빨강, 그 외 정비=주황)
function resultPill(result: string) {
  const ok = result === "양호" || result === "-" || result === "–" || result === "";
  const replace = result.includes("교환");
  const cls = ok ? "ok" : replace ? "bad" : "warn";
  return <span className={`pill ${cls}`}>{result || "양호"}</span>;
}

// 점검 결과 분포 도넛 — 양호 family 통합, 의미색. (SWC-safe하게 별도 컴포넌트로 분리)
function InspectionDonut({ rows }: { rows: readonly (readonly unknown[])[] }) {
  const m = new Map<string, number>();
  for (const r of rows) {
    let k = String(r[2] ?? "").trim();
    if (k === "" || k === "-" || k === "–") k = "양호";
    m.set(k, (m.get(k) || 0) + 1);
  }
  const dist = [...m.entries()].sort((a, b) => (a[0] === "양호" ? -1 : b[0] === "양호" ? 1 : b[1] - a[1])) as [string, number][];
  const colors = dist.map(([k]) => INS_COLOR[k] || "#d97706");
  return <Donut rows={dist} unit="건" colors={colors} />;
}

export default function ReportPanel({ initialCar, onNavigate }: { initialCar?: string; onNavigate?: (id: string, payload?: string) => void }) {
  const [car, setCar] = useState("PALISADE");
  const [carModels, setCarModels] = useState<string[]>([]);   // 실제 차종 목록(불만 데이터)
  const [models, setModels] = useState<Models | null>(null);  // LLM 모델
  const [model, setModel] = useState("");
  const [res, setRes] = useState<ReportResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  // 점검 체크리스트(공통 + 차종별 추가)
  const [chk, setChk] = useState<{ common: [string, string][]; additional: [string, number, string][] } | null>(null);
  // 이 차종의 케이스(개별 차량) 검색 + 진단
  const [cases, setCases] = useState<CaseRecord[]>([]);
  const [caseQ, setCaseQ] = useState("");
  const [caseLoading, setCaseLoading] = useState(false);

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
    api.summary().then((s) => setCarModels((s.byModel || []).map((m) => String(m[0])))).catch(() => {});
  }, []);

  async function gen(carOverride?: string, force = false) {
    const c = (carOverride ?? car).trim().toUpperCase();
    if (!c) return;
    setLoading(true); setErr(""); setRes(null); setCases([]); setCaseQ(""); setChk(null);
    try {
      const r = await api.report(c, NS, model || undefined, force);
      setRes(r);
      loadCases(c, "");
      api.checklist(c).then((k) => setChk({ common: k.common || [], additional: k.additional || [] })).catch(() => setChk(null));
    } catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  async function loadCases(c: string, kw: string) {
    setCaseLoading(true);
    try { const r = await api.cases(c, kw || undefined); setCases(r.cases || []); }
    catch { setCases([]); } finally { setCaseLoading(false); }
  }

  // 홈 "차종 현황"에서 넘어오면 차종 세팅 + 자동 생성
  useEffect(() => {
    if (initialCar && initialCar.trim()) { setCar(initialCar); gen(initialCar); }
    // eslint-disable-next-line
  }, [initialCar]);

  function buildHtml(): string {
    if (!res) return "";
    const esc = (s: string) => (s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;");
    const rows = (arr: [string, number][]) => arr.map((r) => `<tr><td>${esc(String(r[0]))}</td><td style="text-align:right">${r[1]}건</td></tr>`).join("");
    return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${esc(res.car)} 차종 진단 리포트</title>
<style>body{font-family:'Pretendard',-apple-system,sans-serif;max-width:720px;margin:48px auto;padding:0 20px;color:#0d1b2a;line-height:1.6}
h1{font-size:26px;border-bottom:2px solid #002c5f;padding-bottom:10px}h2{font-size:17px;margin-top:28px;color:#002c5f}
.kpi{display:flex;gap:14px;flex-wrap:wrap;margin:16px 0}.kpi div{flex:1;min-width:120px;border:1px solid #e3e8ef;border-radius:10px;padding:14px}
.kpi b{font-size:24px;display:block}table{width:100%;border-collapse:collapse;font-size:14px}td{padding:6px 8px;border-bottom:1px solid #eee}
pre{white-space:pre-wrap;font-family:inherit;background:#f5f7fa;padding:16px;border-radius:8px}footer{margin-top:36px;color:#888;font-size:12px;border-top:1px solid #eee;padding-top:12px}
@media print{body{margin:0}}</style></head>
<body><h1>${esc(res.car)} 차종 진단 리포트</h1>
<div class="kpi"><div><b>${res.recallTotal}</b>리콜 건수</div><div><b>${res.complaintTotal}</b>불만 건수</div><div><b>${res.fires}</b>화재 신고</div><div><b>${res.injuries}</b>부상 합계</div></div>
<h2>주요장치 점검표</h2><p style="color:#888;font-size:12px">상태부호: X교환·W판금/용접·C부식·A흠집·U요철·T손상·– 양호</p>
<table><tr><th style="text-align:left">장치</th><th style="text-align:left">항목</th><th style="text-align:left">결과</th><th style="text-align:left">부호</th></tr>
${(res.inspection || []).map((r) => `<tr><td>${esc(String(r[0]))}</td><td>${esc(String(r[1]))}</td><td>${esc(String(r[2]))}</td><td>${esc(String(r[3]))}</td></tr>`).join("")}</table>
<h2>리콜 주요 부품</h2><table>${rows(res.recallTopComponents || [])}</table>
<h2>불만 주요 부품</h2><table>${rows(res.complaintTopComponents || [])}</table>
<h2>종합 진단</h2><pre>${esc(res.report)}</pre>
<footer>MiniWatson Vehicle — Automotive Domain LLM | 생성 ${new Date().toLocaleString("ko-KR")} | 데이터: NHTSA·오너스 매뉴얼(샘플)</footer></body></html>`;
  }

  function download() {
    if (!res) return;
    const url = URL.createObjectURL(new Blob([buildHtml()], { type: "text/html" }));
    const a = document.createElement("a"); a.href = url; a.download = `${res.car}_진단리포트.html`; a.click();
    URL.revokeObjectURL(url);
  }

  function printPdf() {
    if (!res) return;
    const w = window.open("", "_blank");
    if (!w) { alert("팝업이 차단됐어요. 팝업 허용 후 다시 시도하세요."); return; }
    w.document.write(buildHtml());
    w.document.close();
    w.onload = () => { w.focus(); w.print(); };
    setTimeout(() => { try { w.focus(); w.print(); } catch {} }, 500);
  }

  const fallbackCars = ["PALISADE", "SANTA FE", "ELANTRA", "SONATA", "TUCSON", "KONA"];
  const carOptions = carModels.length ? carModels : fallbackCars;

  return (
    <div className="card">
      <h2>차종 카테고리 <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>· 케이스 빠른 조회</span></h2>
      <div className="row">
        <select className="grow" value={car} onChange={(e) => setCar(e.target.value)} title="차종 선택">
          {carOptions.map((c) => <option key={c} value={c}>{koModel(c)}</option>)}
        </select>
        <select value={model} onChange={(e) => setModel(e.target.value)} title="응답 생성 LLM">
          {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
        <button className="btn" onClick={() => gen()} disabled={loading}>{loading ? "조회 중…" : "조회"}</button>
      </div>
      <div className="hint">차종은 <b>접수번호(케이스)를 빠르게 찾는 카테고리</b>입니다. 차종별 집계(리콜·불만·점검표)는 참고용이고, 실제 진단·견적·점검·정비사 메모는 아래 케이스를 눌러 <b>접수번호별 리포트</b>에서 작성·적재합니다.</div>

      {err && <div className="err">{err}</div>}

      {res && (
        <>
          <div className="car-hero" style={{ marginTop: 14 }}>
            <CarImage model={res.car} height={150} rounded={false} />
            <div className="car-hero-overlay">
              <div className="kicker" style={{ color: "#cfe0ff" }}>차종 진단 리포트</div>
              <h2 title={res.car}>{koModel(res.car)}</h2>
              <p>리콜·불만·매뉴얼을 종합한 한국어 진단서</p>
            </div>
          </div>

          <div className="cards" style={{ marginTop: 14 }}>
            <div className="stat"><div className="v">{res.recallTotal}</div><div className="l">리콜 건수</div></div>
            <div className="stat"><div className="v">{res.complaintTotal}</div><div className="l">불만 건수</div></div>
            <div className={`stat ${res.fires > 0 ? "danger" : ""}`}><div className="v">{res.fires}</div><div className="l">화재 신고</div></div>
            <div className={`stat ${res.injuries > 0 ? "warn" : ""}`}><div className="v">{res.injuries}</div><div className="l">부상 합계</div></div>
          </div>

          {(res.inspection?.length > 0) && (
            <>
              <div className="label">주요장치 점검표 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(상태부호: X교환·W판금/용접·C부식·A흠집·U요철·T손상·– 양호)</span></div>
              <InspectionDonut rows={res.inspection} />
              <div style={{ overflowX: "auto" }}>
                <table>
                  <thead><tr><th>장치</th><th>점검 항목</th><th>결과</th><th>부호</th></tr></thead>
                  <tbody>
                    {res.inspection.map((r, i) => (
                      <tr key={i}>
                        <td className="muted">{r[0]}</td>
                        <td>{r[1]}</td>
                        <td>{resultPill(r[2])}</td>
                        <td>{r[3]}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {(res.recallTopComponents?.length > 0) && (<><div className="label">리콜 주요 부품</div><Donut rows={res.recallTopComponents} unit="건" /></>)}
          {(res.complaintTopComponents?.length > 0) && (<><div className="label">불만 주요 부품</div><Donut rows={res.complaintTopComponents} unit="건" /></>)}

          <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <div className="label" style={{ margin: "18px 0 8px" }}>차종 개요 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(참고 | 결정적 집계)</span></div>
            <div className="row" style={{ gap: 6 }}>
              <button className="ghost" onClick={printPdf}>PDF로 저장</button>
              <button className="ghost" onClick={download}>HTML 다운로드</button>
            </div>
          </div>
          <div className="answer"><Markdown text={res.report} /></div>

          {/* 이 차종의 케이스 — 건별 점검 체크리스트 + 필요 부품 */}
          <div className="label" style={{ marginTop: 22 }}>이 차종의 케이스 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>({koModel(res.car)} | 중요도순)</span></div>
          {chk && chk.common.length > 0 && (
            <div className="hint" style={{ marginTop: 0 }}>공통 점검(모든 차량 표준): {chk.common.map((r) => String(r[0])).join(", ")}. 각 건을 누르면 <b>차량 케이스 진단 페이지</b>(부품 이미지·점검·견적)로 이동합니다.</div>
          )}
          <div className="row">
            <input className="grow" type="text" placeholder="부위 키워드로 검색 (예: ENGINE, AIR BAG)" value={caseQ}
              onChange={(e) => setCaseQ(e.target.value)} onKeyDown={(e) => e.key === "Enter" && loadCases(res.car, caseQ)} />
            <button className="ghost" onClick={() => loadCases(res.car, caseQ)}>검색</button>
          </div>
          {caseLoading && <div className="muted" style={{ marginTop: 8, fontSize: 13 }}>케이스 불러오는 중…</div>}
          {!caseLoading && cases.length === 0 && <div className="muted" style={{ marginTop: 8, fontSize: 13 }}>해당 케이스가 없습니다.</div>}
          <div style={{ marginTop: 6 }}>
            {cases.map((c, i) => {
              const id = String(c[0]);
              const pr = num(c[6]), fire = num(c[7]), crash = num(c[8]), inj = num(c[9]), dea = num(c[10]);
              const accent = dea > 0 || fire > 0 ? "var(--danger)" : pr > 0 ? "var(--warn)" : "var(--border)";
              return (
                <div key={i} onClick={() => onNavigate && onNavigate("triage", `${res.car}::${id}`)}
                  style={{ padding: "10px 0 10px 12px", borderBottom: "1px solid var(--border)", borderLeft: `3px solid ${accent}`, cursor: onNavigate ? "pointer" : "default" }}>
                  <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", gap: 10 }}>
                    <div style={{ minWidth: 0 }}>
                      <div className="muted" style={{ fontSize: 12, marginBottom: 3 }}>
                        <span className="badge" style={{ marginLeft: 0 }}>중요도 {severityPct(dea, inj, fire, crash)}%</span> 접수 #{id} | {c[4]}년 | {c[1]}
                      </div>
                      <div style={{ fontWeight: 600, fontSize: 13 }}>{String(c[3])}</div>
                      {pr > 0 && (
                        <div className="row" style={{ gap: 4, marginTop: 4, flexWrap: "wrap" }}>
                          {dea > 0 && <span className="pill bad">사망 {dea}</span>}
                          {inj > 0 && <span className="pill warn">부상 {inj}</span>}
                          {fire > 0 && <span className="pill bad">화재</span>}
                          {crash > 0 && <span className="pill warn">사고</span>}
                        </div>
                      )}
                      <div className="snip" style={{ marginTop: 4 }}>{String(c[5]).slice(0, 130)}…</div>
                    </div>
                    <span className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>차량 케이스 진단 →</span>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
