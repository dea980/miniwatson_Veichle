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

import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

import org.apache.tika.Tika;

@Service
public class IngestionService {

    private final String WIKIPEDIA_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private static final String DEFAULT_NS = "default";
    private final RestTemplate restTemplate = new RestTemplate();

    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final OllamaService ollamaService;   // 멀티모달: 비전 모델로 이미지 캡션 생성
    private final OcrService ocrService;
    private final ArticleRepository articleStore;
    private final Chunker chunker;// 청킹 분리
    private final int maxSize;
    private final Tika tika = new Tika();
    public IngestionService(ArticleRepository articleStore,
                            EmbeddingService embeddingService,
                            VectorIndex vectorIndex,
                            OllamaService ollamaService,
                            OcrService ocrService,
                            Map<String, Chunker> chunkers,                         // 모든 Chunker 빈
                            @Value("${chunking.strategy:recursive}") String strategy,
                            @Value("${chunking.max-size:1000}") int maxSize) {
        this.articleStore = articleStore;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.ollamaService = ollamaService;
        this.ocrService = ocrService;
        this.chunker = chunkers.getOrDefault(strategy, chunkers.get("recursive"));
        this.maxSize = maxSize;

    }

    /** Backward-compatible: ingest into the "default" namespace. */
    public Article ingest(String title) throws IOException {
        return ingest(title, DEFAULT_NS);
    }

    public Article ingest(String title, String namespace) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;

        // 입력 "Vector_database" -> 저장 제목 "Vector database" 와 맞추기 위해 정규화
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

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
        HttpEntity<String> request = new HttpEntity<>(headers);

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

        Article article = new Article();
        article.setNamespace(ns);
        article.setTitle(response.getTitle());
        article.setSummary(response.getExtract());
        article.setUrl(response.getContent_urls().getDesktop().getPage());
        article.setIngestedAt(LocalDateTime.now());

        String textToEmbed = response.getTitle() + ". " + response.getExtract();
        article.setEmbedding(embeddingService.embed("search_document: " + textToEmbed));

        Article saved = articleStore.save(article);
        vectorIndex.add(saved);
        return saved;
    }

    /**
     * 멀티모달 RAG: 이미지를 "검색 가능한 지식"으로 변환한다.
     */
    public Article ingestImage(MultipartFile image, String namespace, String visionModel) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        byte[] bytes = image.getBytes();
        String base64 = Base64.getEncoder().encodeToString(bytes);

        String ocr = ocrService.extract(bytes);

        String prompt = "Describe the layout and type of this image "
                + "(is it a chart, table, document, or photo?) and what it is about in one or two sentences. "
                + "Do NOT read or guess specific numbers — numeric values are extracted separately by OCR.";
        String caption = ollamaService.askWithImages(prompt, visionModel, List.of(base64));

        String combined = "[OCR]\n" + ocr + "\n\n[Vision]\n" + caption;
        String filename = image.getOriginalFilename();
        String title = (filename == null || filename.isBlank())
                ? "image-" + System.currentTimeMillis()
                : filename;

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

    /** 임의 파일(PDF/docx/txt/csv...)을 추출 → 청킹 → 청크별 Article 저장. */
    public List<Article> ingestText(MultipartFile file, String namespace) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;

        String content;
        try {
            content = tika.parseToString(file.getInputStream());
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract text from file: " + e.getMessage());
        }
        if (content == null || content.isBlank()) {
            throw new RuntimeException("No extractable text in file");
        }

        String filename = file.getOriginalFilename();
        String baseTitle = (filename == null || filename.isBlank())
                ? "text-" + System.currentTimeMillis() : filename;

        List<Article> existing = articleStore.loadAll();
        boolean already = existing.stream().anyMatch(a -> {
            String aNs = (a.getNamespace() == null || a.getNamespace().isBlank()) ? DEFAULT_NS : a.getNamespace();
            String aBase = a.getTitle().replaceAll(" #\\d+$", "");
            return aNs.equals(ns) && aBase.equals(baseTitle);
        });
        if (already) {
            return existing.stream()
                    .filter(a -> a.getTitle().replaceAll(" #\\d+$", "").equals(baseTitle))
                    .toList();   // 이미 있는 청크들 그대로 반환 (재삽입 안 함)
        }

        List<String> chunks = chunker.chunk(content, maxSize); // 분리된 청커 사용
        List<Article> saved = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            Article article = new Article();
            article.setNamespace(ns);
            article.setTitle(baseTitle + " #" + (i + 1));
            article.setSummary(c);
            article.setUrl("file://" + baseTitle + "#" + (i + 1));
            article.setIngestedAt(LocalDateTime.now());
            article.setEmbedding(embeddingService.embed("search_document: " + c));

            Article s = articleStore.save(article);
            vectorIndex.add(s);
            saved.add(s);
        }
        return saved;
    }
}