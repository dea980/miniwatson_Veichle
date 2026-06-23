# 하이브리드 검색 보강 (RAG 정확도 단계 2)

> 1단계(메타 1차 필터)로 후보 노이즈를 줄였지만, **남은 후보 안에서 정렬은 BM25+벡터 RRF**가 책임진다.
> 현행 구현에 한글 매뉴얼에는 치명적인 결함이 있어 우선 그것부터 잡는다.

## 1. 진단 — BM25가 한국어에서 0점이다

`KeywordIndex.tokenize()`:
```java
text.toLowerCase().split("[^a-z0-9]+")
```

- 정규식이 영문 소문자/숫자만 토큰으로 인정.
- 한글(`\p{IsHangul}`) 전부 split 구분자로 떨어져 토큰이 0개.
- 결과: 한글 청크의 `docTokens`는 영문 알파벳·숫자만 남고, 한글 쿼리는 매칭 0건 → **BM25 점수 0**.
- `HybridRetriever`의 RRF가 벡터 단일로 degenerate. "하이브리드"라는 이름만 남고 실제 효과는 벡터 단독.

**증거**: `tokenize("타이어 공기압 점검 주기")` → `[]`. `tokenize("LED 경고등 P0420")` → `["led", "p0420"]`.

## 2. 처방 — 한국어 토큰화 보강

가장 작은 변경으로 가장 큰 효과를 본다(외부 사전·형태소 분석기 없이):

1. **유니코드 인식 분리**: 영문/숫자/한글을 토큰 문자로 인정. `[^\p{IsHangul}\p{IsAlnum}]+` 로 split.
2. **한글 어절(공백 단위) + 문자 bigram**: 한국어는 교착어라 어절 끝의 조사·어미가 매칭을 깬다("타이어를" vs "타이어"). 어절 토큰과 함께 **char-2gram**도 함께 색인하면 부분일치를 회복한다.
   - 예: "타이어 공기압" → `["타이어", "공기압", "타이", "이어", "공기", "기압"]` (영문/숫자는 어절만)
3. **영문/숫자는 기존 유지** — 변수명·코드(P0420 등)는 토큰 그대로.

트레이드오프: 색인 크기 ~2배(bigram 추가). 461권 KB에선 메모리 영향 미미.

## 3. 구현

`KeywordIndex.tokenize()` 한 곳만 수정. BM25 공식·RRF 머지는 그대로.

```java
private static final Pattern SEP = Pattern.compile("[^\\p{IsHangul}\\p{IsAlnum}]+");

private List<String> tokenize(String text) {
    if (text == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String t : SEP.split(text.toLowerCase())) {
        if (t.isBlank()) continue;
        // 한글 어절: 어절 자체 + char-2gram(교착어 조사·어미 흡수)
        boolean hangul = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) { hangul = true; break; }
        }
        out.add(t);
        if (hangul && t.length() >= 2) {
            for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
        }
    }
    return out;
}
```

쿼리와 문서를 같은 함수로 토큰화하므로 일관성 유지.

## 4. 관찰 가능성

문제 재발 방지를 위해:

- `KeywordIndex.hydrate()` 로그에 "namespaces, total docs, avg tokens/doc, vocabulary size"를 추가하면 한글 토큰화 망가짐을 다음 사람도 즉시 본다.
- `HybridRetriever`에 디버그 메서드(점수 분포 dump)는 후속 PR.

## 5. 적용 후 기대값

- 한글 쿼리 BM25가 실제로 점수를 만든다(이전엔 0).
- RRF가 벡터(의미)와 BM25(키워드)를 진짜로 융합 — 차종·부품 키워드가 정확히 들어간 청크가 위로 올라옴.
- 메타 1차 필터(1단계) + 진짜 하이브리드 = 매뉴얼 KB 정확도 누적 개선.

## 6. 검증 방법

- `./mvnw -q -DskipTests compile` — 컴파일.
- 단위: `KeywordIndexTest`에 한글 쿼리 ↔ 한글 문서 매칭 케이스 추가(후속).
- 통합: 동일 질의에 대해 hybrid on/off 결과 비교 (eval seed 활용).

## 7. 다음 단계(3단계로 넘어가는 트리거)

- 한글 토크나이저 보강 후에도 top-K 정확도가 부족하면 **bge-reranker-base** 같은 cross-encoder를 top-50→top-5 단계로 추가.
- 트레이드오프: 쿼리당 +100~300ms (작은 모델·CPU 기준).
