"use client";
import { useEffect, useState } from "react";
import { api, type DocItem } from "@/lib/api";

// 제목으로 자동 분류 (백엔드 메타 없이도 정리)
const MODELS = ["PALISADE", "SANTA FE", "SANTAFE", "TUCSON", "SONATA", "ELANTRA", "KONA", "ACCENT", "VELOSTER",
  "AZERA", "EQUUS", "GENESIS", "ENTOURAGE"];
function vehicleOf(title: string): string {
  const t = (title || "").toUpperCase().replace(/[_\-]/g, " ");
  for (const m of MODELS) if (t.includes(m)) return m === "SANTAFE" ? "SANTA FE" : m;
  return "공통 / 기타";
}
function categoryOf(title: string): "매뉴얼" | "진단리포트" | "기타" {
  const t = (title || "").toLowerCase();
  if (/manual|service|owner|매뉴얼|취급|정비|accent|sonata|tucson|elantra|palisade|kona|santa/.test(t)) return "매뉴얼";
  if (/진단|report|리포트|diagnos/.test(t)) return "진단리포트";
  return "기타";
}

export default function KnowledgeBasePanel() {
  const [docs, setDocs] = useState<DocItem[]>([]);
  const [nsFilter, setNsFilter] = useState("");
  const [uploadNs, setUploadNs] = useState("vehicle");
  const [file, setFile] = useState<File | null>(null);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

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

  return (
    <>
      <div className="card">
        <h2>파일 업로드</h2>
        <div className="row">
          <input type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          <input type="text" placeholder="namespace" value={uploadNs}
            onChange={(e) => setUploadNs(e.target.value)} style={{ width: 130 }} />
          <button className="btn" onClick={upload} disabled={busy}>업로드</button>
        </div>
        <div className="hint">PDF/DOCX/HWP/TXT → RAG, 이미지 → 멀티모달. CSV 표는 Tabular SQL 탭.</div>
        {msg && <div className="hint">{msg}</div>}
      </div>

      <div className="card">
        <h2>지식베이스</h2>
        <div className="row" style={{ marginBottom: 12 }}>
          <input type="text" placeholder="namespace 필터 (비우면 전체)" value={nsFilter}
            onChange={(e) => setNsFilter(e.target.value)} style={{ width: 220 }} />
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
                      <span className="name">{d.title}</span>
                      <span className="badge">{d.chunks} chunks</span>
                      <span className="badge">{d.namespace}</span>
                      <span className="spacer" />
                      <button className="ghost" onClick={() => del(d)}>삭제</button>
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
