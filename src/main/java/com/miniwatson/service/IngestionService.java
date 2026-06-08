package com.miniwatson.service;

import com.miniwatson.data.Article;
// import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.data.ArticleRepository;
import com.miniwatson.data.VectorIndex;
import com.miniwatson.data.WikipediaResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.io.IOException;
import java.time.LocalDateTime;


@Service
public class IngestionService {

    private final String WIKIPEDIA_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private static final String DEFAULT_NS = "default";
    private final RestTemplate restTemplate = new RestTemplate();
    // private final ArticleParquetStore articleStore;
    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final OllamaService ollamaService;   // 멀티모달: 비전 모델로 이미지 캡션 생성
    private final OcrService ocrService;
    private final ArticleRepository articleStore;
    public IngestionService(ArticleRepository articleStore,
                            EmbeddingService embeddingService,
                            VectorIndex vectorIndex,
                            OllamaService ollamaService,
                            OcrService ocrService) {
        this.articleStore = articleStore;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.ollamaService = ollamaService;
        this.ocrService = ocrService;
    }

    /** Backward-compatible: ingest into the "default" namespace. */
    public Article ingest(String title) throws IOException {
        return ingest(title, DEFAULT_NS);
    }

    public Article ingest(String title, String namespace) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;

        // 압력 "Vector_database" -> 저장 제목 "Vector database" 와 맞추기 위해 정규화
        String normalized = title.replace('_', ' ').trim();


        // Dedupe within the same namespace only (tenants are isolated).
        List<Article> existing = articleStore.loadAll();
        for (Article a : existing) {
            String aNs = (a.getNamespace() == null || a.getNamespace().isBlank()) ? DEFAULT_NS : a.getNamespace();
            if (aNs.equals(ns) && a.getTitle().equalsIgnoreCase(normalized)) {
                return a;
            }
        }

        String url = WIKIPEDIA_URL + title;

        // User-Agent 헤더 추가
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
        HttpEntity<String> request = new HttpEntity<>(headers);

        // getForObject → exchange로 변경 (헤더 포함)
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
        article.setNamespace(ns);
        article.setTitle(response.getTitle());
        article.setSummary(response.getExtract());
        article.setUrl(response.getContent_urls().getDesktop().getPage());
        article.setIngestedAt(LocalDateTime.now());


        // embedding 생성 (title + summary 결합)
        String textToEmbed = response.getTitle() + ". " + response.getExtract();
        article.setEmbedding(embeddingService.embed("search_document: " + textToEmbed));

        Article saved = articleStore.save(article);
        // 새 벡터를 인메모리 인덱스에 즉시 반영 (다음 질의부터 검색 대상)
        vectorIndex.add(saved);
        return saved;
    }

    /**
     * 멀티모달 RAG: 이미지를 "검색 가능한 지식"으로 변환한다.
     * 비전 모델로 이미지를 설명(캡션)시키고, 그 텍스트를 임베딩해 일반 Article처럼 저장한다.
     * 이후 /api/rag/ask의 텍스트 질문으로도 이 이미지 내용이 검색된다.
     */
    public Article ingestImage(MultipartFile image, String namespace, String visionModel) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        byte[] bytes = image.getBytes();
        // 1) 이미지 → base64
        String base64 = Base64.getEncoder().encodeToString(image.getBytes());

        // 2) 비전 모델로 캡션/표 추출 (이 텍스트가 곧 지식이 된다)
        String ocr = ocrService.extract(bytes);

        String prompt = "Describe the layout and type of this image "
                + "(is it a chart, table, document, or photo?) and what it is about in one or two sentences. "
                + "Do NOT read or guess specific numbers — numeric values are extracted separately by OCR.";
        String caption = ollamaService.askWithImages(prompt, visionModel, List.of(base64));

        String combined = "[OCR]\n" + ocr + "\n\n[Vision]\n" + caption;
        // 3) 파일명을 제목으로 (없으면 타임스탬프)
        String filename = image.getOriginalFilename();
        String title = (filename == null || filename.isBlank())
                ? "image-" + System.currentTimeMillis()
                : filename;



        // 4) 일반 Article과 동일한 형태로 저장 (source는 url 스킴으로 구분)
        Article article = new Article();
        article.setNamespace(ns);
        article.setTitle(title);
        article.setSummary(combined);
        article.setUrl("image://" + title);
        article.setIngestedAt(LocalDateTime.now());
        article.setEmbedding(embeddingService.embed("search_document: " + combined));

        Article saved = articleStore.save(article);
        vectorIndex.add(saved);
        return saved;
    }

    // ingestText
    public Article ingestText(MultipartFile file, String namespace) throws IOException{
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String filename = file.getOriginalFilename();
        String title = (filename == null || filename.isBlank())
                ? "text-" + System.currentTimeMillis() : filename;
        Article article = new Article();
        article.setNamespace(ns);
        article.setTitle(title);
        article.setSummary(content);
        article.setUrl("file://" +title);
        article.setIngestedAt(LocalDateTime.now());
        article.setEmbedding(embeddingService.embed("search_document: " + content));

        Article saved = articleStore.save(article);
        vectorIndex.add(saved);
        return saved;
    }
}