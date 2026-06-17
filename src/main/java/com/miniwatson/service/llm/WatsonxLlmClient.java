package com.miniwatson.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IBM watsonx.ai 제공자. llm.provider=watsonx 일 때만 활성.
 * granite 모델을 watsonx.ai Runtime REST(/ml/v1/text/generation)로 호출(생성).
 * 인증은 WatsonxAuth(IAM 토큰 공용), 거버넌스/감사는 OllamaService 래퍼가 담당.
 * 비전(granite-vision)은 추후. 임베딩은 WatsonxEmbeddingClient.
 *
 * 한도(Lite 50k 토큰/월) 초과 시 4xx → 과금 대신 폴백 메시지 반환(SECURITY/락 정책).
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "watsonx")
public class WatsonxLlmClient implements RawLlmProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WatsonxLlmClient.class);
    private static final String API_VERSION = "2024-05-01";

    @Value("${watsonx.url}")
    private String url;
    @Value("${watsonx.project-id}")
    private String projectId;
    @Value("${watsonx.model}")
    private String defaultModel;
    @Value("${watsonx.models:}")
    private String modelsCsv;
    @Value("${watsonx.num-predict:256}")
    private int maxNewTokens;

    private final WatsonxAuth auth;
    private final RestTemplate rest = buildTimeoutRestTemplate();

    public WatsonxLlmClient(WatsonxAuth auth) {
        this.auth = auth;
    }

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
        h.setBearerAuth(auth.bearerToken());

        String endpoint = url + "/ml/v1/text/generation?version=" + API_VERSION;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.postForObject(endpoint, new HttpEntity<>(body, h), Map.class);
            if (resp == null || resp.get("results") == null) return "Error: no response";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
            if (results.isEmpty()) return "Error: empty results";
            Object text = results.get(0).get("generated_text");
            return text == null ? "" : text.toString();
        } catch (HttpStatusCodeException e) {
            // 한도 초과(429)/권한 등 4xx·5xx → 과금 대신 폴백(Lite 락 정책). 데모가 죽지 않게.
            LOG.warn("[watsonx] 생성 호출 실패 {} — 폴백 반환: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return "현재 모델 응답을 받을 수 없습니다(한도 초과 또는 일시 오류). 잠시 후 다시 시도해주세요.";
        }
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        // TODO: granite-vision + /ml/v1/text/chat(멀티모달). 추후.
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
}
