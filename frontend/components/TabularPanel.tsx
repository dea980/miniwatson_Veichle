"use client";
import { useState } from "react";
import { api } from "@/lib/api";

type Schema = { name: string; type: string }[];
type AskRes = { sql?: string; columns: string[]; rows: (string | number | null)[][] };

// 도메인 데이터셋 프리셋 — raw 경로 타이핑 대신 원클릭. "설명가능 분석"(숫자 밑에 SQL) 페르소나.
const PRESETS = [
  { label: "리콜", table: "recalls", path: "data/vehicle/recalls/hyundai_recalls_nhtsa.csv",
    examples: ["차종별 리콜 건수 많은 순", "부품(component)별 리콜 상위 5", "연도별 리콜 추세"] },
  { label: "불만", table: "complaints", path: "data/vehicle/complaints/hyundai_complaints_nhtsa.csv",
    examples: ["차종별 불만 건수 많은 순", "화재(fire) 신고가 있는 불만 수", "부상자 합계가 큰 차종 상위"] },
  { label: "부품 단가", table: "parts", path: "data/vehicle/parts/parts_pricing.csv",
    examples: ["부품별 단가 높은 순", "카테고리별 평균 단가"] },
  { label: "점검", table: "inspection", path: "data/vehicle/inspection/inspection_sample.csv",
    examples: ["장치별 점검 항목 수", "결과가 정비필요인 항목"] },
];

function parseSchema(s: string): Schema {
  return (s || "").split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
    const i = l.indexOf(" ");
    return i < 0 ? { name: l, type: "" } : { name: l.slice(0, i), type: l.slice(i + 1) };
  });
}

// 2컬럼이고 두번째가 숫자면 막대그래프용으로 인식
function isChartable(r: AskRes): boolean {
  return r.columns.length === 2 && r.rows.length > 0 && r.rows.every((row) => typeof row[1] === "number");
}

