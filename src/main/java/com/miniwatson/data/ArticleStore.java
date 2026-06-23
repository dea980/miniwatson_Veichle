package com.miniwatson.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hot 저장소 — 문서 단위 JSONL 파일.
 *
 * 변경 이유: 기존엔 단일 articles.json에 전체를 redump했다(청크당 O(N), 누적 O(N²)).
 *   파티션화한 cold(ArticleParquetStore)와 비대칭이라, 같은 문제(전체 재기록)를 hot이 다시 만들었다.
 * 현 구조: data/articles_hot/<safe(ns||baseTitle)>__<hash>.jsonl
 *   - save = 해당 파티션 파일에 한 줄 append → O(1).
 *   - loadAll = 디렉터리 내 모든 jsonl 라인 스캔.
 *   - saveAll(빈 리스트) = 디렉터리 비우기(TieredArticleStore.compact 계약 유지).
 *   - 부팅 시 레거시 data/articles.json이 있으면 1회 자동 분할 후 삭제.
 * 인터페이스 변경 없음 — TieredArticleStore 등 호출부 수정 불필요.
 */
@Component
public class ArticleStore {

    private static final String DEFAULT_STORAGE_DIR = "./data/articles_hot";
    private static final String DEFAULT_LEGACY_PATH = "./data/articles.json";
    private static final String EXT = ".jsonl";

    private final String STORAGE_DIR;
    private final String LEGACY_PATH;
    private final ObjectMapper objectMapper;

    public ArticleStore() {
        this(DEFAULT_STORAGE_DIR, DEFAULT_LEGACY_PATH);
    }

    /** 테스트용 — 경로를 임의 디렉터리로 격리한다. */
    ArticleStore(String storageDir, String legacyPath) {
        this.STORAGE_DIR = storageDir;
        this.LEGACY_PATH = legacyPath;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.addMixIn(Article.class, EmbeddingPersistMixin.class);
    }

    private abstract static class EmbeddingPersistMixin {
        @JsonProperty(access = JsonProperty.Access.READ_WRITE)
        List<Float> embedding;
    }

    private static String partitionKey(Article a) {
        String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? "default" : a.getNamespace();
        String base = a.getTitle() == null ? "untitled" : a.getTitle().replaceAll(" #\\d+$", "");
        return ns + "||" + base;
    }

    private static String keyToFilename(String key) {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe + "__" + Integer.toHexString(key.hashCode()) + EXT;
    }

    private File partitionFile(Article a) {
        return new File(STORAGE_DIR, keyToFilename(partitionKey(a)));
    }

