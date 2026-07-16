# 지식그래프 / 온톨로지 (차종-리콜-부품-증상)

> 요지: 리콜, 불만, 부품이 서로 다른 어휘로 흩어져 있다. 이걸 하나의 개념 체계(온톨로지)로 통합하고, DuckDB 위 그래프 순회로 "이 차종 이 부위" 하나에 규제(리콜)·증상(불만)·비용(부품)을 한 번에 답한다. 검색 성능이 아니라 설명가능성과 내비게이션을 위한 그래프다.

## 0. 왜 그래프인가 (동기와 스코프)

watson-graph 트랙에서 그래프 검색은 eval상 벡터 recall을 못 이겼고, 그래서 그래프를 검색 엔진이 아니라 설명가능성 도구로 스코프했다. 이 온톨로지도 같은 원칙이다. 값어치는 "더 잘 찾는다"가 아니라 "흩어진 사실을 한 개념 아래 모아 왜인지 보여준다"에 있다.

별도 그래프 스토어(Neo4j 등) 대신 DuckDB 위 그래프를 택한 이유: 데이터가 이미 DuckDB에 있고, 순회가 조인으로 표현되며, 결정적이고 감사 가능하다. 새 인프라 부담 없이 온톨로지의 값(어휘 통합 + 순회)을 얻는다.

## 1. 노드와 엣지

노드 5종:
- Model (차종)
- Component (부위, 정규 개념 — 아래 정규화 참조)
- Recall (리콜 캠페인, NHTSACampaignNumber)
- Complaint (불만/증상, odiNumber + summary)
- Part (부품, 카탈로그)

엣지:
- Model has_recall Recall (recalls: model, campaign)
- Recall affects Component (recalls: campaign, component)
- Model has_complaint Complaint (complaints: model, odiNumber)
- Complaint about Component (complaints: odiNumber, components)
- Part services Component (parts: part, component)

허브는 Component다. 리콜, 불만, 부품이 전부 부위로 만난다. 그래서 Model to Component to {Recall, Complaint, Part} 순회 한 번이 한 부위의 규제, 증상, 비용을 모은다.

## 2. 온톨로지의 핵심 = 어휘 정규화

세 소스가 부위를 다르게 부른다:
- recalls Component: `AIR BAGS:SIDE/WINDOW:CURTAIN`, `SERVICE BRAKES, HYDRAULIC:POWER ASSIST`, `POWER TRAIN:AUTOMATIC TRANSMISSION:...`
- complaints components: `VISIBILITY/WIPER`, `ELECTRICAL SYSTEM`, `POWER TRAIN`
- parts component: `AIR BAGS`, `BRAKE`, `TRANSMISSION` (부품 카탈로그의 거친 11종)

이질적 어휘를 부품 카탈로그 taxonomy(정규 개념)로 매핑하는 게 온톨로지다. 매핑은 키워드 규칙으로 결정적으로 수행한다(GraphService.canon):

| 정규 개념 | 매칭 키워드 |
|---|---|
| AIR BAGS | AIR BAG |
| SEAT BELTS | SEAT BELT |
| BRAKE | BRAKE |
| TRANSMISSION | POWER TRAIN, TRANSMISSION |
| ENGINE | ENGINE |
| FUEL | FUEL |
| EXHAUST | EXHAUST |
| TIRE | TIRE |
| VISIBILITY | WIPER, WINDSHIELD, VISIBILITY |
| ELECTRICAL | ELECTRIC |
| CAMERA | CAMERA, REARVIEW, BACKOVER |
| OTHER | (그 외) |

이 정규화가 세 소스를 하나의 개념 축으로 세운다. 이것이 "온톨로지 = 흩어진 어휘를 하나의 개념 체계로 통합"의 실체다.

## 3. 순회 (질의)

- modelComponents(model): Model의 부위별 리스크 맵. 정규 부위마다 리콜 수와 불만 수를 집계해 내림차순. Model to Component 이웃.
- componentProfile(model, component): Model to Component to {Recall, Complaint, Part}. 한 부위에 대한 리콜 목록, 불만 볼륨과 위해, 교체 부품과 비용을 통합.

## 4. 노출

- REST: `/api/graph/model-components?model=`, `/api/graph/component-profile?model=&component=`
- MCP 툴: `component_graph(model, component)` — 에이전트가 그래프 탐색을 도구로 사용. 거버넌스 게이트 통과.
- UI(후속): 부위 프로파일 패널 또는 간단 그래프 뷰.

## 5. JD 연결 (지식그래프/온톨로지)

"차종-리콜-부품-증상 도메인을 온톨로지로 명시화했다. 세 소스의 이질적 부위 어휘를 정규 개념으로 통합하고, Model to Component to 근거 순회로 한 부위의 규제·증상·비용을 한 번에 설명한다. 검색을 이기려는 그래프가 아니라, 설명가능성과 내비게이션을 위한 그래프로 스코프했다."

한계: 정규화는 키워드 규칙이라 OTHER로 빠지는 경계 사례가 있다. 부품 카탈로그가 11종이라 그 밖 부위(스티어링, 서스펜션, 구조 등)는 부품 매칭이 비고, 리콜/불만만 붙는다. 카탈로그를 넓히면 커버리지가 올라간다.
