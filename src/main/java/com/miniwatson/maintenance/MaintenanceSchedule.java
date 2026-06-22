package com.miniwatson.maintenance;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정비 스케줄 — 새 DB가 아니라 기존 JPA 데이터소스(H2 로컬 / PostgreSQL 운영)에 테이블 하나 추가.
 * ddl-auto: update 라 @Entity만 만들면 maintenance_schedule 테이블이 자동 생성된다.
 * 케이스 트리아지의 접수번호(caseNumber)와 차종(model)을 연결해 "우선순위 → 정비 예약" 흐름을 잇는다.
 */
@Entity
@Table(name = "maintenance_schedule")
@Data
public class MaintenanceSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String model;          // 차종 (예: PALISADE)
    private String caseNumber;     // 접수번호(ODI) — 트리아지 케이스 연결(선택)
    private String title;          // 정비 항목 (예: 안전벨트 프리텐셔너 점검)
    private LocalDate scheduledDate;
    private String status = "예정"; // 예정 / 진행 / 완료
    private String technician;     // 담당자(선택)
    @Column(length = 1000)
    private String note;
    private LocalDateTime createdAt = LocalDateTime.now();
}
