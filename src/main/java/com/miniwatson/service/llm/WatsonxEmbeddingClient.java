package com.miniwatson.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IBM watsonx.ai 임베딩 제공자. llm.provider=watsonx 일 때만 활성.
 * granite-embedding-278m-multilingual(768차원, 현재와 동일 → 재인덱싱 차원 변경 없음)을
 * /ml/v1/text/embeddings 로 호출. 인증은 WatsonxAuth 공용.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "watsonx")
public class WatsonxEmbeddingClient implements EmbeddingClient {

    private static final String API_VERSION = "2024-05-01";

    @Value("${watsonx.url}")
    private String url;
    @Value("${watsonx.project-id}")
    private String projectId;
    @Value("${watsonx.embed-model:ibm/granite-embedding-278m-multilingual}")
    private String embedModel;

    private final WatsonxAuth auth;
    private final RestTemplate rest = buildTimeoutRestTemplate();

    public WatsonxEmbeddingClient(WatsonxAuth auth) {
        this.auth = auth;
    }

    private static RestTemplate buildTimeoutRestTemplate() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(f);
    }

    @Override
    public List<Float> embed(String text) {
        Map<String, Object> body = Map.of(
                "model_id", embedModel,
                "inputs", List.of(text),
                "project_id", projectId
        );

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(auth.bearerToken());

        String endpoint = url + "/ml/v1/text/embeddings?version=" + API_VERSION;
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.postForObject(endpoint, new HttpEntity<>(body, h), Map.class);
        if (resp == null || resp.get("results") == null) {
            throw new RuntimeException("watsonx 임베딩 응답 없음");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        if (results.isEmpty() || results.get(0).get("embedding") == null) {
            throw new RuntimeException("watsonx 임베딩 결과 없음");
        }
        @SuppressWarnings("unchecked")
        List<Number> raw = (List<Number>) results.get(0).get("embedding");
        List<Float> out = new ArrayList<>(raw.size());
        for (Number n : raw) out.add(n.floatValue());
        return out;
    }

    // granite-embedding은 query/document prefix가 불필요(EmbeddingService.prefixFor 규약과 동일).
    @Override
    public List<Float> embedQuery(String text) {
        return embed(text);
    }

    @Override
    public List<Float> embedDocument(String text) {
        return embed(text);
    }
}
