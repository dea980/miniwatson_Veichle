package com.miniwatson.a2a;

import com.miniwatson.service.EstimateService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class A2aController {
    private final EstimateService estimate;
    public A2aController(EstimateService estimate) {
        this.estimate = estimate;}

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> agentCard() {
        return Map.of(
                "name", "parts-estimate-agent",
                "description", "차량 문제 설명을 받 필요한 교체 부품과 예상 비용, 재고를 산정한다.",
                "version", "0.1.0",
                "url", "http://localhost:8080/a2a",
                "capabilities", Map.of("streaming", false, "pushNotifications", false),
                "defaultInputModes", List.of("text", "data"),
                "defaultOutputModes", List.of("text", "data"),
                "skills", List.of(Map.of(
                        "id", "parts_estimate",
                        "name", "부품 견적",
                        "description", "problem/car/model로 교체 부품·비용·재고 산정",
                        "tags", List.of("parts", "estimate", "inventory"))));
    }
    @PostMapping("/a2a")
    public Map<String, Object> a2a(@RequestBody Map<String, Object> req) {
        Object id = req.get("id");
        String method = String.valueOf(req.get("method"));
        if (!"message/send".equals(method)) return err(id, -32601, "Method not found: " + method);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) params.getOrDefault("message", Map.of());
            Map<String, Object> in = extractInput(message);
            Map<String, Object> result = estimate.estimate(str(in.get("problem")), str(in.get("car")), str(in.get("model")));
            Map<String, Object> outMsg = Map.of(
                    "kind", "message", "role", "agent",
                    "messageId", UUID.randomUUID().toString(),
                    "parts", List.of(Map.of("kind", "data", "data", result)));
            Map<String, Object> resp = new HashMap<>();
            resp.put("jsonrpc", "2.0"); resp.put("id", id == null ? "" : id); resp.put("result", outMsg);
            return resp;
        } catch (Exception e) { return err(id, -32603, "estimate failed: " + e.getMessage()); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInput(Map<String, Object> message) {
        Object partsO = message.get("parts");
        if (partsO instanceof List<?> parts) {
            for (Object p : parts)
                if (p instanceof Map<?, ?> pm && "data".equals(pm.get("kind")) && pm.get("data") instanceof Map<?, ?> d)
                    return (Map<String, Object>) d;
            for (Object p : parts)
                if (p instanceof Map<?, ?> pm && "text".equals(pm.get("kind"))) {
                    Map<String, Object> m = new HashMap<>(); m.put("problem", str(pm.get("text"))); return m;
                }
        }
        return Map.of();
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Map<String, Object> err(Object id, int code, String msg) {
        Map<String, Object> e = new HashMap<>(); e.put("jsonrpc", "2.0"); e.put("id", id == null ? "" : id);
        e.put("error", Map.of("code", code, "message", msg)); return e;
    }
}
