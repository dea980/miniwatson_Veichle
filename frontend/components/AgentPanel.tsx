"use client";
import { useEffect, useState } from "react";
import { api, type AgentResult, type Models } from "@/lib/api";
import Markdown from "@/components/Markdown";

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
        <input type="text" value={namespace} onChange={(e) => setNamespace(e.target.value)} style={{ width: 110 }} />
        <select value={model} onChange={(e) => setModel(e.target.value)}>
          {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
        <button className="btn" onClick={ask} disabled={loading}>{loading ? "처리 중…" : "질문"}</button>
      </div>
      <div className="row" style={{ gap: 6, marginTop: 6 }}>
        {examples.map((ex, i) => (
          <button key={i} className="ghost" style={{ fontSize: 12 }} onClick={() => setQuestion(ex)}>{ex}</button>
        ))}
      </div>
      <div className="hint">질문을 분석해 <b>RAG(매뉴얼)</b> / <b>리콜 SQL</b> / <b>둘 다</b>를 자동 선택 → 실행 → 한국어 종합.</div>

      {err && <div className="err">{err}</div>}

      {res && (
        <>
          <div className="label">선택된 도구 <span className="badge">{res.tool}</span></div>

          <div className="label">처리 과정</div>
          {res.trace.map((s, i) => (
            <div key={i} className="step">
              <div className="h">{i + 1}. {s.action} <span className="badge">{s.tool}</span> → {s.result}</div>
              {s.detail && <div className="d">{s.detail}</div>}
            </div>
          ))}

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
