"use client";
import { useEffect, useState } from "react";
import { api, type DocItem, type Source } from "@/lib/api";

// 제목으로 자동 분류 (백엔드 메타 없이도 정리).
// 순서 중요: 더 구체적인 토큰(IONIQ5)을 일반(IONIQ)보다 앞에 둬 정확히 그룹핑.
const MODELS = ["PALISADE", "SANTA FE", "SANTAFE", "TUCSON", "SONATA", "ELANTRA", "KONA", "ACCENT", "VELOSTER",
  "AZERA", "EQUUS", "GENESIS", "ENTOURAGE",
  // 전기/수소/하이브리드 + 신형(한국 라인업)
  "IONIQ5", "IONIQ6", "IONIQ9", "IONIQ", "NEXO", "ST1", "CASPER", "VENUE",
  "GRANDEUR", "AVANTE", "STARIA", "STAREX", "PORTER", "I30", "I40",
  // 단종/상용/기타 한국 라인업
  "IX35", "MAXCRUZ", "VERACRUZ", "ASLAN", "BLUEON", "COUNTY", "MIGHTY",
  "UNIVERSE", "XCIENT", "NEWPOWER", "SOLATI", "PAVISE", "ELECCITY"];
function vehicleOf(title: string): string {
  const t = (title || "").toUpperCase().replace(/[_\-]/g, " ");
  for (const m of MODELS) if (t.includes(m)) return m === "SANTAFE" ? "SANTA FE" : m;
  return "공통 / 기타";
}
function categoryOf(title: string): "매뉴얼" | "진단리포트" | "기타" {
  const t = (title || "").toLowerCase();
  if (/manual|service|owner|매뉴얼|취급|정비|accent|sonata|tucson|elantra|palisade|kona|santa|ioniq|nexo|casper|venue|grandeur|avante|staria|starex|porter|veloster|genesis|equus|azera|i30|st1/.test(t)) return "매뉴얼";
  if (/진단|report|리포트|diagnos/.test(t)) return "진단리포트";
  return "기타";
}

// 파일명(hyundai_<연식>_<모델>_*.pdf) → archive.org 원본 PDF URL. 매칭 안 되면 null(업로드 문서 등).
function archiveUrl(title: string): string | null {
  const lc = (title || "").toLowerCase();
  if (/_kr|owners_kr/.test(lc)) return null;   // 한국 포털(full_pdf) 출처 — archive.org에 없음
  const m = lc.match(/hyundai[_-](\d{4})[_-]([a-z0-9]+)/);
  if (!m) return null;
  const id = `car-service-manuals-hyundai-${m[1]}-${m[2]}`;
  return `https://archive.org/download/${id}/${id}.pdf`;
}

