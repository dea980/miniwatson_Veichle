# Ingestion Formats (멀티포맷 수집)

ingest 파이프라인이 어떤 파일 포맷을 받고 어떻게 텍스트를 뽑는지 기록한다. 추출 이후 단계(청킹 -> 임베딩 -> 인덱싱)는 포맷과 무관하게 동일하다. 추출만 포맷별로 갈린다. 이 문서는 실제 구현과 의존성을 읽고 확인한 내용만 적는다.

## 1. 지원 포맷

| 포맷 | 추출기 | 경로 |
|---|---|---|
| PDF / DOCX / PPTX / XLSX / HTML / TXT / MD / CSV | Apache Tika (`tika.parseToString`) | POST /api/data/ingest-file |
| HWP (5.x 바이너리) | kr.dogfoot:hwplib | POST /api/data/ingest-file |
| HWPX | kr.dogfoot:hwpxlib | POST /api/data/ingest-file |
| 이미지 (PNG/JPG) | 멀티모달: OcrService(Tesseract) + Vision(llava) | POST /api/multimodal/ingest |
| Wikipedia 제목 | REST summary | POST /api/data/ingest?title= |

파일 업로드 3종(Tika/HWP/HWPX)은 모두 같은 엔드포인트(ingest-file)로 들어와 IngestionService 안에서 확장자로 갈린다. 이미지는 텍스트 추출이 아니라 OCR+Vision으로 "검색 가능한 설명"을 만드는 별도 경로(MULTIMODAL.md)다. Wikipedia는 파일이 아니라 제목으로 REST를 호출해 summary를 받는다.

## 2. 분기 지점

IngestionService.ingestText()가 호출하는 extractText(file, filename) 안에서 확장자로 추출기를 고른다.

```java
private String extractText(MultipartFile file, String filename) {
    String name = filename == null ? "" : filename.toLowerCase();
    try (InputStream in = file.getInputStream()) {
        if (name.endsWith(".hwpx")) return hwpExtractor.fromHwpx(in);
        if (name.endsWith(".hwp"))  return hwpExtractor.fromHwp(in);
        return tika.parseToString(in);   // pdf/docx/pptx/xlsx/html/txt/md/csv
    } catch (Exception e) {
        throw new RuntimeException("Failed to extract text from file: " + e.getMessage());
    }
}
```

`.hwpx` -> hwpExtractor.fromHwpx, `.hwp` -> fromHwp, 그 외 전부 Tika. 추출이 끝나면 분기가 합류해 chunker.chunk -> embedDocument -> indexingService.index로 동일하게 흐른다. 포맷별로 다른 건 텍스트를 얻는 방법뿐이고, 그 뒤 RAG 파이프라인은 한 줄도 안 갈린다.

## 3. HWP/HWPX 지원 추가 (이번 작업)

왜: 한국 공공기관과 기업 문서의 사실상 표준이 HWP/HWPX인데 Tika는 이를 파싱하지 못한다. 한국어 코퍼스(채용 서류, 공고 등)를 RAG에 넣으려면 전용 추출기가 필요했다.

의존성(pom.xml):

| 라이브러리 | 버전 | 대상 |
|---|---|---|
| kr.dogfoot:hwplib | 1.1.8 | HWP 5.x 바이너리 |
| kr.dogfoot:hwpxlib | 1.0.5 | HWPX(zip 기반 XML) |

HwpExtractor(@Component) 구현:

- fromHwp(InputStream): `HWPReader.fromInputStream(in)`으로 InputStream을 직접 읽는다.
- fromHwpx(InputStream): InputStream을 `File.createTempFile`로 임시파일에 복사한 뒤 `HWPXReader.fromFile(tmp)`로 읽는다(추출 성공 시 그 텍스트 반환). 단 hwpxlib 1.0.5가 이미지 포함 HWPX에서 manifest null NPE(`ContentHPFFile.manifest()`)를 내므로, 실패 시 zip 안의 `Preview/PrvText.txt`(UTF-8 평문 미리보기)로 폴백한다. finally에서 임시파일을 지운다.
- 둘 다 `TextExtractor.extract(..., TextExtractMethod.InsertControlTextBetweenParagraphText, ...)`로 본문을 뽑는다.

