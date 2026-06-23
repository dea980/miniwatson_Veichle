package com.miniwatson.controller;

import com.miniwatson.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 분석 대시보드 API — 차량 데이터 플릿 집계 + LLM 인사이트.
 * GET /api/analytics/overview?model=<llm>
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(value = "model", required = false) String model) {
        return analytics.overview(model);
    }

    /** LLM 인사이트만 별도 (느려서 집계와 분리). */
    @GetMapping("/insight")
    public Map<String, Object> insight(@RequestParam(value = "model", required = false) String model) {
        try { return Map.of("insight", analytics.insightText(model)); }
        catch (Throwable t) { return Map.of("insight", "(인사이트 생성 실패: " + t + ")"); }
    }

    /** 홈 대시보드용 경량 요약 (총계 + 최근 리콜/불만 피드, LLM 미사용). */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return analytics.summary();
    }

    /** 드릴다운: 특정 차종의 개별 차량 기록(불만). 에러 시 응답에 원인을 담아 진단 쉽게. */
    @GetMapping("/vehicles")
    public Map<String, Object> vehicles(@RequestParam String model) {
        try {
            return Map.of("model", model, "vehicles", analytics.vehiclesByModel(model));
        } catch (Throwable t) {
            return Map.of("model", model, "vehicles", java.util.List.of(), "error", t.toString());
        }
    }

    /** 데이터(CSV) 변경 후 재등록 — 등록 1회 정책에서 최신 파일을 반영한다. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        analytics.refresh();
        return Map.of("refreshed", true);
    }

    /** 단일 리콜 상세 (캠페인번호). */
    @GetMapping("/recall")
    public Map<String, Object> recall(@RequestParam String id) {
        try { return analytics.recall(id); }
        catch (Throwable t) { return Map.of("error", t.toString()); }
    }

    /** 단일 케이스(접수번호) 상세 — cases와 동일 형태. */
    @GetMapping("/case")
    public Map<String, Object> caseById(@RequestParam String id) {
        try {
            var r = analytics.caseById(id);
            return Map.of("case", r.isEmpty() ? java.util.List.of() : r.get(0));
        } catch (Throwable t) { return Map.of("case", java.util.List.of(), "error", t.toString()); }
    }

    /** 시계열 추세 — table=recalls|complaints, by=year|month|day, model(선택). */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam String table,
                                     @RequestParam(defaultValue = "year") String by,
                                     @RequestParam(required = false) String model) {
        try { return Map.of("trend", analytics.trend(table, by, model)); }
        catch (Throwable t) { return Map.of("trend", java.util.List.of(), "error", t.toString()); }
    }

    /** 점검 체크리스트: 공통(표준) + 차종별 추가(리콜·불만 부위 → 점검항목). */
    @GetMapping("/checklist")
    public Map<String, Object> checklist(@RequestParam String model,
                                         @RequestParam(required = false) String component) {
        try { return analytics.checklist(model, component); }
        catch (Throwable t) { return Map.of("model", model, "common", java.util.List.of(), "additional", java.util.List.of(), "error", t.toString()); }
    }

    /** 케이스 우선순위 트리아지(전 차종) — 필터(차종/부위) + 심각도 정렬 + 페이지네이션 + 해결제외.
     *  반환: { cases, total, offset, limit }. */
    @GetMapping("/cases")
    public Map<String, Object> cases(@RequestParam(required = false) String model,
                                     @RequestParam(required = false) String component,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "priority") String sort) {
        try {
            return analytics.cases(model, component, offset, limit, sort);
        } catch (Throwable t) {
            return Map.of("cases", java.util.List.of(), "total", 0, "error", t.toString());
        }
    }

    /** 케이스 해결 처리(영속) — 트리아지/홈 큐에서 사라짐. body: {id, note?}. */
    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestBody Map<String, String> body) {
        try {
            analytics.resolveCase(body.get("id"), body.get("note"));
            return Map.of("resolved", true, "id", body.getOrDefault("id", ""));
        } catch (Throwable t) { return Map.of("resolved", false, "error", t.toString()); }
    }

    /** 해결 처리 취소(되돌리기). */
    @DeleteMapping("/resolve/{id}")
    public Map<String, Object> unresolve(@PathVariable String id) {
        try { analytics.unresolveCase(id); return Map.of("unresolved", true, "id", id); }
        catch (Throwable t) { return Map.of("unresolved", false, "error", t.toString()); }
    }

    /** 해결된 케이스 목록. */
    @GetMapping("/resolved")
    public Map<String, Object> resolved() {
        try { return Map.of("resolved", analytics.resolvedCases()); }
        catch (Throwable t) { return Map.of("resolved", java.util.List.of(), "error", t.toString()); }
    }
}
