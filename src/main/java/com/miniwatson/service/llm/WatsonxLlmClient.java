package com.miniwatson.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IBM watsonx.ai 제공자. llm.provider=watsonx 일 때만 활성.
 * granite 모델을 watsonx.ai Runtime REST로 호출(생성). 거버넌스/감사는 OllamaService 래퍼가 담당.
 *
 * 인증: API key -> IAM bearer token(1h) 교환 후 캐시.
 * 생성: POST {url}/ml/v1/text/generation?version=... (model_id + input + project_id).
 * 비전(granite-vision)은 추후. 임베딩은 EmbeddingClient 쪽에서 별도 watsonx 구현 필요(현재 Ollama 유지).
 *
 * 필요한 설정(.env): WATSONX_URL, WATSONX_API_KEY, WATSONX_PROJECT_ID, WATSONX_MODEL.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "watsonx")
public class WatsonxLlmClient implements RawLlmProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WatsonxLlmClient.class);
    private static final String IAM_URL = "https://iam.cloud.ibm.com/identity/token";
    private static final String API_VERSION = "2024-05-01";

    @Value("${watsonx.url}")
    private String url;                 // 예: https://us-south.ml.cloud.ibm.com
    @Value("${watsonx.api-key}")
    private String apiKey;
    @Value("${watsonx.project-id}")
    private String projectId;
    @Value("${watsonx.model}")
    private String defaultModel;
    @Value("${watsonx.models:}")
    private String modelsCsv;
    @Value("${watsonx.num-predict:256}")
    private int maxNewTokens;

    private final RestTemplate rest = buildTimeoutRestTemplate();

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    private static RestTemplate buildTimeoutRestTemplate() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(f);
    }

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        String m = (model == null || model.isBlank()) ? defaultModel : model;

        Map<String, Object> body = Map.of(
                "model_id", m,
                "input", prompt,
                "project_id", projectId,
                "parameters", Map.of("max_new_tokens", maxNewTokens)
        );

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearerToken());

        String endpoint = url + "/ml/v1/text/generation?version=" + API_VERSION;
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.postForObject(endpoint, new HttpEntity<>(body, h), Map.class);

        if (resp == null || resp.get("results") == null) return "Error: no response";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        if (results.isEmpty()) return "Error: empty results";
        Object text = results.get(0).get("generated_text");
        return text == null ? "" : text.toString();
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        // TODO: granite-vision 모델 + /ml/v1/text/chat (멀티모달 메시지). 추후 구현.
        throw new UnsupportedOperationException("WatsonxLlmClient 비전 미구현 — granite-vision으로 추후");
    }

    @Override
    public List<String> availableModels() {
        List<String> models = new ArrayList<>();
        models.add(defaultModel);
        if (modelsCsv != null && !modelsCsv.isBlank()) {
            for (String m : modelsCsv.split(",")) {
                String t = m.trim();
                if (!t.isEmpty() && !models.contains(t)) models.add(t);
            }
        }
        return models;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    /** API key -> IAM bearer token. 만료 1분 전까지 캐시 재사용. */
    private synchronized String bearerToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ibm:params:oauth:grant-type:apikey");
        form.add("apikey", apiKey);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.postForObject(IAM_URL, new HttpEntity<>(form, h), Map.class);
        if (resp == null || resp.get("access_token") == null) {
            throw new IllegalStateException("watsonx IAM 토큰 발급 실패 — WATSONX_API_KEY 확인");
        }
        cachedToken = resp.get("access_token").toString();
        long expiresIn = resp.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        tokenExpiry = Instant.now().plusSeconds(expiresIn);
        LOG.info("[watsonx] IAM 토큰 발급/갱신 (유효 {}s)", expiresIn);
        return cachedToken;
    }
}
