package com.miniwatson.service.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Azure OpenAI 제공자 — 스텁. llm.provider=azure 일 때만 활성.
 * 구현 예정: Azure OpenAI 배포(deployment) 기반 chat + embeddings. OpenAI 호환 API.
 * 현재 Azure 크레딧/접근 미확보로 미구현. 인터페이스만 채워둔다(추상화 Step 5).
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "azure")
public class AzureOpenAiLlmClient implements RawLlmProvider {

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        throw new UnsupportedOperationException("AzureOpenAiLlmClient 미구현 — Azure OpenAI. LLM-ABSTRACTION.md Step 5");
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        throw new UnsupportedOperationException("AzureOpenAiLlmClient 비전 미구현");
    }

    @Override
    public List<String> availableModels() {
        return List.of();
    }

    @Override
    public String defaultModel() {
        return "gpt-4o-mini";
    }
}
