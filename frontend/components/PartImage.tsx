"use client";
import { useEffect, useState } from "react";

// 부품 이미지: 위키백과 REST 요약 API(브라우저 CORS 허용). 부품명(한국어)→영문 위키 타이틀 매핑.
// 실패 시 렌치 아이콘 폴백. 브랜드 부품 사진은 비공개라 *부품 유형* 일반 이미지로.
const cache: Record<string, string | null> = {};

const MAP: [RegExp, string][] = [
  [/브레이크\s*패드/, "Brake_pad"],
  [/브레이크\s*디스크|디스크/, "Disc_brake"],
  [/산소\s*센서/, "Oxygen_sensor"],
  [/촉매/, "Catalytic_converter"],
  [/안전벨트|프리텐셔너/, "Seat_belt"],
  [/앞유리|윈드실드/, "Windshield"],
  [/후방\s*카메라/, "Backup_camera"],
  [/엔진오일|오일.*필터|오일/, "Motor_oil"],
  [/배터리/, "Automotive_battery"],
  [/TPMS|타이어.*압/, "Tire-pressure_monitoring_system"],
  [/에어백/, "Airbag"],
  [/타이어/, "Tire"],
  [/와이퍼/, "Windscreen_wiper"],
  [/점화\s*플러그|스파크/, "Spark_plug"],
  [/연료\s*펌프/, "Fuel_pump"],
  [/등속\s*조인트|조인트/, "Constant-velocity_joint"],
  [/쇼크\s*업소버|쇼바|쇽업소버/, "Shock_absorber"],
  [/라디에이터/, "Radiator_(engine_cooling)"],
  [/타이밍\s*벨트/, "Timing_belt_(camshaft)"],
  [/디머|헤드램프|전조등/, "Headlamp"],
  [/변속기|미션/, "Automatic_transmission"],
];

function wikiTitle(part: string): string {
  for (const [re, t] of MAP) if (re.test(part)) return t;
  return "Automobile_repair";   // 매핑 없으면 일반 정비 이미지
}

export default function PartImage({ part, height = 84 }: { part: string; height?: number }) {
  const [src, setSrc] = useState<string | null | undefined>(part in cache ? cache[part] : undefined);

  useEffect(() => {
    if (part in cache) { setSrc(cache[part]); return; }
    let alive = true;
    fetch(`https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(wikiTitle(part))}`, { headers: { Accept: "application/json" } })
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => { const u = d?.thumbnail?.source || null; cache[part] = u; if (alive) setSrc(u); })
      .catch(() => { cache[part] = null; if (alive) setSrc(null); });
    return () => { alive = false; };
  }, [part]);

  const style: React.CSSProperties = { height, width: height, borderRadius: 8, flexShrink: 0 };
  if (src === undefined) return <div className="car-img skel" style={style} />;
  if (!src)
    return (
      <div className="car-img placeholder" style={style} title={part}>
        <svg viewBox="0 0 24 24" style={{ width: 28, height: 28 }}><path d="M14.7 6.3a4 4 0 0 0-5 5L3 18l3 3 6.7-6.7a4 4 0 0 0 5-5l-2.3 2.3-2-2 2.3-2.3z" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" /></svg>
      </div>
    );
  return <img className="car-img" src={src} alt={part} style={{ ...style, objectFit: "cover" }} loading="lazy" />;
}
