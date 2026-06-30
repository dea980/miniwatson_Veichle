"use client";
import { useEffect, useState } from "react";
import { api, type AgentResult, type Models } from "@/lib/api";
import Markdown from "@/components/Markdown";

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

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
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
    <div className="card">
      <h2>무엇이든 물어보기</h2>
      <div className="row">
        <input className="grow" type="text" placeholder="질문 (매뉴얼 | 리콜 통계 자동 분기)"
          value={question} onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && ask()} />
        <label className="field-model" title="답변 생성에 사용할 LLM">
          <span>모델</span>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
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
  );
}
