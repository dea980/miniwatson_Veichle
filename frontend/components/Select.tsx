"use client";
import { useEffect, useRef, useState } from "react";

/**
 * 커스텀 드롭다운 — 네이티브 <select>의 옵션 팝업은 OS가 그려 스타일이 안 먹는다(특히 macOS).
 * 그래서 버튼 + 절대위치 목록으로 직접 구현해 테마와 100% 일치시킨다.
 * value/onChange는 <select>와 동일 계약. renderLabel로 표시만 바꿀 수 있다(예: 차종 국내명).
 * 접근성: 바깥클릭·Esc 닫기, ↑↓ 이동, Enter 선택.
 */
export default function Select({
  value, onChange, options, renderLabel, title, className = "", style, placeholder = "선택",
}: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
  renderLabel?: (v: string) => React.ReactNode;
  title?: string;
  className?: string;
  style?: React.CSSProperties;
  placeholder?: string;
}) {
  const [open, setOpen] = useState(false);
  const [hi, setHi] = useState(-1);          // 키보드 하이라이트 인덱스
  const ref = useRef<HTMLDivElement>(null);
  const label = (v: string) => (renderLabel ? renderLabel(v) : v);

  // 바깥 클릭 시 닫기
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  // 열릴 때 현재 값에 하이라이트 맞춤
  useEffect(() => { if (open) setHi(Math.max(0, options.indexOf(value))); /* eslint-disable-next-line */ }, [open]);

  function choose(v: string) { onChange(v); setOpen(false); }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === "Escape") { setOpen(false); return; }
    if (!open && (e.key === "Enter" || e.key === "ArrowDown" || e.key === " ")) { e.preventDefault(); setOpen(true); return; }
    if (!open) return;
    if (e.key === "ArrowDown") { e.preventDefault(); setHi((i) => Math.min(options.length - 1, i + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setHi((i) => Math.max(0, i - 1)); }
    else if (e.key === "Enter") { e.preventDefault(); if (options[hi] != null) choose(options[hi]); }
  }

  return (
    <div className={`sel ${className}`} ref={ref} style={style}>
      <button type="button" className="sel-btn" title={title} aria-haspopup="listbox" aria-expanded={open}
        onClick={() => setOpen((o) => !o)} onKeyDown={onKey}>
        <span className="sel-val">{value ? label(value) : placeholder}</span>
        <svg className="sel-caret" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 4.5l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
      {open && (
        <div className="sel-menu" role="listbox">
          {options.map((o, i) => (
            <div key={o} role="option" aria-selected={o === value}
              className={`sel-opt${o === value ? " on" : ""}${i === hi ? " hi" : ""}`}
              onMouseEnter={() => setHi(i)} onClick={() => choose(o)}>
              {label(o)}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
