"use client";
import { useEffect, useState } from "react";

// 차종 사진은 위키백과 REST 요약 API에서(브라우저는 CORS 허용). URL 하드코딩 없음.
// 실패하면 차 실루엣 SVG로 폴백 → 항상 무언가는 보인다.
const cache: Record<string, string | null> = {};

function wikiTitle(model: string): string {
  const m = (model || "").trim().toUpperCase();
  const map: Record<string, string> = {
    "SANTA FE": "Hyundai_Santa_Fe", "SANTAFE": "Hyundai_Santa_Fe",
    "SANTA CRUZ": "Hyundai_Santa_Cruz", "SANTACRUZ": "Hyundai_Santa_Cruz", "SANTA-CRUZ": "Hyundai_Santa_Cruz",
    "GENESIS COUPE": "Hyundai_Genesis_Coupe", "GENESISCOUPE": "Hyundai_Genesis_Coupe",
    "ELANTRA GT": "Hyundai_Elantra_GT", "ELANTRAGT": "Hyundai_Elantra_GT",
    "SONATA HYBRID": "Hyundai_Sonata", "SONATAHYBRID": "Hyundai_Sonata",
    "GENESIS": "Hyundai_Genesis", "AZERA": "Hyundai_Azera",
    "EQUUS": "Hyundai_Equus", "ENTOURAGE": "Hyundai_Entourage", "VENUE": "Hyundai_Venue",
  };
  if (map[m]) return map[m];
  return "Hyundai_" + m.charAt(0) + m.slice(1).toLowerCase();
}

export default function CarImage({ model, height = 120, rounded = true }: { model: string; height?: number; rounded?: boolean }) {
  const [src, setSrc] = useState<string | null | undefined>(model in cache ? cache[model] : undefined);

  useEffect(() => {
    if (model in cache) { setSrc(cache[model]); return; }
    let alive = true;
    fetch(`https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(wikiTitle(model))}`, { headers: { Accept: "application/json" } })
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => { const u = d?.thumbnail?.source || d?.originalimage?.source || null; cache[model] = u; if (alive) setSrc(u); })
      .catch(() => { cache[model] = null; if (alive) setSrc(null); });
    return () => { alive = false; };
  }, [model]);

  const style: React.CSSProperties = { height, borderRadius: rounded ? 10 : 0 };

  if (src === undefined) return <div className="car-img skel" style={style} />;
  if (!src)
    return (
      <div className="car-img placeholder" style={style} title={model}>
        <svg viewBox="0 0 64 26" aria-hidden="true">
          <path d="M3 18h2c0-3 2.5-5 5.5-5S16 15 16 18h20c0-3 2.5-5 5.5-5S47 15 47 18h4c2 0 3-1 3-3l-1-4c-.3-1.2-1-2-2.4-2.3l-9-1.6-5-4C28.6 5 26 4 23 4H10C7 4 5 6 4.4 8.5L3 14c-.4 1.5.2 4 0 4z" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
          <circle cx="10.5" cy="18.5" r="3.2" fill="none" stroke="currentColor" strokeWidth="1.6" />
          <circle cx="41.5" cy="18.5" r="3.2" fill="none" stroke="currentColor" strokeWidth="1.6" />
        </svg>
        <span>{model}</span>
      </div>
    );
  return <img className="car-img" src={src} alt={`${model} 차량 사진`} style={{ ...style, objectFit: "cover", width: "100%" }} loading="lazy" />;
}
