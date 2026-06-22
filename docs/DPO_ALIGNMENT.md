# DPO 정렬 튜닝 — 개념과 구현 해설

> SFT(LoRA) 다음 단계인 **Alignment Tuning**을 DPO로 구현한 것의 의미 정리.
> 코드: [`ml/finetune/train_dpo.py`](../ml/finetune/train_dpo.py) · 데이터: `ml/data/pref_seed.jsonl` · 흐름: [ml/finetune/COLAB_QLORA.md](../ml/finetune/COLAB_QLORA.md) §3.5
> 관련: [WHY_LORA.md](WHY_LORA.md), [PEFT_METHODS.md](PEFT_METHODS.md), [RESULTS.md](RESULTS.md)

---

## 1. 큰 그림 — SFT vs Alignment

- **SFT(지도 미세조정, =우리 LoRA/QLoRA)**: "이 질문엔 이렇게 답해"를 정답 예시로 가르침. *무엇을 말할지*(도메인 지식·말투).
- **Alignment(정렬, =DPO/RLHF)**: "두 답 중 이게 더 낫다"를 선호로 가르침. *어느 답이 더 나은지*(품질·안전·선호).

두 축이 다르다. SFT만으론 "그럴듯하지만 환각·언어혼용"이 남고, 정렬이 그걸 *상대 비교*로 눌러준다.

## 2. DPO가 싼 이유 (RLHF와 비교)

**RLHF — 3단계, 무거움**: ① SFT → ② 선호쌍으로 **리워드 모델(RM)** 학습 → ③ **PPO(RL 루프)** 로 정책 업데이트(샘플링·밸류모델·KL·불안정).

**DPO — RM도 RL도 없음**: RLHF 최적 정책과 리워드 사이의 **닫힌 형태(closed-form) 관계**를 이용해, 선호쌍에 **대조(분류형) 손실**을 직접 건다. 모델이 *자기 자신을 암묵적 리워드*로 사용 → 별도 RM 불필요, 생성/RL 루프 불필요. SFT와 같은 지도학습 기계(forward→loss→backprop)만 돈다.

> "싸다"는 RLHF 대비. DPO도 쌍당 *정책+레퍼런스 × chosen+rejected = 4 forward*라 SFT보단 무겁지만, RM 학습·PPO가 없어 훨씬 가볍고 안정적.

## 3. 코드 한 줄씩의 의미 (train_dpo.py)

| 코드 | 의미 |
|---|---|
| `PeftModel.from_pretrained(base, sft_adapter, is_trainable=True)` | **정책 = base + SFT 어댑터.** DPO는 SFT 위에서 *이어서* 정렬한다(처음부터가 아님). |
| `ref_model=None` | **레퍼런스(KL 앵커).** LoRA면 TRL이 레퍼런스 계산 때 *어댑터를 꺼서* base를 레퍼런스로 씀 → 모델 하나로 정책·레퍼런스 둘 다 = 메모리 절약. |
| `{prompt, chosen, rejected}` | DPO 데이터 단위. chosen=더 나은 답, rejected=더 못한 답. **상대 비교**가 학습 신호. |
| `apply_chat_template(system+user)` | prompt를 모델이 학습 때 본 chat 형식으로 정합(불일치하면 엉뚱하게 학습). |
| `beta`(DPOConfig) | **KL 강도.** 작으면 레퍼런스(SFT)에서 자유롭게 멀어짐(과적합·붕괴↑), 크면 보수적. DPO의 핵심 손잡이. 0.1~0.5 탐색. |
| `learning_rate=5e-6` | SFT(2e-4)보다 **40배 작게.** 정렬은 SFT 위 *미세 조정*이라 lr 크면 SFT 지식이 망가짐. |
| `peft_config` **안 줌** | `model`이 이미 PeftModel(SFT 어댑터)이라, peft_config를 주면 **어댑터가 2겹**(double-wrap)으로 새로 생김. "기존 SFT 어댑터 이어가기"가 목적이라 주지 않는다. |

## 4. 손실이 실제로 하는 일

DPO 손실은 **레퍼런스 대비** chosen의 로그확률은 ↑, rejected는 ↓ 가 되도록 정책을 민다. 즉 "절대적으로 옳은 답"을 외우는 게 아니라 **"이 둘 중에선 이쪽"** 을 학습 → 미묘한 품질/선호 차이를 잡는다. `beta`가 "레퍼런스에서 얼마나 멀어져도 되는지"를 제어(KL 정규화).

