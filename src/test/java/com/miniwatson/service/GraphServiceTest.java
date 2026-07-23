package com.miniwatson.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphService(온톨로지) 검증 — 부위 어휘 정규화(canon)와 Model→Component→{Recall,Complaint,Part} 순회를
 * 임시 CSV + 인메모리 DuckDB로 결정적으로 확인한다. LLM·네트워크 불필요.
 * 같은 패키지라 TabularSqlService.init()(@PostConstruct, package-private)를 수동 호출해 연결을 연다.
 */
class GraphServiceTest {

    private GraphService graph;

    @BeforeEach
    void setup(@TempDir Path dir) throws Exception {
        Path recalls = dir.resolve("recalls.csv");
        Files.writeString(recalls, String.join("\n",
                "nhtsacampaignnumber,reportreceiveddate,model,modelyear,component,parkit,summary",
                "26V034000,2026-01-23,PALISADE,2020,AIR BAGS:SIDE/WINDOW:CURTAIN,false,side curtain airbag",
                "25V607000,2025-09-12,PALISADE,2021,SEAT BELTS: REAR/OTHER:BUCKLE,false,seat belt buckle"));
        Path complaints = dir.resolve("complaints.csv");
        Files.writeString(complaints, String.join("\n",
                "model,components,fire,numberofinjuries,crash,datecomplaintfiled",
                "PALISADE,AIR BAGS,true,2,false,01/01/2024",
                "PALISADE,VISIBILITY/WIPER,false,0,false,01/02/2024",
                "PALISADE,AIR BAGS,false,1,false,01/03/2024"));
        Path parts = dir.resolve("parts.csv");
        Files.writeString(parts, String.join("\n",
                "part,component,unit_price,labor_hours",
                "에어백 모듈,AIR BAGS,400000,1.5",
                "브레이크 패드,BRAKE,80000,1.0"));

        TabularSqlService tabular = new TabularSqlService();
        tabular.init();   // @PostConstruct 수동 호출 → in-memory DuckDB 연결
        VehicleDataProperties data = new VehicleDataProperties();
        data.setTables(Map.of(
                "recalls", recalls.toString(),
                "complaints", complaints.toString(),
                "parts", parts.toString()));
        graph = new GraphService(tabular, data);
    }

    private static int n(Object o) { return ((Number) o).intValue(); }

    @Test
    void modelComponentsGroupsByCanonicalPart() {
        List<List<Object>> rows = graph.modelComponents("PALISADE");
        assertFalse(rows.isEmpty());
        // 이질적 어휘 통합: "AIR BAGS:SIDE/WINDOW:CURTAIN"(리콜)과 "AIR BAGS"(불만)이 한 정규 부위로.
        List<Object> airbags = rows.stream().filter(r -> "AIR BAGS".equals(r.get(0))).findFirst().orElse(null);
        assertNotNull(airbags, "AIR BAGS 정규 부위가 있어야 함");
        assertEquals(1, n(airbags.get(1)), "리콜 1건");
        assertEquals(2, n(airbags.get(2)), "불만 2건");
        // 정렬: (리콜+불만) 내림차순 → AIR BAGS(3)가 첫 행
        assertEquals("AIR BAGS", rows.get(0).get(0));
        // SEAT BELTS도 정규화됨(OTHER로 안 빠짐)
        assertTrue(rows.stream().anyMatch(r -> "SEAT BELTS".equals(r.get(0))));
    }

    @Test
    void componentProfileTraversesRecallComplaintPart() {
        Map<String, Object> p = graph.componentProfile("PALISADE", "AIR BAGS");
        @SuppressWarnings("unchecked")
        List<List<Object>> recalls = (List<List<Object>>) p.get("recalls");
        @SuppressWarnings("unchecked")
        List<List<Object>> complaints = (List<List<Object>>) p.get("complaints");
        @SuppressWarnings("unchecked")
        List<List<Object>> parts = (List<List<Object>>) p.get("parts");

        assertFalse(recalls.isEmpty(), "에어백 리콜 근거");
        assertEquals("26V034000", String.valueOf(recalls.get(0).get(0)));
        // 불만 집계 [count, fires, injuries] = [2, 1, 3]
        assertEquals(2, n(complaints.get(0).get(0)));
        assertEquals(1, n(complaints.get(0).get(1)));
        assertEquals(3, n(complaints.get(0).get(2)));
        // 부품: 에어백 모듈(AIR BAGS)
        assertFalse(parts.isEmpty());
        assertEquals("에어백 모듈", String.valueOf(parts.get(0).get(0)));
    }

    @Test
    void complaintVocabularyIsNormalized() {
        // "VISIBILITY/WIPER" → VISIBILITY 로 매핑되어 OTHER가 아님(정규화 커버리지).
        List<List<Object>> rows = graph.modelComponents("PALISADE");
        assertTrue(rows.stream().anyMatch(r -> "VISIBILITY".equals(r.get(0))));
    }
}
