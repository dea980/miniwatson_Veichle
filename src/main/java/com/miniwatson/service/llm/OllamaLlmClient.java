package com.miniwatson.service.llm;

import com.miniwatson.dto.OllamaRequest;
import com.miniwatson.dto.OllamaResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 추론 제공자 — 순수 호출만 한다(거버넌스 없음).
 * PII 마스킹/감사 로그는 OllamaService(거버넌스 래퍼)가 이 클래스를 감싸 처리한다.
 * 향후 WatsonxLlmClient 등으로 교체할 때 이 클래스 자리에 같은 인터페이스 구현체를 넣으면 된다.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaLlmClient implements RawLlmProvider {

    @Value("${ollama.url}")
    private String ollamaUrl;

    /** Default chat model when the caller doesn't specify one. */
    @Value("${ollama.chat-model}")
    private String defaultModel;

    /** Comma-separated whitelist of selectable chat models (multi-LLM). */
    @Value("${ollama.chat-models:}")
    private String chatModelsCsv;

    @Value("${ollama.num-predict}")
    private int numPredict;

    /** 컨텍스트 윈도(토큰). 미설정 시 Ollama 기본(모델별, 보통 2048~4096)이라 RAG 프롬프트가 넘쳐 답이 깨지거나 빈다.
     *  명시적으로 키워(기본 8192) 리포트/RAG의 긴 프롬프트를 안정적으로 수용한다. */
    @Value("${ollama.num-ctx:8192}")
    private int numCtx;

    private final RestTemplate restTemplate = buildTimeoutRestTemplate();

    /** 타임아웃 없는 RestTemplate은 Ollama가 멈추면 요청이 무한대기 -> 가용성 구멍. 연결 5s/읽기 120s. */
    private static RestTemplate buildTimeoutRestTemplate() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(java.time.Duration.ofSeconds(5));
        f.setReadTimeout(java.time.Duration.ofSeconds(120));
        return new RestTemplate(f);
    }

    /** Available chat models = configured whitelist, always including the default. */
    @Override
    public List<String> availableModels() {
        List<String> models = new ArrayList<>();
        models.add(defaultModel);
        if (chatModelsCsv != null && !chatModelsCsv.isBlank()) {
            for (String m : chatModelsCsv.split(",")) {
                String t = m.trim();
                if (!t.isEmpty() && !models.contains(t)) {
                    models.add(t);
                }
            }
        }
        return models;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    /** Resolve + validate a requested model against the whitelist. */
    private String resolveModel(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultModel;
        }
        List<String> allowed = availableModels();
        if (!allowed.contains(requested)) {
            throw new IllegalArgumentException(
                    "Model '" + requested + "' is not allowed. Available: " + allowed);
        }
        return requested;
    }

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        // userQuestion/sources는 감사 로그용 메타라 순수 제공자에선 쓰지 않는다(래퍼가 처리).
        return generate(prompt, resolveModel(model), null);
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        // 비전 모델은 화이트리스트 밖이라 그대로 사용.
        String m = (visionModel == null || visionModel.isBlank()) ? defaultModel : visionModel;
        return generate(prompt, m, base64Images);
    }

    /** Core Ollama /api/generate 호출. 거버넌스 없음. */
    private String generate(String prompt, String model, List<String> images) {
        OllamaRequest request = new OllamaRequest();
        request.setModel(model);
        request.setPrompt(prompt);
        request.setStream(false);
        request.setThink(false);
        request.setOptions(Map.of("num_predict", numPredict, "num_ctx", numCtx));
        request.setKeepAlive("10m");
        if (images != null && !images.isEmpty()) {
            request.setImages(images);
        }

        OllamaResponse response = restTemplate.postForObject(
                ollamaUrl + "/api/generate",
                request,
                OllamaResponse.class
        );

        return (response != null) ? response.getResponse() : "Error: no response";
    }
}
