package com.miniwatson.service;

import com.miniwatson.data.Article;
// import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.data.ArticleRepository;
import com.miniwatson.data.VectorIndex;
import com.miniwatson.data.WikipediaResponse;
import com.miniwatson.governance.DocumentCatalog;
import com.miniwatson.governance.DocumentCatalogRepository;
import com.miniwatson.service.IndexingService;
import com.miniwatson.service.HwpExtractor;
import com.miniwatson.security.TenantAccessChecker;
import com.miniwatson.service.llm.EmbeddingClient;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;

import org.apache.tika.Tika;

import jakarta.annotation.PostConstruct;

@Service
public class IngestionService {

    private final String WIKIPEDIA_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private static final String DEFAULT_NS = "default";
    private final RestTemplate restTemplate = new RestTemplate();

    private final EmbeddingClient embeddingService;
    // private final VectorIndex vectorIndex;
    private final IndexingService indexingService;
    private final OllamaService ollamaService;   // 멀티모달: 비전 모델로 이미지 캡션 생성
    private final OcrService ocrService;
    private final ArticleRepository articleStore;
    private final Chunker chunker;// 청킹 분리
    private final int maxSize;
    private final boolean expandAcronyms;   // 약어->정식명 주입 토글 (A/B·거버넌스)

    private Map<String, String> seedGlossary = Map.of();   // ★추가: 자동차 약어/DTC 시드 사전

    private final Tika tika = new Tika();
    private final HwpExtractor hwpExtractor;
    private final DocumentCatalogRepository catalogRepo;
    private final String embedModel;
    private final TenantAccessChecker accessChecker;   // 테넌트 격리 강제(적재도)
    //private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IngestionService.class);
    public IngestionService(ArticleRepository articleStore,
                            EmbeddingClient embeddingService,
                            IndexingService indexingService,
                            OllamaService ollamaService,
                            OcrService ocrService,
                            HwpExtractor hwpExtractor,
                            TenantAccessChecker accessChecker,
                            Map<String, Chunker> chunkers,                         // 모든 Chunker 빈
                            DocumentCatalogRepository catalogRepo,
                            @Value("${chunking.strategy:recursive}") String strategy,
                            @Value("${chunking.max-size:1000}") int maxSize,
                            @Value("${chunking.expand-acronyms:true}") boolean expandAcronyms,
                            @Value("${ollama.embed-model:nomic-embed-text}") String embedModel) {
        this.articleStore = articleStore;
        this.embeddingService = embeddingService;
        this.indexingService = indexingService;
        this.ollamaService = ollamaService;
        this.ocrService = ocrService;
        this.chunker = chunkers.getOrDefault(strategy, chunkers.get("recursive"));
        this.maxSize = maxSize;
        this.expandAcronyms = expandAcronyms;
        this.catalogRepo = catalogRepo;
        this.embedModel = embedModel;
        this.hwpExtractor = hwpExtractor;
        this.accessChecker = accessChecker;

    }

    /** Backward-compatible: ingest into the "default" namespace. */
    public Article ingest(String title) throws IOException {
        return ingest(title, DEFAULT_NS);
    }

    public Article ingest(String title, String namespace) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        accessChecker.check(ns);   // 격리: 이 namespace에 적재 권한 있는지

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
        article.setEmbedding(embeddingService.embedDocument(textToEmbed));

