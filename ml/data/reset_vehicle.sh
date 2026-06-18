#!/usr/bin/env bash
# vehicle 네임스페이스만 깨끗이 리셋 (다른 namespace 데이터는 건드리지 않음).
# "데이터 다시 리셋해서 제대로" — 안전하게 vehicle만 비우고 재인제스트.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
NS=${NS:-vehicle}

echo "== [$NS] 기존 문서 목록 조회 =="
docs=$(curl -s "$BASE/api/data/documents?namespace=$NS")
echo "$docs" | python3 -m json.tool || true

echo "== [$NS] 문서 삭제 =="
echo "$docs" | python3 -c '
import sys, json, urllib.parse
docs = json.load(sys.stdin)
for d in docs:
    t = urllib.parse.quote(d["title"])
    print(t)
' | while read -r title; do
  curl -s -X DELETE "$BASE/api/data/documents?title=$title&namespace=$NS" >/dev/null \
    && echo "  - deleted: $title" || echo "  FAIL: $title"
done

echo "== 재인제스트 =="
bash "$(dirname "$0")/ingest_vehicle.sh"
