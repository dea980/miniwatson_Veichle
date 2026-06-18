package com.miniwatson.service;

import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Map;

/**
 * 질문 + 스키마 -> LLM이 DuckDB SQL(SELECT)을 생성 -> 실행. (watsonx.data식 자연어-to-SQL)
 *
 * 벡터 RAG가 못 하는 집계/필터/카운트를 SQL로 정확히 답한다. SQL 생성 호출은
 * OllamaService.ask를 거치므로 query_log에 감사 기록이 남는다.
 */
@Service
public class TextToSqlService {

    private final TabularSqlService sql;
    private final OllamaService ollama;

    public TextToSqlService(TabularSqlService sql, OllamaService ollama) {
        this.sql = sql;
        this.ollama = ollama;
    }

    public Map<String, Object> ask(String table, String question) throws SQLException {
        String prompt = """
            You are a SQL assistant for DuckDB. Write ONE SQL SELECT query that answers the question.
            Use only the table `%s`.
            Quote any column name containing spaces or special characters with double quotes, e.g. "Orbiting Body".
            Use literal values exactly as they appear in the sample rows (e.g. quarter is 'Q3', not 'Q3 2025').
            Return ONLY the SQL, no explanation, no markdown.
            Schema (column type):
            %s
            Sample rows:
            %s
            Question: %s
            SQL:""".formatted(table, sql.schema(table), sql.sample(table, 2), question);

        String query = cleanSql(ollama.ask(prompt, null));   // 기본 모델(granite4)
        try {
            var res = sql.runSelect(query);
            return Map.of("sql", query, "columns", res.columns(), "rows", res.rows());
        } catch (Exception e1) {
            // 자기수정(self-correction): 실행 에러를 모델에 돌려주고 1회 재시도 — agentic 패턴.
            String fixPrompt = """
                이전 SQL이 DuckDB에서 실행 에러가 났다. 에러를 보고 고쳐서 SQL SELECT 하나만 출력하라.
                규칙: 집계(COUNT 등)와 함께 출력하는 비집계 컬럼은 GROUP BY에 넣거나 빼라.
                테이블 `%s`만 사용. 설명/마크다운 없이 SQL만.
                스키마:
                %s
                이전 SQL:
                %s
                에러:
                %s
                수정 SQL:""".formatted(table, sql.schema(table), query, e1.getMessage());
            String fixed = cleanSql(ollama.ask(fixPrompt, null));
            try {
                var res = sql.runSelect(fixed);
                return Map.of("sql", fixed, "columns", res.columns(), "rows", res.rows(), "retried", true);
            } catch (Exception e2) {
                // 재시도도 실패 → 진단 정보 반환(500 아님)
                return Map.of("sql", fixed, "error", e2.getMessage(), "rows", java.util.List.of(), "retried", true);
            }
        }
    }

    /** LLM 응답에서 SQL만 추출(코드펜스/설명/세미콜론 제거). */
    static String cleanSql(String raw) {
        String s = raw.replace("```sql", "").replace("```", "").strip();
        int i = s.toUpperCase().indexOf("SELECT");
        int w = s.toUpperCase().indexOf("WITH");
        int start = (i < 0) ? w : (w < 0 ? i : Math.min(i, w));
        if (start >= 0) s = s.substring(start);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        return s.strip();
    }
}
