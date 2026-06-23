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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private final int embedConcurrency;   // 청크 임베딩 동시 호출 수 (Ollama 부하 한도 내).
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
                            @Value("${ollama.embed-model:nomic-embed-text}") String embedModel,
                            @Value("${ingest.embed.concurrency:8}") int embedConcurrency) {
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
        this.embedConcurrency = Math.max(1, embedConcurrency);
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
        long t0 = System.nanoTime();
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        accessChecker.check(ns);
        String filename = file.getOriginalFilename();
        long tExtractStart = System.nanoTime();
        String content = extractText(file, filename);
        long tExtractMs = (System.nanoTime() - tExtractStart) / 1_000_000;
        if (content == null || content.isBlank()) {
            throw new RuntimeException("No extractable text in file");
        }
        String baseTitle = (filename == null || filename.isBlank())
                ? "text-" + System.currentTimeMillis() : filename;

        long tDedupStart = System.nanoTime();
        boolean already = catalogRepo.findByTitleAndNamespace(baseTitle, ns).isPresent();
        long tDedupMs = (System.nanoTime() - tDedupStart) / 1_000_000;
        if (already) {
            System.out.println("[ingest-timing] " + baseTitle + " skipped (already in catalog) extract=" + tExtractMs + "ms dedup=" + tDedupMs + "ms");
            return List.of();
        }

        long tChunkStart = System.nanoTime();
        List<String> chunks = chunker.chunk(content, maxSize);
        Map<String, String> glossary = new LinkedHashMap<>();
        if (expandAcronyms) {
            glossary.putAll(seedGlossary);
            glossary.putAll(AcronymExpander.buildGlossary(content));
        }
        long tChunkMs = (System.nanoTime() - tChunkStart) / 1_000_000;

        // 약어 확장본을 미리 만들어 두면 임베딩 단계에서 글로서리 락 경합이 없다.
        List<String> expanded = new ArrayList<>(chunks.size());
        for (String raw : chunks) expanded.add(AcronymExpander.expand(raw, glossary));

        // === Phase A: 임베딩 병렬 호출 ===========================================
        // Ollama 서버가 받쳐주는 한 N개 동시 호출 → 직렬 합산 대비 ~N배 단축 기대.
        // save/index는 상태 의존(maxId 카운터·파티션 파일 쓰기)이라 직렬 유지.
        long embedT0 = System.nanoTime();
        List<List<Float>> embeddings = new ArrayList<>(Collections.nCopies(expanded.size(), null));
        ExecutorService pool = Executors.newFixedThreadPool(embedConcurrency);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(expanded.size());
            for (int i = 0; i < expanded.size(); i++) {
                final int idx = i;
                final String text = expanded.get(i);
                futures.add(CompletableFuture.runAsync(
                        () -> embeddings.set(idx, embeddingService.embedDocument(text)), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        long embedMs = (System.nanoTime() - embedT0) / 1_000_000;

        // === Phase B: 직렬 save + index =========================================
        List<Article> saved = new ArrayList<>(expanded.size());
        long saveMs = 0, indexMs = 0;
        for (int i = 0; i < expanded.size(); i++) {
            Article article = new Article();
            article.setNamespace(ns);
            article.setTitle(baseTitle + " #" + (i + 1));
            article.setSummary(expanded.get(i));
            article.setUrl("file://" + baseTitle + "#" + (i + 1));
            article.setIngestedAt(LocalDateTime.now());
            ManualMeta.apply(article, baseTitle);
            article.setEmbedding(embeddings.get(i));

            long s0 = System.nanoTime();
            Article s = articleStore.save(article);
            saveMs += (System.nanoTime() - s0) / 1_000_000;

            long x0 = System.nanoTime();
            indexingService.index(s);
            indexMs += (System.nanoTime() - x0) / 1_000_000;

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
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        int n = saved.size();
        System.out.println("[ingest-timing] " + baseTitle
                + " total=" + totalMs + "ms"
                + " extract=" + tExtractMs + "ms"
                + " dedup=" + tDedupMs + "ms"
                + " chunk=" + tChunkMs + "ms (" + n + " chunks)"
                + " embed=" + embedMs + "ms (avg=" + (n == 0 ? 0 : embedMs / n) + "ms/chunk)"
                + " save=" + saveMs + "ms"
                + " index=" + indexMs + "ms");
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
                // 표 보존 경로(2세대): XHTML로 받아 <table>를 마크다운으로(INGESTION §5.1~5.5).
                // pdf/docx/pptx 표가 구조 그대로 청크에 남는다. txt/md는 태그가 없어 평문과 동일.
                case TIKA -> PdfTableExtractor.toTextWithTables(PdfTableExtractor.toXhtml(in));
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract text from file: " + e.getMessage());
        }
    }

}