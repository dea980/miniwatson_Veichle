package com.miniwatson.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차종 종합 진단서 — 한 차종에 대해 여러 도구를 모아 한 장의 리포트로.
 *   리콜(SQL) + 불만·결함(SQL) + 매뉴얼 안전수칙(RAG) → LLM이 섹션 구조로 종합.
 *
 * 신뢰성: 리포트의 집계 SQL은 LLM 생성이 아니라 고정(결정적) 쿼리를 쓴다(text-to-SQL 변동성 회피).
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final TabularSqlService tabular;
    private final RagService ragService;
    private final OllamaService ollama;
    private final VehicleDataProperties data;

    public ReportService(TabularSqlService tabular, RagService ragService,
                         OllamaService ollama, VehicleDataProperties data) {
        this.tabular = tabular;
        this.ragService = ragService;
        this.ollama = ollama;
        this.data = data;
    }

    public Map<String, Object> generate(String carModel, String namespace, String llmModel) throws Exception {
        String car = (carModel == null ? "" : carModel.trim());
        String ns = (namespace == null || namespace.isBlank()) ? "vehicle" : namespace;
        ensure("recalls"); ensure("complaints"); ensure("inspection");
        String esc = car.replace("'", "''").toUpperCase();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("car", car);

        // ── 주요장치 점검표(성능·상태점검기록부 양식, 샘플) ──
        out.put("inspection", rows("SELECT category, item, result, code FROM inspection"));

        // ── 리콜(SQL, 결정적) ──
        long recallTotal = scalar("SELECT COUNT(*) FROM recalls WHERE upper(model)='" + esc + "'");
        List<List<Object>> recallTop = rows(
            "SELECT component, COUNT(*) n FROM recalls WHERE upper(model)='" + esc + "' GROUP BY component ORDER BY n DESC LIMIT 5");
        out.put("recallTotal", recallTotal);
        out.put("recallTopComponents", recallTop);

        // ── 불만·결함(SQL, 결정적) ──
        long complaintTotal = scalar("SELECT COUNT(*) FROM complaints WHERE upper(model)='" + esc + "'");
        List<List<Object>> complaintTop = rows(
            "SELECT components, COUNT(*) n FROM complaints WHERE upper(model)='" + esc + "' GROUP BY components ORDER BY n DESC LIMIT 5");
        long fires = scalar("SELECT COUNT(*) FROM complaints WHERE upper(model)='" + esc
            + "' AND lower(cast(fire as varchar)) IN ('true','1','yes','y')");
        long injuries = scalar("SELECT COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) FROM complaints WHERE upper(model)='" + esc + "'");
        out.put("complaintTotal", complaintTotal);
        out.put("complaintTopComponents", complaintTop);
        out.put("fires", fires);
        out.put("injuries", injuries);

        // ── 매뉴얼 안전수칙(RAG) ──
        String manualNotes = "";
        List<String> sources = new ArrayList<>();
        try {
            var rag = ragService.ask(car + " 안전 및 정비 시 주의사항", ns, llmModel);
            manualNotes = rag.answer();
            rag.sources().forEach(a -> sources.add(a.getTitle()));
        } catch (Exception e) {
            log.warn("[report] RAG 실패: {}", e.getMessage());
        }
        out.put("manualNotes", manualNotes);
        out.put("sources", sources);

        // ── LLM 종합(섹션 구조) ──
        // 점검표에서 '양호' 아닌 항목(정비필요/교환/판금 등) 요약
        @SuppressWarnings("unchecked")
        List<List<Object>> insp = (List<List<Object>>) out.get("inspection");
        StringBuilder issues = new StringBuilder();
        for (List<Object> r : insp) {
            String result = String.valueOf(r.get(2));
            if (!"양호".equals(result)) issues.append(r.get(0)).append("/").append(r.get(1)).append("(").append(result).append(") ");
        }

        String stats = "리콜 총건수: " + recallTotal + "\n리콜 주요부품(상위): " + recallTop
                + "\n불만 총건수: " + complaintTotal + "\n불만 주요부품(상위): " + complaintTop
                + "\n화재 신고: " + fires + " / 부상 합계: " + injuries
                + "\n점검 지적사항: " + (issues.length() == 0 ? "없음(전 항목 양호)" : issues)
                + "\n매뉴얼 주의(근거): " + manualNotes;
        String prompt = "당신은 현대자동차 A/S 진단 어시스턴트입니다. 아래 데이터로 '" + car
                + "' 차량 진단 의견을 한국어로 작성하세요. 자동차 성능·상태점검기록부 톤.\n"
                + "다음 섹션 구조로, 수치는 데이터를 그대로 인용하고 없는 내용은 지어내지 마세요:\n"
                + "## 차량 개요\n## 주요장치 점검 요약\n## 사고·결함 이력\n## 정비 권고\n## 종합 의견\n\n"
                + "[데이터]\n" + stats + "\n\n진단 의견:";
        String report;
        try {
            report = ollama.ask(prompt, llmModel, "진단서:" + car);
        } catch (Exception e) {
            report = "(종합 생성 실패) 통계: 리콜 " + recallTotal + "건, 불만 " + complaintTotal + "건, 화재 " + fires + "건.";
        }
        out.put("report", report);
        return out;
    }

    private void ensure(String table) {
        String path = data.getTables().get(table);
        if (path == null) return;
        try { tabular.registerCsv(table, path); } catch (Exception e) { log.warn("[report] {} 로드 실패: {}", table, e.getMessage()); }
    }

    private long scalar(String sql) {
        try {
            var r = tabular.runSelect(sql);
            if (!r.rows().isEmpty() && !r.rows().get(0).isEmpty()) {
                Object v = r.rows().get(0).get(0);
                return v == null ? 0 : Long.parseLong(v.toString().split("\\.")[0]);
            }
        } catch (Exception e) { log.warn("[report] scalar 실패({}): {}", sql, e.getMessage()); }
        return 0;
    }

    private List<List<Object>> rows(String sql) {
        try { return tabular.runSelect(sql).rows(); }
        catch (Exception e) { log.warn("[report] rows 실패: {}", e.getMessage()); return List.of(); }
    }
}
