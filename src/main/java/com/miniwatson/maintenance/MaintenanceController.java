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

    public MaintenanceController(MaintenanceRepository repo) {
        this.repo = repo;
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
        return repo.save(m);
    }

    /** 상태 변경 (예정/진행/완료). */
    @PutMapping("/{id}/status")
    public MaintenanceSchedule updateStatus(@PathVariable Long id, @RequestParam String value) {
        MaintenanceSchedule m = repo.findById(id).orElseThrow();
        m.setStatus(value);
        return repo.save(m);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return Map.of("deleted", id);
    }
}
