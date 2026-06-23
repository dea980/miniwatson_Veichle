package com.miniwatson.cases;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ResolvedCaseRepository extends JpaRepository<ResolvedCase, Long> {
    Optional<ResolvedCase> findByCaseNumber(String caseNumber);
    boolean existsByCaseNumber(String caseNumber);
}
