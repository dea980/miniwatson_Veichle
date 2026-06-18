#!/usr/bin/env bash
# P3 — 머지된 모델 → GGUF Q4_K_M 양자화 → Ollama 등록 (온디바이스 경로).
# 선행: train_lora.py(mlx) 후 fuse 로 ml/finetune/merged 생성.
#   python3 -m mlx_lm.fuse --model Qwen/Qwen2.5-1.5B-Instruct \
#           --adapter-path ml/finetune/adapters --save-path ml/finetune/merged
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MERGED="${MERGED:-$ROOT/ml/finetune/merged}"
OUT="${OUT:-$ROOT/ml/optimize/gguf}"
LLAMACPP="${LLAMACPP:-$HOME/llama.cpp}"   # git clone https://github.com/ggerganov/llama.cpp
NAME="${NAME:-vehicle-qwen2.5-1.5b}"
mkdir -p "$OUT"

[ -d "$MERGED" ] || { echo "merged 모델 없음: $MERGED (mlx fuse 먼저)"; exit 1; }
[ -d "$LLAMACPP" ] || { echo "llama.cpp 없음: $LLAMACPP 에 clone"; exit 1; }

# llama-quantize 바이너리 자동탐색 (CMake build/bin 또는 루트, PATH)
QUANT=""
for c in "$(command -v llama-quantize 2>/dev/null)" \
         "$LLAMACPP/build/bin/llama-quantize" \
         "$LLAMACPP/llama-quantize"; do
  [ -n "$c" ] && [ -x "$c" ] && QUANT="$c" && break
done
[ -n "$QUANT" ] || { echo "llama-quantize 못 찾음. 빌드: cd $LLAMACPP && cmake -B build && cmake --build build -j"; exit 1; }
echo "quantize bin: $QUANT"

echo "== 1) HF → GGUF(f16) =="
python3 "$LLAMACPP/convert_hf_to_gguf.py" "$MERGED" --outfile "$OUT/$NAME-f16.gguf" --outtype f16

echo "== 2) Q4_K_M 양자화 =="
"$QUANT" "$OUT/$NAME-f16.gguf" "$OUT/$NAME-q4_k_m.gguf" Q4_K_M

echo "== 3) Ollama Modelfile 등록 =="
cat > "$OUT/Modelfile" <<EOF
FROM ./$NAME-q4_k_m.gguf
PARAMETER num_predict 256
TEMPLATE """{{ .Prompt }}"""
EOF
( cd "$OUT" && ollama create "$NAME" -f Modelfile )
echo "완료 → OLLAMA_CHAT_MODEL=$NAME 로 앱에서 사용 가능"
echo "크기 비교:"; ls -lh "$OUT"/*.gguf
