package com.miniwatson.cases;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 해결 처리된 케이스(접수번호) 영속 — 브라우저 localStorage 대신 DB에.
 * 새 DB 없이 기존 JPA 데이터소스(H2 dev / PostgreSQL prod)에 resolved_case 테이블만 추가.
 * 트리아지/홈 큐에서 이 목록을 제외해 "해결하면 사라짐"을 기기·세션 간 일관되게 만든다.
 */
@Entity
@Table(name = "resolved_case")
@Data
public class ResolvedCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String caseNumber;        // odiNumber

    private String note;
    private LocalDateTime resolvedAt = LocalDateTime.now();

    /** 워크플로 상태: DIAGNOSING(진단중) | REPAIRING(수리중) | DONE(완료). null = DONE(구버전 행 호환).
     *  RECEIVED(접수)는 "행 없음"이 기본이라 저장하지 않는다. */
    private String status;
}
