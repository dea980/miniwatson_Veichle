package com.miniwatson.service.llm;

/**
 * 순수 추론 제공자 마커. 거버넌스 래퍼(OllamaService)가 활성 제공자 1개를 이 타입으로 주입받는다.
 * 제공자 구현체는 llm.provider 설정으로 정확히 하나만 활성화(@ConditionalOnProperty)된다.
 * LlmClient를 그대로 상속하므로 메서드 계약은 동일하고, "이건 거버넌스 없는 raw 제공자"라는 역할만 구분한다.
 */
public interface RawLlmProvider extends LlmClient {
}
