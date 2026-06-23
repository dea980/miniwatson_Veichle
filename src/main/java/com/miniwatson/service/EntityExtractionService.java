package com.miniwatson.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 비정형 텍스트 → 엔티티 추출 → 정규화 → 경량 온톨로지 구조화.
 *
 * JD(비정형 데이터 AI): "엔티티 추출·정규화·온톨로지 기반 구조화 + LLM 구조화 출력".
 *  ① 추출: LLM이 고정 스키마(JSON)로 엔티티를 뽑는다(구조화 출력).
 *  ② 정규화: 영문 부위·약어를 표준 한국어로 통일.
 *  ③ 구조화: 엔티티 간 관계(증상↔부품↔부위↔차종)를 타입 엣지로 — GRAPHRAG_VEHICLE의 경량 공출현 그래프를 구조화에 재활용.
 *
 * 검색용 GraphRAG(미구현)와 다름: 여기는 *구조화*(엔티티/관계 추출)지 검색 순회가 아니다.
 */
@Service
public class EntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final OllamaService ollama;

    public EntityExtractionService(OllamaService ollama) {
        this.ollama = ollama;
    }

    // 영문 부위/약어 → 표준 한국어 (정규화)
    private static final LinkedHashMap<String, String> NORM = new LinkedHashMap<>();
    static {
        NORM.put("AIR BAG", "에어백"); NORM.put("AIRBAG", "에어백");
        NORM.put("SEAT BELT", "안전벨트"); NORM.put("SEATBELT", "안전벨트");
        NORM.put("BRAKE", "제동장치"); NORM.put("ENGINE", "원동기");
        NORM.put("POWER TRAIN", "동력전달"); NORM.put("TRANSMISSION", "변속기");
        NORM.put("ELECTRICAL", "전기장치"); NORM.put("STEERING", "조향장치");
        NORM.put("FUEL", "연료장치"); NORM.put("SUSPENSION", "현가장치");
        NORM.put("SRS", "에어백(SRS)"); NORM.put("ABS", "제동(ABS)"); NORM.put("TPMS", "타이어공기압(TPMS)");
    }

    private static final List<String> TYPES = List.of("차종", "부품", "증상", "DTC", "부위");

    /** 텍스트 → {entities, relations}. entities: 타입별 정규화된 값, relations: [from, label, to]. */
    public Map<String, Object> extract(String text, String model) {
        Map<String, List<String>> ents = llmExtract(text, model);

        // ② 정규화: 부위·약어 표준화 + 중복 제거
        for (String t : TYPES) {
            List<String> vals = ents.getOrDefault(t, List.of());
            LinkedHashSet<String> norm = new LinkedHashSet<>();
            for (String v : vals) norm.add(normalize(v));
            ents.put(t, new ArrayList<>(norm));
        }

        // ③ 관계(경량 온톨로지) — 동일 텍스트 내 공출현 타입 엣지
        List<List<String>> rel = new ArrayList<>();
        link(rel, ents.get("증상"), "→발생", ents.get("부품"));
        link(rel, ents.get("부품"), "→위치", ents.get("부위"));
        link(rel, ents.get("차종"), "→보유", ents.get("부품"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entities", ents);
        out.put("relations", rel);
        out.put("ontology", "차량 ⊃ 시스템 ⊃ 부품; 증상↔부품↔부위 관계");
        return out;
    }

    /** ① LLM 구조화 추출 — 고정 JSON 스키마. */
    private Map<String, List<String>> llmExtract(String text, String model) {
        String prompt = "다음 자동차 정비/불만 텍스트에서 엔티티를 추출해 **JSON으로만** 출력하세요(설명·코드블록 금지).\n"
                + "스키마(없으면 빈 배열): {\"차종\":[],\"부품\":[],\"증상\":[],\"DTC\":[],\"부위\":[]}\n"
                + "텍스트:\n" + (text == null ? "" : text) + "\nJSON:";
        String raw;
        try { raw = ollama.ask(prompt, model); }
        catch (Exception e) { log.warn("[entity] LLM 추출 실패: {}", e.getMessage()); return blank(); }
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parse(String raw) {
        Map<String, List<String>> out = blank();
        if (raw == null) return out;
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        if (s < 0 || e <= s) return out;
        try {
            Map<String, Object> m = OM.readValue(raw.substring(s, e + 1), Map.class);
            for (String t : TYPES) {
                Object v = m.get(t);
                if (v instanceof List<?> l) {
                    List<String> vals = new ArrayList<>();
                    for (Object o : l) {
                        String str = String.valueOf(o).trim();
                        if (!str.isEmpty() && !vals.contains(str)) vals.add(str);
                    }
                    out.put(t, vals);
                }
            }
        } catch (Exception ex) { log.warn("[entity] JSON 파싱 실패: {}", ex.getMessage()); }
        return out;
    }

    private String normalize(String v) {
        String up = v.toUpperCase();
        for (var en : NORM.entrySet()) if (up.contains(en.getKey())) return en.getValue();
        return v.trim();
    }

    private void link(List<List<String>> rel, List<String> from, String label, List<String> to) {
        if (from == null || to == null) return;
        for (String a : from) for (String b : to) if (!a.equals(b)) rel.add(List.of(a, label, b));
    }

    private Map<String, List<String>> blank() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String t : TYPES) m.put(t, new ArrayList<>());
        return m;
    }
}
