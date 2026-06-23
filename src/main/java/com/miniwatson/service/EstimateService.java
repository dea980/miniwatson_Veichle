package com.miniwatson.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수리 견적서 — 증상/진단을 받아 필요한 부품을 고르고(parts 테이블), 부품비+공임을 합산한다.
 *
 * 설계: 부품 선택만 LLM(목록 중에서), 금액 계산은 전부 Java(결정적). LLM에 돈 계산을 맡기지 않는다.
 * 단가는 데모용 샘플(parts_pricing.csv). 실제 단가는 비공개.
 */
@Service
public class EstimateService {

    private static final Logger log = LoggerFactory.getLogger(EstimateService.class);

    private final TabularSqlService tabular;
    private final VehicleDataProperties data;
    private final OllamaService ollama;

    @Value("${vehicle.estimate.labor-rate-krw:50000}")
    private long laborRate;

    public EstimateService(TabularSqlService tabular, VehicleDataProperties data, OllamaService ollama) {
        this.tabular = tabular;
        this.data = data;
        this.ollama = ollama;
    }

    private record Part(String name, String component, long unitPrice, double laborHours) {}

    public Map<String, Object> estimate(String problem, String car, String model) throws Exception {
        ensureParts();
        List<Part> parts = loadParts();
        if (parts.isEmpty()) throw new RuntimeException("parts 테이블이 비어있음(vehicle.tables.parts 확인)");

        // 1) 부품 선택 — LLM이 목록 중에서 (계산 X, 선택만)
        List<Part> chosen = select(problem, parts, model);

        // 2) 금액 계산 — 전부 Java(결정적)
        List<Map<String, Object>> items = new ArrayList<>();
        long partsTotal = 0, laborTotal = 0;
        for (Part p : chosen) {
            long laborCost = Math.round(p.laborHours() * laborRate);
            long line = p.unitPrice() + laborCost;
            partsTotal += p.unitPrice();
            laborTotal += laborCost;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("part", p.name());
            it.put("component", p.component());
            it.put("unitPrice", p.unitPrice());
            it.put("laborHours", p.laborHours());
            it.put("laborCost", laborCost);
            it.put("lineTotal", line);
            items.add(it);
        }
        long grand = partsTotal + laborTotal;       // 공급가액(부품계+공임계, 부가세 전)
        long vat = Math.round(grand * 0.1);          // 부가세 10% (정비 견적서 표준)
        long total = grand + vat;                    // 최종 합계(부가세 포함)

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("car", car == null ? "" : car);
        out.put("problem", problem);
        out.put("laborRate", laborRate);
        out.put("items", items);
        out.put("partsTotal", partsTotal);
        out.put("laborTotal", laborTotal);
        out.put("grandTotal", grand);                // = 공급가액(하위호환)
        out.put("supplyAmount", grand);              // 공급가액
        out.put("vat", vat);                         // 부가세(10%)
        out.put("total", total);                     // 합계(부가세 포함)
        out.put("sample", true);                     // 샘플 단가 플래그 — 실제 청구액 아님

        // 3) 안내문 — 결정적(추가 LLM 호출 제거로 응답 단축, 케이스 상세는 자체 견적표 사용)
        out.put("note", "예상 합계 " + total + "원 (부품 " + partsTotal + " + 공임 " + laborTotal + " + 부가세 " + vat + "). 데모용 샘플 단가 기준.");
        return out;
    }

    /** 목록 중에서 LLM이 부품 선택. 실패/공백이면 키워드 폴백. */
    private List<Part> select(String problem, List<Part> parts, String model) {
        String names = parts.stream().map(Part::name).reduce((a, b) -> a + ", " + b).orElse("");
        String r = "";
        try {
            r = ollama.ask("증상: " + problem + "\n부품 목록: " + names
                    + "\n이 수리에 필요한 부품을 목록에서만 골라 콤마로 출력(최대 4개, 다른 말 금지):", model);
        } catch (Exception e) {
            log.warn("[estimate] 부품 선택 LLM 실패 — 키워드 폴백: {}", e.getMessage());
        }
        final String resp = r == null ? "" : r;
        List<Part> chosen = new ArrayList<>();
        for (Part p : parts) if (resp.contains(p.name())) chosen.add(p);
        if (chosen.isEmpty()) {  // 폴백: 증상 텍스트에 부품/컴포넌트 토큰이 있으면
            String q = problem == null ? "" : problem.toLowerCase();
            for (Part p : parts)
                if (q.contains(p.name().toLowerCase()) || q.contains(p.component().toLowerCase())) chosen.add(p);
        }
        return chosen.size() > 4 ? chosen.subList(0, 4) : chosen;
    }

    private List<Part> loadParts() {
        List<Part> out = new ArrayList<>();
        try {
            var res = tabular.runSelect("SELECT part, component, unit_price, labor_hours FROM parts");
            for (List<Object> row : res.rows()) {
                out.add(new Part(
                        String.valueOf(row.get(0)),
                        String.valueOf(row.get(1)),
                        (long) Double.parseDouble(String.valueOf(row.get(2))),
                        Double.parseDouble(String.valueOf(row.get(3)))));
            }
        } catch (Exception e) {
            log.warn("[estimate] parts 로드 실패: {}", e.getMessage());
        }
        return out;
    }

    private void ensureParts() {
        String path = data.getTables().get("parts");
        if (path == null) return;
        try { tabular.registerCsv("parts", path); } catch (Exception e) { log.warn("[estimate] parts 등록 실패: {}", e.getMessage()); }
    }
}
