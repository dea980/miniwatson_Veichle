package com.miniwatson.service.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AWS Bedrock 제공자 — 스텁. llm.provider=bedrock 일 때만 활성.
 * 구현 예정: Claude(생성+비전, Converse API), Titan Text Embeddings v2(1024 → 재인덱싱 필요).
 * 현재 AWS 프리티어 자격 미확보로 미구현. 인터페이스만 채워둔다(추상화 Step 5).
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "bedrock")
public class BedrockLlmClient implements RawLlmProvider {

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        throw new UnsupportedOperationException("BedrockLlmClient 미구현 — AWS Bedrock(Claude Converse). LLM-ABSTRACTION.md Step 5");
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        throw new UnsupportedOperationException("BedrockLlmClient 비전 미구현 — Claude 멀티모달");
    }

    @Override
    public List<String> availableModels() {
        return List.of("anthropic.claude-3-5-haiku-20241022-v1:0");
    }

    @Override
    public String defaultModel() {
        return "anthropic.claude-3-5-haiku-20241022-v1:0";
    }
}