PrvText 폴백 덕에 깨졌거나 이미지가 박힌 HWPX에도 ingest가 500나지 않고 최소한의 미리보기 텍스트를 확보한다. OCR/DJL cross-encoder 폴백과 같은 패턴 — 라이브러리가 죽어도 파이프라인은 graceful degradation으로 살린다.

## 4. 함정/교훈 (실제 겪음)

같은 저자(kr.dogfoot)의 두 라이브러리인데 reader API가 비대칭이다. hwplib의 HWPReader에는 `fromInputStream`이 있지만, hwpxlib의 HWPXReader에는 그게 없고 `fromFile`/`fromFilepath`만 있다. 그래서 hwpx만 InputStream을 임시파일로 떨어뜨린 뒤 파일 경로로 읽는다. hwp는 InputStream 직접.

시그니처는 추측하지 않고 javadoc(javadoc.io)으로 직접 확인했다. "다른 예제에서 본 이름이 맞겠지"로 가정하면 컴파일 단계에서 막힌다. DJL cross-encoder 때 똑같은 교훈을 얻었다(RERANKING.md 6절 — Factory가 버전에 없고 입력 타입이 String[]이 아니라 StringPair였던 건). 외부 라이브러리는 버전마다 클래스명과 시그니처가 다르니 프로젝트가 실제로 의존하는 버전의 javadoc을 본다.

또 하나: 의존성을 추가한 뒤 한 소스파일이 bad source file이면 Lombok annotation processing이 중단돼, 그 파일과 무관한 @Data 클래스들의 getter/setter가 "cannot find symbol"로 줄줄이 뜬다. 에러 100개가 떠도 근본은 한 파일일 수 있다. 맨 위 에러부터 보고, 첫 에러를 고치면 나머지가 한꺼번에 사라지는지 확인한다.

## 5. 추출 품질의 한계

- HWP는 표와 머리말 추출이 거칠 수 있다. InsertControlTextBetweenParagraphText는 표 셀 텍스트도 문단 사이에 끌어오므로, 표가 많은 문서는 셀 값이 본문에 섞여 청크 경계가 지저분해진다.
- 스캔 이미지로 된 PDF/HWP는 텍스트가 안 나온다. 그 파일들은 본문이 이미지라 Tika/hwplib가 뽑을 글자가 없다. 그런 입력은 OCR 경로(/api/multimodal/ingest)로 처리해야 한다.
- 표(xlsx/csv)는 Tika로 평탄화하면 행이 헤더와 분리돼 셀과 집계 조회가 약하다. "무슨 표인가" 같은 거친 사실만 RAG로 잡고, "몇 개/평균/필터" 같은 정밀하고 집계가 필요한 질의는 벡터가 아니라 text-to-SQL 경로로 처리한다 — 표는 임베딩이 아니라 SQL([TABULAR-SQL.md](TABULAR-SQL.md), DuckDB 라이크하우스)이 정답이다.

## 5.1 매뉴얼 PDF — 표·이미지·다이어그램 (현재 한계와 로드맵)

현 PDF 인제스트는 `tika.parseToString` = **텍스트만** 뽑는다. 매뉴얼(오너스/정비)은 표·다이어그램·그래프가 핵심인데, 이게 다음처럼 갈린다.

| 요소 | 현재 | 비고 |
|---|---|---|
| 산문 텍스트 | ✅ | Tika→PDFBox 텍스트 레이어(archive 매뉴얼은 OCR 레이어 보유) |
| **표**(토크값·점검주기) | ⚠️ 깨짐 | `parseToString` 평탄화 → 행·열 뭉개짐 |
| **이미지·다이어그램**(배선도·경고등) | ❌ 유실 | 텍스트 추출은 그림을 가져오지 않음 |
| 그래프 | ❌ 유실 | 동일 |