    /** JSONL 한 줄 = 한 청크. 단건 save는 해당 파일에 append. */
    public synchronized Article save(Article article) throws IOException {
        migrateLegacyIfPresent();
        File dir = new File(STORAGE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create hot dir: " + STORAGE_DIR);
        }
        if (article.getId() == 0) {
            // 단독 호출(드묾) — 디렉터리 전체에서 max id 계산
            long nextId = loadAll().stream().mapToLong(Article::getId).max().orElse(0) + 1;
            article.setId(nextId);
        }
        File f = partitionFile(article);
        try (BufferedWriter w = Files.newBufferedWriter(
                f.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(objectMapper.writeValueAsString(article));
            w.write('\n');
        }
        return article;
    }

    public synchronized List<Article> loadAll() throws IOException {
        migrateLegacyIfPresent();
        List<Article> out = new ArrayList<>();
        File dir = new File(STORAGE_DIR);
        File[] parts = dir.exists() ? dir.listFiles((d, n) -> n.endsWith(EXT)) : null;
        if (parts == null) return out;
        for (File f : parts) readJsonl(f, out);
        return out;
    }

    /**
     * saveAll 시멘틱:
     *  - 빈 리스트 → 디렉터리 비우기(compact 후 hot drain).
     *  - 비어있지 않으면 → 파티션별로 그룹핑해 각 파일 새로 쓰기(전체 상태 재구성).
     */
    public synchronized void saveAll(List<Article> articles) throws IOException {
        File dir = new File(STORAGE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create hot dir: " + STORAGE_DIR);
        }
        // 기존 파일 모두 제거
        File[] parts = dir.listFiles((d, n) -> n.endsWith(EXT));
        if (parts != null) {
            for (File f : parts) {
                if (!f.delete()) System.err.println("Warning: cannot delete " + f);
            }
        }
        if (articles == null || articles.isEmpty()) {
            // 빈 상태로 둠
            File legacy = new File(LEGACY_PATH);
            if (legacy.exists() && !legacy.delete()) {
                System.err.println("Warning: cannot delete legacy " + LEGACY_PATH);
            }
            return;
        }
        Map<String, List<Article>> byPart = new LinkedHashMap<>();
        for (Article a : articles) byPart.computeIfAbsent(partitionKey(a), k -> new ArrayList<>()).add(a);
        for (var e : byPart.entrySet()) {
            File f = new File(dir, keyToFilename(e.getKey()));
            try (BufferedWriter w = Files.newBufferedWriter(
                    f.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Article a : e.getValue()) {
                    w.write(objectMapper.writeValueAsString(a));
                    w.write('\n');
                }
            }
        }
        File legacy = new File(LEGACY_PATH);
        if (legacy.exists() && !legacy.delete()) {
            System.err.println("Warning: cannot delete legacy " + LEGACY_PATH);
        }
    }

    public synchronized boolean deleteByID(long id) throws IOException {
        migrateLegacyIfPresent();
        File dir = new File(STORAGE_DIR);
        File[] parts = dir.exists() ? dir.listFiles((d, n) -> n.endsWith(EXT)) : null;
        if (parts == null) return false;
        for (File f : parts) {
            List<Article> in = new ArrayList<>();
            readJsonl(f, in);
            boolean removed = in.removeIf(a -> a.getId() == id);
            if (!removed) continue;
            if (in.isEmpty()) {
                if (!f.delete()) System.err.println("Warning: cannot delete " + f);
            } else {
                try (BufferedWriter w = Files.newBufferedWriter(
                        f.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (Article a : in) {
                        w.write(objectMapper.writeValueAsString(a));
                        w.write('\n');
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void readJsonl(File f, List<Article> out) throws IOException {
        if (f.length() == 0) return;
        try (var lines = Files.lines(f.toPath(), StandardCharsets.UTF_8)) {
            for (String ln : (Iterable<String>) lines::iterator) {
                String t = ln.trim();
                if (t.isEmpty()) continue;
                out.add(objectMapper.readValue(t, Article.class));
            }
        }
    }

    /** 레거시 단일 파일(articles.json) → 파티션 디렉터리로 1회 자동 분할. */
    private void migrateLegacyIfPresent() throws IOException {
        File legacy = new File(LEGACY_PATH);
        if (!legacy.exists() || legacy.length() == 0) return;
        File dir = new File(STORAGE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create hot dir for migration: " + STORAGE_DIR);
        }
        Article[] arr;
        try {
            arr = objectMapper.readValue(legacy, Article[].class);
        } catch (IOException e) {
            System.err.println("[ArticleStore] legacy migration skipped (parse failed): " + e.getMessage());
            return;
        }
        Map<String, List<Article>> byPart = new LinkedHashMap<>();
        for (Article a : arr) byPart.computeIfAbsent(partitionKey(a), k -> new ArrayList<>()).add(a);
        for (var e : byPart.entrySet()) {
            File f = new File(dir, keyToFilename(e.getKey()));
            try (BufferedWriter w = Files.newBufferedWriter(
                    f.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Article a : e.getValue()) {
                    w.write(objectMapper.writeValueAsString(a));
                    w.write('\n');
                }
            }
        }
        if (!legacy.delete()) System.err.println("[ArticleStore] cannot delete legacy " + LEGACY_PATH);
        System.out.println("[ArticleStore] migrated legacy articles.json → "
                + STORAGE_DIR + " (" + arr.length + " articles, " + byPart.size() + " partitions)");
    }

    /** TieredArticleStore.save 가 hot.loadAll().size()를 임계치 검사로 사용. 라인 카운트로 비용 절감. */
    public synchronized int size() throws IOException {
        migrateLegacyIfPresent();
        File dir = new File(STORAGE_DIR);
        File[] parts = dir.exists() ? dir.listFiles((d, n) -> n.endsWith(EXT)) : null;
        if (parts == null) return 0;
        int n = 0;
        for (File f : parts) {
            if (f.length() == 0) continue;
            try (var lines = Files.lines(f.toPath(), StandardCharsets.UTF_8)) {
                n += (int) lines.filter(s -> !s.isBlank()).count();
            }
        }
        return n;
    }
}
