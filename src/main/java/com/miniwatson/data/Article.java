package com.miniwatson.data;

import lombok.Data;
import java.time.LocalDateTime;

// id 우리 시스템 내부 식별자(Long, auto-increment 같은 거지만 우리가 정함)
// title Wikipedia article 제목
// summary extract 텍스트 (실제 내용)
// url Wikipedia 원본 페이지
// URL ingestedAt 우리가 가져온 시각
@Data
public class Article {
    private long id;
    private String title;
    private String summary;
    private String url;
    private LocalDateTime ingestedAt;
}
