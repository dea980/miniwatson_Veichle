package com.miniwatson.reports;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 생성된 진단 리포트 적재 — 차종(CAR) 또는 접수번호(CASE) 단위로 1회 생성 후 저장.
 *
 * 왜: 리포트 생성은 RAG + LLM 종합으로 매번 ~30초가 걸리고 부하가 크다(Ollama·DuckDB 경합).
 *     생성 결과를 날짜와 함께 적재해두면, 다음 조회는 즉시 캐시 반환하고 필요할 때만 "재생성"한다.
 * 새 DB 없이 기존 JPA 데이터소스(H2 dev / PostgreSQL prod)에 generated_report 테이블만 추가.
 */
@Entity
@Table(name = "generated_report",
       indexes = @Index(name = "ix_report_key_type", columnList = "reportType,reportKey"))
@Data
public class GeneratedReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reportType;   // "CAR" (차종) | "CASE" (접수번호)
    private String reportKey;     // 차종명(대문자) 또는 접수번호
    private String model;         // 생성에 쓴 LLM (재현성)

    @Lob
    @Column(columnDefinition = "TEXT")
    private String contentJson;   // 리포트 전체 Map을 JSON 직렬화 (KPI·표·종합 의견 등)

    private LocalDateTime createdAt = LocalDateTime.now();
}
