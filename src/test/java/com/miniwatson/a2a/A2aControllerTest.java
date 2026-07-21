package com.miniwatson.a2a;

import com.miniwatson.service.EstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A2A 서버(견적 에이전트) 계약 검증 — 라이브 컨텍스트 없이 컨트롤러만 격리(standalone).
 * 에이전트 카드(무인증 GET)와 message/send(JSON-RPC)의 응답 형태를 결정적으로 검증한다.
 * EstimateService는 LLM을 타므로 고정 스텁으로 대체(결정성 확보, CI에서 네트워크 불필요).
 */
class A2aControllerTest {

    // 생성자는 필드만 할당 → null 의존성으로 만들고 estimate만 오버라이드.
    private final EstimateService stubEstimate = new EstimateService(null, null, null) {
        @Override
        public Map<String, Object> estimate(String problem, String car, String model) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("problem", problem); m.put("car", car);   // LinkedHashMap → null car(텍스트 폴백 케이스) 허용
            m.put("total", 120000); m.put("sample", true);
            return m;
        }
    };

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new A2aController(stubEstimate)).build();

    @Test
    void agentCardIsServed() throws Exception {
        mvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("parts-estimate-agent"))
                .andExpect(jsonPath("$.skills[0].id").value("parts_estimate"))
                .andExpect(jsonPath("$.url").exists());
    }

    @Test
    void messageSendReturnsEstimateInDataPart() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"role\":\"user\",\"parts\":"
                + "[{\"kind\":\"data\",\"data\":{\"problem\":\"브레이크 패드 교체\",\"car\":\"PALISADE\",\"model\":\"palisade\"}}]}}}";
        mvc.perform(post("/a2a").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.result.kind").value("message"))
                .andExpect(jsonPath("$.result.role").value("agent"))
                .andExpect(jsonPath("$.result.parts[0].kind").value("data"))
                .andExpect(jsonPath("$.result.parts[0].data.total").value(120000));
    }

    @Test
    void textPartFallsBackToProblem() throws Exception {
        // data part가 없으면 첫 text part를 problem으로 읽는다.
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"role\":\"user\",\"parts\":"
                + "[{\"kind\":\"text\",\"text\":\"엔진 소음\"}]}}}";
        mvc.perform(post("/a2a").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.parts[0].data.problem").value("엔진 소음"));
    }

    @Test
    void unknownMethodReturnsJsonRpcError() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"9\",\"method\":\"tasks/get\",\"params\":{}}";
        mvc.perform(post("/a2a").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }
}
