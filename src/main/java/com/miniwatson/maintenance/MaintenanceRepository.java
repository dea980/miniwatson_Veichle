package com.miniwatson.maintenance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface MaintenanceRepository extends JpaRepository<MaintenanceSchedule, Long> {
    List<MaintenanceSchedule> findAllByOrderByScheduledDateAsc();
    List<MaintenanceSchedule> findByScheduledDateBetweenOrderByScheduledDateAsc(LocalDate from, LocalDate to);
}
