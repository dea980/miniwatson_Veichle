package com.miniwatson.reports;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, Long> {
    /** 같은 키·타입의 최신 1건(캐시 조회). */
    Optional<GeneratedReport> findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc(String reportType, String reportKey);
    /** 적재 목록(타입별 최신순). */
    List<GeneratedReport> findByReportTypeOrderByCreatedAtDesc(String reportType);
}
