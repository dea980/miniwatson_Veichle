"use client";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

/**
 * 커스텀 드롭다운 — 네이티브 <select>의 옵션 팝업은 OS가 그려 스타일이 안 먹는다(특히 macOS).
 * 목록은 **portal로 <body>에 렌더 + position:fixed** 로 띄운다 → 카드의 transform(stacking context)·
 * overflow에 갇히지 않아 footer/다른 요소 위로 항상 올라온다. value/onChange는 <select>와 동일 계약.
 * renderLabel로 표시만 바꿀 수 있다(예: 차종 국내명). 접근성: 바깥클릭·Esc 닫기, ↑↓ 이동, Enter 선택, 스크롤 시 닫힘.
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
  const [hi, setHi] = useState(-1);
  const [mounted, setMounted] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const label = (v: string) => (renderLabel ? renderLabel(v) : v);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open) return;
    const r = btnRef.current?.getBoundingClientRect();
    if (r) setPos({ top: r.bottom + 4, left: r.left, width: r.width });
    setHi(Math.max(0, options.indexOf(value)));
    const onDoc = (e: MouseEvent) => {
      const t = e.target as Node;
      if (btnRef.current?.contains(t) || menuRef.current?.contains(t)) return;
      setOpen(false);
    };
    // 페이지 스크롤 시 닫기(fixed라 따라가지 않으므로). 단, 메뉴 내부 스크롤은 유지.
    const onScroll = (e: Event) => {
      if (menuRef.current && menuRef.current.contains(e.target as Node)) return;
      setOpen(false);
    };
    const onResize = () => setOpen(false);
    document.addEventListener("mousedown", onDoc);
    window.addEventListener("scroll", onScroll, true);
    window.addEventListener("resize", onResize);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      window.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("resize", onResize);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function choose(v: string) { onChange(v); setOpen(false); }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === "Escape") { setOpen(false); return; }
    if (!open && (e.key === "Enter" || e.key === "ArrowDown" || e.key === " ")) { e.preventDefault(); setOpen(true); return; }
    if (!open) return;
    if (e.key === "ArrowDown") { e.preventDefault(); setHi((i) => Math.min(options.length - 1, i + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setHi((i) => Math.max(0, i - 1)); }
    else if (e.key === "Enter") { e.preventDefault(); if (options[hi] != null) choose(options[hi]); }
  }

  const menu = open && pos && mounted
    ? createPortal(
        <div className="sel-menu" role="listbox" ref={menuRef}
          style={{ position: "fixed", top: pos.top, left: pos.left, minWidth: pos.width }}>
          {options.map((o, i) => (
            <div key={o} role="option" aria-selected={o === value}
              className={`sel-opt${o === value ? " on" : ""}${i === hi ? " hi" : ""}`}
              onMouseEnter={() => setHi(i)} onClick={() => choose(o)}>
              {label(o)}
            </div>
          ))}
        </div>, (typeof document !== "undefined" && document.querySelector(".shell")) || document.body)
    : null;

  return (
    <div className={`sel ${className}`} style={style}>
      <button ref={btnRef} type="button" className="sel-btn" title={title} aria-haspopup="listbox" aria-expanded={open}
        onClick={() => setOpen((o) => !o)} onKeyDown={onKey}>
        <span className="sel-val">{value ? label(value) : placeholder}</span>
        <svg className="sel-caret" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 4.5l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
      {menu}
    </div>
  );
}
