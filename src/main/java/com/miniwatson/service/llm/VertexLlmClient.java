package com.miniwatson.service.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GCP Vertex AI(Gemini) 제공자 — 스텁. llm.provider=vertex 일 때만 활성.
 * 구현 예정: gemini-2.5-flash 생성/비전, gemini-embedding-001(768)로 임베딩.
 * 현재 GCP 크레딧 미확보로 미구현. 인터페이스만 채워둔다(추상화 Step 5).
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "vertex")
public class VertexLlmClient implements RawLlmProvider {

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        throw new UnsupportedOperationException("VertexLlmClient 미구현 — GCP Vertex AI(Gemini). LLM-ABSTRACTION.md Step 5");
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        throw new UnsupportedOperationException("VertexLlmClient 비전 미구현 — Gemini 멀티모달");
    }

    @Override
    public List<String> availableModels() {
        return List.of("gemini-2.5-flash");
    }

    @Override
    public String defaultModel() {
        return "gemini-2.5-flash";
    }
}
