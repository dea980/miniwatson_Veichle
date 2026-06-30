package com.miniwatson.controller;

import com.miniwatson.dto.AskRequest;
import com.miniwatson.service.OllamaService;
import com.miniwatson.service.RagService;
import com.miniwatson.service.RagCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final OllamaService ollamaService;
    private final RagCacheService ragCache;   // 답변 캐시(compute-once + KB버전 키 무효화)

    public RagController(RagService ragService, OllamaService ollamaService, RagCacheService ragCache) {
        this.ragService = ragService;
        this.ollamaService = ollamaService;
        this.ragCache = ragCache;
    }

    /**
     * Ask a RAG question. 캐시 경유(같은 질문·필터·모델 + KB버전이면 즉시 반환).
     * rerank/hybrid 는 EVAL-ONLY 오버라이드라 캐시 경로에선 제외(평가는 캐시 안 탐).
     */
    @PostMapping("/ask")
    public RagService.RagResult ask(@RequestBody AskRequest request) throws IOException {
        return ragCache.askCached(
                            request.getQuestion(),
                            request.getNamespace(),
                            request.getModel(),
                            request.getTitle(), // 문서 전용(선택)
                            request.getCar(),       // 메타 필터(매뉴얼 KB 정확도)
                            request.getYear(),
                            request.getLang(),
                            request.getPowertrain(),
                            false);
    }

    /** Multi-LLM: list selectable chat models and the default. */
    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "default", ollamaService.defaultModel(),
                "available", ollamaService.availableModels()
        );
    }
}
