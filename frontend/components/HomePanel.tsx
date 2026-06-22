"use client";
import { useEffect, useRef, useState } from "react";
import { api, type Summary, type Source, type Models } from "@/lib/api";
import CarImage from "@/components/CarImage";

const num = (v: unknown) => Number(v) || 0;

export default function HomePanel({ onNavigate }: { onNavigate: (id: string, payload?: string) => void }) {
  const [sum, setSum] = useState<Summary | null>(null);
  const [feed, setFeed] = useState<"recalls" | "complaints">("recalls");
  const [sumErr, setSumErr] = useState("");

  // RAG 채팅 어시스턴트 (멀티턴)
  type Msg = { role: "user" | "assistant"; text: string; sources?: Source[] };
  const [q, setQ] = useState("");
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const threadRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api.summary().then(setSum).catch((e) => setSumErr(String(e)));
    // 기본 모델: 지시 잘 따르는 instruct/7B 우선(약한 모델은 프롬프트를 메아리치는 경우가 있음)
    api.models().then((m) => {
      setModels(m);
      const pref = m.available.find((x) => /7b/i.test(x) && /instruct/i.test(x))
        || m.available.find((x) => /instruct/i.test(x)) || m.default;
      setModel(pref);
    }).catch(() => {});
  }, []);

  async function ask(text?: string) {
    const query = (text ?? q).trim();
    if (!query || busy) return;
    setMsgs((m) => [...m, { role: "user", text: query }]);
    setQ(""); setBusy(true);
    try {
      const r = await api.ask(query, "vehicle", model || undefined);
      setMsgs((m) => [...m, { role: "assistant", text: r.answer || "(답변 없음)", sources: r.sources }]);
    } catch (e) {
      setMsgs((m) => [...m, { role: "assistant", text: "오류: " + String(e) }]);
    } finally { setBusy(false); }
  }
  useEffect(() => { threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: "smooth" }); }, [msgs, busy]);

  const t = sum?.totals;
  const rows = feed === "recalls" ? sum?.recentRecalls : sum?.recentComplaints;
  const featured = String(sum?.byModel?.[0]?.[0] || "PALISADE");

  return (
    <>
    {/* 히어로 — 대표 차종 사진 배너 */}
    <div className="car-hero">
      <CarImage model={featured} height={180} rounded={false} />
      <div className="car-hero-overlay">
        <div className="kicker" style={{ color: "#cfe0ff" }}>HYUNDAI FLEET INTELLIGENCE</div>
        <h2>{featured}</h2>
        <p>리콜·불만·정비 데이터를 한 화면에서. 가장 활동이 많은 차종을 표시합니다.</p>
      </div>
    </div>

    <div style={{ display: "grid", gridTemplateColumns: "1.35fr 1fr", gap: 18, alignItems: "start" }} className="home-grid">
      {/* 좌측: KPI + 피드 + 차종 현황 */}
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        {sumErr && <div className="card" style={{ margin: 0 }}><div className="err">현황 데이터 로드 실패: {sumErr}<div className="hint">백엔드가 새 코드로 떴는지 확인하세요(/api/analytics/summary). 재시작 필요할 수 있음.</div></div></div>}

        {/* KPI */}
        <div className="card" style={{ margin: 0 }}>
          <h2>차량 품질 현황</h2>
          <div className="cards">
            <div className="stat"><div className="v">{num(t?.recalls)}</div><div className="l">리콜</div></div>
            <div className="stat"><div className="v">{num(t?.complaints)}</div><div className="l">불만</div></div>
            <div className={`stat ${num(t?.fires) > 0 ? "danger" : ""}`}><div className="v">{num(t?.fires)}</div><div className="l">화재 신고</div></div>
            <div className={`stat ${num(t?.injuries) > 0 ? "warn" : ""}`}><div className="v">{num(t?.injuries)}</div><div className="l">부상 합계</div></div>
          </div>
          <div className="hint">전체 플릿 집계. 자세한 분석은 <a onClick={() => onNavigate("analytics")} style={{ cursor: "pointer" }}>분석 대시보드</a>에서.</div>
        </div>

        {/* 최근 리콜/불만 피드 (= 도메인 뉴스) */}
        <div className="card" style={{ margin: 0 }}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 style={{ margin: 0 }}>최근 {feed === "recalls" ? "리콜" : "불만"}</h2>
            <div className="row" style={{ gap: 6 }}>
              <button className={feed === "recalls" ? "btn" : "ghost"} style={{ fontSize: 12, padding: "6px 12px" }} onClick={() => setFeed("recalls")}>리콜</button>
              <button className={feed === "complaints" ? "btn" : "ghost"} style={{ fontSize: 12, padding: "6px 12px" }} onClick={() => setFeed("complaints")}>불만</button>
            </div>
          </div>
          {!rows && <div className="muted" style={{ marginTop: 10 }}>불러오는 중…</div>}
          {rows?.map((r, i) => (
            <div className="doc" key={i} style={{ alignItems: "flex-start" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div><span className="badge" style={{ marginLeft: 0 }}>{r[1]}</span> <span className="badge">{r[2]}</span></div>
                <div className="snip" style={{ marginTop: 4 }}>{String(r[3]).slice(0, 110)}…</div>
              </div>
              <span className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>{r[0]}</span>
            </div>
          ))}
        </div>

        {/* 차종 현황 — 차종 클릭 → 차종 진단 리포트, 트리아지 바로가기 */}
        <div className="card" style={{ margin: 0 }}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 style={{ margin: 0 }}>차종 현황</h2>
            <button className="ghost" style={{ fontSize: 12 }} onClick={() => onNavigate("triage")}>케이스 트리아지 →</button>
          </div>
          <div className="hint">차종을 누르면 종합 진단 리포트로, 개별 케이스 우선순위는 트리아지에서 봅니다.</div>
          {!sum && <div className="muted" style={{ marginTop: 10 }}>불러오는 중…</div>}
          {sum?.byModel?.map((m, i) => {
            const model = String(m[0]);
            return (
              <div className="doc" key={i} style={{ cursor: "pointer" }} onClick={() => onNavigate("report", model)}>
                <span style={{ width: 56, flexShrink: 0 }}><CarImage model={model} height={36} /></span>
                <span className="name">{model}</span>
                <span className="badge">불만 {num(m[1])}</span>
                <span className="badge">리콜 {num(m[2])}</span>
                <span className="spacer" />
                <span className="muted" style={{ fontSize: 12 }}>진단 리포트 →</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* 우측: AI 어시스턴트 — 멀티턴 채팅 */}
      <div className="card chat-card" style={{ margin: 0, display: "flex", flexDirection: "column" }}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>AI 어시스턴트</h2>
          <div className="row" style={{ gap: 6 }}>
            <select value={model} onChange={(e) => setModel(e.target.value)} style={{ fontSize: 12, padding: "4px 6px", maxWidth: 160 }}>
              {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
            <span className="badge">RAG</span>
          </div>
        </div>
        <div className="hint">오너스 매뉴얼(취급설명서) 근거로 답하는 대화형 어시스턴트. 대화가 이어집니다. {msgs.length > 0 && <a onClick={() => setMsgs([])} style={{ cursor: "pointer" }}>대화 비우기</a>}</div>

        <div className="chat-thread" ref={threadRef}>
          {msgs.length === 0 && !busy && (
            <div style={{ margin: "auto", textAlign: "center", maxWidth: 320 }}>
              <div className="empty-ic" style={{ margin: "0 auto 10px" }}><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg></div>
              <div className="muted" style={{ marginBottom: 12 }}>무엇이든 물어보세요. 답변에 <b>출처</b>가 함께 표시됩니다.</div>
              <div className="row" style={{ gap: 6, flexWrap: "wrap", justifyContent: "center" }}>
                {["프리텐셔너 주의사항?", "P0420 코드가 뭐야?", "후방 카메라 점검 항목?"].map((ex) => (
                  <button key={ex} className="ghost" style={{ fontSize: 12 }} onClick={() => ask(ex)}>{ex}</button>
                ))}
              </div>
            </div>
          )}
          {msgs.map((m, i) => (
            <div key={i} className={`bubble ${m.role}`}>
              <div className="bubble-text">{m.text}</div>
              {m.sources && m.sources.length > 0 && (
                <div className="bubble-src">근거: {m.sources.map((s) => s.title).slice(0, 3).join(", ")}</div>
              )}
            </div>
          ))}
          {busy && <div className="bubble assistant"><span className="muted">검색 중…</span></div>}
        </div>

        <div className="row" style={{ marginTop: 4 }}>
          <input className="grow" type="text" placeholder="메시지 입력 (예: 안전벨트 경고등은 언제 울리나요?)"
            value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === "Enter" && ask()} />
          <button className="btn" onClick={() => ask()} disabled={busy}>전송</button>
        </div>
      </div>
    </div>
    </>
  );
}
