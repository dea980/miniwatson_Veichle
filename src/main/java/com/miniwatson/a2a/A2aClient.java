package com.miniwatson.a2a;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;

/** A2A 클라이언트 — 에이전트 카드로 엔드포인트를 발견한 뒤 message/send로 위임한다. */
@Component
public class A2aClient {
    private final RestClient http = RestClient.create();

    @SuppressWarnings("unchecked")
    public Map<String, Object> delegateViaCard(String agentBaseUrl, Map<String, Object> payload) {
        try {
            Map<String, Object> card = http.get().uri(agentBaseUrl + "/.well-known/agent-card.json")
                .retrieve().body(Map.class);
            String endpoint = (card != null && card.get("url") != null)
                ? String.valueOf(card.get("url")) : agentBaseUrl + "/a2a";
            Map<String, Object> req = Map.of(
                "jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", "message/send",
                "params", Map.of("message", Map.of(
                    "role", "user", "messageId", UUID.randomUUID().toString(),
                    "parts", List.of(Map.of("kind", "data", "data", payload)))));
            Map<String, Object> resp = http.post().uri(endpoint)
                .header("Content-Type", "application/json").body(req).retrieve().body(Map.class);
            if (resp == null) return Map.of("error", "no response");
            if (resp.get("error") != null) return Map.of("error", resp.get("error"));
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            if (result != null && result.get("parts") instanceof List<?> parts)
                for (Object p : parts)
                    if (p instanceof Map<?, ?> pm && "data".equals(pm.get("kind")))
                        return (Map<String, Object>) pm.get("data");
            return Map.of("raw", resp);
        } catch (Exception e) {
            return Map.of("error", "a2a delegate failed: " + e.getMessage());
        }
    }
}