// 문서 상세 — 원본 PDF(새 탭) + 그 문서만 근거로 답하는 전용 어시스턴트
function DocDetail({ doc, onBack }: { doc: DocItem; onBack: () => void }) {
  type Msg = { role: "user" | "assistant"; text: string; sources?: Source[] };
  const [q, setQ] = useState("");
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const pdf = archiveUrl(doc.title);

  async function ask(text?: string) {
    const query = (text ?? q).trim();
    if (!query || busy) return;
    setMsgs((m) => [...m, { role: "user", text: query }]);
    setQ(""); setBusy(true);
    try {
      const r = await api.ask(query, doc.namespace || "vehicle", undefined, doc.title);   // ← 이 문서로 한정
      setMsgs((m) => [...m, { role: "assistant", text: r.answer || "(답변 없음)", sources: r.sources }]);
    } catch (e) {
      setMsgs((m) => [...m, { role: "assistant", text: "오류: " + String(e) }]);
    } finally { setBusy(false); }
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <button className="ghost" style={{ fontSize: 12 }} onClick={onBack}>← 지식베이스</button>
        {pdf
          ? <a className="btn" href={pdf} target="_blank" rel="noopener noreferrer" style={{ fontSize: 12, textDecoration: "none" }}>원본 PDF 새 탭으로 열기 ↗</a>
          : <span className="muted" style={{ fontSize: 12 }}>원본 링크 없음(업로드 문서)</span>}
      </div>
      <h2 style={{ marginTop: 10 }}>{doc.title}</h2>
      <div className="hint">이 문서(<b>{doc.chunks} chunks</b>)만 근거로 답하는 <b>문서 전용 어시스턴트</b>입니다. 다른 매뉴얼은 참조하지 않습니다.</div>

      <div className="chat-thread" style={{ minHeight: 300 }}>
        {msgs.length === 0 && !busy && (
          <div style={{ margin: "auto", textAlign: "center", maxWidth: 340 }}>
            <div className="muted" style={{ marginBottom: 12 }}>이 매뉴얼에 대해 물어보세요. 답변은 <b>이 문서</b>에서만 찾습니다.</div>
            <div className="row" style={{ gap: 6, flexWrap: "wrap", justifyContent: "center" }}>
              {["주요 경고등 의미는?", "정기 점검 주기 알려줘", "안전벨트 관련 주의사항?"].map((ex) => (
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
        {busy && <div className="bubble assistant"><span className="muted">이 문서에서 찾는 중…</span></div>}
      </div>
      <div className="row" style={{ marginTop: 4 }}>
        <input className="grow" type="text" placeholder={`${doc.title}에 질문…`}
          value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === "Enter" && ask()} />
        <button className="btn" onClick={() => ask()} disabled={busy}>전송</button>
      </div>
    </div>
  );
}

export default function KnowledgeBasePanel() {
  const [docs, setDocs] = useState<DocItem[]>([]);
  const [nsFilter, setNsFilter] = useState("");
  const [uploadNs, setUploadNs] = useState("vehicle");
  const [file, setFile] = useState<File | null>(null);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<DocItem | null>(null);   // 문서 상세(전용 어시스턴트)

  async function refresh() {
    setErr("");
    try { setDocs(await api.documents(nsFilter || undefined)); }
    catch (e) { setErr(String(e)); }
  }
  useEffect(() => { refresh(); /* eslint-disable-next-line */ }, []);

  async function upload() {
    if (!file) { setErr("파일을 선택하세요"); return; }
    setBusy(true); setErr(""); setMsg("업로드 중…");
    try {
      const data = await api.ingestFile(file, uploadNs || "default");
      setMsg(Array.isArray(data) ? `인제스트 완료: ${data.length} 청크` : "인제스트 완료");
      setFile(null); refresh();
    } catch (e) { setErr(String(e)); setMsg(""); } finally { setBusy(false); }
  }

  async function del(d: DocItem) {
    if (!confirm(`"${d.title}" 문서를 삭제할까요? (모든 청크)`)) return;
    await api.deleteDocument(d.title, d.namespace);
    refresh();
  }

  if (selected) return <DocDetail doc={selected} onBack={() => setSelected(null)} />;

  return (
    <>
      <div className="card">
        <h2>파일 업로드</h2>
        <div className="row">
          <input type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          <button className="btn" onClick={upload} disabled={busy}>업로드</button>
        </div>
        <div className="hint">PDF/DOCX/HWP/TXT → RAG, 이미지 → 멀티모달. CSV 표는 Tabular SQL 탭.</div>
        {msg && <div className="hint">{msg}</div>}
      </div>

      <div className="card">
        <h2>지식베이스</h2>
        <div className="row" style={{ marginBottom: 12 }}>
          <button className="ghost" onClick={refresh}>새로고침</button>
        </div>
        {err && <div className="err">{err}</div>}
        {docs.length === 0 && !err && (
          <div className="empty">
            <div className="empty-ic"><svg viewBox="0 0 24 24"><path d="M5 4h13v16H7a2 2 0 01-2-2z" /><line x1="9" y1="4" x2="9" y2="20" /></svg></div>
            <div>아직 적재된 문서가 없습니다. 위에서 매뉴얼 PDF나 문서를 업로드하면 청크로 나뉘어 <b>매뉴얼 검색</b> 탭에서 근거로 쓰입니다.</div>
          </div>
        )}
        {(["매뉴얼", "진단리포트", "기타"] as const).map((cat) => {
          const catDocs = docs.filter((d) => categoryOf(d.title) === cat);
          if (catDocs.length === 0) return null;
          // 차종별로 묶기 (차종 추출 → 그룹)
          const byVeh: Record<string, DocItem[]> = {};
          for (const d of catDocs) (byVeh[vehicleOf(d.title)] ||= []).push(d);
          const vehKeys = Object.keys(byVeh).sort((a, b) => (a === "공통 / 기타" ? 1 : b === "공통 / 기타" ? -1 : a.localeCompare(b)));
          const chunks = catDocs.reduce((s, d) => s + (d.chunks || 0), 0);
          return (
            <section key={cat} style={{ marginTop: 14 }}>
              <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline", borderBottom: "2px solid var(--border)", paddingBottom: 6 }}>
                <h3 style={{ margin: 0, fontSize: 15 }}>{cat}</h3>
                <span className="muted" style={{ fontSize: 12 }}>{catDocs.length}개 문서 · {chunks} chunks</span>
              </div>
              {vehKeys.map((veh) => (
                <div key={veh} style={{ marginTop: 8 }}>
                  <div className="muted" style={{ fontSize: 12, fontWeight: 600, margin: "4px 0" }}>{veh}</div>
                  {byVeh[veh].map((d, i) => (
                    <div className="doc" key={i}>
                      <a className="name" onClick={() => setSelected(d)} style={{ cursor: "pointer", color: "var(--accent-2)" }}
                        title="문서 전용 어시스턴트 열기">{d.title}</a>
                      <span className="badge">{d.chunks} chunks</span>
                      <span className="badge">{d.namespace}</span>
                      <span className="spacer" />
                      <button className="ghost" style={{ fontSize: 12 }} onClick={() => setSelected(d)}>열기 →</button>
                      <button className="ghost" style={{ fontSize: 12 }} onClick={() => del(d)}>삭제</button>
                    </div>
                  ))}
                </div>
              ))}
            </section>
          );
        })}
      </div>
    </>
  );
}
