#!/usr/bin/env bash
# 전체 Knowledge Base 리셋 → 자동차 전용으로 갈아끼우기.
# 기존 인덱스 데이터(articles.parquet/json)를 백업 후 비운다.
# data/vehicle/(리콜 CSV·매뉴얼 원본)와 소스코드는 건드리지 않음.
#
# 반드시 앱을 끈 상태에서 실행! (parquet 파일 잠금/덮어쓰기 충돌 방지)
#   1) 앱 종료(Ctrl+C)
#   2) bash ml/data/reset_kb.sh
#   3) ./mvnw spring-boot:run  → KB 비어있음 확인
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

TS=$(date +%Y%m%d_%H%M%S)
BACKUP="data_backup_$TS"

echo "== 1) 백업 → $BACKUP/ =="
mkdir -p "$BACKUP"
for f in data/articles.json data/articles.parquet data/.articles.parquet.crc data/miniwatson.mv.db; do
  [ -e "$f" ] && cp -p "$f" "$BACKUP/" && echo "  backup: $f"
done

echo "== 2) 인덱스 비우기 =="
echo "[]" > data/articles.json
rm -f data/articles.parquet data/.articles.parquet.crc
# H2(카탈로그/감사) — dev는 인메모리라 자동 초기화. demo(파일) 쓰면 같이 비우려면 아래 주석 해제:
# rm -f data/miniwatson.mv.db
echo "  articles.json → []   |   articles.parquet 삭제"

echo "== 3) 보존 확인 =="
echo "  data/vehicle/ 유지:"; ls data/vehicle 2>/dev/null | sed 's/^/    /'

echo
echo "완료. 백업: $BACKUP/  (되돌리려면 이 파일들을 data/로 복사)"
echo "다음: ./mvnw spring-boot:run  → http://localhost:8080 새로고침 → KB 비었는지 확인"
echo "그다음: bash ml/data/ingest_vehicle.sh  (자동차 매뉴얼 인제스트)"