> 멀티모달(vision/llava) 경로는 있으나(`/api/multimodal/ingest`, `DiagnosePanel`) **단건 이미지 업로드용**이고 PDF 인제스트엔 연결돼 있지 않다. 즉 *문서 전용 어시스턴트*([AS-OPERATIONS.md](AS-OPERATIONS.md)의 문서 RAG)도 현재는 그 문서의 **텍스트**만 근거로 답한다.

**검증법**: 문서 전용 채팅에서 표/그림 의존 질문(예: "엔진 오일 토크값 표", "경고등 아이콘 종류")을 던져 답이 부실하면 유실 확인.

**개선 로드맵 (사다리):**

1. **표 (가벼움, 우선)** — Tika를 평문 대신 **XHTML 모드**(`ToXMLContentHandler`)로 받아 `<table>` 구조를 보존하거나, pdfplumber/Camelot로 표를 **마크다운/CSV로 추출**해 청킹. 정밀 집계(몇 개/평균/필터)는 임베딩이 아니라 text-to-SQL로([TABULAR-SQL.md](TABULAR-SQL.md)).
2. **이미지·다이어그램 (무거움, 임팩트 큼)** — 페이지를 이미지로 렌더(PDFBox `PDFRenderer`/pdf2image) → **vision 모델로 캡션**("이 페이지는 배선도, X 회로") → 캡션을 텍스트로 인덱싱(검색 가능) + 원본 이미지 저장(표시용). **기존 vision 경로 재사용.** 비용 절감: 전 페이지가 아니라 *이미지 비중 높은 페이지만* 선택적으로.
3. **OCR** — 스캔본 PDF/HWP는 Tesseract 경로. (archive 매뉴얼은 텍스트 레이어가 있어 불필요.)

**설계 원칙**: 추출은 `extractText` 한 곳에 격리돼 있어, 표/이미지 개선도 *그 분기와 전용 추출기*만 손대면 되고 청킹·임베딩·인덱싱 파이프라인은 불변(§6). 표는 SQL, 그림은 vision-캡션, 산문은 텍스트 — **요소별로 맞는 경로**로 보내는 게 핵심이다.

## 5.2 업계는 어떻게 하나 — 복잡 PDF 파싱 3세대 (참고)

복잡한 PDF(표·그림·차트)를 RAG에 넣는 방식은 2024~2025에 크게 3갈래로 갈렸다.

**1세대 — 텍스트 추출 (현재 우리)**
PDFBox/Tika로 텍스트만. 빠르고 단순하나 표는 평탄화, 그림은 유실. 산문엔 충분.

**2세대 — 레이아웃 인식 파싱 (현 프로덕션 "정석")**
딥러닝으로 페이지 레이아웃(제목/표/그림)을 감지해 *요소별로* 구조 추출.
- 표: **pdfplumber·Camelot**(간단) → 마크다운/CSV.
- 종합 파이프라인: **Unstructured.io**, **Docling(IBM Research)** — 레이아웃 감지 + 표 재구성 + 스캔본 OCR + 그림 분리. 그림은 떼어 VLM 캡션.
- 장점: 정밀 인용(요소 단위) 유지. 단점: 파이프라인이 무겁고 파싱 실패 여지.

**3세대 — 비전 우선 / page-as-image (2025 SOTA)**
파싱을 **건너뛰고** 페이지를 통째 이미지로 렌더 → 비전-언어 모델이 임베딩.
- **ColPali / ColQwen2** — PaliGemma 백본, 페이지 이미지를 패치 멀티벡터로. **OCR·레이아웃 감지 없음** → 파싱 실패가 원천 차단, 표·차트·다이어그램을 네이티브 검색. 검색된 페이지 이미지를 VLM(Qwen-VL/Gemini)이 보고 답함.
- 장점: 그림·차트에 강함, 파싱 무관. 단점: 비전 임베딩 인덱스·추론 비용↑(GPU), 인용 단위가 "페이지"라 정밀 인용 약함.

**선택 기준**: 정밀 인용·비용 민감 → 2세대. 그림·차트가 핵심 → 3세대. 둘을 섞기도(표=2세대 구조추출 + 그림=3세대/VLM 캡션).

