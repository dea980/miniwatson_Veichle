"use client";
import { useEffect, useRef, useState } from "react";
import { api, type AskResult, type Models } from "@/lib/api";

export default function AskPanel() {
  const [question, setQuestion] = useState("");
  const [namespace, setNamespace] = useState("vehicle");
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const [result, setResult] = useState<AskResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  // --- 음성 ---
  const [listening, setListening] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const [voiceOk, setVoiceOk] = useState(false);
  const recRef = useRef<any>(null);

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
    // 브라우저 음성 지원 확인 (Chrome/Safari)
    const SR = (typeof window !== "undefined") && ((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition);
    setVoiceOk(Boolean(SR) && typeof window !== "undefined" && "speechSynthesis" in window);
    // 음성 목록은 비동기 로드 — 미리 채워둬야 speak()에서 한국어 voice를 고를 수 있음
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.getVoices();
      window.speechSynthesis.onvoiceschanged = () => window.speechSynthesis.getVoices();
    }
  }, []);

  async function ask(q?: string) {
    const query = (q ?? question).trim();
    if (!query) return;
    setLoading(true); setErr(""); setResult(null);
    try {
      const r = await api.ask(query, namespace, model || undefined);
      setResult(r);
    } catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  // STT: 마이크 → 텍스트 → 자동 질문
  function startListening() {
    const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SR) return;
    const rec = new SR();
    rec.lang = "ko-KR";
    rec.interimResults = false;
    rec.maxAlternatives = 1;
    rec.onresult = (e: any) => {
      const text = e.results[0][0].transcript;
      setQuestion(text);
      setListening(false);
      ask(text);                 // 인식되면 바로 질문
    };
    rec.onerror = () => setListening(false);
    rec.onend = () => setListening(false);
    recRef.current = rec;
    setListening(true);
    rec.start();
  }
  function stopListening() {
    recRef.current?.stop();
    setListening(false);
  }

  // 마크다운 기호/링크 제거 — 안 그러면 "#", "*" 같은 기호를 그대로 읽음
  function cleanForSpeech(t: string): string {
    return (t || "")
      .replace(/\[(.*?)\]\([^)]*\)/g, "$1")  // [텍스트](링크) → 텍스트
      .replace(/[#*_`>~|]/g, " ")            // 마크다운 기호 제거
      .replace(/\s+/g, " ")
      .trim();
  }

  // TTS: 답변 읽기 — 한국어 voice를 명시 선택(미선택 시 글자 단위로 읽히는 문제 방지)
  function speak(text: string) {
    if (!("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(cleanForSpeech(text));
    u.lang = "ko-KR";
    const voices = window.speechSynthesis.getVoices();
    const ko = voices.find((v) => v.lang?.toLowerCase().startsWith("ko"));
    if (ko) u.voice = ko;       // 실제 한국어 음성 지정 → 자연스러운 단어 단위 발음
    u.rate = 1.0; u.pitch = 1.0;
    u.onend = () => setSpeaking(false);
    setSpeaking(true);
    window.speechSynthesis.speak(u);
  }
  function stopSpeak() { window.speechSynthesis.cancel(); setSpeaking(false); }

  async function vote(value: string) {
    if (result?.logId == null) return;
    try { await api.feedback(result.logId, value); } catch {}
  }

  return (
    <div className="card">
      <h2>매뉴얼에 물어보기</h2>
      <div className="row">
        <input className="grow" type="text" placeholder="질문 (예: 안전벨트 경고등은 언제 울리나요?)"
          value={question} onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && ask()} />
        {voiceOk && (
          <button className={`ghost iconbtn ${listening ? "listening" : ""}`} title="음성으로 질문"
            onClick={listening ? stopListening : startListening}>
            {listening ? <>● 듣는 중</> : (
              <svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0" /><line x1="12" y1="18" x2="12" y2="21" /><line x1="8" y1="21" x2="16" y2="21" /></svg>
            )}
          </button>
        )}
        <input type="text" placeholder="namespace" value={namespace}
          onChange={(e) => setNamespace(e.target.value)} style={{ width: 120 }} />
        <select value={model} onChange={(e) => setModel(e.target.value)}>
          {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
        <button className="btn" onClick={() => ask()} disabled={loading}>{loading ? "검색 중…" : "질문"}</button>
      </div>
      <div className="hint">
        자동차 데이터는 namespace에 <b>vehicle</b> 입력. {voiceOk ? "🎤로 말하면 자동 질문, 답변은 🔊로 듣기." : "(이 브라우저는 음성 미지원 — Chrome 권장)"}
      </div>
      <div className="row" style={{ gap: 6, marginTop: 8 }}>
        {["안전벨트 경고등은 언제 울리나요?", "프리텐셔너 안전띠 취급 시 주의사항은?", "후방 카메라가 안 나올 때 점검 항목은?"].map((ex) => (
          <button key={ex} className="ghost" style={{ fontSize: 12 }} onClick={() => { setQuestion(ex); ask(ex); }}>{ex}</button>
        ))}
      </div>

      {err && <div className="err">{err}</div>}

      {!result && !loading && !err && (
        <div className="empty">
          <div className="empty-ic"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg></div>
          <div>오너스 매뉴얼(취급설명서)에 무엇이든 물어보세요. 답변에는 항상 <b>출처(근거 청크)</b>가 함께 표시됩니다.</div>
        </div>
      )}

      {result && (
        <>
          <div className="row" style={{ justifyContent: "space-between", marginTop: 14 }}>
            <span className="label" style={{ margin: 0 }}>답변</span>
            {voiceOk && (
              <button className="ghost spk" onClick={() => (speaking ? stopSpeak() : speak(result.answer || ""))}>
                {speaking ? (<><span className="spk-stop" />정지</>) : (
                  <><svg viewBox="0 0 24 24"><polygon points="4 9 8 9 13 5 13 19 8 15 4 15" /><path d="M16.5 9a4 4 0 0 1 0 6" /></svg>듣기</>
                )}
              </button>
            )}
          </div>
          <div className="answer">{result.answer || "(No answer)"}</div>

          <div className="label">근거</div>
          {(result.sources || []).length === 0 && <div className="muted">근거 없음</div>}
          {(result.sources || []).map((s, i) => (
            <div className="source" key={i}>
              <div className="title">{s.title}</div>
              <div className="snip">{(s.summary || "").slice(0, 180)}…</div>
            </div>
          ))}
          {result.logId != null && (
            <div className="row" style={{ marginTop: 10 }}>
              <button className="ghost" onClick={() => vote("up")}>👍</button>
              <button className="ghost" onClick={() => vote("down")}>👎</button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
