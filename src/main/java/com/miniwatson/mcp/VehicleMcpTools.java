package com.miniwatson.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniwatson.governance.PiiRedactionService;
import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;
import com.miniwatson.service.AgentService;
import com.miniwatson.service.AnalyticsService;
import com.miniwatson.service.RagService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import com.miniwatson.service.GraphService;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VehicleMcpTools {
    private final AnalyticsService analytics;
    private final RagService rag;
    private final AgentService agent;
    private final PiiRedactionService pii;
    private final QueryLogRepository queryLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphService graph;



    public VehicleMcpTools(AnalyticsService analytics, RagService rag, AgentService agent,
                           PiiRedactionService pii, QueryLogRepository queryLog, GraphService graph) {
        this.analytics = analytics; this.rag = rag; this.agent = agent;
        this.pii = pii; this.queryLog = queryLog; this.graph = graph;
    }

    @FunctionalInterface private interface Body { String get() throws Exception; }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return "{\"error\":\"serialize failed\"}"; }
    }

    // 거버넌스 게이트: 모든 MCP 툴 호출 = 출력 PII 마스킹 + 감사 로그(fail-open, 감사 실패해도 응답은 반환).
    private String governed(String tool, String input, Body body) {
        long t0 = System.currentTimeMillis();
        String raw;
        try { raw = body.get(); }
        catch (Exception e) { raw = "{\"error\":\"" + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}"; }
        var red = pii.redact(raw);                            // 카드/SSN/이메일/전화 마스킹
        QueryLog log = new QueryLog();
        log.setQuestion("mcp:" + tool + " " + input);         // 무엇을 호출했나(감사)
        log.setAnswer(red.text());
        log.setModel("mcp");
        log.setLatencyMs(System.currentTimeMillis() - t0);
        log.setPiiCount(red.count());
        try { queryLog.save(log); } catch (Exception ignore) {}
        return red.text();
    }

    @Tool(name = "recall_check", description = "특정 차종·연식의 미조치 리콜을 조회한다. 차량 인수·점검 전 규제 리스크 확인용.")
    public String recallCheck(
            @ToolParam(description = "차종명 (예: PALISADE, TUCSON)") String model,
            @ToolParam(description = "연식 4자리. 모르면 생략", required = false) Integer modelYear) {
        return governed("recall_check", "model=" + model + (modelYear == null ? "" : ",year=" + modelYear), () -> {
            var rows = analytics.recallCheck(model, modelYear);
            var byCampaign = new LinkedHashMap<String, Map<String, Object>>();
            for (var r : rows) {
                String c = String.valueOf(r.get("campaign"));
                var agg = byCampaign.computeIfAbsent(c, k -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("campaign", c); m.put("date", r.get("date"));
                    m.put("component", r.get("component")); m.put("summary", r.get("summary"));
                    m.put("parkIt", r.get("parkIt")); m.put("years", new java.util.TreeSet<String>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                var years = (java.util.Set<String>) agg.get("years");
                years.add(String.valueOf(r.get("year")));
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("model", model); out.put("year", modelYear);
            out.put("count", byCampaign.size()); out.put("recalls", byCampaign.values());
            return json(out);
        });
    }

    @Tool(name = "ask_manual", description = "차량 매뉴얼 지식베이스에 자연어 질문. 정비/사양 근거를 출처와 함께 답한다.")
    public String askManual(
            @ToolParam(description = "질문(한국어 가능). 예: 팰리세이드 엔진오일 교환 주기") String question) {
        return governed("ask_manual", question, () -> {
            var r = rag.ask(question, "vehicle", null);
            var out = new LinkedHashMap<String, Object>();
            out.put("answer", r.answer()); out.put("sources", r.sources());
            return json(out);
        });
    }

    @Tool(name = "fleet_overview", description = "플릿 품질 집계(리콜·불만·화재·부상·사고, 차종별 핫스팟)를 기간별로 반환.")
    public String fleetOverview(
            @ToolParam(description = "기간: all/year/month/week", required = false) String period) {
        return governed("fleet_overview", "period=" + (period == null ? "all" : period),
                () -> json(analytics.overview(null, period == null ? "all" : period)));
    }

    @Tool(name = "similar_cases", description = "접수번호로 과거 유사 증상 케이스 top-k를 찾는다.")
    public String similarCases(
            @ToolParam(description = "케이스 접수번호(ODI number)") String caseId,
            @ToolParam(description = "가져올 개수(기본 5)", required = false) Integer k) {
        return governed("similar_cases", "caseId=" + caseId + ",k=" + (k == null ? 5 : k),
                () -> json(Map.of("similar", analytics.similarCases(caseId, k == null ? 5 : k))));
    }

    @Tool(name = "diagnose", description = "정비 진단 에이전트. 매뉴얼 RAG와 데이터 SQL을 스스로 조합해 답한다.")
    public String diagnose(
            @ToolParam(description = "진단 질문. 예: 2022 투싼 시동 꺼짐 다발 원인과 점검 항목") String question) {
        return governed("diagnose", question, () -> {
            var r = agent.run(question, "vehicle", null);
            var out = new LinkedHashMap<String, Object>();
            out.put("answer", r.answer()); out.put("tool", r.tool());
            return json(out);
        });
    }

    @Tool(name = "component_graph", description = "차종-리콜-부품-증상 온톨로지 그래프 순회. component 생략하면 차종의 부위별 리스크 맵(리콜/불만 수), 주면 그 부위의 리콜·증상·부품·비용 통합 프로파일.")
    public String componentGraph(
            @ToolParam(description = "차종명 (예: PALISADE, TUCSON)") String model,
            @ToolParam(description = "정규 부위: AIR BAGS/BRAKE/ENGINE/TRANSMISSION/VISIBILITY/ELECTRICAL/FUEL/SEAT BELTS/TIRE/EXHAUST/CAMERA. 생략하면 부위별 맵.", required = false) String component) {
        return governed("component_graph", "model=" + model + (component == null ? "" : ",component=" + component), () -> {
            if (component == null || component.isBlank())
                return json(Map.of("model", model, "components", graph.modelComponents(model)));
            return json(graph.componentProfile(model, component));
        });
    }
}