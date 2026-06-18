#!/usr/bin/env bash
# vehicle 코퍼스 인제스트: data/vehicle/manuals/*.{pdf,hwp,hwpx,docx,txt,md} → namespace=vehicle
# 리콜 CSV는 RAG가 아니라 text-to-SQL 트랙(/api/tabular). eval/ingest_corpus.sh 패턴 준수.
#
# 사용:
#   1) 현대 오너스매뉴얼 PDF를 data/vehicle/manuals/ 에 저장 (sources.md 참고)
#   2) 앱 실행(./mvnw spring-boot:run) 후:  bash ml/data/ingest_vehicle.sh
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
NS=${NS:-vehicle}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MANUALS="$ROOT/data/vehicle/manuals"
RECALLS="$ROOT/data/vehicle/recalls"

echo "== [$NS] 매뉴얼 인제스트 (RAG) =="
shopt -s nullglob
found=0
for f in "$MANUALS"/*.pdf "$MANUALS"/*.hwp "$MANUALS"/*.hwpx "$MANUALS"/*.docx "$MANUALS"/*.txt "$MANUALS"/*.md; do
  [ -e "$f" ] || continue
  found=1
  if curl -fsS -X POST "$BASE/api/data/ingest-file" \
        -F "file=@$f" -F "namespace=$NS" >/dev/null; then
    echo "  + $(basename "$f")"
  else
    echo "  FAIL $(basename "$f")"
  fi
done
[ "$found" = 0 ] && echo "  (비어있음) → data/vehicle/manuals/ 에 PDF를 먼저 넣어주세요. ml/data/sources.md"

echo "== [$NS] 리콜 CSV (text-to-SQL 트랙) =="
for c in "$RECALLS"/*.csv; do
  [ -e "$c" ] || continue
  echo "  CSV: $(basename "$c") → /api/tabular 로 로드 (TABULAR-SQL.md 참고). RAG 임베딩 아님."
done

echo "== 인덱스 상태 =="
curl -s "$BASE/api/data/index/stats" | python3 -m json.tool || true
echo "== vehicle 문서 수 =="
curl -s "$BASE/api/data/count?namespace=$NS" | python3 -m json.tool || true
