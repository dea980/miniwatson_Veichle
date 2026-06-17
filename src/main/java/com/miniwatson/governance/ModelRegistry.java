package com.miniwatson.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 모델/설정 지문(fingerprint) — 응답이 "어떤 구성으로" 생성됐는지 추적한다.
 * 같은 질문이어도 provider/embed/rerank/앱버전이 다르면 답이 달라지므로,
 * query_log.modelConfig에 이 지문을 박아 재현성·드리프트 귀인·A/B 비교의 근거로 쓴다.
 *
 * 실제 생성 모델(per-call)은 query_log.model에 따로 남는다. 여기선 그 주변 구성을 묶는다.
 */
@Component
public class ModelRegistry {

    @Value("${llm.provider:ollama}")
    private String provider;

    @Value("${ollama.embed-model:}")
    private String embedModel;

    @Value("${rerank.strategy:mmr}")
    private String rerank;

    // CI가 APP_VERSION(=git sha 등)을 주입하면 그 값, 없으면 dev.
    @Value("${app.version:dev}")
    private String appVersion;

    /** 사람이 읽는 지문. 예: provider=ollama;embed=granite-embedding:278m;rerank=mmr;ver=dev */
    public String fingerprint() {
        return "provider=" + provider
                + ";embed=" + embedModel
                + ";rerank=" + rerank
                + ";ver=" + appVersion;
    }

    /** 같은 구성을 빠르게 비교·그룹핑하기 위한 짧은 해시. */
    public String hash() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "nohash";
        }
    }
}
