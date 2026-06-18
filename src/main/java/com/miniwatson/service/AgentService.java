package com.miniwatson.service;

import com.miniwatson.data.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic Search — 질문을 분석해 도구를 고르고(RAG / 리콜 text-to-SQL / 둘 다),
 * 실행한 뒤 결과를 한국어로 종합한다. 작은 로컬 모델 신뢰성을 위해
 * LLM 라우팅 + 키워드 폴백을 함께 쓰고, 도구 선택 과정(trace)을 함께 반환한다.
 *
 * 자동차 밸류체인 NLP: 서술형(A·S·매뉴얼)=RAG, 정형 집계(리콜 통계)=SQL.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final RagService ragService;
    private final TextToSqlService textToSql;
    private final TabularSqlService tabularSql;   // 리콜 테이블 자동 로드(idempotent)
    private final OllamaService ollama;
    private final VehicleDataProperties data;   // 정형 데이터셋 레지스트리(설정 주도)

    public AgentService(RagService ragService, TextToSqlService textToSql,
                        TabularSqlService tabularSql, OllamaService ollama,
                        VehicleDataProperties data) {
        this.ragService = ragService;
        this.textToSql = textToSql;
        this.tabularSql = tabularSql;
        this.ollama = ollama;
        this.data = data;
    }

    public enum Tool { RAG, SQL, BOTH }

    public AgentResult run(String question, String namespace, String model) throws Exception {
        String ns = (namespace == null || namespace.isBlank()) ? "vehicle" : namespace;
        List<Map<String, Object>> trace = new ArrayList<>();

        // 1) 라우팅 — 어떤 도구가 필요한가
        Tool tool = route(question, model);
        trace.add(step("route", "도구 선택", tool.name(),
                "RAG=매뉴얼/사용법/주의사항, SQL=리콜 건수/통계/집계, BOTH=둘 다"));

        List<Article> sources = new ArrayList<>();
        String ragAnswer = null;
        Object sql = null;
        Object rows = null;

        // 2) 도구 실행
        if (tool == Tool.RAG || tool == Tool.BOTH) {
            var rag = ragService.ask(question, ns, model);
            ragAnswer = rag.answer();
            sources = rag.sources();
            trace.add(step("rag", "매뉴얼 RAG 검색", (sources == null ? 0 : sources.size()) + "개 근거",
                    ragAnswer == null ? "" : ragAnswer.substring(0, Math.min(120, ragAnswer.length()))));
        }
        if (tool == Tool.SQL || tool == Tool.BOTH) {
            String table = pickTable(question, model);
            if (table == null) {
                trace.add(step("sql", "text-to-SQL", "테이블 없음", "등록된 정형 데이터셋이 없습니다(vehicle.tables)."));
            } else {
                ensureLoaded(table);
                Map<String, Object> r = textToSql.ask(table, question);
                sql = r.get("sql");
                rows = r.getOrDefault("rows", List.of());
                trace.add(step("sql", "text-to-SQL (" + table + ")", String.valueOf(sql),
                        r.containsKey("error") ? "ERROR: " + r.get("error") : (rows + "").substring(0, Math.min(160, (rows + "").length()))));
            }
        }

        // 3) 종합 — 한국어 최종 답변
        String answer = synthesize(question, tool, ragAnswer, sql, rows, model);
        Long logId = ollama.lastQueryLogId();

        return new AgentResult(answer, tool.name(), trace, sources, sql, rows, logId);
    }

    /**
     * 라우팅 — 결정적(rule-based) 우선, 신호 모호할 때만 LLM 폴백.
     * 모델 크기에 흔들리지 않게(작은 모델이 BOTH 남발 → RAG 노이즈 방지) 규칙을 먼저 적용한다.
     */
    private Tool route(String question, String model) {
        String q = question == null ? "" : question;
        // 집계/통계 신호 (→ SQL)
        boolean sqlSignal = q.matches("(?s).*(리콜|건수|몇\\s*건|통계|개수|순으로|순위|차종별|연도별|가장\\s*많|평균|합계|count|얼마나).*");
        // 서술/설명 신호 (→ RAG)
        boolean ragSignal = q.matches("(?s).*(주의|방법|어떻게|설명|원인|점검|교체|작동|의미|무엇|뭐|언제|어디|사용법|절차|증상).*");

        if (sqlSignal && !ragSignal) return Tool.SQL;   // 순수 집계 → SQL만 (RAG 노이즈 차단)
        if (ragSignal && !sqlSignal) return Tool.RAG;   // 순수 서술 → RAG만
        if (sqlSignal && ragSignal) return Tool.BOTH;   // 둘 다 → BOTH

        // 신호 없음(모호) → LLM 분류 폴백
        try {
            String p = "질문에 답하려면 어떤 도구가 필요한지 한 단어만 답하라.\n"
                    + "- RAG: 매뉴얼/사용법/주의사항/원인/설명 질문\n"
                    + "- SQL: 리콜 건수·통계·집계 수치 질문\n"
                    + "- BOTH: 둘 다 필요\n"
                    + "오직 RAG, SQL, BOTH 중 하나만 출력.\n질문: " + q + "\n도구:";
            String r = ollama.ask(p, model).toUpperCase();
            if (r.contains("BOTH")) return Tool.BOTH;
            if (r.contains("SQL")) return Tool.SQL;
        } catch (Exception e) {
            log.warn("[agent] 라우팅 LLM 실패 — RAG 폴백: {}", e.getMessage());
        }
        return Tool.RAG;
    }

    private String synthesize(String question, Tool tool, String ragAnswer,
                              Object sql, Object rows, String model) {
        // RAG 단독: 이미 한국어 근거 기반 답변이라 그대로 사용
        if (tool == Tool.RAG) return ragAnswer;

        // SQL 포함: 결과를 한국어로 요약/종합
        StringBuilder ctx = new StringBuilder();
        if (ragAnswer != null) ctx.append("[매뉴얼 답변]\n").append(ragAnswer).append("\n\n");
        if (sql != null) ctx.append("[실행 SQL]\n").append(sql).append("\n[결과]\n").append(rows).append("\n");
        String p = "당신은 한국어 자동차 어시스턴트입니다. 아래 도구 결과를 바탕으로 질문에 간결히 답하세요.\n"
                + "- 표(SQL) 결과가 있으면 핵심 수치를 반드시 포함해 정리하세요(예: 차종별 건수).\n"
                + "- 매뉴얼 답변이 있으면 함께 반영하되, 없는 내용은 지어내지 마세요.\n"
                + "- 대괄호 라벨이나 프롬프트 형식([질문] 등)을 그대로 출력하지 마세요.\n\n"
                + "질문: " + question + "\n\n" + ctx + "\n답변:";
        try {
            return ollama.ask(p, model, question, sql == null ? null : ("SQL: " + sql));
        } catch (Exception e) {
            // 종합 실패 시 원자료라도 반환
            return (ragAnswer == null ? "" : ragAnswer + "\n\n") + "SQL 결과: " + rows;
        }
    }

    /** 질문에 맞는 정형 테이블 선택. 1개면 그것, 여러 개면 LLM이 이름으로 선택(폴백: 첫 테이블). */
    private String pickTable(String question, String model) {
        Map<String, String> tables = data.getTables();
        if (tables == null || tables.isEmpty()) return null;
        List<String> names = new ArrayList<>(tables.keySet());
        if (names.size() == 1) return names.get(0);
        try {
            String p = "다음 표 중 질문에 답할 수 있는 테이블 이름 하나만 출력하라.\n"
                    + "테이블: " + String.join(", ", names) + "\n질문: " + question + "\n테이블:";
            String r = ollama.ask(p, model).toLowerCase();
            for (String n : names) if (r.contains(n.toLowerCase())) return n;
        } catch (Exception e) {
            log.warn("[agent] 테이블 선택 실패 — 첫 테이블 사용: {}", e.getMessage());
        }
        return names.get(0);
    }

    /** 선택된 테이블을 DuckDB에 등록(CREATE OR REPLACE라 idempotent). 재시작 후 소실 대비. */
    private void ensureLoaded(String table) {
        String path = data.getTables().get(table);
        if (path == null) return;
        try {
            tabularSql.registerCsv(table, path);
        } catch (Exception e) {
            log.warn("[agent] 테이블 로드 실패({} → {}): {}", table, path, e.getMessage());
        }
    }

    private Map<String, Object> step(String tool, String action, String result, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("action", action);
        m.put("result", result);
        m.put("detail", detail);
        return m;
    }

    public record AgentResult(String answer, String tool, List<Map<String, Object>> trace,
                              List<Article> sources, Object sql, Object rows, Long logId) {}
}
