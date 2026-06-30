"use client";
import { useState } from "react";

const num = (v: unknown) => Number(v) || 0;

// 기본 팔레트(네이비→블루). 의미색이 필요하면 colors prop으로 덮어쓴다(예: 점검 결과).
export const DONUT_COLORS = ["#002c5f", "#15467f", "#1f6feb", "#4d90e8", "#7eb2f0", "#a9ccf6", "#cbe0f7"];

/**
 * 도넛(구멍 뚫린 파이). 슬라이스 hover 시 가운데에 그 항목 상세(건수·%)가 뜨고 슬라이스가 강조된다.
 * 범례도 hover와 동기화. 순위 비교는 바가 낫지만 구성비 표현엔 도넛이 깔끔.
 */
export default function Donut({ rows, unit = "", colors = DONUT_COLORS }:
  { rows: [string, number][]; unit?: string; colors?: string[] }) {
  const [hi, setHi] = useState<number | null>(null);
  const data = rows.map((r) => [String(r[0]), num(r[1])] as [string, number]).filter((d) => d[1] > 0);
  const total = data.reduce((s, d) => s + d[1], 0) || 1;
  const R = 56, SW = 22, C = 2 * Math.PI * R;
  let acc = 0;
  const sel = hi != null ? data[hi] : null;
  return (
    <div className="donut-wrap" onMouseLeave={() => setHi(null)}>
      <svg viewBox="0 0 140 140" className="donut" role="img">
        <g transform="rotate(-90 70 70)">
          {data.map((d, i) => {
            const len = (d[1] / total) * C;
            const off = acc; acc += len;
            const dim = hi != null && hi !== i;
            return (
              <circle key={i} cx="70" cy="70" r={R} fill="none"
                stroke={colors[i % colors.length]}
                strokeWidth={hi === i ? SW + 6 : SW}
                strokeDasharray={`${Math.max(len - 0.6, 0)} ${C - len + 0.6}`} strokeDashoffset={-off}
                opacity={dim ? 0.35 : 1} style={{ cursor: "pointer", transition: "stroke-width .12s, opacity .12s" }}
                onMouseEnter={() => setHi(i)} />
            );
          })}
        </g>
        {sel ? (
          <>
            <text x="70" y="64" textAnchor="middle" className="donut-total">{sel[1]}{unit}</text>
            <text x="70" y="80" textAnchor="middle" className="donut-totlbl">{Math.round((sel[1] / total) * 100)}%</text>
          </>
        ) : (
          <>
            <text x="70" y="64" textAnchor="middle" className="donut-total">{total}</text>
            <text x="70" y="80" textAnchor="middle" className="donut-totlbl">합계{unit}</text>
          </>
        )}
      </svg>
      <div className="donut-legend">
        {data.map((d, i) => (
          <div className={`donut-leg ${hi === i ? "on" : ""}`} key={i} onMouseEnter={() => setHi(i)}>
            <span className="donut-sw" style={{ background: colors[i % colors.length] }} />
            <span className="donut-lbl" title={d[0]}>{d[0]}</span>
            <span className="donut-pct">{d[1]}{unit} · {Math.round((d[1] / total) * 100)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}
