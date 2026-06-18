"use client";
import { useEffect, useState } from "react";
import { api, type DocItem } from "@/lib/api";

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
        {docs.length === 0 && <div className="muted">문서 없음</div>}
        {docs.map((d, i) => (
          <div className="doc" key={i}>
            <span className="name">{d.title}</span>
            <span className="badge">{d.chunks} chunks</span>
            <span className="badge">{d.namespace}</span>
            <span className="spacer" />
            <button className="ghost" onClick={() => del(d)}>삭제</button>
          </div>
        ))}
      </div>
    </>
  );
}
