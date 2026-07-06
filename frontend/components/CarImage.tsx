"use client";
import { useEffect, useState } from "react";

// 차종 사진은 위키백과 REST 요약 API에서(브라우저는 CORS 허용). URL 하드코딩 없음.
// 실패하면 차 실루엣 SVG로 폴백 → 항상 무언가는 보인다.
const cache: Record<string, string | null> = {};

function wikiTitle(model: string): string {
  const raw = (model || "").trim().toUpperCase().replace(/[_-]+/g, " ").replace(/\s+/g, " ").trim();
  const map: Record<string, string> = {
    "SANTA FE": "Hyundai_Santa_Fe", "SANTAFE": "Hyundai_Santa_Fe",
    "SANTA CRUZ": "Hyundai_Santa_Cruz", "SANTACRUZ": "Hyundai_Santa_Cruz",
    // 변형(N)은 전용 위키 문서에 썸네일이 없어 베이스 문서로 보낸다.
    "VELOSTER": "Hyundai_Veloster", "VELOSTER N": "Hyundai_Veloster", "VELOSTERN": "Hyundai_Veloster",
    "GENESIS COUPE": "Hyundai_Genesis_Coupe", "GENESISCOUPE": "Hyundai_Genesis_Coupe",
    "ELANTRA GT": "Hyundai_Elantra_GT", "ELANTRAGT": "Hyundai_Elantra_GT",
    "GENESIS": "Hyundai_Genesis", "AZERA": "Hyundai_Azera",
    "EQUUS": "Hyundai_Equus", "ENTOURAGE": "Hyundai_Entourage", "VENUE": "Hyundai_Venue",
  };
  if (map[raw]) return map[raw];
  // 변형 접미사(하이브리드·전기·수소·N 등) 제거 → 베이스 모델의 안정적 위키 문서로.
  //   "SANTA FE HYBRID"→SANTA FE, "IONIQ 5 N"→IONIQ 5, "KONA ELECTRIC"→KONA. (숫자 모델명 IONIQ 5 는 보존)
  const base = raw.replace(/\s+(N LINE|HYBRID|PLUG IN HYBRID|PHEV|HEV|ELECTRIC|EV|FCEV|FUEL CELL|LONG|N)$/g, "").trim();
  const key = map[base] ? base : (base || raw);
  if (map[key]) return map[key];
  // 폴백: 각 단어 Title-case 후 '_' 결합 (SANTA CRUZ→Hyundai_Santa_Cruz, IONIQ 5→Hyundai_Ioniq_5)
  return "Hyundai_" + key.toLowerCase().split(/\s+/)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("_");
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
