package com.miniwatson.governance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueryLogRepository extends JpaRepository<QueryLog, Long> {
    // 끝. 기본 메서드 다 자동 (save, findAll, findById, delete...)
}