export default function TabularPanel() {
  const [table, setTable] = useState("recalls");
  const [path, setPath] = useState("data/vehicle/recalls/hyundai_recalls_nhtsa.csv");
  const [question, setQuestion] = useState("차종(Model)별 리콜 건수를 많은 순으로 보여줘");
  const [schema, setSchema] = useState<Schema | null>(null);
  const [res, setRes] = useState<AskRes | null>(null);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [examples, setExamples] = useState<string[]>(PRESETS[0].examples);

  // 프리셋 선택 → 테이블·경로 세팅 + 자동 로드 + 예시 질문 교체
  function selectPreset(p: typeof PRESETS[number]) {
    setTable(p.table); setPath(p.path); setLoaded(false); setRes(null); setErr(""); setExamples(p.examples);
    api.tabularLoad(p.table, p.path)
      .then((r) => { setSchema(parseSchema(r.schema)); setLoaded(true); })
      .catch((e) => setErr(String(e)));
  }

  async function load() {
    setBusy(true); setErr(""); setSchema(null);
    try {
      const r = await api.tabularLoad(table, path);
      setSchema(parseSchema(r.schema)); setLoaded(true);
    } catch (e) { setErr(String(e)); setLoaded(false); } finally { setBusy(false); }
  }

  async function upload() {
    if (!file) { setErr("CSV/XLSX 파일을 선택하세요"); return; }
    setBusy(true); setErr(""); setSchema(null);
    try {
      const r = await api.tabularUpload(file, table || "uploaded");
      setSchema(parseSchema(r.schema)); setLoaded(true);
    } catch (e) { setErr(String(e)); setLoaded(false); } finally { setBusy(false); }
  }

  function downloadSample() {
    const csv = "model,year,component,count\nPALISADE,2022,BRAKE,3\nSANTA FE,2021,FUEL,2\nELANTRA,2021,SEAT BELTS,1\n";
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const a = document.createElement("a"); a.href = url; a.download = "sample_table.csv"; a.click();
    URL.revokeObjectURL(url);
  }

  // 질문 시 테이블이 아직 안 올라갔으면 자동으로 먼저 로드 (재시작/순서 문제 우회)
  async function ask(q?: string) {
    const query = (q ?? question).trim();
    if (!query) return;
    setBusy(true); setErr(""); setRes(null);
    try {
      if (!loaded) { const r = await api.tabularLoad(table, path); setSchema(parseSchema(r.schema)); setLoaded(true); }
      setRes(await api.tabularAsk(table, query));
    } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  }

  const chart = res && isChartable(res);
  const maxVal = chart ? Math.max(...res!.rows.map((r) => Number(r[1]) || 0)) : 0;

  return (
    <div className="card">
      <h2>데이터로 질문하기 <span className="muted" style={{ fontSize: 12, fontWeight: 400 }}>· 숫자 밑에 생성 SQL을 함께 보여주는 설명가능 분석</span></h2>

      {/* 도메인 데이터셋 프리셋 — 원클릭 로드 */}
      <div className="row" style={{ gap: 6, alignItems: "center", flexWrap: "wrap" }}>
        <span className="muted" style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase", letterSpacing: ".05em" }}>데이터셋</span>
        {PRESETS.map((p) => (
          <button key={p.table} className={table === p.table && loaded ? "btn" : "ghost"}
            style={{ fontSize: 12, padding: "5px 12px" }} onClick={() => selectPreset(p)} disabled={busy}>{p.label}</button>
        ))}
        {loaded && <span className="pill ok" style={{ marginLeft: 4 }}>로드됨</span>}
      </div>

      <details style={{ marginTop: 8 }}>
        <summary className="muted" style={{ fontSize: 12, cursor: "pointer" }}>고급 — 직접 경로 / 파일 업로드</summary>
        <div className="row" style={{ marginTop: 8 }}>
          <input type="text" value={table} onChange={(e) => { setTable(e.target.value); setLoaded(false); }} style={{ width: 120 }} placeholder="table" />
          <input className="grow" type="text" value={path} onChange={(e) => { setPath(e.target.value); setLoaded(false); }} placeholder="CSV/XLSX 경로" />
          <button className="btn" onClick={load} disabled={busy}>경로로 로드</button>
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          <input type="file" accept=".csv,.xlsx,.tsv" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          <button className="ghost" onClick={upload} disabled={busy}>파일 업로드로 등록</button>
          <button className="ghost" onClick={downloadSample}>샘플 CSV 받기</button>
        </div>
      </details>

      <div className="example">
        <div className="label" style={{ marginTop: 12 }}>업로드 형식 예시</div>
        <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>
          1행 = 헤더(컬럼명), 2행부터 데이터. 공백/특수문자 컬럼은 자동 정규화돼요.
        </div>
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead><tr><th>model</th><th>year</th><th>component</th><th>count</th></tr></thead>
            <tbody>
              <tr><td>PALISADE</td><td>2022</td><td>BRAKE</td><td>3</td></tr>
              <tr><td>SANTA FE</td><td>2021</td><td>FUEL</td><td>2</td></tr>
              <tr><td className="muted">…</td><td className="muted">…</td><td className="muted">…</td><td className="muted">…</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      {schema && (
        <>
          <div className="label">Schema | {table}</div>
          <div className="row" style={{ gap: 6 }}>
            {schema.map((c) => (
              <span className="badge" key={c.name}>{c.name}<span className="muted"> {c.type}</span></span>
            ))}
          </div>
        </>
      )}

      <div className="row" style={{ marginTop: 14 }}>
        <input className="grow" type="text" value={question} onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && ask()} placeholder="자연어 질문" />
        <button className="btn" onClick={() => ask()} disabled={busy}>{busy ? "생성 중…" : "질문"}</button>
      </div>
      <div className="row" style={{ gap: 6, marginTop: 6, flexWrap: "wrap" }}>
        {examples.map((ex, i) => (
          <button key={i} className="ghost" style={{ fontSize: 12 }} onClick={() => { setQuestion(ex); ask(ex); }} disabled={busy}>{ex}</button>
        ))}
      </div>
      {err && <div className="err">{err}</div>}

      {!res && !err && !busy && (
        <div className="empty">
          <div className="empty-ic"><svg viewBox="0 0 24 24"><ellipse cx="12" cy="5" rx="8" ry="3" /><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5" /><path d="M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" /></svg></div>
          <div>표(CSV/엑셀)를 올리고 자연어로 물어보면 <b>SQL을 생성</b>해 집계합니다. 숫자 결과는 <b>막대 차트</b>로도 보여줍니다.</div>
        </div>
      )}

      {res && (
        <>
          {res.sql && (<><div className="label">이 결과의 근거 SQL <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>(숫자가 어떻게 나왔는지 검증)</span></div><pre className="sqlbox">{res.sql}</pre></>)}

          {chart && (
            <>
              <div className="label">차트</div>
              <div className="bars">
                {res.rows.map((r, i) => (
                  <div className="bar-row" key={i}>
                    <span className="bar-label">{String(r[0])}</span>
                    <span className="bar-track">
                      <span className={`bar-fill s${Math.min(i, 5)}`} style={{ width: `${(Number(r[1]) / maxVal) * 100}%` }} />
                    </span>
                    <span className="bar-val">{String(r[1])}</span>
                  </div>
                ))}
              </div>
            </>
          )}

          <div className="label">결과 ({res.rows.length}행)</div>
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr>{res.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
              <tbody>
                {res.rows.map((r, i) => (
                  <tr key={i}>{r.map((v, j) => <td key={j}>{v === null ? "–" : String(v)}</td>)}</tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
