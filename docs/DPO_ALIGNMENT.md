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

## 7. 면접 한 줄

> "SFT(LoRA)로 도메인 지식을 넣은 뒤, 관찰된 실패(중국어 누수·환각)를 rejected로 둔 선호쌍으로 **DPO 정렬**을 얹었다. RLHF와 달리 리워드 모델·RL 루프 없이 대조 손실로 정책을 직접 최적화 — `beta`(KL)와 작은 lr로 'SFT를 안 망가뜨리며 선호로 민다'. 정렬은 SFT 어댑터를 *이어서* 학습하고, 레퍼런스는 어댑터를 끈 base로 메모리 없이 KL 앵커를 잡는다."