## 5. 이 프로젝트에서 왜 의미 있나

1.5B SFT 모델이 보인 실제 실패(진단서의 **중국어 누수 `枕头囊气囊`, 숫자 환각 `489건`, 언어 혼용 `ています`**)를 **그대로 rejected**로, 같은 질문의 깨끗한 답을 chosen으로 둔다. → 정렬이 *관찰된 실패를 직접 교정*. 억지 선호가 아니라 실데이터 기반이라 정당한 0→1 데모.

검증: 같은 질문을 **SFT vs DPO**로 생성해 rejected 패턴(중국어·환각)이 줄었는지 비교 — 그게 "정렬튜닝이 작동했다"는 증거.

## 6. 흔한 함정 (구현 중 겪은 것)

- **import 누락** — `from trl import DPOTrainer, DPOConfig` 안 풀면 `NameError`.
- **닫는 괄호를 주석에 묻기** — 함수 인자를 주석 처리할 때 닫는 `)`까지 주석에 들어가면 호출이 안 닫혀 `SyntaxError`. 닫는 `)`는 항상 주석 밖 코드로.
- **이중 어댑터** — 이미 PeftModel인데 `peft_config`를 또 주면 SFT 위에 빈 LoRA가 덧씌워짐.
- **TRL 버전차** — 신버전 `processing_class=tok` vs 구버전 `tokenizer=tok`, DPOConfig 인자명 변동. 설치 버전 문서 확인.
- **SFTConfig 인자 리네임(Colab 실측)** — 최신 TRL은 `SFTConfig(max_seq_length=...)` 를 받지 않는다 → `max_length`로 변경됨(`TypeError: unexpected keyword argument 'max_seq_length'`). transformers/TRL이 빠르게 바뀌니 Colab의 설치 버전 기준으로 인자명을 맞춘다.
- **데이터 경로 기본값(cwd 의존)** — `--data ../data/pref_seed.jsonl` 같은 상대경로 기본값은 실행 위치(cwd)에 따라 `.../finetune/../content/.../pref_seed.jsonl` 처럼 꼬여 `FileNotFoundError`. 기본값을 **스크립트 기준 절대경로**(`os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", ...)`)로 두면 cwd와 무관하게 안정적.
- **gitignore된 데이터는 clone에 없다** — SFT 데이터(`ml/data/out/`)는 gitignore라 Colab `git clone`엔 안 들어간다 → 업로드하거나(임시) gitignore 예외로 커밋(재현성). 반면 `pref_seed.jsonl`은 `ml/data/`에 커밋돼 있어 clone에 포함된다.
- **SFT 없이 DPO(0→1 데모)** — `adapters_7b`(SFT 산출)가 없으면 base에 새 LoRA를 얹어 DPO만 돌린다. 정석은 SFT→DPO지만, 메커니즘·지표를 보여주는 데는 충분(로그에 `[dpo] SFT 어댑터 없음 → base에 새 LoRA로 DPO`).

## 6.5 지표 읽는 법 (학습 로그)

DPO 로그에서 봐야 할 핵심 지표:

| 지표 | 의미 | 좋은 방향 |
|---|---|---|
| `rewards/chosen` | chosen 응답의 (정책 vs 레퍼런스) 로그확률 차 | 0 위로 상승 |
| `rewards/rejected` | rejected 응답의 같은 값 | 0 아래로 하락 |
| `rewards/margins` | chosen − rejected | **커질수록 선호 분리 잘 됨** |
| `rewards/accuracies` | chosen > rejected 인 비율 | 1.0에 가까울수록 |
| `loss` | DPO 대조 손실 | 하락 |

해석: **margin이 벌어지고 accuracy가 오르면** 정책이 chosen(한국어·근거·정확)을 rejected(중국어 누수·환각)보다 선호하도록 정렬되는 중. 단 `beta`(KL)가 너무 작으면 base에서 과하게 멀어져(품질 붕괴), 너무 크면 거의 안 변한다 — 작은 데이터(10쌍)에선 epoch·lr를 보수적으로.

**실측(7B QLoRA SFT 어댑터 위에서 DPO, Colab T4):**

