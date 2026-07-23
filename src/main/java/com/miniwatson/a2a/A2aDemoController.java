package com.miniwatson.a2a;

import com.miniwatson.service.AgentService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** A2A 데모 — 진단 에이전트가 견적 에이전트에게 A2A로 위임한다(카드 발견 + message/send). */
@RestController
@RequestMapping("/api/a2a")
public class A2aDemoController {
    private final AgentService agent;
    private final A2aClient a2a;
    public A2aDemoController(AgentService agent, A2aClient a2a) { this.agent = agent; this.a2a = a2a; }

    @PostMapping("/diagnose-estimate")
    public Map<String, Object> diagnoseEstimate(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("question", "");
        String model = body.get("model");
        var diag = agent.run(q, "vehicle", null);   // 3번째=LLM 모델명(기본값 사용). 차종(model)은 견적 payload로만 전달.
        Map<String, Object> payload = new HashMap<>();
        payload.put("problem", q);
        payload.put("car", body.getOrDefault("car", ""));
        payload.put("model", model == null ? "" : model);
        var est = a2a.delegateViaCard("http://localhost:8080", payload);
        Map<String, Object> out = new HashMap<>();
        out.put("diagnosis", Map.of("answer", diag.answer(), "tool", diag.tool()));
        out.put("estimate_via_a2a", est);
        return out;
    }
}
