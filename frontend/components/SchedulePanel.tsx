"use client";
import { useEffect, useMemo, useState } from "react";
import { api, type Maintenance } from "@/lib/api";

const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const WD = ["일", "월", "화", "수", "목", "금", "토"];
const statusColor = (s: string) => (s === "완료" ? "var(--ok)" : s === "진행" ? "var(--warn)" : "var(--accent-2)");
const STATUSES = ["예정", "진행", "완료"];

export default function SchedulePanel() {
  const [items, setItems] = useState<Maintenance[]>([]);
  const [carModels, setCarModels] = useState<string[]>([]);   // 차종 목록(불만 데이터 기준)
  const [view, setView] = useState(() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  // 추가 폼
  const [form, setForm] = useState({ model: "", title: "", scheduledDate: ymd(new Date()), technician: "", note: "" });

  async function load() {
    setErr("");
    try { setItems(await api.maintenanceList()); }
    catch (e) { setErr(String(e)); }
  }
  useEffect(() => {
    load();
    api.summary().then((s) => setCarModels((s.byModel || []).map((m) => String(m[0])))).catch(() => {});
  }, []);

  const byDate = useMemo(() => {
    const m: Record<string, Maintenance[]> = {};
    for (const it of items) (m[it.scheduledDate] ||= []).push(it);
    return m;
  }, [items]);

  // 월 그리드 셀 (선행 공백 + 날짜)
  const cells = useMemo(() => {
    const first = new Date(view.getFullYear(), view.getMonth(), 1);
    const days = new Date(view.getFullYear(), view.getMonth() + 1, 0).getDate();
    const lead = first.getDay();
    const arr: (Date | null)[] = [];
    for (let i = 0; i < lead; i++) arr.push(null);
    for (let d = 1; d <= days; d++) arr.push(new Date(view.getFullYear(), view.getMonth(), d));
    while (arr.length % 7 !== 0) arr.push(null);
    return arr;
  }, [view]);

  async function add() {
    if (!form.title.trim()) { setErr("정비 항목(제목)을 입력하세요"); return; }
    setBusy(true); setErr("");
    try {
      await api.maintenanceCreate(form);
      setForm((f) => ({ ...f, title: "", note: "" }));
      load();
    } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  }
  async function setStatus(it: Maintenance, value: string) { await api.maintenanceStatus(it.id, value); load(); }
  async function del(it: Maintenance) { if (confirm(`"${it.title}" 일정을 삭제할까요?`)) { await api.maintenanceDelete(it.id); load(); } }

  const todayStr = ymd(new Date());
  const upcoming = items.filter((it) => it.scheduledDate >= todayStr).slice(0, 12);

  return (
    <div style={{ display: "grid", gridTemplateColumns: "1.5fr 1fr", gap: 18, alignItems: "start" }} className="home-grid">
      {/* 좌측: 달력 */}
      <div className="card" style={{ margin: 0 }}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>{view.getFullYear()}년 {view.getMonth() + 1}월</h2>
          <div className="row" style={{ gap: 6 }}>
            <button className="ghost" onClick={() => setView(new Date(view.getFullYear(), view.getMonth() - 1, 1))}>‹ 이전</button>
            <button className="ghost" onClick={() => { const d = new Date(); setView(new Date(d.getFullYear(), d.getMonth(), 1)); }}>오늘</button>
            <button className="ghost" onClick={() => setView(new Date(view.getFullYear(), view.getMonth() + 1, 1))}>다음 ›</button>
          </div>
        </div>
        {err && <div className="err">{err}</div>}
        <div className="cal-grid cal-head">
          {WD.map((w, i) => <div key={w} className="cal-wd" style={{ color: i === 0 ? "var(--danger)" : i === 6 ? "var(--accent-2)" : "var(--muted)" }}>{w}</div>)}
        </div>
        <div className="cal-grid">
          {cells.map((d, i) => {
            if (!d) return <div key={i} className="cal-cell empty-cell" />;
            const key = ymd(d);
            const list = byDate[key] || [];
            const isToday = key === todayStr;
            return (
              <div key={i} className={`cal-cell ${isToday ? "today" : ""}`}
                onClick={() => setForm((f) => ({ ...f, scheduledDate: key }))} title="클릭하면 이 날짜로 추가 폼 설정">
                <div className="cal-date">{d.getDate()}</div>
                {list.slice(0, 3).map((it) => (
                  <div key={it.id} className="cal-chip" style={{ borderLeft: `3px solid ${statusColor(it.status)}` }} title={`${it.model} | ${it.title} (${it.status})`}>
                    {it.title}
                  </div>
                ))}
                {list.length > 3 && <div className="muted" style={{ fontSize: 10 }}>+{list.length - 3}</div>}
              </div>
            );
          })}
        </div>
        <div className="hint">날짜를 클릭하면 우측 추가 폼의 날짜가 설정됩니다. 칩 색: <span style={{ color: "var(--accent-2)" }}>예정</span> | <span style={{ color: "var(--warn)" }}>진행</span> | <span style={{ color: "var(--ok)" }}>완료</span></div>
      </div>

      {/* 우측: 추가 + 다가오는 일정 */}
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        <div className="card" style={{ margin: 0 }}>
          <h2>정비 일정 추가</h2>
          <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 8 }}>
            <select value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })}>
              <option value="">차종 선택(선택)</option>
              {carModels.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
            <input type="text" placeholder="정비 항목 (예: 안전벨트 프리텐셔너 점검)" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            <input type="date" value={form.scheduledDate} onChange={(e) => setForm({ ...form, scheduledDate: e.target.value })} />
            <input type="text" placeholder="담당자(선택)" value={form.technician} onChange={(e) => setForm({ ...form, technician: e.target.value })} />
            <input type="text" placeholder="메모(선택)" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
            <button className="btn" onClick={add} disabled={busy}>{busy ? "추가 중…" : "일정 추가"}</button>
          </div>
        </div>

        <div className="card" style={{ margin: 0 }}>
          <h2>다가오는 일정</h2>
          {upcoming.length === 0 && <div className="muted" style={{ marginTop: 8 }}>예정된 정비가 없습니다.</div>}
          {upcoming.map((it) => (
            <div className="doc" key={it.id} style={{ alignItems: "flex-start" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{it.title}</div>
                <div className="muted" style={{ fontSize: 12 }}>
                  {it.scheduledDate}{it.model ? ` | ${it.model}` : ""}{it.technician ? ` | ${it.technician}` : ""}{it.caseNumber ? ` | 접수#${it.caseNumber}` : ""}
                </div>
              </div>
              <select value={it.status} onChange={(e) => setStatus(it, e.target.value)} style={{ fontSize: 12, padding: "4px 6px" }}>
                {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
              <button className="ghost" style={{ fontSize: 12 }} onClick={() => del(it)}>삭제</button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