| 지표 | 값 | 읽기 |
|---|---|---|
| `rewards/chosen` | **+0.0035** | chosen 보상 상승(방향 ✓) |
| `rewards/rejected` | **−0.0166** | rejected 보상 하락(방향 ✓) |
| `rewards/margins` | **+0.020** | chosen−rejected 양수 → 선호 분리 시작 |
| `rewards/accuracies` | **0.20** | 낮음 — 데이터·스텝 부족 |
| `train_loss` | **0.669** | 시작점(−ln0.5≈0.693)에서 거의 안 내려감 |
| 스텝/epoch | 2 steps / 1 epoch | 10쌍 × 1 epoch ÷ 유효배치8 |

정직한 결론: **파이프라인과 메커니즘은 증명**(SFT→DPO 이어가기 정상, 어댑터 `adapters_dpo` 생성, 보상 부호 방향 정확)됐으나, **10쌍·1 epoch·2 step이라 undertrained**(accuracy 0.2, loss 정체). 이건 "0→1 메커니즘 데모"의 의도된 한계다. 실제 정렬 효과를 내려면: (1) 선호쌍 수십~수백으로 확대(관찰된 실패를 rejected로 계속 수집), (2) epoch 3~5, (3) `beta` 0.1~0.3 스윕, (4) 정렬 전/후를 같은 프롬프트로 정성 비교(중국어 누수·환각 감소 확인).

## 8. 선호 데이터 늘려 재학습하기 (추후 ② 가이드라인)

§6.5 실측이 보여주듯 10쌍·1 epoch는 메커니즘 데모용이다. 정렬을 *실제로* 강화하려면 **선호쌍을 늘려 재학습**한다. 절차:

1. **수집 — 실패를 rejected로.** 좋은 rejected는 *진짜 관찰된 실패*다. 출처 세 가지:
   - 1.5B/약한 모델의 실패 출력(중국어 누수·숫자 환각·언어 혼용)을 그대로 rejected에.
   - **거버넌스 피드백 활용(데이터 플라이휠)**: UI의 👎(thumbs-down) 답변을 rejected 후보로, 사람이 고친 답을 chosen으로. `query_log`에 호출·피드백이 쌓이므로 주기적으로 선호쌍으로 승격.
   - 같은 prompt에 강한 선생모델 답(chosen) vs 현 모델 답(rejected) 대조.
2. **형식 — 한 줄 = `{prompt, chosen, rejected}`** (`ml/data/pref_seed.jsonl`에 append). chosen=한국어·근거·숫자 정확 / rejected=실패 모드.
3. **검증** — `python ml/data/validate_pref.py` 로 스키마·빈값·중복·동일(chosen==rejected) 점검 후 학습.
4. **재학습** — 수십~수백 쌍이면: `--epochs 3~5`, `--beta` 0.1~0.3 스윕. SFT 어댑터(`adapters_7b`) 위에서 이어가기(`--sft-adapter adapters_7b`).
   ```bash
   python ml/finetune/train_dpo.py --sft-adapter adapters_7b \
     --data ml/data/pref_seed.jsonl --out adapters_dpo --epochs 4 --beta 0.2
   ```
5. **평가(전/후 비교)** — 같은 프롬프트 셋으로 정렬 전(SFT만) vs 후(DPO) 출력을 나란히: 중국어 누수율·숫자 정확도·언어 일관성. 지표는 §6.5(`margins`↑, `accuracies`↑) + 정성 비교.
6. **목표 신호** — `rewards/accuracies`가 0.2 → 0.7+ 로, `margins`가 꾸준히 양수로 벌어지고, 정성 비교에서 실패 모드가 줄면 성공.

> 핵심: DPO 품질은 **선호쌍의 질과 양**이 좌우한다. 모델을 다시 짜는 게 아니라 *데이터를 모으는 루프*(특히 👎 피드백 → rejected)를 운영에 붙이는 게 정렬의 본질이다.

## 7. 면접 한 줄

> "SFT(LoRA)로 도메인 지식을 넣은 뒤, 관찰된 실패(중국어 누수·환각)를 rejected로 둔 선호쌍으로 **DPO 정렬**을 얹었다. RLHF와 달리 리워드 모델·RL 루프 없이 대조 손실로 정책을 직접 최적화 — `beta`(KL)와 작은 lr로 'SFT를 안 망가뜨리며 선호로 민다'. 정렬은 SFT 어댑터를 *이어서* 학습하고, 레퍼런스는 어댑터를 끈 base로 메모리 없이 KL 앵커를 잡는다."
