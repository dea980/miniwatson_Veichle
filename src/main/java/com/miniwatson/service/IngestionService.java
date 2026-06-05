package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleStore;
import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.data.WikipediaResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.io.IOException;
import java.time.LocalDateTime;


@Service
public class IngestionService {

    private final String WIKIPEDIA_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private final RestTemplate restTemplate = new RestTemplate();
//    private final ArticleStore articleStore;
    private final ArticleParquetStore articleStore;
    private final EmbeddingService embeddingService;

//    public IngestionService(ArticleStore articleStore, EmbeddingService embeddingService) {
//        this.articleStore = articleStore;
//        this.embeddingService = embeddingService;
//    }

    public IngestionService(ArticleParquetStore articleStore, EmbeddingService embeddingService) {
        this.articleStore = articleStore;
        this.embeddingService = embeddingService;
    }

    public Article ingest(String title) throws IOException {
        //Dedupe : 동일 Title 시,
        List<Article>existing = articleStore.loadAll();
        for (Article a : existing){
            if (a.getTitle().equalsIgnoreCase(title)){
                return a;
            }
        }

        String url = WIKIPEDIA_URL + title;

        // ⭐ User-Agent 헤더 추가
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
        HttpEntity<String> request = new HttpEntity<>(headers);

        // ⭐ getForObject → exchange로 변경 (헤더 포함)
        ResponseEntity<WikipediaResponse> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                WikipediaResponse.class
        );

        WikipediaResponse response = responseEntity.getBody();
        if (response == null) {
            throw new RuntimeException("Wikipedia returned no response for: " + title);
        }

        // 아티클 객체 생성 변환
        Article article = new Article();
        article.setTitle(response.getTitle());
        article.setSummary(response.getExtract());
        article.setUrl(response.getContent_urls().getDesktop().getPage());
        article.setIngestedAt(LocalDateTime.now());

        // embedding 생성 (title + summary 결합)
        String textToEmbed = response.getTitle() + ". " + response.getExtract();
        article.setEmbedding(embeddingService.embed(textToEmbed));
        return articleStore.save(article);
    }
}