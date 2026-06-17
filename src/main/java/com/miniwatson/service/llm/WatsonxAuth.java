package com.miniwatson.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * watsonx IAM 토큰 발급/캐시 — WatsonxLlmClient / WatsonxEmbeddingClient 공용.
 * API key -> IAM bearer token(약 1h) 교환 후 만료 1분 전까지 재사용.
 */
@Component
public class WatsonxAuth {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WatsonxAuth.class);
    private static final String IAM_URL = "https://iam.cloud.ibm.com/identity/token";

    @Value("${watsonx.api-key:}")
    private String apiKey;

    private final RestTemplate rest = buildTimeoutRestTemplate();
    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    private static RestTemplate buildTimeoutRestTemplate() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(f);
    }

    public synchronized String bearerToken() {
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
