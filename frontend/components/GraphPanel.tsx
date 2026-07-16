"use client";
import { useEffect, useState } from "react";
import { api, koModel } from "@/lib/api";

type Profile = {
  model: string;
  component: string;
  recalls: [string, string, string, string][];
  complaints: [number, number, number][];
  parts: [string, number, number][];
};

const num = (v: unknown) => Number(v) || 0;
const won = (n: number) => Math.round(num(n)).toLocaleString("ko-KR") + "원";

export default function GraphPanel() {
  const [model, setModel] = useState("PALISADE");
  const [comps, setComps] = useState<[string, number, number][]>([]);
  const [sel, setSel] = useState<string>("");
  const [profile, setProfile] = useState<Profile | null>(null);
  const [loading, setLoading] = useState(false);

  async function loadModel() {
    setLoading(true); setProfile(null); setSel("");
    try { const r = await api.graphModelComponents(model.trim().toUpperCase()); setComps(r.components || []); }
    catch { setComps([]); } finally { setLoading(false); }
  }
  async function loadProfile(c: string) {
    setSel(c);
    try { setProfile(await api.graphComponentProfile(model.trim().toUpperCase(), c)); } catch { setProfile(null); }
  }
  useEffect(() => { loadModel(); /* eslint-disable-next-line */ }, []);

  const maxN = Math.max(1, ...comps.map((c) => num(c[1]) + num(c[2])));
  // 리콜은 연식별 중복 행이라 캠페인 기준으로 dedup해서 표시
  const recallRows = profile ? [...new Map(profile.recalls.map((r) => [r[0], r])).values()] : [];
  const cx = profile?.complaints?.[0];

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <h2 style={{ margin: 0 }}>지식그래프 탐색 <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>| 차종 → 부위 → 리콜·증상·부품</span></h2>
        <div className="row" style={{ gap: 6 }}>
          <input value={model} onChange={(e) => setModel(e.target.value)} onKeyDown={(e) => e.key === "Enter" && loadModel()}
            placeholder="차종 (예: PALISADE)"
            style={{ padding: "6px 10px", borderRadius: 8, border: "1px solid var(--border-strong, #ccc)", background: "var(--surface-1, #fff)", color: "var(--text-primary, #111)" }} />
          <button className="btn" onClick={loadModel} disabled={loading}>{loading ? "탐색 중…" : "탐색"}</button>
        </div>
      </div>
      <div className="hint">부위 어휘를 정규 개념으로 통합해 리콜·불만·부품을 한 축으로 순회합니다. 막대(부위)를 누르면 그 부위의 규제·증상·비용 프로파일이 열립니다.</div>

      <div className="label" style={{ marginTop: 12 }}>{koModel(model)} 부위별 리스크 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>| 리콜 + 불만 (부위 클릭)</span></div>
      {comps.length > 0 ? (
        <div className="bars">
          {comps.map((c, i) => (
            <div className="bar-row" key={i} style={{ cursor: "pointer" }} onClick={() => loadProfile(String(c[0]))}>
              <span className="bar-label" style={{ fontWeight: sel === c[0] ? 700 : 400 }}>{String(c[0])}</span>
              <span className="bar-track"><span className={`bar-fill s${Math.min(i, 5)}`} style={{ width: `${((num(c[1]) + num(c[2])) / maxN) * 100}%` }} /></span>
              <span className="bar-val" style={{ width: 130 }}>리콜 {num(c[1])} / 불만 {num(c[2])}</span>
            </div>
          ))}
        </div>
      ) : <div className="muted" style={{ fontSize: 13 }}>데이터 없음 — 차종명을 확인하세요.</div>}

      {profile && (
        <div style={{ marginTop: 16, paddingTop: 12, borderTop: "1px solid var(--border, #eee)" }}>
          <div className="label">{koModel(profile.model)} · {profile.component} 프로파일 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>| Model → Component → 근거</span></div>
          {cx && (
            <div className="cards" style={{ marginBottom: 10 }}>
              <div className="stat"><div className="v">{num(cx[0])}</div><div className="l">불만</div></div>
              <div className={`stat ${num(cx[1]) > 0 ? "danger" : ""}`}><div className="v">{num(cx[1])}</div><div className="l">화재</div></div>
              <div className={`stat ${num(cx[2]) > 0 ? "warn" : ""}`}><div className="v">{num(cx[2])}</div><div className="l">부상</div></div>
              <div className="stat"><div className="v">{recallRows.length}</div><div className="l">리콜</div></div>
            </div>
          )}
          {profile.parts?.length > 0 && (
            <>
              <div className="label">교체 부품 · 비용</div>
              <div style={{ overflowX: "auto" }}>
                <table>
                  <thead><tr><th>부품</th><th className="right">단가</th><th className="right">공임(h)</th></tr></thead>
                  <tbody>{profile.parts.map((p, i) => (
                    <tr key={i}><td>{p[0]}</td><td className="right">{won(num(p[1]))}</td><td className="right">{num(p[2])}</td></tr>
                  ))}</tbody>
                </table>
              </div>
            </>
          )}
          {recallRows.length > 0 && (
            <>
              <div className="label">리콜 근거 <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>| 캠페인 기준</span></div>
              <div style={{ overflowX: "auto" }}>
                <table>
                  <thead><tr><th>캠페인</th><th>일자</th><th>부위</th></tr></thead>
                  <tbody>{recallRows.map((r, i) => (
                    <tr key={i}><td>{String(r[0])}</td><td>{String(r[1])}</td><td className="muted">{String(r[2]).slice(0, 44)}</td></tr>
                  ))}</tbody>
                </table>
              </div>
            </>
          )}
          {!profile.parts?.length && (
            <div className="hint">이 부위는 부품 카탈로그에 매핑이 없어 비용이 비어 있습니다(리콜·증상만). 카탈로그를 넓히면 커버리지가 올라갑니다.</div>
          )}
        </div>
      )}
    </div>
  );
}