        Article saved = articleStore.save(article);
        indexingService.index(saved);
        return saved;
    }

    /**
     * 멀티모달 RAG: 이미지를 "검색 가능한 지식"으로 변환한다.
     */
    public Article ingestImage(MultipartFile image, String namespace, String visionModel) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        accessChecker.check(ns);   // 격리: 이미지 적재 권한
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
        article.setEmbedding(embeddingService.embedDocument(combined));

        Article saved = articleStore.save(article);
        indexingService.index(saved);
        return saved;
    }

    /** 임의 파일(PDF/docx/txt/csv...)을 추출 → 청킹 → 청크별 Article 저장. */
    public List<Article> ingestText(MultipartFile file, String namespace) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        accessChecker.check(ns);   // 격리: 파일 적재 권한
        String filename = file.getOriginalFilename();
        String content = extractText(file, filename);          // 확장자 분기 (HWP/HWPX/Tika) — 결과를 덮어쓰지 않는다
        if (content == null || content.isBlank()) {
            throw new RuntimeException("No extractable text in file");
        }
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
        // 약어 정의: 자동차 시드 사전 + 문서에서 추출한 정의를 병합
        Map<String, String> glossary = new LinkedHashMap<>();
        if (expandAcronyms) {
            glossary.putAll(seedGlossary);                            // ★ 자동차 시드 먼저
            glossary.putAll(AcronymExpander.buildGlossary(content));  // 문서 정의가 있으면 우선(덮어씀)
        }
        List<Article> saved = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String c = AcronymExpander.expand(chunks.get(i), glossary);   // 약어->정식명 보정
            Article article = new Article();
            article.setNamespace(ns);
            article.setTitle(baseTitle + " #" + (i + 1));
            article.setSummary(c);                              // 저장본 = 임베딩본 (감사 추적 일관성)
            article.setUrl("file://" + baseTitle + "#" + (i + 1));
            article.setIngestedAt(LocalDateTime.now());
            article.setEmbedding(embeddingService.embedDocument(c));

            Article s = articleStore.save(article);
            indexingService.index(s);
            saved.add(s);
        }
        catalogRepo.findByTitleAndNamespace(baseTitle, ns).ifPresentOrElse(
                c -> { c.setChunks(saved.size()); catalogRepo.save(c); },
                () -> {
                    DocumentCatalog c = new DocumentCatalog();
                    c.setTitle(baseTitle);
                    c.setNamespace(ns);
                    c.setSourceType("file");
                    c.setChunks(saved.size());
                    c.setEmbedModel(embedModel);   // 임베더명 (없으면 상수)
                    c.setIngestedAt(LocalDateTime.now());
                    catalogRepo.save(c);
                    // log.info("[catalog] saved {} [{}]", baseTitle, ns); // debugging
                }
        );
        return saved;
    }
    /** 자동차 약어/DTC 시드 사전 로드. 파일 없거나 깨져도 기존 동작 유지(빈 맵). */
    @PostConstruct
    public void loadSeedGlossary() {
        try {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = om.readTree(
                    new org.springframework.core.io.ClassPathResource(
                            "vehicle/automotive-glossary.json").getInputStream());
            Map<String, String> seed = new LinkedHashMap<>();
            root.get("acronyms").fields()
                    .forEachRemaining(e -> seed.put(e.getKey(), e.getValue().asText()));
            root.get("dtc_sample").fields()
                    .forEachRemaining(e -> seed.put(e.getKey(), e.getValue().asText()));
            this.seedGlossary = seed;
        } catch (Exception ignore) {
            // 시드 없으면 문서 추출만으로 동작 (기존과 동일)
        }
    }

    @PostConstruct
    public void hydrateCatalog(){
        try{
            List<Article> all = articleStore.loadAll();
            Map<String, List<Article>> grouped = new LinkedHashMap<>();
            for (Article a : all){
                String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? "default" : a.getNamespace();
                String base = a.getTitle().replaceAll(" #\\d+$", "");
                grouped.computeIfAbsent(ns + "||" + base, k -> new ArrayList<>()).add(a);
            }
            for (var e: grouped.entrySet()){
                String[] parts = e.getKey().split("\\|\\|",2);
                String ns = parts[0], base = parts[1];
                if (catalogRepo.findByTitleAndNamespace(base, ns).isPresent()) continue;
                DocumentCatalog c = new DocumentCatalog();
                c.setTitle(base);
                c.setNamespace(ns);
                c.setSourceType(guessType(e.getValue().get(0)));   // url 스킴으로 추정
                c.setChunks(e.getValue().size());
                c.setEmbedModel(embedModel);
                c.setIngestedAt(e.getValue().get(0).getIngestedAt());
                catalogRepo.save(c);

            }
        } catch (Exception ex){

        }
    }
    private String guessType(Article a) {
        String url = a.getUrl() == null ? "" : a.getUrl();
        if (url.startsWith("image://")) return "image";
        if (url.startsWith("file://")) return "file";
        return "wikipedia";
    }
    /** 확장자 -> 추출기 라우팅. 순수 함수라 단위 테스트 가능 (IngestionServiceTest). */
    enum SourceFormat { HWP, HWPX, TIKA }
    static SourceFormat formatOf(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".hwpx")) return SourceFormat.HWPX;   // .hwp보다 먼저 (접미사 포함관계)
        if (n.endsWith(".hwp"))  return SourceFormat.HWP;
        return SourceFormat.TIKA;                            // pdf/docx/pptx/xlsx/html/txt/md/csv...
    }
    private String extractText(MultipartFile file, String filename) {
        try (InputStream in = file.getInputStream()) {
            return switch (formatOf(filename)) {
                case HWPX -> hwpExtractor.fromHwpx(in);
                case HWP  -> hwpExtractor.fromHwp(in);
                case TIKA -> tika.parseToString(in);         // pdf/docx/pptx/xlsx/html/txt/md
                // 표 보존 경로(2세대): PdfTableExtractor.toTextWithTables(PdfTableExtractor.toXhtml(in)) 로 교체.
                //   - toTextWithTables의 TODO를 구현하면 <table>가 마크다운 표로 청크에 남는다(INGESTION §5.1~5.3).
                //   - 폴백 구현이라 지금 교체해도 회귀는 없지만, TODO 완성 전엔 표 이득이 없으니 그대로 둠.
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract text from file: " + e.getMessage());
        }
    }

}