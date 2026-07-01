"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, cleanText, koModel, severityPct, isSafetyCritical, type Summary, type Source, type Models, type CaseRecord, type RecallDetail } from "@/lib/api";
import CarImage from "@/components/CarImage";

const num = (v: unknown) => Number(v) || 0;

export default function HomePanel({ onNavigate }: { onNavigate: (id: string, payload?: string) => void }) {
  const [sum, setSum] = useState<Summary | null>(null);
  const [feed, setFeed] = useState<"recalls" | "complaints">("recalls");
  const [slide, setSlide] = useState(0);     // 히어로 캐러셀 현재 슬라이드
  const [paused, setPaused] = useState(false); // hover 시 자동 전환 멈춤
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null); // 마지막 자동 갱신 시각
  const [sumErr, setSumErr] = useState("");
  const [topCases, setTopCases] = useState<CaseRecord[]>([]);     // 우선 대응 케이스
  const [resolved, setResolved] = useState<Set<string>>(new Set());
  const [recall, setRecall] = useState<RecallDetail | "loading" | null>(null);   // 리콜 상세 모달
  const [complaint, setComplaint] = useState<CaseRecord | "loading" | "error" | null>(null);   // 불만 상세 모달
  const [csum, setCsum] = useState<{ gist?: string; cached?: boolean } | "loading" | null>(null);   // 접수 내용 AI 요약

  function openRecall(id: string) {
    setRecall("loading");
    api.recall(id)
      .then((r) => setRecall(r && (r.campaign || r.summary) ? r : ({ summary: "" } as RecallDetail)))
      .catch((e) => setRecall({ summary: `__ERR__${String(e)}` } as RecallDetail));
  }

  function openComplaint(id: string) {
    setComplaint("loading"); setCsum("loading");
    api.caseById(id)
      .then((r) => setComplaint(Array.isArray(r.case) && r.case.length ? (r.case as CaseRecord) : "error"))
      .catch(() => setComplaint("error"));
    // 접수 내용 한국어 요약 — 처음 1회만 LLM(서버 캐시), 느려서 별도 로드
    api.caseSummary(id, model || undefined)
      .then((r) => setCsum(r && r.gist ? { gist: r.gist, cached: r.cached } : null))
      .catch(() => setCsum(null));
  }

  // RAG 채팅 어시스턴트 (멀티턴)
  type Msg = { role: "user" | "assistant"; text: string; sources?: Source[] };
  const [q, setQ] = useState("");
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const threadRef = useRef<HTMLDivElement>(null);

  // 집계·케이스 재조회 — 해결/추가가 다음 갱신에 반영됨
  const loadData = useCallback(() => {
    Promise.all([
      api.summary().then(setSum).catch((e) => setSumErr(String(e))),
      api.cases().then((r) => setTopCases(r.cases || [])).catch(() => {}),
    ]).finally(() => setUpdatedAt(new Date()));
  }, []);

  useEffect(() => {
    loadData();
    try { setResolved(new Set(JSON.parse(localStorage.getItem("mw-resolved-cases") || "[]"))); } catch {}
    // 기본 모델: 지시 잘 따르는 instruct/7B 우선(약한 모델은 프롬프트를 메아리치는 경우가 있음)
    api.models().then((m) => {
      setModels(m);
      const pref = m.available.find((x) => /7b/i.test(x) && /instruct/i.test(x))
        || m.available.find((x) => /instruct/i.test(x)) || m.default;
      setModel(pref);
    }).catch(() => {});
  }, [loadData]);

  // 자동 갱신 — 20초 폴링 + 탭 복귀 시 즉시(해결/추가 반영). 백그라운드 탭에선 폴링 안 함.
  useEffect(() => {
    const tick = () => { if (document.visibilityState === "visible") loadData(); };
    const iv = setInterval(tick, 20000);
    document.addEventListener("visibilitychange", tick);
    window.addEventListener("focus", loadData);
    return () => { clearInterval(iv); document.removeEventListener("visibilitychange", tick); window.removeEventListener("focus", loadData); };
  }, [loadData]);

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
  // 히어로 자동 슬라이드 — 활동 상위 차종들을 일정 간격으로 순환(hover 시 멈춤)
  useEffect(() => {
    const n = Math.min(sum?.byModel?.length || 0, 6);
    if (n <= 1 || paused) return;
    const t = setInterval(() => setSlide((s) => (s + 1) % n), 3800);
    return () => clearInterval(t);
  }, [sum, paused]);

  const t = sum?.totals;
  const rows = feed === "recalls" ? sum?.recentRecalls : sum?.recentComplaints;
  const heroModels = (sum?.byModel || []).slice(0, 6);
  const idx = heroModels.length ? slide % heroModels.length : 0;
  const cur = heroModels[idx];
  const featured = String(cur?.[0] || "PALISADE");

  return (
    <>
    {/* 히어로 — 활동 상위 차종 자동 슬라이드 캐러셀 */}
    <div className="car-hero" onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      <CarImage key={featured} model={featured} height={180} rounded={false} />
      <div className="car-hero-overlay">
        <div className="kicker" style={{ color: "#cfe0ff" }}>HYUNDAI FLEET INTELLIGENCE</div>
        <h2 title={featured}>{koModel(featured)}</h2>
        <p>{cur ? <>불만 <b>{num(cur[1])}</b> | 리콜 <b>{num(cur[2])}</b> — 활동 상위 차종을 순환 표시합니다.</> : "리콜·불만·정비 데이터를 한 화면에서."}</p>
      </div>
      {heroModels.length > 1 && (
        <div className="hero-dots">
          {heroModels.map((m, i) => (
            <button key={i} className={`hero-dot ${i === idx ? "on" : ""}`}
              onClick={() => setSlide(i)} title={String(m[0])} aria-label={String(m[0])} />
          ))}
        </div>
      )}
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
          <div className="hint">전체 플릿 집계. 자세한 분석은 <a onClick={() => onNavigate("analytics")} style={{ cursor: "pointer" }}>분석 대시보드</a>에서.
            {updatedAt && <span className="muted"> | 자동 갱신 {updatedAt.toLocaleTimeString("ko-KR")}</span>}</div>
        </div>

        {/* 우선 대응 케이스 — 클릭 → 차량 케이스 진단 (스파인으로 연결) */}
        <div className="card" style={{ margin: 0 }}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 style={{ margin: 0 }}>우선 대응 케이스</h2>
            <button className="ghost" style={{ fontSize: 12 }} onClick={() => onNavigate("triage")}>트리아지 전체 →</button>
          </div>
          <div className="hint">중요도 상위. 누르면 차량 케이스 진단으로.</div>
          {topCases.filter((c) => !resolved.has(String(c[0]))).slice(0, 5).map((c, i) => {
            const fire = num(c[7]), inj = num(c[9]), dea = num(c[10]), prio = num(c[6]);
            const lvl = dea > 0 || fire > 0 ? "crit" : prio >= 20 ? "high" : "";
            return (
              <div className="doc caserow" key={i} style={{ cursor: "pointer" }}
                onClick={() => onNavigate("triage", `${c[2]}::${c[0]}`)}>
                <span className={`prio ${lvl}`}>{prio}</span>
                <span className="name" style={{ fontSize: 13 }} title={String(c[2])}>{koModel(String(c[2]))} | {String(c[3]).slice(0, 26)}</span>
                <span className="spacer" />
                {dea > 0 && <span className="sevtag dea">사망 {dea}</span>}
                {inj > 0 && <span className="sevtag inj">부상 {inj}</span>}
                {fire > 0 && <span className="sevtag dea">화재</span>}
                <span className="muted" style={{ fontSize: 12 }}>진단 →</span>
              </div>
            );
          })}
          {topCases.filter((c) => !resolved.has(String(c[0]))).length === 0 && <div className="muted" style={{ marginTop: 8, fontSize: 13 }}>대기 케이스 없음.</div>}
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
          <div className="mail-list">
            {rows?.map((r, i) => {
              const id = String(r[0]), mdl = String(r[2]), comp = String(r[3]), open = () => feed === "recalls" ? openRecall(id) : openComplaint(id);
              return (
                <div className="mail-row" key={i} onClick={open}>
                  <span className="mail-from" title={mdl}>{koModel(mdl)}</span>
                  <span className="mail-subject"><b>{comp}</b> <span className="mail-preview">— {cleanText(String(r[4]))}</span></span>
                  <span>
                    <span className="mail-date">{r[1]}</span>
                    <span className="mail-actions">
                      <button className="mail-act" onClick={(e) => { e.stopPropagation(); open(); }}>상세</button>
                      {feed === "recalls"
                        ? <button className="mail-act" onClick={(e) => { e.stopPropagation(); onNavigate("report", mdl); }}>차종 리포트</button>
                        : <button className="mail-act" onClick={(e) => { e.stopPropagation(); onNavigate("triage", `${mdl}::${id}`); }}>케이스 리포트</button>}
                    </span>
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        {/* 차종 현황 — 차종 클릭 → 차종 진단 리포트, 트리아지 바로가기 */}
        <div className="card" style={{ margin: 0 }}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 style={{ margin: 0 }}>차종 현황</h2>
            <button className="ghost" style={{ fontSize: 12 }} onClick={() => onNavigate("triage")}>케이스 트리아지 →</button>
          </div>
          <div className="hint">차종을 누르면 종합 진단 리포트로, 개별 케이스 중요도·입고순은 트리아지에서 봅니다.</div>
          {!sum && <div className="muted" style={{ marginTop: 10 }}>불러오는 중…</div>}
          {sum?.byModel?.map((m, i) => {
            const model = String(m[0]);
            return (
              <div className="doc" key={i} style={{ cursor: "pointer" }} onClick={() => onNavigate("report", model)}>
                <span style={{ width: 56, flexShrink: 0 }}><CarImage model={model} height={36} /></span>
                <span className="name" title={model}>{koModel(model)}</span>
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
          <label className="field-model" title="답변 생성에 사용할 LLM">
            <span>모델</span>
            <select value={model} onChange={(e) => setModel(e.target.value)}>
              {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </label>
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

    {/* 리콜 상세 모달 — 결함내용·위험·시정조치 */}
    {recall && (
      <div onClick={() => setRecall(null)}
        style={{ position: "fixed", inset: 0, background: "rgba(13,27,42,.55)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}>
        <div className="card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 640, width: "100%", maxHeight: "85vh", overflowY: "auto", margin: 0 }}>
          <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start" }}>
            <h2 style={{ margin: 0 }}>리콜 상세</h2>
            <button className="ghost" style={{ fontSize: 13 }} onClick={() => setRecall(null)}>닫기</button>
          </div>
          {recall === "loading" ? <div className="muted" style={{ marginTop: 12 }}>불러오는 중…</div>
            : String(recall.summary || "").startsWith("__ERR__") ? (
              <div className="err" style={{ marginTop: 12 }}>리콜 상세를 불러오지 못했습니다. 백엔드가 새 코드로 재시작됐는지 확인하세요(/api/analytics/recall).
                <div className="hint" style={{ marginTop: 6 }}>{String(recall.summary).replace("__ERR__", "")}</div></div>)
            : (!recall.campaign && !recall.summary) ? (
              <div className="muted" style={{ marginTop: 12 }}>해당 리콜 정보를 찾지 못했습니다(접수번호 불일치 또는 데이터 없음).</div>)
            : (
            <>
              <div className="row" style={{ gap: 6, marginTop: 10, flexWrap: "wrap" }}>
                <span className="badge" style={{ marginLeft: 0 }}>{recall.model}{recall.year ? ` | ${recall.year}년` : ""}</span>
                <span className="badge">{recall.component}</span>
                {String(recall.parkIt) === "true" && <span className="pill bad">주차 권고(화재위험)</span>}
              </div>
              <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>캠페인 #{recall.campaign} | 접수일 {recall.date}</div>
              {recall.summary && (<><div className="label" style={{ marginTop: 14 }}>결함 내용</div><div style={{ fontSize: 13.5, lineHeight: 1.6 }}>{recall.summary}</div></>)}
              {recall.consequence && (<><div className="label" style={{ marginTop: 14 }}>위험 (Consequence)</div><div style={{ fontSize: 13.5, lineHeight: 1.6 }}>{recall.consequence}</div></>)}
              {recall.remedy && (<><div className="label" style={{ marginTop: 14 }}>시정 조치 (Remedy)</div><div style={{ fontSize: 13.5, lineHeight: 1.6 }}>{recall.remedy}</div></>)}
              <div className="row" style={{ justifyContent: "flex-end", marginTop: 16, gap: 8 }}>
                <button className="ghost" style={{ fontSize: 13 }} onClick={() => { const m = String(recall.model || ""); setRecall(null); if (m) onNavigate("report", m); }}>차종 리포트</button>
                <button className="btn" style={{ fontSize: 13 }} onClick={() => { const m = String(recall.model || ""); setRecall(null); if (m) onNavigate("triage", m); }}>케이스 리포트 →</button>
              </div>
            </>
          )}
        </div>
      </div>
    )}

    {/* 불만 상세 모달 — 전체 내용·심각도, 케이스 접수번호 리포트로 연결 */}
    {complaint && (
      <div onClick={() => setComplaint(null)}
        style={{ position: "fixed", inset: 0, background: "rgba(13,27,42,.55)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}>
        <div className="card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 640, width: "100%", maxHeight: "85vh", overflowY: "auto", margin: 0 }}>
          <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start" }}>
            <h2 style={{ margin: 0 }}>불만 상세</h2>
            <button className="ghost" style={{ fontSize: 13 }} onClick={() => setComplaint(null)}>닫기</button>
          </div>
          {complaint === "loading" ? <div className="muted" style={{ marginTop: 12 }}>불러오는 중…</div>
            : complaint === "error" ? (
              <div className="err" style={{ marginTop: 12 }}>불만 상세를 불러오지 못했습니다. 백엔드가 새 코드로 재시작됐는지 확인하세요(/api/analytics/case).</div>)
            : (() => {
              const c = complaint; const mdl = String(c[2]); const id = String(c[0]);
              return (
                <>
                  <div className="row" style={{ gap: 6, marginTop: 10, flexWrap: "wrap" }}>
                    <span className="badge" style={{ marginLeft: 0 }} title={mdl}>{koModel(mdl)}{c[4] ? ` | ${c[4]}년` : ""}</span>
                    <span className="badge">{String(c[3])}</span>
                    <span className="badge">중요도 {severityPct(num(c[10]), num(c[9]), num(c[7]), num(c[8]))}%</span>
                    {isSafetyCritical(num(c[10]), num(c[7]), String(c[5])) && <span className="pill bad">⚠ 안전확인</span>}
                    {num(c[10]) > 0 && <span className="pill bad">사망 {num(c[10])}</span>}
                    {num(c[9]) > 0 && <span className="pill warn">부상 {num(c[9])}</span>}
                    {num(c[7]) > 0 && <span className="pill bad">화재</span>}
                    {num(c[8]) > 0 && <span className="pill warn">사고</span>}
                  </div>
                  <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>접수 #{id} | 접수일 {String(c[1])}</div>
                  <div className="label" style={{ marginTop: 14 }}>AI 요약 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(한국어 | 처음 1회 생성 후 캐시)</span></div>
                  {csum === "loading" ? <div className="muted" style={{ fontSize: 13 }}>요약 생성 중…</div>
                    : csum && csum.gist ? <div className="answer" style={{ marginTop: 0 }}>{csum.gist}</div>
                    : <div className="muted" style={{ fontSize: 13 }}>요약 없음 — 아래 원문 참고.</div>}
                  <div className="label" style={{ marginTop: 14 }}>접수 내용 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(원문)</span></div>
                  <div style={{ fontSize: 13.5, lineHeight: 1.6 }}>{cleanText(String(c[5])) || "(내용 없음)"}</div>
                  <div className="row" style={{ justifyContent: "flex-end", marginTop: 16, gap: 8 }}>
                    <button className="ghost" style={{ fontSize: 13 }} onClick={() => { setComplaint(null); onNavigate("report", mdl); }}>차종 리포트</button>
                    <button className="btn" style={{ fontSize: 13 }} onClick={() => { setComplaint(null); onNavigate("triage", `${mdl}::${id}`); }}>케이스 리포트 →</button>
                  </div>
                </>
              );
            })()}
        </div>
      </div>
    )}
    </>
  );
}
