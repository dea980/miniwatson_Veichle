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
    private final com.miniwatson.reports.GeneratedReportRepository reportRepo;
    private final EstimateService estimateService;
    private final AnalyticsService analytics;
    // JavaTimeModule 등록 — LocalDate/LocalDateTime 직렬화(케이스 리포트 적재 시 "date" 필드 실패 방지).
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public ReportService(TabularSqlService tabular, RagService ragService,
                         OllamaService ollama, VehicleDataProperties data,
                         com.miniwatson.reports.GeneratedReportRepository reportRepo,
                         EstimateService estimateService, AnalyticsService analytics) {
        this.tabular = tabular;
        this.ragService = ragService;
        this.ollama = ollama;
        this.data = data;
        this.reportRepo = reportRepo;
        this.estimateService = estimateService;
        this.analytics = analytics;
    }

    /** 차종 리포트 = 빠른 카테고리(전부 결정적 SQL). LLM·캐시 없이 즉시. force는 무시(호환용). */
    public Map<String, Object> generate(String carModel, String namespace, String llmModel) throws Exception {
        return buildCarReport(carModel, namespace, llmModel);
    }
    public Map<String, Object> generate(String carModel, String namespace, String llmModel, boolean force) throws Exception {
        return buildCarReport(carModel, namespace, llmModel);
    }

    private Map<String, Object> buildCarReport(String carModel, String namespace, String llmModel) throws Exception {
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

        // 차종 = 빠른 카테고리: LLM/RAG 미사용(서술형 진단은 접수번호별 리포트로 이관). 매뉴얼 근거는 케이스 진단에서.
        out.put("manualNotes", "");
        out.put("sources", new ArrayList<>());

        // 점검표 지적사항(양호 아님) 요약 — 결정적
        @SuppressWarnings("unchecked")
        List<List<Object>> insp = (List<List<Object>>) out.get("inspection");
        StringBuilder issues = new StringBuilder();
        for (List<Object> r : insp) {
            String result = String.valueOf(r.get(2));
            if (!"양호".equals(result)) issues.append(r.get(0)).append("/").append(r.get(1)).append("(").append(result).append(") ");
        }
        // 차종 개요(참고) — 결정적 한 줄 요약(LLM 없음). 개별 서술 진단은 케이스(접수번호) 리포트에서.
        String report = "차종 개요(참고): 리콜 " + recallTotal + "건, 불만 " + complaintTotal + "건"
                + (fires > 0 ? ", 화재 " + fires + "건" : "") + (injuries > 0 ? ", 부상 " + injuries + "명" : "") + ". "
                + "주요 리콜 부품 " + fmtTop(recallTop) + ". 주요 불만 부품 " + fmtTop(complaintTop) + ". "
                + (issues.length() == 0 ? "점검표 전 항목 양호." : "점검 지적: " + issues.toString().trim() + ".")
                + " 개별 차량 진단은 아래 케이스(접수번호)에서 확인하세요.";
        out.put("report", report);
        return out;
    }

    /** 접수번호별 리포트 — AI 진단 + 견적 + 점검을 통째로 적재(스냅샷). 캐시 우선, force면 재생성(정비사 메모 보존). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> caseReport(String caseNumber, String namespace, String llmModel, boolean force) {
        String id = caseNumber == null ? "" : caseNumber.trim();
        String ns = (namespace == null || namespace.isBlank()) ? "vehicle" : namespace;
        var existing = reportRepo.findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc("CASE", id);
        if (!force && existing.isPresent()) {
            try {
                Map<String, Object> m = mapper.readValue(existing.get().getContentJson(), Map.class);
                m.put("generatedAt", existing.get().getCreatedAt().toString());
                m.put("cached", true);
                return m;
            } catch (Exception e) { log.warn("[case-report] 캐시 파싱 실패 — 재생성: {}", e.getMessage()); }
        }
        // 재생성 시 기존 정비사 메모 보존
        String prevNote = "";
        if (existing.isPresent()) {
            try { Object n = mapper.readValue(existing.get().getContentJson(), Map.class).get("note"); if (n != null) prevNote = String.valueOf(n); }
            catch (Exception ignore) {}
        }
        Map<String, Object> out = new LinkedHashMap<>();
        var caseRows = analytics.caseById(id);
        if (caseRows.isEmpty()) { out.put("error", "해당 접수번호를 찾지 못함: " + id); out.put("caseNumber", id); return out; }
        List<Object> c = caseRows.get(0);
        String model = String.valueOf(c.get(2)), component = String.valueOf(c.get(3)), summary = String.valueOf(c.get(5));
        out.put("caseNumber", id);
        out.put("model", model); out.put("component", component);
        out.put("year", c.get(4)); out.put("date", c.get(1)); out.put("summary", summary);
        out.put("priority", c.get(6)); out.put("fire", c.get(7)); out.put("crash", c.get(8));
        out.put("injuries", c.get(9)); out.put("deaths", c.get(10));

        // AI 진단(RAG, 매뉴얼 근거) — 느린 부분, 적재로 1회만
        String diagnosis = "";
        try {
            String q = model + " 차량, 증상: \"" + (summary.length() > 200 ? summary.substring(0, 200) : summary)
                + "\" (결함 부위: " + component + "). "
                + "정비사 관점에서 추정 원인과 점검·조치를 한국어로 3~4문장 간결히. 매뉴얼 근거에 충실하고 지어내지 말 것.";
            var rag = ragService.ask(q, ns, llmModel);
            diagnosis = rag.answer();
            List<String> src = new ArrayList<>(); rag.sources().forEach(a -> src.add(a.getTitle()));
            out.put("sources", src);
        } catch (Throwable t) { log.warn("[case-report] 진단 실패: {}", t.toString()); out.put("sources", new ArrayList<>()); }
        out.put("diagnosis", diagnosis);

        // 견적(부품+공임+부가세) — 결정적 계산
        try { out.put("estimate", estimateService.estimate(component, model, llmModel)); }
        catch (Throwable t) { log.warn("[case-report] 견적 실패: {}", t.toString()); }
        // 점검(이 건 부위 + 차종 전반) — 결정적
        try { out.put("checklistThis", analytics.checklist(model, component)); } catch (Throwable t) { log.warn("[case-report] 점검(건) 실패: {}", t.toString()); }
        try { out.put("checklistCar", analytics.checklist(model, null)); } catch (Throwable t) { log.warn("[case-report] 점검(차종) 실패: {}", t.toString()); }

        out.put("note", prevNote);   // 정비사 메모(보존)

        try {
            com.miniwatson.reports.GeneratedReport gr = existing.orElseGet(com.miniwatson.reports.GeneratedReport::new);
            gr.setReportType("CASE"); gr.setReportKey(id); gr.setModel(llmModel);
            gr.setContentJson(mapper.writeValueAsString(out));
            gr.setCreatedAt(java.time.LocalDateTime.now());
            var saved = reportRepo.save(gr);
            out.put("generatedAt", saved.getCreatedAt().toString());
        } catch (Exception e) { log.warn("[case-report] 적재 실패: {}", e.getMessage()); }
        out.put("cached", false);
        return out;
    }

    /**
     * 불만 접수 내용(NHTSA 영문 원문) → 한국어 핵심 요약.
     * 처음 1회만 로컬 LLM 호출, 이후 캐시(GeneratedReport type="SUMMARY")에서 즉시 반환.
     * 반환: { caseNumber, fullText, gist, cached, generatedAt }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> caseSummary(String caseNumber, String llmModel, boolean force) {
        String id = caseNumber == null ? "" : caseNumber.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        var existing = reportRepo.findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc("SUMMARY", id);
        if (!force && existing.isPresent()) {
            try {
                Map<String, Object> m = mapper.readValue(existing.get().getContentJson(), Map.class);
                m.put("generatedAt", existing.get().getCreatedAt().toString());
                m.put("cached", true);
                return m;
            } catch (Exception e) { log.warn("[case-summary] 캐시 파싱 실패 — 재생성: {}", e.getMessage()); }
        }
        var rows = analytics.caseById(id);
        if (rows.isEmpty()) { out.put("error", "해당 접수번호를 찾지 못함: " + id); out.put("caseNumber", id); return out; }
        String full = String.valueOf(rows.get(0).get(5))
            .replaceAll("\\*(?:[A-Z]{1,6}\\*)+[A-Z]{0,6}", " ")   // NHTSA 편집코드 *DT* *JB* 제거
            .replaceAll("\\s{2,}", " ").trim();
        out.put("caseNumber", id);
        out.put("fullText", full);
        String gist = "";
        try {
            String prompt = "다음은 차량 불만 접수 내용(영문 원문)이다. 정비사가 빠르게 파악하도록 "
                + "한국어로 핵심만 2~3문장으로 요약하라. 증상·발생 상황·안전 위험 위주로, 지어내지 말 것.\n\n"
                + "=== 접수 내용 ===\n" + full;
            gist = ollama.ask(prompt, llmModel);
        } catch (Throwable t) { log.warn("[case-summary] 요약 실패: {}", t.toString()); }
        out.put("gist", gist);
        try {   // out은 전부 문자열이라 jsr310 무관하게 직렬화 안전
            com.miniwatson.reports.GeneratedReport gr = existing.orElseGet(com.miniwatson.reports.GeneratedReport::new);
            gr.setReportType("SUMMARY"); gr.setReportKey(id); gr.setModel(llmModel);
            gr.setContentJson(mapper.writeValueAsString(out));
            gr.setCreatedAt(java.time.LocalDateTime.now());
            var saved = reportRepo.save(gr);
            out.put("generatedAt", saved.getCreatedAt().toString());
        } catch (Exception e) { log.warn("[case-summary] 적재 실패: {}", e.getMessage()); }
        out.put("cached", false);
        return out;
    }

    /** 정비사 메모 저장 — 적재된 케이스 리포트에 병합(없으면 먼저 생성). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveCaseNote(String caseNumber, String note, String namespace, String llmModel) {
        String id = caseNumber == null ? "" : caseNumber.trim();
        var existing = reportRepo.findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc("CASE", id);
        Map<String, Object> m;
        com.miniwatson.reports.GeneratedReport gr;
        if (existing.isPresent()) {
            gr = existing.get();
            try { m = mapper.readValue(gr.getContentJson(), Map.class); } catch (Exception e) { m = new LinkedHashMap<>(); }
        } else {
            caseReport(id, namespace, llmModel, true);   // 없으면 먼저 생성·적재
            var created = reportRepo.findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc("CASE", id);
            gr = created.orElseGet(com.miniwatson.reports.GeneratedReport::new);
            try { m = mapper.readValue(gr.getContentJson() == null ? "{}" : gr.getContentJson(), Map.class); } catch (Exception e) { m = new LinkedHashMap<>(); }
        }
        m.put("note", note == null ? "" : note);
        m.remove("generatedAt"); m.remove("cached");
        try {
            gr.setReportType("CASE"); gr.setReportKey(id);
            gr.setContentJson(mapper.writeValueAsString(m));
            var saved = reportRepo.save(gr);
            m.put("generatedAt", saved.getCreatedAt().toString());
        } catch (Exception e) { log.warn("[case-report] 메모 저장 실패: {}", e.getMessage()); }
        m.put("cached", true);
        return m;
    }

    /** 적재된 리포트 목록(차종·생성일) — 최신순. */
    public List<Map<String, Object>> savedReports() {
        return reportRepo.findByReportTypeOrderByCreatedAtDesc("CAR").stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.getReportKey());
            m.put("model", r.getModel() == null ? "" : r.getModel());
            m.put("generatedAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
            return m;
        }).toList();
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
