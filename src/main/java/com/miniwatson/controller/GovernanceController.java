package com.miniwatson.controller;
import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/governance")

public class GovernanaceController {
    private final QueryLogRepository queryLogRepository;

    public GovernanaceController(QueryLogRepository queryLogRepository){
        this.queryLogRepository = queryLogRepository;
    }
    // ahems Q&A 로그 반환
    @GetMapping("/logs")
    public List<QueryLog> getAllLogs(){//Json 으로 자동 별놘
        return queryLogRepository.findAll();
    }
}
