package com.miniwatson.controller;

import com.miniwatson.service.TabularSqlService;
import com.miniwatson.service.TextToSqlService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 표 파일을 DuckDB로 SQL 질의 (watsonx.data 라이크하우스 경로).
 * 비정형 -> RAG(/api/rag), 정형(표) -> SQL(/api/tabular).
 */
@RestController
@RequestMapping("/api/tabular")
public class TabularController {

    private final TabularSqlService sqlService;
    private final TextToSqlService textToSql;

    public TabularController(TabularSqlService sqlService, TextToSqlService textToSql) {
        this.sqlService = sqlService;
        this.textToSql = textToSql;
    }

    /**
     * POST /api/tabular/load?table=revenue&path=sample/x.csv
     * xlsx는 헤더가 N행 아래일 수 있어 &headerRow=6 처럼 지정(기본 0).
     */
    // 외부 입력 경로는 허용 디렉터리 하위로만. DuckDB read_csv_auto가 임의 파일을 읽으므로
    // /load?path=/etc/passwd 같은 path traversal로 임의 파일 읽기가 가능 → 베이스로 봉쇄.
    private static final List<Path> ALLOWED_BASES = List.of(
            Path.of("sample").toAbsolutePath().normalize(),
            Path.of("data").toAbsolutePath().normalize());

    private static String resolveAllowed(String path) {
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        for (Path base : ALLOWED_BASES) {
            if (resolved.startsWith(base)) return resolved.toString();
        }
        throw new IllegalArgumentException("허용되지 않은 경로: sample/ 또는 data/ 하위만 가능");
    }

    @PostMapping("/load")
    public Map<String, Object> load(@RequestParam String table, @RequestParam String path,
                                    @RequestParam(required = false, defaultValue = "0") int headerRow) throws Exception {
        path = resolveAllowed(path);
        if (path.toLowerCase().endsWith(".xlsx")) {
            sqlService.registerXlsx(table, path, headerRow);
        } else {
            sqlService.registerCsv(table, path);
        }
        return Map.of("table", table, "schema", sqlService.schema(table));
    }

    /**
     * 파일 업로드로 테이블 등록 (UI에서 직접). CSV/XLSX 멀티파트.
     * POST /api/tabular/upload  form: file, table, headerRow?
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam String table,
                                      @RequestParam(required = false, defaultValue = "0") int headerRow) throws Exception {
        String name = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File tmp = File.createTempFile("tabular-", "-" + safe);
        try {
            file.transferTo(tmp);
            if (name.toLowerCase().endsWith(".xlsx")) {
                sqlService.registerXlsx(table, tmp.getAbsolutePath(), headerRow);
            } else {
                sqlService.registerCsv(table, tmp.getAbsolutePath());   // CREATE TABLE AS SELECT → 즉시 materialize
            }
            return Map.of("table", table, "file", name, "schema", sqlService.schema(table));
        } finally {
            tmp.delete();   // 등록 시 DuckDB가 materialize하므로 임시파일 삭제 안전
        }
    }

    /** POST /api/tabular/ask  body: {"table":"revenue","question":"..."} */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) throws Exception {
        return textToSql.ask(body.get("table"), body.get("question"));
    }

    /** 입력 검증 실패(허용 외 경로·잘못된 테이블명 등)는 400 — 500/스택트레이스로 새지 않게. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, Object> onBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
