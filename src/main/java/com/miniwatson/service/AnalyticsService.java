package com.miniwatson.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 대시보드 — 차량 데이터(리콜·불만·부품)의 플릿 단위 집계 + LLM 비즈니스 인사이트.
 *
 * 설계 일관성(ReportService와 동형): 집계 수치는 LLM이 아니라 **결정적 SQL(DuckDB)** 로 만든다.
 * LLM은 그 집계를 한국어 인사이트로 *서술*만 한다(= "분석에 LLM 적용", 환각의 폭발 반경 차단).
 *
 * JD 매핑(데이터 분석 직무): 워런티/부품 수요, 리콜·불만 추세, 안전 핫스팟, 인사이트.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final TabularSqlService tabular;
    private final OllamaService ollama;
    private final VehicleDataProperties data;
    private final double laborRate;

    public AnalyticsService(TabularSqlService tabular, OllamaService ollama,
                            VehicleDataProperties data,
                            @org.springframework.beans.factory.annotation.Value("${estimate.labor-rate-krw:50000}") double laborRate) {
        this.tabular = tabular;
        this.ollama = ollama;
        this.data = data;
        this.laborRate = laborRate;
    }

    public Map<String, Object> overview(String llmModel) {
        ensure("recalls"); ensure("complaints"); ensure("parts");
        Map<String, Object> out = new LinkedHashMap<>();

        // ── 총계 KPI ──
        long recalls    = scalar("SELECT COUNT(*) FROM recalls");
        long complaints = scalar("SELECT COUNT(*) FROM complaints");
        long fires      = scalar("SELECT COUNT(*) FROM complaints WHERE lower(cast(fire AS varchar)) IN ('true','1','yes','y')");
        long injuries   = scalar("SELECT COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) FROM complaints");
        long crashes    = scalar("SELECT COUNT(*) FROM complaints WHERE lower(cast(crash AS varchar)) IN ('true','1','yes','y')");
        out.put("totals", Map.of("recalls", recalls, "complaints", complaints,
                "fires", fires, "injuries", injuries, "crashes", crashes));

        // ── 리콜 추세(연도별) — 날짜 컬럼은 DATE 타입이라 year()로 연도 추출(substr 불가) ──
        out.put("recallByYear", rows(
            "SELECT year(reportreceiveddate) AS y, COUNT(*) n FROM recalls "
            + "WHERE reportreceiveddate IS NOT NULL GROUP BY y ORDER BY y"));
        // ── 불만 추세(연도별) ──
        out.put("complaintByYear", rows(
            "SELECT year(datecomplaintfiled) AS y, COUNT(*) n FROM complaints "
            + "WHERE datecomplaintfiled IS NOT NULL GROUP BY y ORDER BY y"));

        // ── 결함 부위 Top (리콜/불만) — 부품 수요·품질 신호 ──
        out.put("recallTopComponents", rows(
            "SELECT component, COUNT(*) n FROM recalls GROUP BY component ORDER BY n DESC LIMIT 8"));
        out.put("complaintTopComponents", rows(
            "SELECT components, COUNT(*) n FROM complaints GROUP BY components ORDER BY n DESC LIMIT 8"));

        // ── 차종별 불만 ──
        out.put("complaintByModel", rows(
            "SELECT model, COUNT(*) n FROM complaints GROUP BY model ORDER BY n DESC LIMIT 8"));

        // ── 안전 핫스팟: 차종별 화재/부상/사고 ──
        out.put("safetyHotspots", rows(
            "SELECT model, "
            + "SUM(CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END) fires, "
            + "COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) injuries, "
            + "SUM(CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END) crashes "
            + "FROM complaints GROUP BY model ORDER BY fires DESC, injuries DESC LIMIT 8"));

        // ── 부품 수요/워런티 비용 프록시: 부품 부위명이 불만 components에 등장한 횟수 × 단가(+공임) ──
        // 결함 신호(불만)를 부품 카탈로그에 매핑해 예상 수요·비용을 근사(정확 청구액 아님, 운영 우선순위용).
        out.put("partsDemand", rows(
            "SELECT p.part, p.component, "
            + "  (SELECT COUNT(*) FROM complaints c WHERE upper(c.components) LIKE '%'||upper(p.component)||'%') AS demand, "
            + "  p.unit_price, "
            + "  (SELECT COUNT(*) FROM complaints c WHERE upper(c.components) LIKE '%'||upper(p.component)||'%') "
            + "   * (TRY_CAST(p.unit_price AS DOUBLE) + TRY_CAST(p.labor_hours AS DOUBLE)*" + laborRate + ") AS est_cost "
            + "FROM parts p ORDER BY est_cost DESC LIMIT 10"));

        // (OTA 비율 지표는 SELECT-전용 가드가 컬럼명 'overTheAirUpdate'의 'update'를 DML로 오탐 → 일시 제거.)
        // LLM 인사이트는 분리(/insight) — 집계(차트)는 즉시 반환하고 느린 LLM은 별도 호출로.
        return out;
    }

    /** LLM 인사이트만 별도로 (느린 LLM이 집계 응답을 막지 않게 분리). */
    public String insightText(String llmModel) {
        Map<String, Object> agg = overview(llmModel);
        long recalls    = scalar("SELECT COUNT(*) FROM recalls");
        long complaints = scalar("SELECT COUNT(*) FROM complaints");
        long fires      = scalar("SELECT COUNT(*) FROM complaints WHERE lower(cast(fire AS varchar)) IN ('true','1','yes','y')");
        long injuries   = scalar("SELECT COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) FROM complaints");
        return insight(agg, recalls, complaints, fires, injuries, llmModel);
    }

    /** 홈 대시보드용 경량 요약 — LLM 안 거치고 빠르게(총계 + 최근 이벤트 피드). */
    public Map<String, Object> summary() {
        ensure("recalls"); ensure("complaints");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", Map.of(
                "recalls",    scalar("SELECT COUNT(*) FROM recalls"),
                "complaints", scalar("SELECT COUNT(*) FROM complaints"),
                "fires",      scalar("SELECT COUNT(*) FROM complaints WHERE lower(cast(fire AS varchar)) IN ('true','1','yes','y')"),
                "injuries",   scalar("SELECT COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) FROM complaints")));
        // 최근 리콜/불만 (날짜 DD/MM/YYYY → try_strptime로 안전 정렬). "뉴스 피드"를 실데이터로.
        out.put("recentRecalls", rows(
            "SELECT reportreceiveddate, model, component, substr(summary,1,140) "
            + "FROM recalls ORDER BY reportreceiveddate DESC NULLS LAST LIMIT 6"));
        out.put("recentComplaints", rows(
            "SELECT datecomplaintfiled, model, components, substr(summary,1,140) "
            + "FROM complaints ORDER BY datecomplaintfiled DESC NULLS LAST LIMIT 6"));
        // 차종별 현황: [차종, 불만, 리콜] — 차종별 업무 진입점
        out.put("byModel", rows(
            "SELECT c.model, c.n AS complaints, COALESCE(r.n,0) AS recalls FROM "
            + "(SELECT upper(model) model, COUNT(*) n FROM complaints GROUP BY upper(model)) c "
            + "LEFT JOIN (SELECT upper(model) model, COUNT(*) n FROM recalls GROUP BY upper(model)) r "
            + "ON c.model=r.model ORDER BY complaints DESC LIMIT 8"));
        return out;
    }

    /** 드릴다운 + 케이스 우선순위 트리아지: 특정 차종의 개별 차량 기록(불만).
     *  [접수번호, 날짜, 부위, 연식, 요약, 우선순위, 화재, 사고, 부상, 사망]
     *  우선순위 = 사망×100 + 부상×10 + 화재×5 + 사고×3 → 심각한 케이스가 위로(A/S 접수 트리아지).
     *  에러를 삼키지 않고 던진다 → 컨트롤러가 응답에 원인을 담아 화면에서 바로 보이게. */
    public List<List<Object>> vehiclesByModel(String model) throws Exception {
        ensure("complaints");
        String esc = (model == null ? "" : model).replace("'", "''").toUpperCase();
        String fireT  = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String crashT = "CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj    = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea    = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        return tabular.runSelect(
            "SELECT odinumber, datecomplaintfiled, components, modelyear, substr(summary,1,160), "
            + "(" + dea + "*100 + " + inj + "*10 + " + fireT + "*5 + " + crashT + "*3) AS priority, "
            + fireT + " AS fire, " + crashT + " AS crash, " + inj + " AS injuries, " + dea + " AS deaths "
            + "FROM complaints WHERE upper(model)='" + esc + "' "
            + "ORDER BY priority DESC, datecomplaintfiled DESC NULLS LAST LIMIT 20").rows();
    }

    /** 케이스 우선순위 트리아지(전 차종) — 필터(차종/부위) + 우선순위 정렬.
     *  [접수번호, 날짜, 차종, 부위, 연식, 요약, 우선순위, 화재, 사고, 부상, 사망] */
    public List<List<Object>> cases(String model, String component) throws Exception {
        ensure("complaints");
        String fireT  = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String crashT = "CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj    = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea    = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        StringBuilder where = new StringBuilder("WHERE 1=1");
        if (model != null && !model.isBlank())
            where.append(" AND upper(model)='").append(model.replace("'", "''").toUpperCase()).append("'");
        if (component != null && !component.isBlank())
            where.append(" AND upper(components) LIKE '%").append(component.replace("'", "''").toUpperCase()).append("%'");
        return tabular.runSelect(
            "SELECT odinumber, datecomplaintfiled, model, components, modelyear, substr(summary,1,160), "
            + "(" + dea + "*100 + " + inj + "*10 + " + fireT + "*5 + " + crashT + "*3) AS priority, "
            + fireT + " AS fire, " + crashT + " AS crash, " + inj + " AS injuries, " + dea + " AS deaths "
            + "FROM complaints " + where + " "
            + "ORDER BY priority DESC, datecomplaintfiled DESC NULLS LAST LIMIT 60").rows();
    }

    @SuppressWarnings("unchecked")
    private String insight(Map<String, Object> agg, long recalls, long complaints, long fires, long injuries, String llmModel) {
        String stats = "리콜 총 " + recalls + "건, 불만 총 " + complaints + "건, 화재 " + fires + "건, 부상 " + injuries + "명.\n"
                + "리콜 주요 부위: " + fmtTop((List<List<Object>>) agg.get("recallTopComponents")) + "\n"
                + "불만 주요 부위: " + fmtTop((List<List<Object>>) agg.get("complaintTopComponents")) + "\n"
                + "차종별 불만: " + fmtTop((List<List<Object>>) agg.get("complaintByModel")) + "\n"
                + "부품 수요 상위: " + fmtTop((List<List<Object>>) agg.get("partsDemand"));
        String prompt = "당신은 현대자동차 데이터 분석가입니다. 아래 플릿 통계만 근거로 운영 인사이트를 한국어로 작성하세요.\n"
                + "규칙: 주어진 수치만 인용, 지어내지 말 것. 대괄호·표 기호 없이 자연스러운 문장으로 4~6문장. "
                + "품질 우선순위, 워런티/부품 수요, 안전 위험 관점에서 시사점을 제시하세요.\n\n통계:\n" + stats + "\n\n인사이트:";
        try {
            return ollama.ask(prompt, llmModel, "분석 인사이트");
        } catch (Throwable t) {
            log.warn("[analytics] 인사이트 생성 실패({}): {}", llmModel, t.toString());
            return "## 인사이트 (자동 생성 실패)\n수치는 위 차트를 참고하세요. 더 가벼운 모델(qwen2.5:7b, granite4)을 권장합니다.";
        }
    }

    private String fmtTop(List<List<Object>> top) {
        if (top == null || top.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (List<Object> r : top) {
            if (r.size() < 2) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.get(0)).append(" ").append(r.get(1));
            if (++i >= 5) break;
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }

    private void ensure(String table) {
        String path = data.getTables().get(table);
        if (path == null) return;
        try { tabular.registerCsv(table, path); } catch (Exception e) { log.warn("[analytics] {} 로드 실패: {}", table, e.getMessage()); }
    }

    private long scalar(String sql) {
        try {
            var r = tabular.runSelect(sql);
            if (!r.rows().isEmpty() && !r.rows().get(0).isEmpty()) {
                Object v = r.rows().get(0).get(0);
                return v == null ? 0 : Long.parseLong(v.toString().split("\\.")[0]);
            }
        } catch (Exception e) { log.warn("[analytics] scalar 실패({}): {}", sql, e.getMessage()); }
        return 0;
    }

    private List<List<Object>> rows(String sql) {
        try { return tabular.runSelect(sql).rows(); }
        catch (Exception e) { log.warn("[analytics] rows 실패: {}", e.getMessage()); return new ArrayList<>(); }
    }
}
