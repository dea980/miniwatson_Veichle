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
        } catch (Throwable t) {
            // Exception뿐 아니라 Error(OOM 등 — 대형 모델 첫 호출 시 모델 로딩에서 메모리 초과)도 잡아 500 방지
            log.warn("[report] RAG 실패({}): {}", llmModel, t.toString());
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

        String manualClean = manualNotes == null ? "" : manualNotes.replaceAll("\\s+", " ").trim();
        if (manualClean.length() > 300) manualClean = manualClean.substring(0, 300);
        String stats = "차종: " + car + "\n"
                + "리콜 총 " + recallTotal + "건. 주요 부품: " + fmtTop(recallTop) + "\n"
                + "불만 총 " + complaintTotal + "건. 주요 부품: " + fmtTop(complaintTop) + "\n"
                + "화재 신고 " + fires + "건, 부상 합계 " + injuries + "명\n"
                + "점검 지적사항: " + (issues.length() == 0 ? "없음(전 항목 양호)" : issues.toString().trim())
                + (manualClean.isBlank() ? "" : "\n매뉴얼 주의: " + manualClean);
        String prompt = "당신은 현대자동차 A/S 진단 어시스턴트입니다. 아래 통계만 근거로 '" + car
                + "' 차량의 진단 의견을 한국어 서술형으로 작성하세요.\n"
                + "규칙: 주어진 수치만 인용하고 없는 내용은 지어내지 마세요. "
                + "대괄호([]), 표, 코드 기호를 쓰지 말고 자연스러운 완결된 문장으로만 4~6문장 이내로 간결하게 쓰세요.\n\n"
                + "통계:\n" + stats + "\n\n진단 의견:";
        String report;
        try {
            report = ollama.ask(prompt, llmModel, "진단서:" + car);
        } catch (Throwable t) {
            // Exception뿐 아니라 Error(OutOfMemoryError 등 — 9.6GB gemma4 같은 대형 모델이 M2 메모리 초과)도 잡아
            // 500으로 터지지 않게 한다. 집계(KPI·표·차트)는 이미 결정적 SQL로 만들어졌으니 부분 리포트로라도 응답.
            log.warn("[report] 종합 생성 실패({}): {} — 부분 리포트 반환", llmModel, t.toString());
            report = "## 종합 의견 (자동 생성 실패)\n\n"
                + "선택 모델(" + (llmModel == null ? "기본" : llmModel) + ")로 종합 서술 생성에 실패했습니다. "
                + "집계 수치는 위 KPI와 표를 참고하세요. 더 가벼운 모델(qwen2.5:7b-instruct, granite4)을 권장합니다.\n\n"
                + "- 리콜 " + recallTotal + "건, 불만 " + complaintTotal + "건, 화재 " + fires + "건, 부상 " + injuries + "명.";
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

    /** [[부품,건수],...] → "부품 N건, 부품 N건" 사람이 읽는 문장(프롬프트에 원시 리스트 누수 방지). */
    private String fmtTop(List<List<Object>> top) {
        if (top == null || top.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        for (List<Object> r : top) {
            if (r.isEmpty()) continue;
            String name = String.valueOf(r.get(0));
            String cnt = r.size() > 1 ? String.valueOf(r.get(1)) : "";
            if (sb.length() > 0) sb.append(", ");
            sb.append(name).append(cnt.isBlank() ? "" : " " + cnt + "건");
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }
}
