package com.miniwatson.governance;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "query_log")
public class QueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                          // Primary Key (Long)

    @Column(columnDefinition = "TEXT")
    private String question;                  // @Id 제거

    @Column(columnDefinition = "TEXT")
    private String answer;

    private String model;

    private Long latencyMs;                   // latency (lateny X)

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}