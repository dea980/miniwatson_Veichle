"use client";
import { useEffect, useState } from "react";
import { api, type DiagnoseResult, type EstimateResult, type Models } from "@/lib/api";
import Markdown from "@/components/Markdown";

const won = (n: number) => n.toLocaleString("ko-KR") + "원";

export default function DiagnosePanel() {
  const [file, setFile] = useState<File | null>(null);
  const [namespace, setNamespace] = useState("vehicle");
  const [models, setModels] = useState<Models | null>(null);
  const [model, setModel] = useState("");
  const [car, setCar] = useState("");
  const [diag, setDiag] = useState<DiagnoseResult | null>(null);
  const [est, setEst] = useState<EstimateResult | null>(null);
  const [problem, setProblem] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    api.models().then((m) => { setModels(m); setModel(m.default); }).catch(() => {});
  }, []);

  async function diagnose() {
    if (!file) { setErr("이미지를 선택하세요"); return; }
    setBusy(true); setErr(""); setDiag(null); setEst(null);
    try {
      const d = await api.diagnoseImage(file, namespace, model || undefined);
      setDiag(d); setProblem(d.problem || d.caption || "");
    } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  }

  async function estimate() {
    const p = problem.trim();
    if (!p) { setErr("증상/진단 내용이 필요합니다"); return; }
    setBusy(true); setErr("");
    try { setEst(await api.estimate(p, car, model || undefined)); }
    catch (e) { setErr(String(e)); } finally { setBusy(false); }
  }

  return (
    <div className="card">
      <h2>사진으로 진단받기</h2>
      <div className="row">
        <input type="file" accept="image/*" onChange={(e) => setFile(e.target.files?.[0] || null)} />
        <label className="field-model" title="진단에 사용할 멀티모달 LLM">
          <span>모델</span>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            {(models?.available || []).map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
        <button className="btn" onClick={diagnose} disabled={busy}>{busy ? "처리 중…" : "이미지 진단"}</button>
      </div>
      <div className="hint">계기판 경고등 | 파손 부품 사진 → Vision+OCR 식별 → 매뉴얼 RAG 진단. (멀티모달 모델 필요: llava 등)</div>

      {err && <div className="err">{err}</div>}

      {!diag && !err && !busy && (
        <div className="empty">
          <div className="empty-ic"><svg viewBox="0 0 24 24"><path d="M4 8h3l1.5-2h7L17 8h3v11H4z" /><circle cx="12" cy="13" r="3.2" /></svg></div>
          <div>계기판 경고등이나 파손 부품 <b>사진</b>을 올리면, Vision과 OCR로 인식한 뒤 매뉴얼 근거로 진단하고 <b>필요 부품</b>까지 산정합니다.</div>
        </div>
      )}

      {busy && !diag && <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>이미지를 인식하고 매뉴얼을 검색하는 중…</div></div>}

      {diag && (
        <>
          <div className="label">이미지 인식</div>
          <div className="muted">{diag.caption || "(설명 없음)"}{diag.ocr ? ` | OCR: ${diag.ocr.slice(0, 80)}` : ""}</div>
          <div className="label">진단</div>
          <div className="answer"><Markdown text={diag.diagnosis} /></div>
          {diag.sources?.length > 0 && <div className="hint">매뉴얼 근거: {diag.sources.join(" | ")}</div>}

          <div className="label">필요 부품 산정</div>
          <div className="row">
            <input className="grow" type="text" value={problem} onChange={(e) => setProblem(e.target.value)} placeholder="증상/진단 (자동 채움, 수정 가능)" />
            <input type="text" value={car} onChange={(e) => setCar(e.target.value)} style={{ width: 110 }} placeholder="차종(선택)" />
            <button className="btn" onClick={estimate} disabled={busy}>부품 산정</button>
          </div>
        </>
      )}

      {est && (
        <>
          <div className="label">필요 부품 명세</div>
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr><th>부품</th><th>부위</th><th className="right">공임(h)</th><th className="right">참고 단가</th></tr></thead>
              <tbody>
                {est.items.length === 0 && <tr><td colSpan={4} className="muted">해당 부품을 찾지 못했어요 (증상을 더 구체적으로)</td></tr>}
                {est.items.map((it, i) => (
                  <tr key={i}>
                    <td>{it.part}</td>
                    <td className="muted">{it.component}</td>
                    <td className="right">{it.laborHours}</td>
                    <td className="right">{won(it.lineTotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {est.items.length > 0 && (
            <div className="hint">참고 합계: 부품 {won(est.partsTotal)} + 공임 {won(est.laborTotal)} = <b>{won(est.grandTotal)}</b>
              {" "}· 공임 {won(est.laborRate)}/h <b>(샘플 단가 — 실제 가격 아님)</b></div>
          )}
          {est.note && <div className="answer" style={{ marginTop: 8 }}>{est.note}</div>}
        </>
      )}
    </div>
  );
}
