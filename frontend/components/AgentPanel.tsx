"use client";
import { useEffect, useState } from "react";
import { api, koModel, type AgentResult, type Models, type IntegratedAdvice } from "@/lib/api";
import Markdown from "@/components/Markdown";
import Select from "@/components/Select";
import Donut from "@/components/Donut";

// 도구명 → 색 클래스(트레이스 시각화). RAG=액션블루 / SQL=네이비 / 복합=주황.
const toolClass = (t?: string) => {
  const s = String(t || "").toLowerCase();
  if (s.includes("both") || s.includes("복합") || s.includes("둘")) return "both";
  if (s.includes("sql") || s.includes("리콜") || s.includes("tabular")) return "sql";
  if (s.includes("rag") || s.includes("매뉴얼") || s.includes("검색")) return "rag";
  return "";
};

// 도구명 → 색 클래스(트레이스 시각화). RAG=액션블루 / SQL=네이비 / 복합=주황.
const toolClass = (t?: string) => {
  const s = String(t || "").toLowerCase();
  if (s.includes("both") || s.includes("복합") || s.includes("둘")) return "both";
  if (s.includes("sql") || s.includes("리콜") || s.includes("tabular")) return "sql";
  if (s.includes("rag") || s.includes("매뉴얼") || s.includes("검색")) return "rag";
  return "";
};

