"use client";
import { useEffect, useState } from "react";
import { api, type CaseRecord, type EstimateResult } from "@/lib/api";

const num = (v: unknown) => Number(v) || 0;
const won = (n: number) => Math.round(Number(n) || 0).toLocaleString("ko-KR") + "원";

export default function CaseTriagePanel() {
  const [carModels, setCarModels] = useState<string[]>([]);   // 차종 목록(불만 데이터 기준)
  const [model, setModel] = useState("");        // "" = 전체
  const [component, setComponent] = useState(""); // 부위 키워드
  const [cases, setCases] = useState<CaseRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  // 케이스별 필요 부품(차량 진단) 인라인
  const [diag, setDiag] = useState<Record<string, EstimateResult | "loading">>({});

  useEffect(() => { api.summary().then((s) => setCarModels((s.byModel || []).map((m) => String(m[0])))).catch(() => {}); }, []);
  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);

  async function load() {
    setLoading(true); setErr("");
    try {
      const r = await api.cases(model || undefined, component || undefined);
      setCases(r.cases || []);
      if (r.error) setErr(r.error);
    } catch (e) { setErr(String(e)); } finally { setLoading(false); }
  }

  async function diagnose(id: string, comp: string, mdl: string) {
    if (diag[id] && diag[id] !== "loading") { setDiag((d) => { const n = { ...d }; delete n[id]; return n; }); return; }
    setDiag((d) => ({ ...d, [id]: "loading" }));
    try { const e = await api.estimate(comp, mdl); setDiag((d) => ({ ...d, [id]: e })); }
    catch { setDiag((d) => ({ ...d, [id]: { items: [], partsTotal: 0, laborTotal: 0, grandTotal: 0, note: "진단 실패", car: mdl, problem: comp, laborRate: 0 } })); }
  }

  const critical = cases.filter((c) => num(c[9]) > 0 || num(c[7]) > 0).length; // 부상 또는 화재

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ margin: 0 }}>케이스 우선순위 트리아지</h2>
        <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            <option value="">전체 차종</option>
            {carModels.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <input type="text" placeholder="부위 키워드 (예: ENGINE, AIR BAG)" value={component}
            onChange={(e) => setComponent(e.target.value)} onKeyDown={(e) => e.key === "Enter" && load()} style={{ width: 220 }} />
          <button className="btn" onClick={load} disabled={loading}>{loading ? "조회 중…" : "조회"}</button>
        </div>
      </div>
      <div className="hint">고객 불만(접수)을 <b>심각도 우선순위(사망×100 + 부상×10 + 화재×5 + 사고×3)</b>로 정렬합니다. 먼저 대응할 케이스가 위로 옵니다.</div>

      {err && <div className="err">{err}</div>}

      {cases.length > 0 && (
        <div className="cards" style={{ marginTop: 14 }}>
          <div className="stat"><div className="v">{cases.length}</div><div className="l">조회된 케이스</div></div>
          <div className={`stat ${critical > 0 ? "warn" : ""}`}><div className="v">{critical}</div><div className="l">중대(부상·화재)</div></div>
          <div className="stat"><div className="v">{num(cases[0]?.[6])}</div><div className="l">최고 우선순위</div></div>
        </div>
      )}

      {loading && cases.length === 0 && (
        <div className="empty"><div className="empty-ic"><svg className="spin" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /></svg></div><div>케이스를 불러오는 중…</div></div>
      )}
      {!loading && cases.length === 0 && !err && (
        <div className="empty"><div className="empty-ic"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg></div><div>조건에 맞는 케이스가 없습니다.</div></div>
      )}

      <div style={{ marginTop: 12 }}>
        {cases.map((c, i) => {
          const id = String(c[0]);
          const pr = num(c[6]), fire = num(c[7]), crash = num(c[8]), inj = num(c[9]), dea = num(c[10]);
          const accent = dea > 0 || fire > 0 ? "var(--danger)" : pr > 0 ? "var(--warn)" : "var(--border)";
          const d = diag[id];
          return (
            <div key={i} style={{ padding: "12px 0 12px 12px", borderBottom: "1px solid var(--border)", borderLeft: `3px solid ${accent}` }}>
              <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", gap: 10 }}>
                <div style={{ minWidth: 0 }}>
                  <div className="muted" style={{ fontSize: 12, marginBottom: 3 }}>
                    <span className="badge" style={{ marginLeft: 0 }}>우선순위 {pr}</span>
                    접수 #{id} · {c[2]} · {c[4]}년 · {c[1]}
                  </div>
                  <div style={{ fontWeight: 600, fontSize: 13.5 }}>{String(c[3])}</div>
                  {pr > 0 && (
                    <div className="row" style={{ gap: 4, marginTop: 4, flexWrap: "wrap" }}>
                      {dea > 0 && <span className="pill bad">사망 {dea}</span>}
                      {inj > 0 && <span className="pill warn">부상 {inj}</span>}
                      {fire > 0 && <span className="pill bad">화재</span>}
                      {crash > 0 && <span className="pill warn">사고</span>}
                    </div>
                  )}
                  <div className="snip" style={{ marginTop: 4 }}>{String(c[5]).slice(0, 140)}…</div>
                </div>
                <button className="ghost" style={{ fontSize: 12, whiteSpace: "nowrap" }} onClick={() => diagnose(id, String(c[3]), String(c[2]))}>
                  {d ? "닫기" : "차량 진단"}
                </button>
              </div>
              {d === "loading" && <div className="muted" style={{ fontSize: 12, padding: "6px 0" }}>필요 부품 산정 중…</div>}
              {d && d !== "loading" && (
                <div style={{ marginTop: 6, padding: "8px 10px", background: "var(--surface-2)", borderRadius: 8 }}>
                  {d.items.length === 0 ? <div className="muted" style={{ fontSize: 12 }}>해당 부품을 찾지 못했어요 (부위 신호가 약함)</div> : (
                    <table style={{ fontSize: 12 }}><tbody>
                      {d.items.map((it, k) => (
                        <tr key={k}><td>{it.part}</td><td className="muted">{it.component}</td><td className="right">{won(it.lineTotal)}</td></tr>
                      ))}
                    </tbody></table>
                  )}
                  {d.items.length > 0 && <div className="hint" style={{ marginTop: 4 }}>참고 합계 <b>{won(d.grandTotal)}</b> (샘플 단가)</div>}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
