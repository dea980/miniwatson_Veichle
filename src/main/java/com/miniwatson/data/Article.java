package com.miniwatson.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
// id 우리 시스템 내부 식별자(Long, auto-increment 같은 거지만 우리가 정함)
// title Wikipedia article 제목
// summary extract 텍스트 (실제 내용)
// url Wikipedia 원본 페이지
// URL ingestedAt 우리가 가져온 시각
@Data
public class Article {
    private long id;
    // namespace = multi-tenant 분리 키 (tenant / project / collection 단위)
    // 비어 있으면 "default" 네임스페이스로 취급한다.
    private String namespace;
    private String title;
    private String summary;
    private String url;
    private LocalDateTime ingestedAt;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<Float> embedding;

    // 매뉴얼 메타데이터(파일명에서 파싱) — RAG 1차 필터로 검색 정확도↑.
    // 비-매뉴얼(이미지·위키)은 null.
    private String carCode;    // 프로젝트코드 (NE1, AX, CN7HEV…) — 대문자
    private String carModel;   // 로마자 모델 (ioniq5, casper, avante…) — 소문자
    private String powertrain; // hybrid|electric|phev|fcev|sv|null
    private Integer year;      // 연식
    private String lang;       // ko|en
    private String region;     // KR|EN|US…
}
