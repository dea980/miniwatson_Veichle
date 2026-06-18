package com.miniwatson.controller;

import com.miniwatson.service.TabularSqlService;
import com.miniwatson.service.TextToSqlService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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
    @PostMapping("/load")
    public Map<String, Object> load(@RequestParam String table, @RequestParam String path,
                                    @RequestParam(required = false, defaultValue = "0") int headerRow) throws Exception {
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
}
