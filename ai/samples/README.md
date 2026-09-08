# 샘플 뉴스 클립

scene 분할 품질을 눈으로 확인하기 위한 로컬 전용 폴더다. **영상 파일은 커밋하지 않는다**
(`ai/.gitignore`). 이 문서와 `manifest.example.json` 만 저장소에 있다.

```bash
uv run --directory ai python -m npick_worker.scene_detection.report \
    samples/01-standard-report.mp4 --out samples/out/01
```

## 어떤 클립이 필요한가

1·2번은 필수다. 이 둘이 없으면 "잘 나뉘었다"를 말할 근거가 없다. 3~8은 구할 수 있는 만큼.

| # | 유형 | 왜 필요한가 | 확인 포인트 |
| --- | --- | --- | --- |
| 1 | 표준 리포트 1건 (앵커 인트로 → 현장 → 인터뷰 → 앵커 아웃), 1~2분 | 가장 흔한 입력. hard cut 위주 | 기본 정확도 |
| 2 | 앵커 고정 샷 단독, 30초 이상 (거의 움직임 없음) | F-03 의 하한 "한 개 이상" | scene 이 **1개**여야 한다. 쪼개지면 threshold 가 낮은 것 |
| 3 | 디졸브·와이프 전환 포함 | ContentDetector 의 알려진 약점 (점진 전환) | **놓침** 여부 → threshold 조정 / 모델 detector 필요성 판단 |
| 4 | 전면 CG·차트·지도 화면 전환 | 색이 급변해 과분할되기 쉽다 | 한 그래픽 안에서 쪼개지는지 |
| 5 | 하단 자막·로고 오버레이 on/off 구간 | 컷이 아닌데 화면이 크게 바뀐다 | 오탐 여부 |
| 6 | 핸드헬드·빠른 팬 현장 화면 | 프레임 간 차이가 크다 | `content` vs `adaptive` 비교 근거가 여기서 나온다 |
| 7 | 카메라 플래시·조명 번쩍 | 1~2프레임만 튄다 | `min_scene_len_ms` 가 실제로 먹는지 |
| 8 | 5분 이상 긴 클립 | 성능·메모리 | 처리 시간 기록 (FRD §8.2 처리 속도) |

### 1번 클립(`KNI_02205`)이 이미 덮은 것

측정 결과는 [../docs/scene-detection.md](../docs/scene-detection.md) §4.

- **3번 불필요.** 이 클립에 디졸브가 3곳 있고 셋 다 놓친다. 확인 포인트("놓침 여부 →
  threshold 조정 / 모델 detector 필요성")까지 답이 나왔다 — threshold 로는 못 고친다(§4.4).
- **2번 부분 충족.** 별도 클립 대신 이 클립의 정지 구간 0~17s 로 확인했다(§4.3).
  30초 이상 고정 샷은 여전히 없으므로 구해지면 다시 본다.
- 4·5·6·7·8 번은 그대로 필요하다.

## 파일 형식

- mp4 / H.264. **원본 fps·해상도를 유지한다** — 재인코딩하면 컷 경계가 흐려져 판단이 오염된다.
- **CFR(고정 프레임레이트)를 쓴다.** PySceneDetect 의 타임코드는 프레임 번호 기반이라
  VFR 소스에서는 실제 PTS 와 어긋난다. 결정론은 유지되지만 ms 정확도가 떨어진다.
  뉴스 방송분은 보통 29.97fps CFR 이고, 웹 다운로드본이 VFR 인 경우가 있다.
- 파일명 앞에 위 표의 번호를 붙인다: `01-standard-report.mp4`, `02-anchor-static.mp4` …

## manifest.json

`manifest.example.json` 을 `manifest.json` 으로 복사해 채운다. `manifest.json` 은 커밋되지 않는다.

- `expected_cut_count` — **필수**. 눈으로 센 컷 개수. 이게 없으면 분할 품질 확인이 인상 평가가 된다.
- `ground_truth_cuts_ms` — 선택. 있으면 실측 후 임계값 확정에 그대로 재사용한다.
- `external_processing_allowed`·`license_note` — FRD §7.1 clip 필드와 같은 이름으로 적는다.
  scene detection 은 완전 로컬 처리라 `no` 인 클립도 쓸 수 있다. 오히려 `no` 클립을 넣어두면
  나중에 외부 전송 차단(FRD §6.4) 검증에 재사용된다.
