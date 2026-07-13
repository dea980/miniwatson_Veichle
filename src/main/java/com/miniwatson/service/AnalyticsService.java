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
    private final com.miniwatson.cases.ResolvedCaseRepository resolvedRepo;

    public AnalyticsService(TabularSqlService tabular, OllamaService ollama,
                            VehicleDataProperties data,
                            com.miniwatson.cases.ResolvedCaseRepository resolvedRepo,
                            @org.springframework.beans.factory.annotation.Value("${estimate.labor-rate-krw:50000}") double laborRate) {
        this.tabular = tabular;
        this.ollama = ollama;
        this.data = data;
        this.resolvedRepo = resolvedRepo;
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

        // ── 차종별 리콜 + 주차권고(화재위험) 리콜 수 — 리콜 딥다이브용 ──
        out.put("recallByModel", rows(
            "SELECT model, COUNT(*) n, "
            + "SUM(CASE WHEN lower(cast(parkit AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END) parkit "
            + "FROM recalls GROUP BY model ORDER BY n DESC LIMIT 8"));

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
        // 지역 핫스팟:  주(state)별 불만 , 화재, 부상 (Geographic 렌즈) ──
        out.put("complaintsByState", rows(
                "SELECT state, COUNT(*) n, "
                        + "SUM(CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END) fires, "
                        + "COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) injuries "
                        + "FROM complaints WHERE state IS NOT NULL AND state <> '' "
                        + "GROUP BY state ORDER BY n DESC LIMIT 10"));

        // LLM 인사이트는 분리(/insight) — 집계(차트)는 즉시 반환하고 느린 LLM은 별도 호출로.
        return out;
    }

    /** LLM 인사이트 — 레벨(탭)별로 다른 데이터·프레이밍 + 시간축(연간·계절). 느린 LLM이라 요청 시에만. */
    public String insightText(String llmModel) { return insightText(llmModel, null); }

    @SuppressWarnings("unchecked")
    public String insightText(String llmModel, String level) {
        Map<String, Object> agg = overview(llmModel);
        String lv = (level == null || level.isBlank()) ? "overview" : level.toLowerCase();
        String annual = fmtYears((List<List<Object>>) agg.get("complaintByYear"));
        String seasonal = fmtSeason(seasonalComplaints());
        String deltas = deltaFacts();   // 주간·연간 증감률(%) — Java에서 결정적으로 계산(LLM 계산 금지)
        String stats, focus;
        switch (lv) {
            case "recall" -> {
                focus = "리콜(규제 리스크)";
                stats = "리콜 주요 부위: " + fmtTop((List<List<Object>>) agg.get("recallTopComponents")) + "\n"
                      + "리콜 연도별: " + fmtYears((List<List<Object>>) agg.get("recallByYear"));
            }
            case "safety" -> {
                focus = "안전(누가 언제 다치나)";
                stats = "차종별 위해(화재·부상·사고): " + fmtSafety((List<List<Object>>) agg.get("safetyHotspots")) + "\n"
                      + "계절별 불만·화재: " + seasonal;
            }
            case "parts" -> {
                focus = "부품·워런티(비용)";
                stats = "부품 예상 워런티 상위: " + fmtParts((List<List<Object>>) agg.get("partsDemand")) + "\n"
                      + "불만 연도별(수요 추이 프록시): " + annual;
            }
            case "geo" -> {
                focus = "지역(어디서 터지나)";
                stats = "주별 불만 상위: " + fmtTop((List<List<Object>>) agg.get("complaintsByState")) + "\n"
                      + "계절별 불만·화재: " + seasonal;
            }
            default -> {
                focus = "종합(3렌즈: 볼륨·심각도·비용)";
                stats = "볼륨(불만 많은 차종): " + fmtTop((List<List<Object>>) agg.get("complaintByModel")) + "\n"
                      + "심각도(차종·화재): " + fmtTop((List<List<Object>>) agg.get("safetyHotspots")) + "\n"
                      + "비용(부품 워런티): " + fmtParts((List<List<Object>>) agg.get("partsDemand")) + "\n"
                      + "연도별 불만: " + annual + "\n"
                      + "계절별 불만·화재: " + seasonal;
            }
        }
        String prompt = "당신은 현대자동차 품질 데이터 분석가입니다. 아래 집계(결정적 SQL 결과)만 근거로 "
                + "'" + focus + "' 관점의 운영 인사이트를 한국어로 작성하세요.\n\n"
                + "[규칙]\n"
                + "1) 언급하는 모든 항목에 반드시 정확한 수치를 괄호로 인용하세요. "
                + "예: \"팰리세이드 불만이 가장 많다(2,248건)\".\n"
                + "2) 수치는 반올림·생략·근사하지 말고 집계에 나온 값을 그대로 쓰세요. 집계에 없는 수는 만들지 마세요.\n"
                + "3) [시간 비교]의 증감률(%)을 반드시 인용하세요. 퍼센트는 직접 계산하지 말고 주어진 값을 그대로 쓰세요. "
                + "예: \"최근 7일 불만이 직전 대비 +12% 상승(59건 vs 53건)\".\n"
                + "4) 연도별·계절별 변화도 시작→끝 값으로 대비하세요.\n"
                + "5) 출력은 마크다운으로 가독성 있게 작성하세요:\n"
                + "   - 첫 줄: 가장 중요한 신호를 한 문장으로 요약하되 **굵게**.\n"
                + "   - 그다음 관점별 불릿 3~4개. 각 불릿은 '- **라벨**: 내용(수치)' 형식. 라벨 예: 비용/추세/계절/리스크.\n"
                + "   - 마지막 불릿: '- **권고**: 구체적 조치'.\n"
                + "   - 각 불릿은 1문장으로 짧게. 수치·증감률(%)은 괄호로. (수요는 프록시·예상비용은 우선순위용)\n\n"
                + (deltas.isEmpty() ? "" : "[시간 비교]\n" + deltas + "\n\n")
                + "[집계]\n" + stats + "\n\n인사이트:";
        try {
            return ollama.ask(prompt, llmModel, focus + " 인사이트");
        } catch (Throwable t) {
            log.warn("[analytics] 인사이트 생성 실패({}, {}): {}", lv, llmModel, t.toString());
            return "## 인사이트 (자동 생성 실패)\n수치는 위 차트를 참고하세요. 더 가벼운 모델을 권장합니다.";
        }
    }

    /** 계절(봄/여름/가을/겨울)별 불만·화재 집계 — 시간 패턴 인사이트용. */
    private List<List<Object>> seasonalComplaints() {
        ensure("complaints");
        String cDate = "COALESCE("
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%m/%d/%Y') AS DATE), "
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%Y%m%d') AS DATE), "
            + "TRY_CAST(CAST(datecomplaintfiled AS VARCHAR) AS DATE))";
        return rows("SELECT CASE "
            + "WHEN month(" + cDate + ") IN (3,4,5) THEN '봄' "
            + "WHEN month(" + cDate + ") IN (6,7,8) THEN '여름' "
            + "WHEN month(" + cDate + ") IN (9,10,11) THEN '가을' "
            + "ELSE '겨울' END AS season, COUNT(*) n, "
            + "SUM(CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END) fires "
            + "FROM complaints WHERE " + cDate + " IS NOT NULL GROUP BY season ORDER BY n DESC");
    }

    /** 연도별 [year,n] 중 최근 6개만 서술용 문자열로. */
    private String fmtYears(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, rows.size() - 6); i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            if (r.size() < 2) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(r.get(0)).append(" ").append(r.get(1));
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }

    /** 계절 [season,n,fires] 서술용. */
    private String fmtSeason(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        for (List<Object> r : rows) {
            if (r.size() < 2) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(r.get(0)).append(" 불만").append(r.get(1));
            if (r.size() >= 3) sb.append("(화재").append(r.get(2)).append(")");
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }

    /** 안전 핫스팟 [model,fires,injuries,crashes] top5 서술용. */
    private String fmtSafety(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (List<Object> r : rows) {
            if (r.size() < 4) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.get(0)).append("(화재").append(r.get(1)).append("·부상").append(r.get(2)).append("·사고").append(r.get(3)).append(")");
            if (++i >= 5) break;
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }

    /** 부품 수요 [part,comp,demand,price,cost] top5 서술용(예상비용). */
    private String fmtParts(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (List<Object> r : rows) {
            if (r.size() < 5) continue;
            long cost = 0;
            try { cost = Math.round(Double.parseDouble(String.valueOf(r.get(4)))); } catch (Exception ignore) {}
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.get(0)).append("(약 ").append(String.format("%,d", cost)).append("원)");
            if (++i >= 5) break;
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }

    /** 퍼센트 증감(now vs prev) — LLM 대신 결정적으로 계산. */
    private static String pct(long now, long prev) {
        if (prev == 0) return now == 0 ? "0%" : "신규";
        return String.format("%+.0f%%", (now - prev) * 100.0 / prev);
    }

    private static long parseLong(Object o) {
        try { return Long.parseLong(String.valueOf(o).split("\\.")[0]); } catch (Exception e) { return 0; }
    }

    /** 주간(WoW)·연간(YoY) 증감 사실 — 인사이트에 "직전 대비 +X%"로 쓰이게 Java에서 미리 계산. */
    private String deltaFacts() {
        ensure("complaints");
        String cDate = "COALESCE("
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%m/%d/%Y') AS DATE), "
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%Y%m%d') AS DATE), "
            + "TRY_CAST(CAST(datecomplaintfiled AS VARCHAR) AS DATE))";
        List<List<Object>> mx = rows("SELECT CAST(max(" + cDate + ") AS VARCHAR) FROM complaints");
        if (mx.isEmpty() || mx.get(0).isEmpty() || mx.get(0).get(0) == null) return "";
        String to = String.valueOf(mx.get(0).get(0));
        long tw = scalar("SELECT COUNT(*) FROM complaints WHERE " + cDate
            + " BETWEEN CAST('" + to + "' AS DATE) - INTERVAL 6 DAY AND CAST('" + to + "' AS DATE)");
        long lw = scalar("SELECT COUNT(*) FROM complaints WHERE " + cDate
            + " BETWEEN CAST('" + to + "' AS DATE) - INTERVAL 13 DAY AND CAST('" + to + "' AS DATE) - INTERVAL 7 DAY");
        StringBuilder sb = new StringBuilder();
        sb.append("주간(WoW): 최근 7일 불만 ").append(tw).append("건 vs 직전 7일 ").append(lw)
          .append("건 → ").append(pct(tw, lw));
        List<List<Object>> yr = rows("SELECT year(" + cDate + ") y, COUNT(*) n FROM complaints WHERE "
            + cDate + " IS NOT NULL GROUP BY y ORDER BY y");
        if (yr.size() >= 2) {
            List<Object> a = yr.get(yr.size() - 2), b = yr.get(yr.size() - 1);
            long an = parseLong(a.get(1)), bn = parseLong(b.get(1));
            sb.append("\n연간(YoY): ").append(a.get(0)).append("년 ").append(an).append("건 → ")
              .append(b.get(0)).append("년 ").append(bn).append("건 → ").append(pct(bn, an))
              .append(" (최신 연도는 진행중일 수 있음)");
        }
        return sb.toString();
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
        // 피드: id를 첫 컬럼으로(클릭→상세). 같은 캠페인이 차종/연식마다 중복 들어와 캠페인+차종 단위 1행만.
        out.put("recentRecalls", rows(
            "SELECT nhtsacampaignnumber, reportreceiveddate, model, component, substr(summary,1,160) "
            + "FROM recalls "
            + "QUALIFY row_number() OVER (PARTITION BY nhtsacampaignnumber, model ORDER BY reportreceiveddate DESC) = 1 "
            + "ORDER BY reportreceiveddate DESC NULLS LAST LIMIT 6"));
        out.put("recentComplaints", rows(
            "SELECT odinumber, datecomplaintfiled, model, components, substr(summary,1,160) "
            + "FROM complaints "
            + "QUALIFY row_number() OVER (PARTITION BY odinumber ORDER BY datecomplaintfiled DESC) = 1 "
            + "ORDER BY datecomplaintfiled DESC NULLS LAST LIMIT 6"));
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
     *  중요도 = 사망×10000 + 부상×10 + 화재×5 + 사고×3 (+최신성) → 심각한 케이스가 위로(A/S 접수 트리아지).
     *  에러를 삼키지 않고 던진다 → 컨트롤러가 응답에 원인을 담아 화면에서 바로 보이게. */
    public List<List<Object>> vehiclesByModel(String model) throws Exception {
        ensure("complaints");
        String m = (model == null ? "" : model).trim().toUpperCase();   // ? 바인딩(SQL 인젝션 차단)
        String fireT  = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String crashT = "CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj    = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea    = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        // 최신성 가중: 접수일이 최근일수록 가산(반감 180일, W=12). YYYYMMDD/ISO 둘 다 파싱, 실패 시 0.
        String recDate = "COALESCE("
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%m/%d/%Y') AS DATE), "  // 실제 NHTSA 포맷 MM/DD/YYYY
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%Y%m%d') AS DATE), "      // YYYYMMDD 폴백
            + "TRY_CAST(CAST(datecomplaintfiled AS VARCHAR) AS DATE))";                          // ISO 폴백
        String rec     = "COALESCE(ROUND(12 * exp(-date_diff('day', " + recDate + ", current_date) / 180.0)), 0)";
        return tabular.runSelect(
            "SELECT odinumber, datecomplaintfiled, components, modelyear, substr(summary,1,2000), "
            + "(" + dea + "*10000 + " + inj + "*10 + " + fireT + "*5 + " + crashT + "*3 + " + rec + ") AS priority, "
            + fireT + " AS fire, " + crashT + " AS crash, " + inj + " AS injuries, " + dea + " AS deaths "
            + "FROM complaints WHERE upper(model)=? "
            + "ORDER BY priority DESC, datecomplaintfiled DESC NULLS LAST LIMIT 20", m).rows();
    }

    /** 케이스 우선순위 트리아지(전 차종) — 필터(차종/부위) + 우선순위 정렬 + 서버 페이지네이션 + 해결 제외.
     *  반환: { cases: [[접수번호,날짜,차종,부위,연식,요약,우선순위,화재,사고,부상,사망]...], total }
     *  - 해결(resolved_case)된 접수번호는 NOT IN으로 제외 → "해결하면 큐에서 사라짐"을 DB로 영속.
     *  - OFFSET/LIMIT로 1·2·3… 페이지 분할. ORDER에 odinumber 타이브레이커로 페이지 경계 안정화. */
    public Map<String, Object> cases(String model, String component, int offset, int limit) {
        return cases(model, component, offset, limit, "priority");
    }

    /** sort: "priority"(전체 심각도순) | "model"(차종별 그룹 후 심각도순). */
    public Map<String, Object> cases(String model, String component, int offset, int limit, String sort) {
        ensure("complaints");
        String fireT  = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String crashT = "CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj    = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea    = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        // 최신성 가중: 접수일이 최근일수록 가산(반감 180일, W=12). YYYYMMDD/ISO 둘 다 파싱, 실패 시 0.
        String recDate = "COALESCE("
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%m/%d/%Y') AS DATE), "  // 실제 NHTSA 포맷 MM/DD/YYYY
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%Y%m%d') AS DATE), "      // YYYYMMDD 폴백
            + "TRY_CAST(CAST(datecomplaintfiled AS VARCHAR) AS DATE))";                          // ISO 폴백
        String rec     = "COALESCE(ROUND(12 * exp(-date_diff('day', " + recDate + ", current_date) / 180.0)), 0)";
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();   // 사용자 입력은 전부 ? 바인딩(SQL 인젝션 차단)
        if (model != null && !model.isBlank()) {
            where.append(" AND upper(model)=?");
            args.add(model.trim().toUpperCase());
        }
        if (component != null && !component.isBlank()) {
            where.append(" AND upper(components) LIKE ?");
            args.add("%" + component.trim().toUpperCase() + "%");
        }
        // 완료(DONE) 케이스만 제외 (DB 영속) — 진단중/수리중은 큐에 상태칩으로 남는다
        List<String> resolved = resolvedRepo.findAll().stream()
            .filter(rc -> rc.getStatus() == null || "DONE".equals(rc.getStatus()))
            .map(com.miniwatson.cases.ResolvedCase::getCaseNumber)
            .filter(s -> s != null && !s.isBlank()).toList();
        if (!resolved.isEmpty()) {
            where.append(" AND odinumber NOT IN (")
                 .append("?,".repeat(resolved.size() - 1)).append("?)");
            args.addAll(resolved);
        }
        long total = scalar("SELECT COUNT(*) FROM complaints " + where, args.toArray());
        int lim = limit <= 0 ? 50 : Math.min(limit, 200);
        int off = Math.max(0, offset);
        // 정렬: priority=중요도순(심각도), date=입고순(접수일 최신), model=차종그룹 후 중요도.
        //   날짜 정렬은 파싱된 DATE(recDate)로 — 원문 MM/DD/YYYY 문자열은 연도경계서 오정렬돼서.
        String orderBy = switch (sort == null ? "priority" : sort.toLowerCase()) {
            case "model" -> "ORDER BY model, priority DESC, " + recDate + " DESC NULLS LAST, odinumber";
            case "date"  -> "ORDER BY " + recDate + " DESC NULLS LAST, priority DESC, odinumber";
            default       -> "ORDER BY priority DESC, " + recDate + " DESC NULLS LAST, odinumber";
        };
        List<List<Object>> rows = rows(
            "SELECT odinumber, datecomplaintfiled, model, components, modelyear, substr(summary,1,2000), "
            + "(" + dea + "*10000 + " + inj + "*10 + " + fireT + "*5 + " + crashT + "*3 + " + rec + ") AS priority, "
            + fireT + " AS fire, " + crashT + " AS crash, " + inj + " AS injuries, " + dea + " AS deaths "
            + "FROM complaints " + where + " "
            + orderBy + " LIMIT " + lim + " OFFSET " + off, args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", rows);
        out.put("total", total);
        out.put("offset", off);
        out.put("limit", lim);
        return out;
    }

    /** 케이스 해결 처리(영속). 이미 있으면 무시(멱등). */
    public void resolveCase(String caseNumber, String note) {
        if (caseNumber == null || caseNumber.isBlank()) return;
        if (resolvedRepo.existsByCaseNumber(caseNumber)) return;
        com.miniwatson.cases.ResolvedCase rc = new com.miniwatson.cases.ResolvedCase();
        rc.setCaseNumber(caseNumber);
        rc.setNote(note);
        resolvedRepo.save(rc);
    }

    /** 해결 처리 취소(되돌리기). */
    public void unresolveCase(String caseNumber) {
        resolvedRepo.findByCaseNumber(caseNumber).ifPresent(resolvedRepo::delete);
    }

    /** 해결(완료)된 케이스 목록 (접수번호·시각·메모). 진단중/수리중은 제외. */
    public List<Map<String, Object>> resolvedCases() {
        return resolvedRepo.findAll().stream()
            .filter(rc -> rc.getStatus() == null || "DONE".equals(rc.getStatus()))
            .map(rc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseNumber", rc.getCaseNumber());
            m.put("note", rc.getNote() == null ? "" : rc.getNote());
            m.put("resolvedAt", rc.getResolvedAt() == null ? "" : rc.getResolvedAt().toString());
            return m;
        }).toList();
    }

    private static final java.util.Set<String> CASE_STATUSES = java.util.Set.of("DIAGNOSING", "REPAIRING", "DONE");

    /** 케이스 워크플로 상태 설정(업서트). RECEIVED(기본)로 되돌리면 행 삭제. */
    public void setCaseStatus(String caseNumber, String status, String note) {
        if (caseNumber == null || caseNumber.isBlank()) return;
        String s = status == null ? "" : status.trim().toUpperCase();
        if (s.isEmpty() || "RECEIVED".equals(s)) { unresolveCase(caseNumber); return; }
        if (!CASE_STATUSES.contains(s)) throw new IllegalArgumentException("unknown status: " + status);
        com.miniwatson.cases.ResolvedCase rc = resolvedRepo.findByCaseNumber(caseNumber)
            .orElseGet(com.miniwatson.cases.ResolvedCase::new);
        rc.setCaseNumber(caseNumber);
        rc.setStatus(s);
        if (note != null && !note.isBlank()) rc.setNote(note);
        rc.setResolvedAt(java.time.LocalDateTime.now());
        resolvedRepo.save(rc);
    }

    /** 전체 케이스 상태 맵 — [{caseNumber, status, updatedAt}] (행 없음 = RECEIVED). */
    public List<Map<String, Object>> caseStatuses() {
        return resolvedRepo.findAll().stream().map(rc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseNumber", rc.getCaseNumber());
            m.put("status", rc.getStatus() == null ? "DONE" : rc.getStatus());
            m.put("updatedAt", rc.getResolvedAt() == null ? "" : rc.getResolvedAt().toString());
            return m;
        }).toList();
    }

    private static final java.util.Set<String> TBL = java.util.Set.of("recalls", "complaints");

    /** 시계열 추세 — 연/월/일 그래뉼래리티 + 차종 필터. [버킷, 건수] */
    public List<List<Object>> trend(String table, String by, String model) {
        if (!TBL.contains(table)) return new ArrayList<>();
        ensure(table);
        String dc = "recalls".equals(table) ? "reportreceiveddate" : "datecomplaintfiled";
        // 날짜 robust 파싱: 불만 MM/DD/YYYY + 리콜 ISO(YYYY-MM-DD) 모두 DATE로. 실패행은 NULL→제외.
        String d = "COALESCE("
            + "CAST(try_strptime(CAST(" + dc + " AS VARCHAR),'%m/%d/%Y') AS DATE), "
            + "TRY_CAST(CAST(" + dc + " AS VARCHAR) AS DATE))";
        String bucket = switch (by == null ? "year" : by) {
            case "day"   -> "strftime(" + d + ", '%Y-%m-%d')";
            case "week"  -> "strftime(date_trunc('week', " + d + "), '%Y-%m-%d')";   // 그 주 월요일 기준
            case "month" -> "strftime(" + d + ", '%Y-%m')";
            default       -> "strftime(" + d + ", '%Y')";
        };
        StringBuilder w = new StringBuilder("WHERE " + d + " IS NOT NULL");
        List<Object> args = new ArrayList<>();   // 사용자 입력은 ? 바인딩(SQL 인젝션 차단)
        if (model != null && !model.isBlank()) {
            w.append(" AND upper(model)=?");
            args.add(model.trim().toUpperCase());
        }
        // 최근 120개 버킷만(DESC LIMIT) 가져온 뒤 표시용으로 오름차순 재정렬 — 일별도 최근 구간이 보이게.
        return rows("SELECT bucket, n FROM (SELECT " + bucket + " AS bucket, COUNT(*) n FROM " + table + " " + w
            + " GROUP BY bucket ORDER BY bucket DESC LIMIT 120) t ORDER BY bucket", args.toArray());
    }

    /** 단일 리콜 상세 (캠페인번호) — 결함내용·위험·시정조치 포함. */
    public Map<String, Object> recall(String id) {
        ensure("recalls");
        List<List<Object>> r = rows("SELECT nhtsacampaignnumber, reportreceiveddate, model, modelyear, component, "
            + "summary, consequence, remedy, cast(parkit AS varchar), cast(parkoutside AS varchar) "
            + "FROM recalls WHERE nhtsacampaignnumber=? LIMIT 1", id == null ? "" : id.trim());
        if (r.isEmpty()) return new LinkedHashMap<>();
        List<Object> row = r.get(0);
        String[] keys = {"campaign", "date", "model", "year", "component", "summary", "consequence", "remedy", "parkIt", "parkOutside"};
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.length && i < row.size(); i++) m.put(keys[i], row.get(i) == null ? "" : row.get(i));
        return m;
    }

    /** 단일 케이스(접수번호) — cases()와 동일 11열 형태(상세 진단 열기용). */
    public List<List<Object>> caseById(String id) {
        ensure("complaints");
        String cid = (id == null ? "" : id).trim();   // ? 바인딩(SQL 인젝션 차단)
        String fireT = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String crashT = "CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        // 최신성 가중(반감 180일, W=12). YYYYMMDD/ISO/MM-DD-YYYY 모두 파싱, 실패 시 0.
        String recDate = "COALESCE("
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%m/%d/%Y') AS DATE), "
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%Y%m%d') AS DATE), "
            + "TRY_CAST(CAST(datecomplaintfiled AS VARCHAR) AS DATE))";
        String rec = "COALESCE(ROUND(12 * exp(-date_diff('day', " + recDate + ", current_date) / 180.0)), 0)";
        return rows("SELECT odinumber, datecomplaintfiled, model, components, modelyear, substr(summary,1,2000), "
            + "(" + dea + "*10000 + " + inj + "*10 + " + fireT + "*5 + " + crashT + "*3 + " + rec + ") AS priority, "
            + fireT + ", " + crashT + ", " + inj + ", " + dea + " "
            + "FROM complaints WHERE odinumber=? LIMIT 1", cid);
    }

    // ── 점검 체크리스트: 공통(표준 성능·상태점검) + 차종별 추가(리콜·불만 부위 → 점검 항목) ──
    /** 성능·상태점검기록부 표준 공통 항목(차종 무관). [장치, 점검 포인트] */
    private static final List<List<Object>> COMMON = List.of(
        List.of("원동기(엔진)", "작동상태·오일 누유·경고등"),
        List.of("변속기", "변속 충격·누유"),
        List.of("동력전달", "클러치·드라이브샤프트·등속조인트"),
        List.of("조향", "유격·쏠림·작동"),
        List.of("제동", "패드·디스크·제동력·누유"),
        List.of("전기", "배터리·등화·배선"),
        List.of("연료", "누유·연료계통"),
        List.of("외판·골격", "부식·판금·사고 흔적"));

    /** CSV 없을 때 폴백 매핑(결함부위 부분일치 → 점검항목). CSV: data/vehicle/inspection_map.csv */
    private static final LinkedHashMap<String, String> DEFAULT_MAP = new LinkedHashMap<>();
    static {
        DEFAULT_MAP.put("SEAT BELT", "안전벨트·프리텐셔너 체결/작동 점검");
        DEFAULT_MAP.put("AIR BAG", "에어백 경고등·전개 시스템 점검");
        DEFAULT_MAP.put("FORWARD COLLISION", "전방충돌방지보조(FCA) 작동 점검");
        DEFAULT_MAP.put("LANE", "차로이탈방지보조(LKA) 작동 점검");
        DEFAULT_MAP.put("BACK OVER", "후방 카메라·주차센서 점검");
        DEFAULT_MAP.put("VEHICLE SPEED CONTROL", "정속주행·속도제어 점검");
        DEFAULT_MAP.put("ELECTRICAL", "배선·배터리·퓨즈 점검");
        DEFAULT_MAP.put("ENGINE", "원동기(엔진) 정밀 점검");
        DEFAULT_MAP.put("POWER TRAIN", "변속기·동력전달 정밀 점검");
        DEFAULT_MAP.put("TRANSMISSION", "변속기 정밀 점검");
        DEFAULT_MAP.put("BRAKE", "제동장치(브레이크) 점검");
        DEFAULT_MAP.put("FUEL", "연료계통 점검");
        DEFAULT_MAP.put("STEERING", "조향장치 점검");
        DEFAULT_MAP.put("SUSPENSION", "현가장치(서스펜션) 점검");
        DEFAULT_MAP.put("STRUCTURE", "차체 골격·구조 점검");
        DEFAULT_MAP.put("VISIBILITY", "와이퍼·시야 점검");
        DEFAULT_MAP.put("TRAILER HITCH", "트레일러 히치 점검");
        DEFAULT_MAP.put("SEATS", "시트 고정·조절 점검");
    }
    private volatile LinkedHashMap<String, String> inspectMapCache;

    private LinkedHashMap<String, String> inspectMap() {
        if (inspectMapCache != null) return inspectMapCache;
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        try {
            java.nio.file.Path p = java.nio.file.Path.of("data/vehicle/inspection_map.csv");
            if (java.nio.file.Files.exists(p)) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#") || s.toLowerCase().startsWith("keyword")) continue;
                    int i = s.indexOf(',');
                    if (i < 0) continue;
                    m.put(s.substring(0, i).trim().toUpperCase(), s.substring(i + 1).trim());
                }
            }
        } catch (Exception e) { log.warn("[checklist] inspection_map 로드 실패: {}", e.getMessage()); }
        if (m.isEmpty()) m.putAll(DEFAULT_MAP);
        inspectMapCache = m;
        return m;
    }

    private String mapItem(String compUpper, LinkedHashMap<String, String> map) {
        for (var e : map.entrySet()) if (compUpper.contains(e.getKey())) return e.getValue();
        String head = compUpper.split(":")[0].trim();   // 매핑 없으면 대표 부위명만
        return "기타 점검: " + head;
    }

    /** 점검 체크리스트.
     *  component 지정 → 건별(그 건 부위만 매핑). 미지정 → 차종 집계(상위 부위 빈도순).
     *  공통 항목은 항상 포함. */
    public Map<String, Object> checklist(String model, String component) {
        LinkedHashMap<String, String> map = inspectMap();
        if (component != null && !component.isBlank()) {
            // 건별: 이 건의 부위만 → 점검 항목 (콤마/세미콜론 분리, 중복 제거)
            java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>();
            for (String part : component.split("[,;]")) {
                if (part.isBlank()) continue;
                items.add(mapItem(part.trim().toUpperCase(), map));
            }
            List<List<Object>> additional = new ArrayList<>();
            for (String it : items) additional.add(List.of(it, 0, component));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("model", model); out.put("component", component);
            out.put("common", COMMON); out.put("additional", additional);
            return out;
        }
        ensure("complaints");
        String m = (model == null ? "" : model).trim().toUpperCase();   // ? 바인딩(SQL 인젝션 차단)
        List<List<Object>> topComp = rows(
            "SELECT components, COUNT(*) n FROM complaints WHERE upper(model)=? "
            + "GROUP BY components ORDER BY n DESC LIMIT 25", m);
        LinkedHashMap<String, Integer> agg = new LinkedHashMap<>();
        LinkedHashMap<String, String> sample = new LinkedHashMap<>();
        for (List<Object> r : topComp) {
            if (r.size() < 2 || r.get(0) == null) continue;
            String comp = r.get(0).toString();
            int n = (int) Long.parseLong(r.get(1).toString().split("\\.")[0]);
            String item = mapItem(comp.toUpperCase(), map);
            agg.merge(item, n, Integer::sum);
            sample.putIfAbsent(item, comp);
        }
        List<List<Object>> additional = new ArrayList<>();
        agg.entrySet().stream()
           .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
           .forEach(e -> additional.add(List.of(e.getKey(), e.getValue(), sample.getOrDefault(e.getKey(), ""))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("common", COMMON);
        out.put("additional", additional);
        return out;
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
        // 1회만 등록(읽기 경로가 매 요청 재등록하지 않게). 데이터 변경 시 refresh()로 갱신.
        try { tabular.registerCsvOnce(table, path); } catch (Exception e) { log.warn("[analytics] {} 로드 실패: {}", table, e.getMessage()); }
    }

    /** 데이터(CSV) 변경 시 호출 — 등록 캐시를 비우고 핵심 테이블을 다시 등록(최신 파일 반영). */
    public void refresh() {
        tabular.invalidateRegistrations();
        ensure("recalls"); ensure("complaints"); ensure("parts");
    }

    /** 차종·연식 핫스팟 — "이 차·연식은 어디가 자주 고장나나"(결정적 SQL). 통합 질의의 정형 신호.
     *  연식(year) 없으면 차종 전체. modelyear는 문자열일 수 있어 VARCHAR 비교. */
    public Map<String, Object> modelYearHotspots(String model, Integer year) {
        ensure("recalls"); ensure("complaints");
        if (model == null || model.isBlank()) return Map.of();
        String m = model.trim().toUpperCase();
        // 사용자 입력(model·year)은 ? 바인딩 — 문자열 조립 금지(SQL 인젝션). 연식 없으면 조건 생략.
        String cw = "WHERE upper(model)=?" + (year == null ? "" : " AND CAST(modelyear AS VARCHAR)=?");
        Object[] p = year == null ? new Object[]{m} : new Object[]{m, String.valueOf(year)};
        String fireT = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String crashT = "CASE WHEN lower(cast(crash AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", m);
        out.put("year", year);
        out.put("complaints", scalar("SELECT COUNT(*) FROM complaints " + cw, p));
        out.put("complaintTop", rows("SELECT components, COUNT(*) n FROM complaints " + cw
            + " GROUP BY components ORDER BY n DESC LIMIT 6", p));
        out.put("fires", scalar("SELECT COALESCE(SUM(" + fireT + "),0) FROM complaints " + cw, p));
        out.put("crashes", scalar("SELECT COALESCE(SUM(" + crashT + "),0) FROM complaints " + cw, p));
        out.put("injuries", scalar("SELECT COALESCE(SUM(" + inj + "),0) FROM complaints " + cw, p));
        out.put("deaths", scalar("SELECT COALESCE(SUM(" + dea + "),0) FROM complaints " + cw, p));
        out.put("recalls", scalar("SELECT COUNT(*) FROM recalls " + cw, p));
        out.put("recallTop", rows("SELECT component, COUNT(*) n FROM recalls " + cw
            + " GROUP BY component ORDER BY n DESC LIMIT 6", p));
        return out;
    }

    /** 주간 품질 집계(결정적 SQL) — 데이터 최신일 기준 최근 7일. LLM 브리핑의 사실 원천.
     *  NHTSA 데이터는 과거분이라 "오늘" 기준이 아니라 max(접수일) 기준 주간으로 잡는다. */
    public Map<String, Object> weeklyStats() {
        ensure("recalls"); ensure("complaints");
        String cDate = "COALESCE("
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%m/%d/%Y') AS DATE), "
            + "CAST(try_strptime(CAST(datecomplaintfiled AS VARCHAR),'%Y%m%d') AS DATE), "
            + "TRY_CAST(CAST(datecomplaintfiled AS VARCHAR) AS DATE))";
        String fireT = "CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END";
        String inj = "COALESCE(TRY_CAST(numberofinjuries AS INTEGER),0)";
        String dea = "COALESCE(TRY_CAST(numberofdeaths AS INTEGER),0)";
        List<List<Object>> mx = rows("SELECT CAST(max(" + cDate + ") AS VARCHAR) FROM complaints");
        String to = mx.isEmpty() || mx.get(0).get(0) == null ? null : String.valueOf(mx.get(0).get(0));
        if (to == null) return Map.of();
        String win = cDate + " BETWEEN CAST('" + to + "' AS DATE) - INTERVAL 6 DAY AND CAST('" + to + "' AS DATE)";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("to", to);
        out.put("from", String.valueOf(rows("SELECT CAST(CAST('" + to + "' AS DATE) - INTERVAL 6 DAY AS VARCHAR)").get(0).get(0)));
        out.put("complaints", scalar("SELECT COUNT(*) FROM complaints WHERE " + win));
        out.put("deaths", scalar("SELECT COALESCE(SUM(" + dea + "),0) FROM complaints WHERE " + win));
        out.put("injuries", scalar("SELECT COALESCE(SUM(" + inj + "),0) FROM complaints WHERE " + win));
        out.put("fires", scalar("SELECT COALESCE(SUM(" + fireT + "),0) FROM complaints WHERE " + win));
        out.put("topModels", rows("SELECT model, COUNT(*) n FROM complaints WHERE " + win
            + " GROUP BY model ORDER BY n DESC LIMIT 3"));
        out.put("topComponents", rows("SELECT components, COUNT(*) n FROM complaints WHERE " + win
            + " GROUP BY components ORDER BY n DESC LIMIT 3"));
        out.put("worstCases", rows("SELECT odinumber, model, components, (" + dea + "*10000 + " + inj + "*10 + " + fireT + "*5) AS prio "
            + "FROM complaints WHERE " + win + " ORDER BY prio DESC LIMIT 3"));
        // 리콜은 접수일 컬럼이 달라 별도 윈도
        out.put("recalls", scalar("SELECT COUNT(*) FROM recalls WHERE reportreceiveddate >= CAST('" + to + "' AS DATE) - INTERVAL 6 DAY"));
        return out;
    }

    /** 리콜 대상 조회 — "제 차(차종·연식) 리콜 대상인가요?" 고객 응대 첫 질문.
     *  연식 없으면 차종 전체. parkIt(주차 권고=화재위험) 우선 정렬. */
    public List<Map<String, Object>> recallCheck(String model, Integer year) {
        ensure("recalls");
        if (model == null || model.isBlank()) return List.of();
        // 사용자 입력은 ? 바인딩(SQL 인젝션 차단)
        String where = "WHERE upper(model)=?" + (year == null ? "" : " AND TRY_CAST(modelyear AS INTEGER)=?");
        Object[] p = year == null ? new Object[]{model.trim().toUpperCase()} : new Object[]{model.trim().toUpperCase(), year};
        List<List<Object>> r = rows("SELECT nhtsacampaignnumber, reportreceiveddate, modelyear, component, "
            + "substr(summary,1,300), cast(parkit AS varchar) FROM recalls " + where
            + " ORDER BY (CASE WHEN lower(cast(parkit AS varchar))='true' THEN 0 ELSE 1 END), reportreceiveddate DESC LIMIT 50", p);
        return r.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaign", String.valueOf(row.get(0)));
            m.put("date", String.valueOf(row.get(1)));
            m.put("year", String.valueOf(row.get(2)));
            m.put("component", String.valueOf(row.get(3)));
            m.put("summary", String.valueOf(row.get(4)));
            m.put("parkIt", "true".equalsIgnoreCase(String.valueOf(row.get(5))));
            return m;
        }).toList();
    }

    /** 유사 케이스 검색 — "과거에 같은 증상 접수 있었나?" top-k.
     *  ponytail: 토큰 코사인 근사(같은 부위 후보 → 요약 토큰 중복도). 전 불만 임베딩 적재가 없어 1차는
     *  어휘 기반 — 정밀도 올릴 땐 complaints를 EmbeddingService로 적재 후 벡터 코사인으로 교체. */
    public List<Map<String, Object>> similarCases(String caseNumber, int k) {
        List<List<Object>> target = caseById(caseNumber);
        if (target.isEmpty()) return List.of();
        List<Object> t = target.get(0);
        String comp = String.valueOf(t.get(3));
        java.util.Set<String> tTok = simTokens(String.valueOf(t.get(5)));
        if (tTok.isEmpty()) return List.of();
        String sel = "SELECT odinumber, datecomplaintfiled, model, components, modelyear, substr(summary,1,800) FROM complaints "
            + "WHERE odinumber<>?";   // 사용자 입력은 ? 바인딩(SQL 인젝션 차단)
        // 후보: 같은 부위 우선(정밀) → 부족하면 전체로 확장(회수). 비용 상한 2000건.
        List<List<Object>> cands = rows(sel + " AND upper(components)=upper(?) LIMIT 2000", caseNumber, comp);
        if (cands.size() < 50) cands = rows(sel + " LIMIT 2000", caseNumber);
        int kk = k <= 0 ? 5 : Math.min(k, 20);
        return cands.stream()
            .map(r -> {
                java.util.Set<String> ct = simTokens(String.valueOf(r.get(5)));
                if (ct.isEmpty()) return Map.entry(r, 0.0);
                long inter = ct.stream().filter(tTok::contains).count();
                return Map.entry(r, inter / Math.sqrt((double) tTok.size() * ct.size()));  // 코사인 근사
            })
            .filter(e -> e.getValue() > 0.05)
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(kk)
            .map(e -> {
                List<Object> r = e.getKey();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caseNumber", String.valueOf(r.get(0)));
                m.put("date", String.valueOf(r.get(1)));
                m.put("model", String.valueOf(r.get(2)));
                m.put("component", String.valueOf(r.get(3)));
                m.put("year", String.valueOf(r.get(4)));
                m.put("snippet", String.valueOf(r.get(5)).substring(0, Math.min(180, String.valueOf(r.get(5)).length())));
                m.put("score", Math.round(e.getValue() * 100));
                return m;
            }).toList();
    }

    private static final java.util.Set<String> SIM_STOP = java.util.Set.of(
        "THE", "AND", "WAS", "WERE", "THAT", "THIS", "WITH", "HAVE", "HAS", "HAD", "FROM", "WHEN", "WHILE",
        "THERE", "BEEN", "WOULD", "COULD", "ABOUT", "AFTER", "BEFORE", "VEHICLE", "CONTACT", "STATED",
        "HYUNDAI", "MILES", "DEALER", "MANUFACTURER", "FAILURE", "ISSUE", "PROBLEM", "TIME", "ALSO", "ONLY", "INTO");

    /** 요약 → 의미 토큰(4자+ 영숫자, 불용어 제외). */
    private java.util.Set<String> simTokens(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null) return out;
        for (String w : s.toUpperCase().split("[^A-Z0-9]+"))
            if (w.length() >= 4 && !SIM_STOP.contains(w)) out.add(w);
        return out;
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

    /** 파라미터 바인딩 버전 — 사용자 입력(차종·부위·접수번호 등)은 반드시 이쪽으로(SQL 인젝션 차단). */
    private long scalar(String sql, Object... params) {
        try {
            var r = tabular.runSelect(sql, params);
            if (!r.rows().isEmpty() && !r.rows().get(0).isEmpty()) {
                Object v = r.rows().get(0).get(0);
                return v == null ? 0 : Long.parseLong(v.toString().split("\\.")[0]);
            }
        } catch (Exception e) { log.warn("[analytics] scalar 실패({}): {}", sql, e.getMessage()); }
        return 0;
    }

    private List<List<Object>> rows(String sql, Object... params) {
        try { return tabular.runSelect(sql, params).rows(); }
        catch (Exception e) { log.warn("[analytics] rows 실패: {}", e.getMessage()); return new ArrayList<>(); }
    }
}
