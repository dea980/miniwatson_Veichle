package com.miniwatson.dto;
import lombok.Data;


@Data // 어노테이션 하나로 getter/setter/toString 자동 생성. 본인이 안 적어도 됨.
public class AskRequest {
    private String question;
    // 선택: 검색 대상 tenant/collection (없으면 "default")
    private String namespace;
    // 선택: 사용할 chat model (없으면 서버 기본 모델)
    private String model;
    // Eval-Only 검색 전략 요청별 오버라이드 (평가용)
    private String rerank;
    private Boolean hybrid;
    // 선택: 특정 문서(제목)로 검색을 한정 — "문서 전용 어시스턴트"용
    private String title;

    // 메타 1차 필터(매뉴얼 KB 정확도↑). 안 주면 무필터.
    // car: carModel("ioniq5") 또는 carCode("NE1N") 부분일치
    private String car;
    private Integer year;
    private String lang;        // ko|en
    private String powertrain;  // hybrid|electric|phev|fcev|sv

}
