### 모델 비교 (자동, n=3)

| 모델 | 질문 | 점수 | 누수율 | 평균지연 | 답변(앞 70자) |
|---|---|---|---|---|---|
| qwen2.5:7b-instruct | ko_quality | 1.00 | 0.00 | 10.7s | 쏘나타에서 안전벨트 프리텐셔너를 처리할 때 주의해야 할 사항은 다음과 같습니다:  1. SRS 공기-bag 경고등이 절반 정도 |
| qwen2.5:7b-instruct | table | 1.00 | 0.00 | 1.0s | 35 N·m |
| qwen2.5:7b-instruct | grounding | 1.00 | 0.00 | 2.6s | 컨텍스트 정보에 따라 PALISADE의 리콜 건수를 알 수 없습니다.PALISADE에 대한Specific 리콜 정보가 제공되지 |
| qwen2.5:7b-instruct | domain | 1.00 | 0.67 | 5.2s | 에어백 경고등이 켜지면 다음과 같은 점검 항목을 확인해야 합니다: - 전기 시스템 - 에어백 하드웨어 - 연결 케이블  이를  |
| exaone3.5:7.8b | ko_quality | 1.00 | 0.00 | 23.0s | 쏘나타 차량의 안전벨트 프리텐셔너를 사용할 때 다음 사항을 주의하세요:  1. **경고등 확인**: 안전벨트 경고등이 켜지면  |
| exaone3.5:7.8b | table | 1.00 | 0.00 | 5.0s | 오일 드레인 플러그의 토크값은 35 N·m입니다. |
| exaone3.5:7.8b | grounding | 1.00 | 0.00 | 9.6s | 제시된 컨텍스트에는 PALISADE 리콜의 정확한 숫자에 대한 정보가 포함되어 있지 않습니다. 따라서 해당 숫자를 제공할 수  |
| exaone3.5:7.8b | domain | 1.00 | 0.00 | 12.0s | 에어백 경고등이 켜지면 우선 **안전 시스템 점검**을 해야 합니다. 차량 매뉴얼의 **안전 시스템** 섹션을 참조하거나 ** |
| qwen3:8b | ko_quality | 0.89 | 0.00 | 16.2s | 쏘나타의 안전벨트 프리텐셔너는 차량 전원을 켜고 6초 후에 경고등이 꺼지도록 설계되어 있습니다. 경고등이 꺼지지 않거나 이상이 |
| qwen3:8b | table | 1.00 | 0.00 | 3.4s | 35 N·m입니다. |
| qwen3:8b | grounding | 1.00 | 0.00 | 5.1s | 컨텍스트에 PALISADE 리콜 건수에 대한 정확한 숫자는 제공되지 않았습니다. |
| qwen3:8b | domain | 1.00 | 0.00 | 11.3s | 에어백 경고등이 켜지면 점검 항목은 차량의 안전 시스템을 확인해야 하며, 이는 서비스 매뉴얼의 "Safety system of |
| vehicle-qwen2.5-1.5b | ko_quality | 0.22 | 0.00 | 4.9s | 1. SRS air bag warning light:     - Ignition switch ON position for 6  |
| vehicle-qwen2.5-1.5b | table | 1.00 | 0.33 | 1.1s | 오일 드레인 플러그의 헤드 크기별로 35 N·m입니다. |
| vehicle-qwen2.5-1.5b | grounding | 0.00 | 0.00 | 2.2s | RECALLS FOR THE PARAMILAR SEDAN ARE AS FOLLOWS:  1. 5 REPAIR PROCEDURE |
| vehicle-qwen2.5-1.5b | domain | 0.00 | 0.00 | 1.0s | airbag warning light on: Inspection items required. |

**요약** (누수율↓·점수↑·지연↓ 이 좋음 — 누수율 1차 정렬)

| 순위 | 모델 | 누수율 | 평균점수 | 평균지연 |
|---|---|---|---|---|
| 1 | qwen3:8b | 0.00 | 0.97 | 9.0s |
| 2 | exaone3.5:7.8b | 0.00 | 1.00 | 12.4s |
| 3 | vehicle-qwen2.5-1.5b | 0.08 | 0.31 | 2.3s |
| 4 | qwen2.5:7b-instruct | 0.17 | 1.00 | 4.9s |
