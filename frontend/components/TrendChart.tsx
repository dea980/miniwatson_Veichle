"use client";
import { useMemo, useRef, useState } from "react";

const num = (v: unknown) => Number(v) || 0;

export type Series = { name: string; color: string; data: [string, number][] };

/**
 * 시계열 꺾은선(면적) 차트. 여러 시리즈(리콜·불만)를 한 축에 겹쳐 그린다.
 * 시간축 데이터는 막대 나열보다 선이 추세를 훨씬 잘 보여준다.
 * hover 시 세로 가이드 + 각 시리즈 점 + 그 시점 값 툴팁.
 */
export default function TrendChart({ series, unit = "" }: { series: Series[]; unit?: string }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [hi, setHi] = useState<number | null>(null);

  // x축 = 모든 시리즈 라벨의 합집합(정렬). 각 시리즈를 라벨→값 맵으로 정렬해 결측은 0.
  const { labels, points, maxY } = useMemo(() => {
    const set = new Set<string>();
    for (const s of series) for (const [k] of s.data) set.add(k);
    const labels = [...set].sort();
    const points = series.map((s) => {
      const m = new Map(s.data.map(([k, v]) => [k, num(v)]));
      return labels.map((k) => m.get(k) ?? 0);
    });
    const maxY = Math.max(1, ...points.flat());
    return { labels, points, maxY };
  }, [series]);

  const n = labels.length;
  const W = 720, H = 240, padL = 40, padR = 14, padT = 14, padB = 28;
  const plotW = W - padL - padR, plotH = H - padT - padB, baseY = padT + plotH;
  const x = (i: number) => (n <= 1 ? padL + plotW / 2 : padL + (i / (n - 1)) * plotW);
  const y = (v: number) => padT + plotH * (1 - v / maxY);

  // y 눈금 4단계
  const ticks = 4;
  const yTicks = Array.from({ length: ticks + 1 }, (_, i) => Math.round((maxY * i) / ticks));
  // x 라벨은 겹치지 않게 최대 8개만 (균등 간격)
  const step = Math.max(1, Math.ceil(n / 8));

  function onMove(e: React.MouseEvent) {
    const r = wrapRef.current?.getBoundingClientRect();
    if (!r || n === 0) return;
    const vx = ((e.clientX - r.left) / r.width) * W;   // viewBox 좌표로 환산
    const i = Math.round(((vx - padL) / plotW) * (n - 1));
    setHi(Math.max(0, Math.min(n - 1, i)));
  }

  if (n === 0) return <div className="muted" style={{ fontSize: 13 }}>추세 데이터 없음</div>;

  const tipLeft = hi != null ? (x(hi) / W) * 100 : 0;
  const tipRight = tipLeft > 60;   // 오른쪽 끝이면 툴팁을 왼쪽으로

  return (
    <div className="trend-wrap" ref={wrapRef} onMouseMove={onMove} onMouseLeave={() => setHi(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} className="trend-svg" role="img" preserveAspectRatio="none">
        {/* y 그리드 + 눈금 */}
        {yTicks.map((v, i) => (
          <g key={i}>
            <line x1={padL} y1={y(v)} x2={W - padR} y2={y(v)} stroke="var(--border)" strokeWidth="1" />
            <text x={padL - 6} y={y(v) + 3} textAnchor="end" className="trend-axis">{v}</text>
          </g>
        ))}
        {/* x 라벨 */}
        {labels.map((lb, i) => (i % step === 0 || i === n - 1) && (
          <text key={i} x={x(i)} y={H - 9} textAnchor="middle" className="trend-axis">{lb}</text>
        ))}
        {/* 시리즈: 면적 + 선 */}
        {series.map((s, si) => {
          const pts = points[si];
          const line = pts.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
          const area = `${line} L${x(n - 1).toFixed(1)},${baseY} L${x(0).toFixed(1)},${baseY} Z`;
          return (
            <g key={si}>
              <path d={area} fill={s.color} opacity={0.1} />
              <path d={line} fill="none" stroke={s.color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
            </g>
          );
        })}
        {/* hover 가이드 + 점 */}
        {hi != null && (
          <g>
            <line x1={x(hi)} y1={padT} x2={x(hi)} y2={baseY} stroke="var(--accent-2)" strokeWidth="1" strokeDasharray="3 3" />
            {series.map((s, si) => (
              <circle key={si} cx={x(hi)} cy={y(points[si][hi])} r="3.5" fill="var(--card)" stroke={s.color} strokeWidth="2" />
            ))}
          </g>
        )}
      </svg>

      {/* 범례 */}
      <div className="trend-legend">
        {series.map((s, si) => (
          <span className="trend-leg" key={si}>
            <span className="trend-sw" style={{ background: s.color }} />{s.name}
          </span>
        ))}
      </div>

      {/* 툴팁 */}
      {hi != null && (
        <div className="trend-tip" style={{ left: `${tipLeft}%`, transform: `translateX(${tipRight ? "-100%" : "0"})` }}>
          <div className="trend-tip-lb">{labels[hi]}</div>
          {series.map((s, si) => (
            <div className="trend-tip-row" key={si}>
              <span className="trend-sw" style={{ background: s.color }} />{s.name} <b>{points[si][hi]}{unit}</b>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
