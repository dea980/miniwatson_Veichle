package com.miniwatson.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ApiKeyAuthFilter 라이브 인증 검증 — 전체 컨텍스트 없이 필터만 격리(standalone).
 * 라이브 서버 경로가 로컬 x86_64 JVM 크래시(SIGSEGV, docs/SESSION-SECURITY-TEST-2026-07-03.md)로
 * 불안정하므로, 필터의 401/통과 규칙을 인프로세스로 결정적으로 검증한다.
 */
class ApiKeyAuthFilterTest {

    @RestController
    static class Probe {
        @GetMapping("/api/probe") String probe() { return "ok"; }
        @GetMapping("/public")    String open()  { return "ok"; }  // 비-/api → 필터 우회
    }

    private MockMvc mvc(boolean enabled) {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(enabled);
        props.setApiKeys(java.util.Map.of(
                "dev-all", "*",
                "acme", "default,kr-bcg"));
        return MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new ApiKeyAuthFilter(props))
                .build();
    }

    @Test void noKey_isUnauthorized() throws Exception {
        mvc(true).perform(get("/api/probe")).andExpect(status().isUnauthorized());
    }

    @Test void wrongKey_isUnauthorized() throws Exception {
        mvc(true).perform(get("/api/probe").header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test void blankKey_isUnauthorized() throws Exception {
        mvc(true).perform(get("/api/probe").header("X-API-Key", "  "))
                .andExpect(status().isUnauthorized());
    }

    @Test void validWildcardKey_passes() throws Exception {
        mvc(true).perform(get("/api/probe").header("X-API-Key", "dev-all"))
                .andExpect(status().isOk());
    }

    @Test void validTenantKey_passes() throws Exception {
        mvc(true).perform(get("/api/probe").header("X-API-Key", "acme"))
                .andExpect(status().isOk());
    }

    @Test void nonApiPath_bypassesFilter() throws Exception {
        mvc(true).perform(get("/public")).andExpect(status().isOk());  // 키 없이도 통과
    }

    @Test void securityDisabled_bypassesFilter() throws Exception {
        mvc(false).perform(get("/api/probe")).andExpect(status().isOk());  // enabled=false → 무조건 통과
    }
}
