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

    /** 케이스 우선순위 트리아지(전 차종) — 필터(차종/부위) + 심각도 우선순위 정렬. */
    @GetMapping("/cases")
    public Map<String, Object> cases(@RequestParam(required = false) String model,
                                     @RequestParam(required = false) String component) {
        try {
            return Map.of("cases", analytics.cases(model, component));
        } catch (Throwable t) {
            return Map.of("cases", java.util.List.of(), "error", t.toString());
        }
    }
}
