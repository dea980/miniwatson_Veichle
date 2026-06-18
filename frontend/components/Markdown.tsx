"use client";
import React from "react";

// 의존성 없는 초경량 마크다운 렌더러 — ## 헤더, ### 소제목, - 불릿, **굵게**, 문단.
function inline(text: string): React.ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*)/g);
  return parts.map((p, i) =>
    p.startsWith("**") && p.endsWith("**") ? <strong key={i}>{p.slice(2, -2)}</strong> : <span key={i}>{p}</span>
  );
}

export default function Markdown({ text }: { text: string }) {
  const lines = (text || "").split("\n");
  const out: React.ReactNode[] = [];
  let bullets: string[] = [];
  const flush = (key: number) => {
    if (bullets.length) {
      out.push(<ul key={"ul" + key}>{bullets.map((b, i) => <li key={i}>{inline(b)}</li>)}</ul>);
      bullets = [];
    }
  };
  lines.forEach((raw, i) => {
    const l = raw.trimEnd();
    if (l.startsWith("## ")) { flush(i); out.push(<h2 key={i}>{inline(l.slice(3))}</h2>); }
    else if (l.startsWith("### ")) { flush(i); out.push(<h3 key={i}>{inline(l.slice(4))}</h3>); }
    else if (/^\s*[-*]\s+/.test(l)) { bullets.push(l.replace(/^\s*[-*]\s+/, "")); }
    else if (l.trim() === "") { flush(i); }
    else { flush(i); out.push(<p key={i}>{inline(l)}</p>); }
  });
  flush(lines.length);
  return <div className="md">{out}</div>;
}