export default function AgentPanel() {
  const [question, setQuestion] = useState("");
  const [namespace, setNamespace] = useState("vehicle");
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const [res, setRes] = useState<AgentResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  // 통합 질의 — 차종·연식 핫스팟(정형) + 매뉴얼 근거(비정형) 점검 추천
  const [carModels, setCarModels] = useState<string[]>([]);
  const [iaModel, setIaModel] = useState("");
  const [iaYear, setIaYear] = useState("");
  const [ia, setIa] = useState<IntegratedAdvice | "loading" | null>(null);

  function runIntegrated(force = false) {
    if (!iaModel) return;
    setIa("loading");
    api.integrated(iaModel, iaYear || undefined, force)
      .then((r) => setIa(r.error ? null : r)).catch(() => setIa(null));
  }

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
    api.summary().then((s) => setCarModels((s.byModel || []).map((x) => String(x[0])))).catch(() => {});
  }, []);

  async function ask() {
    if (!question.trim()) return;
    setLoading(true); setErr(""); setRes(null);
    try { setRes(await api.agentAsk(question, namespace, model || undefined)); }
    catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  const examples = [
    "안전벨트 프리텐셔너 취급 시 주의사항은?",       // RAG
    "차종(Model)별 리콜 건수를 많은 순으로 보여줘",   // SQL
    "팰리세이드 리콜은 몇 건이고 어떤 주의가 필요해?", // BOTH
  ];

  return (
    <>
    {/* 통합 질의 — 정형 신호로 "무엇을 볼지" 좁히고, 매뉴얼(비정형)로 "어떻게 볼지" 답한다 */}
    <div className="card">
      <h2>차종·연식 통합 점검 <span className="muted" style={{ fontWeight: 400, fontSize: 12 }}>(핫스팟 집계 + 매뉴얼 근거 추천)</span></h2>
      <div className="row" style={{ marginTop: 8 }}>
        <Select value={iaModel} onChange={setIaModel} placeholder="차종 선택"
          options={carModels} renderLabel={(v) => koModel(v)} title="차종" />
        <input type="text" inputMode="numeric" placeholder="연식 (예: 2020, 생략 가능)" value={iaYear}
          onChange={(e) => setIaYear(e.target.value.replace(/[^0-9]/g, "").slice(0, 4))}
          onKeyDown={(e) => e.key === "Enter" && runIntegrated()} style={{ width: 170 }} />
        <button className="btn" onClick={() => runIntegrated()} disabled={!iaModel || ia === "loading"}>
          {ia === "loading" ? "분석 중…" : "통합 점검"}</button>
        {ia && ia !== "loading" && (
          <span className="muted" style={{ fontSize: 12 }}>
            {ia.cached ? "캐시" : "방금 생성"}{ia.generatedAt ? ` | ${ia.generatedAt.slice(0, 16).replace("T", " ")}` : ""}
            {" "}<a style={{ cursor: "pointer" }} onClick={() => runIntegrated(true)}>↻ 재생성</a>
          </span>
        )}
      </div>
      <div className="hint">불만/리콜 <b>핫스팟(결정적 SQL)</b>으로 볼 곳을 좁히고, 그 차·연식 <b>매뉴얼 RAG</b>로 점검 방법을 엮어 우선순위 추천을 만듭니다. 최초 1회만 LLM(이후 캐시).</div>

      {ia === "loading" && <div className="empty"><div className="empty-ic loading"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>핫스팟 집계 → 매뉴얼 검색 → 종합 중(최초 1회는 수십 초)…</div></div>}

      {ia && ia !== "loading" && (
        <>
          <div className="row" style={{ gap: 6, marginTop: 12, flexWrap: "wrap" }}>
            <span className="badge" style={{ marginLeft: 0 }}>불만 {ia.complaints}</span>
            <span className="badge">리콜 {ia.recalls}</span>
            {ia.deaths > 0 && <span className="pill bad">사망 {ia.deaths}</span>}
            {ia.injuries > 0 && <span className="pill warn">부상 {ia.injuries}</span>}
            {ia.fires > 0 && <span className="pill bad">화재 {ia.fires}</span>}
          </div>
          {(ia.complaintTop || []).length > 0 && (
            <>
              <div className="label">불만 핫스팟 (부위별)</div>
              <Donut rows={(ia.complaintTop || []).map((r) => [String(r[0]).slice(0, 28), Number(r[1])] as [string, number])} unit="건" />
            </>
          )}
          <div className="label">추가 점검 추천 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(매뉴얼 근거)</span></div>
          {ia.advice ? <div className="answer"><Markdown text={ia.advice} /></div>
            : <div className="muted" style={{ fontSize: 13 }}>추천 서술 없음 — 위 집계를 참고하세요.</div>}
          {(ia.evidence || []).some((e) => (e.sources || []).length > 0) && (
            <div className="hint">근거: {(ia.evidence || []).flatMap((e) => e.sources || []).filter((v, i, a) => a.indexOf(v) === i).slice(0, 4).join(" | ")}</div>
          )}
        </>
      )}
    </div>

    <div className="card">
      <h2>무엇이든 물어보기</h2>
      <div className="row">
        <input className="grow" type="text" placeholder="질문 (매뉴얼 | 리콜 통계 자동 분기)"
          value={question} onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && ask()} />
        <label className="field-model" title="답변 생성에 사용할 LLM">
          <span>모델</span>
          <Select value={model} onChange={setModel} options={models?.available || []} />
        </label>
        <button className="btn" onClick={ask} disabled={loading}>{loading ? "처리 중…" : "질문"}</button>
      </div>
      <div className="row" style={{ gap: 6, marginTop: 6 }}>
        {examples.map((ex, i) => (
          <button key={i} className="ghost" style={{ fontSize: 12 }} onClick={() => setQuestion(ex)}>{ex}</button>
        ))}
      </div>
      <div className="hint">질문을 분석해 <b>RAG(매뉴얼)</b> / <b>리콜 SQL</b> / <b>둘 다</b>를 자동 선택 → 실행 → 한국어 종합.</div>

      {err && <div className="err">{err}</div>}

      {!res && !err && !loading && (
        <div className="empty">
          <div className="empty-ic"><svg viewBox="0 0 24 24"><polygon points="13 2 4 14 12 14 11 22 20 10 12 10 13 2" /></svg></div>
          <div>질문을 입력하면 <b>매뉴얼 검색 / 리콜 SQL / 복합</b> 중 알맞은 도구를 자동으로 고르고, <b>처리 과정</b>을 단계별로 보여줍니다.</div>
        </div>
      )}

      {loading && <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>질문을 분석하고 도구를 선택하는 중…</div></div>}

      {res && (
        <>
          <div className="label">에이전트 실행 트레이스 <span className={`tool-tag ${toolClass(res.tool)}`}>{res.tool}</span></div>
          <div className="agent-trace">
            {res.trace.map((s, i) => (
              <div key={i} className="trace-step">
                <span className="trace-node">{i + 1}</span>
                <div className="trace-body">
                  <div className="trace-head">
                    {s.action}
                    {s.tool && <span className={`tool-tag ${toolClass(s.tool)}`}>{s.tool}</span>}
                  </div>
                  {s.result && <div className="trace-result">→ {s.result}</div>}
                  {s.detail && <div className="trace-detail">{s.detail}</div>}
                </div>
              </div>
            ))}
          </div>

          <div className="label">답변</div>
          <div className="answer"><Markdown text={res.answer || "(No answer)"} /></div>

          {res.sources && res.sources.length > 0 && (
            <>
              <div className="label">근거 (매뉴얼)</div>
              {res.sources.map((s, i) => (
                <div className="source" key={i}>
                  <div className="title">{s.title}</div>
                  <div className="snip">{(s.summary || "").slice(0, 160)}…</div>
                </div>
              ))}
            </>
          )}

          {res.sql != null && (
            <>
              <div className="label">실행 SQL</div>
              <pre className="sqlbox">{String(res.sql)}</pre>
            </>
          )}
        </>
      )}
    </div>
    </>
  );
}
