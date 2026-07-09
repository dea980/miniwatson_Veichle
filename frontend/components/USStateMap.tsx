"use client"
import { useMemo, useState } from "react";

import USA from "@svg-maps/usa";

type Row = [string, number, number, number, number, number, string];
export default function USStateMap({ data }: { data: Row[] }) {
    const [hover, setHover] = useState<{code: string; x:number; y: number }| null>(null);

    const byState = useMemo(() => {
        const m = new Map<string, Row>();
        for (const r of data) m.set(String(r[0]).toUpperCase(), r);
        return m;
    }, [data]);

    const max = useMemo(() => Math.max(1, ...data.map((r) => Number(r[1]) || 0)), [data]);

    const color = (v: number) => {
        if (!v) return "var(--surface-2)";
        const t = Math.pow(v / max, 0.6);
        const lerp = (a: number, b: number) => Math.round(a + (b - a) * t);
        return 'rgb(${lerp(0xe6, 0x00)}, ${lerp(0xed, 0x2c)},${lerp(0xf5, 0x5f)})';
    };

    const norm = (id: string) => id.replace(/[^a-z]/gi, "").slice(-2).toUpperCase();
    const hv = hover ? byState.get(hover.code) : null;

    return (
        <div style={{ position: "relative" }}>
            <svg viewBox={USA.viewBox} className="usmap" role="img" aria-label="주별 불만 지도">
                {USA.locations.map((loc: any) => {
                    const code = norm(loc.id);
                    const v = byState.get(code) ? Number(byState.get(code)![1]) :0;
                    return (
                        <path key={loc.id} d={loc.path} fill={color(v)}
                        stroke="var(--border)" strokeWidth={0.8}
                        onMouseMove={(e) => setHover({ code, x: e.clientX, y: e.clientY })}
                        onMouseLeave={() => setHover(null)}
                        style={{ cursor: "pointer", transition: "fill .15s"}} />
                        );
                    })}
            </svg>
            {hv && (
                    <div className="usmap-tip" style={{ position: "fixed", left: hover!.x + 12, top: hover!.y + 12 }}>
                      <div style={{ fontWeight: 700, marginBottom: 3 }}>{hv[0]}</div>
                      <div>불만 {hv[1]} · 사망 {hv[4]} · 부상 {hv[3]}</div>
                      <div>화재 {hv[2]} · 사고 {hv[5]}</div>
                      {hv[6] && <div style={{ marginTop: 3, opacity: .8 }}>주요 부위: {String(hv[6]).slice(0, 28)}</div>}
                    </div>
                  )}
        </div>
    );
}