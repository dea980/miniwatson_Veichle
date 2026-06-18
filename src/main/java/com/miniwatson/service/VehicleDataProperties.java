package com.miniwatson.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 정형(tabular) 데이터셋 레지스트리 — 논리 테이블명 → CSV/XLSX 경로.
 * application.yaml의 vehicle.tables.* 로 바인딩. 새 데이터셋은 여기에 한 줄 추가하면
 * Agent의 text-to-SQL 도구가 자동으로 대상에 포함한다(하드코딩 제거).
 *
 *   vehicle:
 *     tables:
 *       recalls: data/vehicle/recalls/hyundai_recalls_nhtsa.csv
 *       maintenance: data/vehicle/maintenance/schedule.csv
 */
@Component
@ConfigurationProperties(prefix = "vehicle")
public class VehicleDataProperties {

    /** 논리 테이블명 → 파일 경로 (서버 작업 디렉터리 기준). */
    private Map<String, String> tables = new LinkedHashMap<>();

    public Map<String, String> getTables() { return tables; }
    public void setTables(Map<String, String> tables) { this.tables = tables; }
}
