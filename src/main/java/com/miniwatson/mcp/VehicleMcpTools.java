package com.miniwatson.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniwatson.service.AgentService;
import com.miniwatson.service.AnalyticsService;
import com.miniwatson.service.RagService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VehicleMcpTools {
    private final AnalyticsService analytics;
    private final RagService rag;
    private final AgentService agent;
    private final ObjectMapper mapper = new ObjectMapper();  // Spring이 기본 제공

    public VehicleMcpTools(AnalyticsService analytics, RagService rag, AgentService agent) {
        this.analytics = analytics; this.rag = rag; this.agent = agent;
    }

    // 반환은 항상 JSON 문자열 → MCP 텍스트 블록으로 깔끔히 감싸짐. null-safe(LinkedHashMap).
    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return "{\"error\":\"serialize failed: " + e.getMessage() + "\"}"; }
    }

    @Tool(name = "recall_check", description = "특정 차종·연식의 미조치 리콜을 조회한다. 차량 인수·점검 전 규제 리스크 확인용.")
    public String recallCheck(
            @ToolParam(description = "차종명 (예: PALISADE, TUCSON)") String model,
            @ToolParam(description = "연식 4자리. 모르면 생략", required = false) Integer modelYear) {
        var rows = analytics.recallCheck(model, modelYear);
        // recalls는 연식별 행이라 같은 캠페인이 여러 줄 → 캠페인 기준 중복 제거, 연식은 묶는다.
        var byCampaign = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : rows) {
            String c = String.valueOf(r.get("campaign"));
            var agg = byCampaign.computeIfAbsent(c, k -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("campaign", c);
                m.put("date", r.get("date"));
                m.put("component", r.get("component"));
                m.put("summary", r.get("summary"));
                m.put("parkIt", r.get("parkIt"));
                m.put("years", new java.util.TreeSet<String>());
                return m;
            });
            @SuppressWarnings("unchecked")
            var years = (java.util.Set<String>) agg.get("years");
            years.add(String.valueOf(r.get("year")));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("model", model);
        out.put("year", modelYear);
        out.put("count", byCampaign.size());        // 이제 distinct 캠페인 수(=12)
        out.put("recalls", byCampaign.values());    // 각 캠페인에 years 배열
        return json(out);
    }

    @Tool(name = "ask_manual", description = "차량 매뉴얼 지식베이스에 자연어 질문. 정비/사양 근거를 출처와 함께 답한다.")
    public String askManual(
            @ToolParam(description = "질문(한국어 가능). 예: 팰리세이드 엔진오일 교환 주기") String question) throws Exception {
        var r = rag.ask(question, "vehicle", null);
        var out = new LinkedHashMap<String, Object>();
        out.put("answer", r.answer()); out.put("sources", r.sources());
        return json(out);
    }

    @Tool(name = "fleet_overview", description = "플릿 품질 집계(리콜·불만·화재·부상·사고, 차종별 핫스팟)를 기간별로 반환.")
    public String fleetOverview(
            @ToolParam(description = "기간: all/year/month/week", required = false) String period) {
        return json(analytics.overview(null, period == null ? "all" : period));
    }

    @Tool(name = "similar_cases", description = "접수번호로 과거 유사 증상 케이스 top-k를 찾는다.")
    public String similarCases(
            @ToolParam(description = "케이스 접수번호(ODI number)") String caseId,
            @ToolParam(description = "가져올 개수(기본 5)", required = false) Integer k) {
        return json(Map.of("similar", analytics.similarCases(caseId, k == null ? 5 : k)));
    }

    @Tool(name = "diagnose", description = "정비 진단 에이전트. 매뉴얼 RAG와 데이터 SQL을 스스로 조합해 답한다.")
    public String diagnose(
            @ToolParam(description = "진단 질문. 예: 2022 투싼 시동 꺼짐 다발 원인과 점검 항목") String question) throws Exception {
        var r = agent.run(question, "vehicle", null);
        var out = new LinkedHashMap<String, Object>();
        out.put("answer", r.answer()); out.put("tool", r.tool());
        return json(out);
    }
}