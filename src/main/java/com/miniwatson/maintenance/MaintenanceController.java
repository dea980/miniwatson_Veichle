package com.miniwatson.maintenance;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 정비 스케줄 API — 캘린더에서 일정 추가·조회·상태변경·삭제.
 * 기존 JPA 데이터소스에 영속(새 DB 불필요).
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceRepository repo;
    private final com.miniwatson.service.AnalyticsService analytics;

    public MaintenanceController(MaintenanceRepository repo, com.miniwatson.service.AnalyticsService analytics) {
        this.repo = repo;
        this.analytics = analytics;
    }

    /** 전체 일정(날짜순). from/to(YYYY-MM-DD) 주면 기간 필터. */
    @GetMapping
    public List<MaintenanceSchedule> list(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            return repo.findByScheduledDateBetweenOrderByScheduledDateAsc(LocalDate.parse(from), LocalDate.parse(to));
        }
        return repo.findAllByOrderByScheduledDateAsc();
    }

    /** 일정 추가. body: {model, caseNumber?, title, scheduledDate(YYYY-MM-DD), technician?, note?} */
    @PostMapping
    public MaintenanceSchedule create(@RequestBody Map<String, String> body) {
        MaintenanceSchedule m = new MaintenanceSchedule();
        m.setModel(body.getOrDefault("model", ""));
        m.setCaseNumber(body.get("caseNumber"));
        m.setTitle(body.getOrDefault("title", "(제목 없음)"));
        String d = body.get("scheduledDate");
        m.setScheduledDate(d != null && !d.isBlank() ? LocalDate.parse(d) : LocalDate.now());
        m.setTechnician(body.get("technician"));
        m.setNote(body.get("note"));
        if (body.get("status") != null && !body.get("status").isBlank()) m.setStatus(body.get("status"));
        MaintenanceSchedule saved = repo.save(m);
        // 케이스 연결 예약 → 그 케이스는 수리중 (진단→예약→완료 루프의 가운데 고리)
        syncCase(saved.getCaseNumber(), "REPAIRING");
        return saved;
    }

    /** 상태 변경 (예정/진행/완료). 완료 시 연결된 케이스도 완료 처리(루프 닫힘). */
    @PutMapping("/{id}/status")
    public MaintenanceSchedule updateStatus(@PathVariable Long id, @RequestParam String value) {
        MaintenanceSchedule m = repo.findById(id).orElseThrow();
        m.setStatus(value);
        MaintenanceSchedule saved = repo.save(m);
        if ("완료".equals(value)) syncCase(m.getCaseNumber(), "DONE");
        return saved;
    }

    /** 케이스 상태 동기화 — 실패해도 일정 저장은 성공으로(부수 효과 격리). */
    private void syncCase(String caseNumber, String status) {
        if (caseNumber == null || caseNumber.isBlank()) return;
        try { analytics.setCaseStatus(caseNumber, status, null); } catch (Exception ignored) {}
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return Map.of("deleted", id);
    }
}
