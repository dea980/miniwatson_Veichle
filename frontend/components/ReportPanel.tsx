"use client";
import { useEffect, useState } from "react";
import { api, type ReportResult, type Models } from "@/lib/api";
import Markdown from "@/components/Markdown";

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

export default function ReportPanel() {
  const [car, setCar] = useState("PALISADE");
  const [namespace, setNamespace] = useState("vehicle");
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const [res, setRes] = useState<ReportResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
  }, []);

  async function gen() {
    if (!car.trim()) return;
    setLoading(true); setErr(""); setRes(null);
    try { setRes(await api.report(car.trim().toUpperCase(), namespace, model || undefined)); }
    catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  function buildHtml(): string {
    if (!res) return "";
    const esc = (s: string) => (s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;");
    const rows = (arr: [string, number][]) => arr.map((r) => `<tr><td>${esc(String(r[0]))}</td><td style="text-align:right">${r[1]}건</td></tr>`).join("");
    return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${esc(res.car)} 차량 진단 리포트</title>
<style>body{font-family:'Pretendard',-apple-system,sans-serif;max-width:720px;margin:48px auto;padding:0 20px;color:#0d1b2a;line-height:1.6}
h1{font-size:26px;border-bottom:2px solid #002c5f;padding-bottom:10px}h2{font-size:17px;margin-top:28px;color:#002c5f}
.kpi{display:flex;gap:14px;flex-wrap:wrap;margin:16px 0}.kpi div{flex:1;min-width:120px;border:1px solid #e3e8ef;border-radius:10px;padding:14px}
.kpi b{font-size:24px;display:block}table{width:100%;border-collapse:collapse;font-size:14px}td{padding:6px 8px;border-bottom:1px solid #eee}
pre{white-space:pre-wrap;font-family:inherit;background:#f5f7fa;padding:16px;border-radius:8px}footer{margin-top:36px;color:#888;font-size:12px;border-top:1px solid #eee;padding-top:12px}
@media print{body{margin:0}}</style></head>
<body><h1>${esc(res.car)} 차량 진단 리포트</h1>
<div class="kpi"><div><b>${res.recallTotal}</b>리콜 건수</div><div><b>${res.complaintTotal}</b>불만 건수</div><div><b>${res.fires}</b>화재 신고</div><div><b>${res.injuries}</b>부상 합계</div></div>
<h2>주요장치 점검표</h2><p style="color:#888;font-size:12px">상태부호: X교환·W판금/용접·C부식·A흠집·U요철·T손상·– 양호</p>
<table><tr><th style="text-align:left">장치</th><th style="text-align:left">항목</th><th style="text-align:left">결과</th><th style="text-align:left">부호</th></tr>
${(res.inspection || []).map((r) => `<tr><td>${esc(String(r[0]))}</td><td>${esc(String(r[1]))}</td><td>${esc(String(r[2]))}</td><td>${esc(String(r[3]))}</td></tr>`).join("")}</table>
<h2>리콜 주요 부품</h2><table>${rows(res.recallTopComponents || [])}</table>
<h2>불만 주요 부품</h2><table>${rows(res.complaintTopComponents || [])}</table>
<h2>종합 진단</h2><pre>${esc(res.report)}</pre>
<footer>MiniWatson Vehicle — Automotive Domain LLM · 생성 ${new Date().toLocaleString("ko-KR")} · 데이터: NHTSA·매뉴얼(샘플)</footer></body></html>`;
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

  const cars = ["PALISADE", "SANTA FE", "ELANTRA", "SONATA", "TUCSON", "KONA"];

  return (
    <div className="card">
      <h2>내 차 진단 리포트</h2>
      <div className="row">
        <input className="grow" type="text" value={car} onChange={(e) => setCar(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && gen()} placeholder="차종 (예: PALISADE)" />
        <input type="text" value={namespace} onChange={(e) => setNamespace(e.target.value)} style={{ width: 100 }} />
        <select value={model} onChange={(e) => setModel(e.target.value)}>
          {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
        <button className="btn" onClick={gen} disabled={loading}>{loading ? "생성 중…" : "진단서 생성"}</button>
      </div>
      <div className="row" style={{ gap: 6, marginTop: 6 }}>
        {cars.map((c) => <button key={c} className="ghost" style={{ fontSize: 12 }} onClick={() => setCar(c)}>{c}</button>)}
      </div>
      <div className="hint">차종 하나에 대해 리콜(SQL) 불만(SQL) 매뉴얼(RAG)을 모아 한국어 진단서로 종합합니다.</div>

      {err && <div className="err">{err}</div>}

      {res && (
        <>
          <div className="cards" style={{ marginTop: 14 }}>
            <div className="stat"><div className="v">{res.recallTotal}</div><div className="l">리콜 건수</div></div>
            <div className="stat"><div className="v">{res.complaintTotal}</div><div className="l">불만 건수</div></div>
            <div className={`stat ${res.fires > 0 ? "danger" : ""}`}><div className="v">{res.fires}</div><div className="l">화재 신고</div></div>
            <div className={`stat ${res.injuries > 0 ? "warn" : ""}`}><div className="v">{res.injuries}</div><div className="l">부상 합계</div></div>
          </div>

          {(res.inspection?.length > 0) && (
            <>
              <div className="label">주요장치 점검표 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(상태부호: X교환·W판금/용접·C부식·A흠집·U요철·T손상·– 양호)</span></div>
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

          {(res.recallTopComponents?.length > 0) && (<><div className="label">리콜 주요 부품</div><Bars rows={res.recallTopComponents} /></>)}
          {(res.complaintTopComponents?.length > 0) && (<><div className="label">불만 주요 부품</div><Bars rows={res.complaintTopComponents} /></>)}

          <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <div className="label" style={{ margin: "18px 0 8px" }}>진단서</div>
            <div className="row" style={{ gap: 6 }}>
              <button className="ghost" onClick={printPdf}>PDF로 저장</button>
              <button className="ghost" onClick={download}>HTML 다운로드</button>
            </div>
          </div>
          <div className="answer"><Markdown text={res.report} /></div>

          {res.sources && res.sources.length > 0 && (
            <div className="hint">매뉴얼 근거: {res.sources.join(" · ")}</div>
          )}
        </>
      )}
    </div>
  );
}