| 세대 | 대표 | 표 | 그림/차트 | 비용 |
|---|---|---|---|---|
| 1 텍스트 | Tika/PDFBox | 평탄(약) | 유실 | 최저 |
| 2 레이아웃 | Unstructured, **Docling(IBM)**, pdfplumber | 구조추출(강) | 분리+캡션 | 중 |
| 3 비전 | **ColPali/ColQwen2** | 네이티브 | 네이티브(강) | 높음(GPU) |

내러티브 연결: **Docling은 IBM Research**라 watsonx/Granite 결과 맞고, **ColQwen2**는 우리 멀티모달(llava)·온디바이스의 다음 칸이다.

출처:
- ColPali: Efficient Document Retrieval with Vision Language Models — https://arxiv.org/pdf/2407.01449
- Using Vision Models for PDF Parsing in RAG Systems — https://www.chitika.com/vision-models-pdf-parsing-rag/
- Multimodal RAG: Retrieving from Images, PDFs, and Tables — https://tensoria.fr/en/blog/multimodal-rag-images-pdfs-tables
- Extracting Knowledge from Complex PDFs with ColPali — https://www.superteams.ai/blog/extracting-knowledge-from-complex-pdf-documents-enterprise
- Multimodal RAG in 2026 — https://bigdataboutique.com/blog/multimodal-rag-retrieval-over-images-pdfs-and-text

## 5.3 우리 결정 — 요소별 경로 (왜 "페이지 캡션"을 안 쓰나)

실제 경험: **페이지 렌더 → 로컬 VLM(llava) 캡션 → 인덱싱**(2.5세대, "가난한 자의 ColPali")을 시도했으나 **정확도가 낮아 OCR로 회귀**했다. 이 실패는 정당하며, 방향을 다시 잡아준다.

**왜 캡션이 실패했나:**
- 페이지를 한 문장으로 **이중 압축**(약한 VLM의 이해 + 캡션 텍스트화) → 손실이 크다. 작은 로컬 VLM에선 특히.
- 더 근본적으로, **"표까지 캡션으로" 묶은 게 잘못**이었다. 표는 비전/캡션 자체가 틀린 도구다.

**결정 — 요소별로 맞는 경로로 분리:**

| 요소 | 채택 | 이유 |
|---|---|---|
| **표**(토크·점검주기) | **구조 추출**(Tika XHTML/pdfplumber → 마크다운) + 정밀집계는 SQL | 결정적·정확, 온디바이스 가능. 캡션은 오답 |
| **텍스트성 그림 라벨**(코드·아이콘명) | **OCR** | 라벨·코드는 OCR이 정확(현재 선택 유지) |
| **진짜 다이어그램**(배선도·전개도) | **③ ColQwen / 클라우드 VLM** = 스케일 칸 | 캡션은 lossy. 강한 VLM이 *답변 시 원본 페이지를 직접* 봐야 정확. 단 GPU 전제라 온디바이스 PoC 밖 |

**핵심**: "비전 캡션"은 **표엔 틀린 도구**(구조추출이 정답), **진짜 다이어그램엔 lossy**(그래서 ③ 비전검색이 정답). 둘을 한 도구로 묶으면 양쪽 다 어중간해진다 — 이게 ②가 실패한 이유다. PoC는 표=구조추출 + 라벨=OCR로 가고, 진짜 다이어그램은 한계로 인정하거나 *그 페이지만* 클라우드 VLM으로 선택 라우팅(하이브리드). 풀 비전 인덱스(ColQwen)는 GPU 스케일 칸으로 남긴다([DECISIONS.md](DECISIONS.md) §7.5의 학습/서빙 예산 논리와 동일).

## 6. 새 포맷 추가법

extractText 분기에 확장자 한 줄과 전용 추출기 호출만 더하면 된다.

```java
if (name.endsWith(".xyz")) return xyzExtractor.from(in);
```

호출부(ingestText)도, 추출 이후 파이프라인(청킹/임베딩/인덱싱)도 바뀌지 않는다. 추출이라는 한 관심사만 격리돼 있어, 포맷이 늘어도 변경 범위가 extractText 한 메서드와 새 추출기 클래스로 한정된다.
