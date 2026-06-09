package com.miniwatson.controller;

import com.miniwatson.governance.DocumentCatalogRepository;
import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/governance")

public class GovernanceController {
    private final QueryLogRepository queryLogRepository;

    private final DocumentCatalogRepository catalogRepo;;

    public GovernanceController(QueryLogRepository queryLogRepository,
                                DocumentCatalogRepository catalogRepo){
        this.queryLogRepository = queryLogRepository;
        this.catalogRepo = catalogRepo;
    }
    // ahems Q&A 로그 반환
    @GetMapping("/logs")
    public List<QueryLog> getAllLogs(){//Json 으로 자동 별놘
        return queryLogRepository.findAll();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 카드용 합계
        out.put("totalCalls", queryLogRepository.count());
        out.put("avgLatencyMs", Math.round(queryLogRepository.avgLatency()));
        out.put("totalPii", queryLogRepository.totalPii());
        out.put("totalDocs", catalogRepo.count());

        // 모델별
        List<Map<String, Object>> byModel = new ArrayList<>();
        for (Object[] r : queryLogRepository.statsByModel()) {
            byModel.add(Map.of("model", r[0], "calls", r[1], "avgMs", Math.round(((Number) r[2]).doubleValue())));
        }
        out.put("byModel", byModel);

        // 소스 타입별
        List<Map<String, Object>> bySource = new ArrayList<>();
        for (Object[] r : catalogRepo.statsBySourceType()) {
            bySource.add(Map.of("sourceType", r[0] == null ? "?" : r[0], "docs", r[1], "chunks", r[2]));
        }
        out.put("bySourceType", bySource);

        return out;
    }
}
