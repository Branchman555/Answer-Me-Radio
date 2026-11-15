<div align="center">

<img src="docs/assets/reply-radio-hero.png" alt="레트로 라디오의 전파가 산길과 연결되는 응답하라 라디오 콘셉트" width="100%" />

# 응답하라 라디오

### 부모님의 플레이리스트와 내 플레이리스트 사이.

**Answer Me Radio · 현대자동차 공모전 프로젝트**

내 MP3를 두 시대의 선곡과 짧은 DJ 멘트로 이어 듣는 차량용 라디오.

![Android](https://img.shields.io/badge/Android-Kotlin-153F42?style=flat-square)
![Media3](https://img.shields.io/badge/Media3-Local_Audio-AD7941?style=flat-square)
![Stage](https://img.shields.io/badge/Prototype-UI--002-153F42?style=flat-square)
![Tests](https://img.shields.io/badge/JVM-177_passed-397566?style=flat-square)
![API](https://img.shields.io/badge/Mock_API-115_passed-AD7941?style=flat-square)

[프로젝트 소개](#왜-응답하라-라디오인가요) · [실제 앱 화면](#실제-앱-화면) · [검증 결과](#검증으로-확인한-것) · [실행 방법](#직접-실행하기) · [개발 로드맵](#개발-로드맵)

<sub>대표 이미지는 AI 생성 콘셉트입니다. 아래 앱 화면은 실제 에뮬레이터 캡처입니다.</sub>

</div>

---

## 왜 응답하라 라디오인가요?

같은 차에 타도 좋아하는 음악의 시대는 다릅니다. 누군가에게는 익숙한 1980년대 노래가, 누군가에게는 2010년대 노래가 여행의 배경음악이 됩니다.

**응답하라 라디오는 1980년대와 2010년대 음악을 번갈아 들려주는 차량용 라디오입니다.** 직접 고른 MP3를 재생하고, DJ를 켜면 곡 사이에 짧은 소개가 나옵니다. 지나는 길의 역사 이야기는 후속 개발 항목입니다.

| 함께 듣는 음악 | 라디오처럼 이어지는 흐름 | 길 위의 타임머신 |
| :--- | :--- | :--- |
| 한 시대 또는 두 시대를 번갈아 편성하는 SINGLE/FAMILY 모델 | 곡 경계에서만 DJ를 배치하고, 사용자의 정지·일시정지를 우선 | 명시적으로 동의한 경우 검수된 지역 이야기와 사건 연도의 음악을 연결 |
| **선곡 로직·로컬 재생 구현** | **기기 내 한국어 DJ · 음악과 순차 재생 구현** | **로컬 계약·모의 전이 구현 / 실제 콘텐츠·위치 연동 예정** |

> **1980·2010년대 로컬 음악 + 선택형 한국어 DJ**를 실제로 재생합니다. 차량 플랫폼 제한이 켜지거나 상태를 알 수 없으면 상세 조작을 잠급니다. 역사·위치 연동과 실차 검증은 후속 단계입니다.

## 실제 앱 화면

**크림색 화면에 짙은 녹색 라디오와 큰 재생 버튼을 배치했습니다.** Pretendard로 곡 정보와 안내를 정리하고, 재생·일시정지·이전·다음은 표준 아이콘으로 표시합니다. 휴대폰은 세로, 차량은 좌우 구성입니다.

<table>
  <tr>
    <td align="center" width="50%"><strong>01 · 내 음악 등록</strong><br /><sub>SAF 선택 · 태그 확인 · 권한 관리</sub></td>
    <td align="center" width="50%"><strong>02 · 나만의 드라이브 라디오</strong><br /><sub>레트로 다이얼 · 현재 곡 · 큰 재생 제어</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/assets/product-catalog.png" alt="선택한 MP3의 제목, 아티스트, 연도와 편성 가능 상태를 보여주는 실제 카탈로그 화면" width="240" /></td>
    <td align="center"><img src="docs/assets/product-radio.png" alt="앰버 라디오 다이얼과 현재 곡, 실제 재생 상태를 보여주는 프로토타입 화면" width="240" /></td>
  </tr>
  <tr>
    <td>선택한 파일만 읽습니다. 필요한 태그가 없으면 제외 사유를 표시하고, 목록 제거 시 원본 파일은 유지합니다.</td>
    <td>음악과 음성은 한 번에 하나만 출력합니다. 사용자가 멈추면 정지 확인을 기다리고, 늦은 콜백으로 다시 재생하지 않습니다.</td>
  </tr>
</table>

<sub>Android API37 프로토타입의 실제 캡처입니다. 화면 속 곡은 검증용 합성 음악입니다. 라디오 다이얼은 시대를 표현하는 장식이며 실제 FM 주파수가 아닙니다.</sub>

<details>
<summary><strong>03 · Pleos 차량 제한 화면 보기</strong></summary>

<img src="docs/assets/product-restricted.png" alt="Pleos 에뮬레이터의 상세 조작 잠금 상태. DJ 설정과 파일 관리가 숨겨지고 표준 재생 버튼만 남는다." width="100%" />

Virtual Console의 D/P 변경으로 실제 플랫폼 제한 콜백을 검사했습니다. 속도 0이어도 D에서 상세 조작이 잠깁니다. 실제 차량 주행·안전 인증을 의미하지 않습니다.

</details>

### 지금 체험할 수 있는 흐름

**MP3 선택 → TITLE / ARTIST / YEAR 확인 → 시대별 편성 → 음악과 DJ → 표준 버튼으로 제어**

- **선택한 파일만 접근** — SAF 읽기 권한을 보존하고, 파일 삭제·권한 철회 시 다시 확인합니다.
- **시대 조건을 지키는 선곡** — 연도를 파일명이나 AI로 추측하지 않고, 필수 태그가 없는 곡은 제외합니다.
- **중단과 복귀를 분명하게** — 일시정지와 포커스 상실을 구분하고, 사용자가 멈춘 방송을 임의로 재개하지 않습니다.
- **차량의 제한을 그대로 반영** — 플랫폼이 허용한 상태에서만 파일 관리와 DJ 설정을 열고, 알 수 없는 상태에서는 잠급니다.
- **실패해도 끝없이 반복하지 않도록** — 문제가 생긴 곡을 제외·보충하고, 연속 3회 실패하면 중단합니다.

## 한 곳에서 결정하는 재생 흐름

```mermaid
flowchart LR
    A[SAF · 선택한 로컬 MP3] --> B[로컬 카탈로그]
    B --> C[RadioPlanner · 시대별 선곡]
    C --> D[RadioReducer · 단일 전이]
    U[앱 · 시스템 미디어 버튼] --> S[MediaLibrarySession]
    S --> D
    D --> E[PlaybackEngine · 순차 실행]
    E --> P[Media3 · 한 곡 재생]
    E --> V[Pleos / Android · 한국어 TTS]
    V -->|완료 · 정지 확인| D
    G[CarUxRestrictions · 상세 조작 gate] --> U
    P -->|종료 · 오류 · 정지 확인| D
    F[오디오 포커스] --> D
    X[ContextEngine · 로컬 DJ 계약] --> D
    Y[역사 콘텐츠 계약 · 모의 구간] -.-> D
    classDef core fill:#153F42,color:#F5EBD8,stroke:#D9A058
    classDef future fill:#F5EBD8,color:#153F42,stroke:#AD7941
    class D,E,P,V core
    class Y future
```

미디어 콜백이 직접 다음 곡을 선택하지 않습니다. **RadioReducer가 상태를 결정하고, 출력 해제를 확인한 뒤 다음 출력을 시작합니다.** 한국어 DJ는 기기 내 TTS로 연결했습니다. 점선 경로의 역사·위치 연동과 외부 AI 서버는 후속 단계이며, 현재 대본은 로컬 템플릿입니다.

### 검증 가능한 DJ 서버

RR-006에서는 **FastAPI + Mock LLM 로컬 API**를 추가했습니다. 서버가 음악을 고르거나 재생을 제어하지 않습니다. 승인된 짧은 대본을 만들고, 응답이 잘못되거나 3초를 넘기면 시대 안내 폴백을 반환합니다.

```mermaid
flowchart LR
    R[v0.5 요청] --> A[인증 · 크기 · 사용량 제한]
    A --> C[허용 필드 · 날씨 TTL 검사]
    C --> M[Mock LLM · 3초 제한]
    M --> V[식별자 · 근거 · 내용 검증]
    V --> O[승인 대본]
    M -->|장애 · 시간 초과| F[고정 폴백]
    V -->|검증 실패| F
    O --> T[실제 HTTP → Kotlin 계약 검사]
    F --> T
    classDef core fill:#153F42,color:#F5EBD8,stroke:#D9A058
    classDef fallback fill:#F5EBD8,color:#153F42,stroke:#AD7941
    class A,C,M,V,T core
    class F fallback
```

**현재 앱은 기기 내 대본으로 재생합니다.** 위 서버는 독립적으로 실행·검증하는 로컬 Mock API이며, 외부 LLM·클라우드 배포·앱 HTTP 연결은 아직 활성화하지 않았습니다. 실제 HTTP로 받은 정상/폴백 9개 응답이 기존 Kotlin 대본 검증기를 통과하는 것까지 확인했습니다. [서버 구현과 실행 방법 →](docs/rr006-backend.md)

## 검증으로 확인한 것

**2026-11-15 · UI-002 프로토타입 검증 스냅샷** — 아래 수치는 실행 증거에 기반하며 CI 또는 실차 인증 배지가 아닙니다.

| 검증 대상 | 결과 | 확인한 범위 |
| :--- | :---: | :--- |
| JVM 회귀 테스트 | **177 / 177** | 기존 177개 소스·테스트 유지, 실패·스킵 0 |
| FastAPI + Mock LLM | **115 / 115** | 원본 계약·인증·한도·실제 3초 timeout, HTTP 9개 시나리오 포함 |
| Pleos API34 에뮬레이터 | **38 / 38** | UI-002 새 APK로 전체 38개 재검증 |
| Android API37 에뮬레이터 | **38 / 38** | UI-002 새 APK로 전체 38개 재검증 |
| Android lint | **오류 0 · 경고 0** | debug 앱·테스트 APK 생성, strict 의존성 검증 |
| 설계 원본 보존 | **27개 hash 일치** | 기존 인수 기준 54 + 40개 상태 보존 |

실제 MP3 디코더 출력 버퍼와 AudioTrack 진행, 자연 종료, 표준/legacy 제어, 오디오 포커스, 파일 삭제·권한 철회·손상 오류를 검사했습니다. 별도 UID의 무단 브라우저 연결 거부, 실제 음성 완료·정지·포커스 중단/복귀, 3곡 정상 완료 뒤 DJ 전환과 차량 상세 조작 잠금도 확인했습니다.

**검증 경계도 함께 공개합니다.** 에뮬레이터는 호스트 무음으로 실행했으므로 물리 스피커 청취 검증은 아닙니다. 물리 USB 분리·실제 차량 버튼·실차 주행·국가별 UX 심사는 미검증입니다. 오류·늦은 음성 콜백 순서는 JVM에서 별도로 검증했습니다. 현재 Pleos 이미지의 파일 선택기는 stub이어서 실제 파일 선택 왕복은 일반 Android에서 검증했습니다.

아이콘 직접 클릭·48dp 터치 영역·빈 화면·화면 재진입은 별도 UI 검사 **7개씩**으로 확인합니다.

[전체 검증 증거](evidence/ui002.json) · [서버 검증](evidence/ui002-backend.json) · [UI 변경·재현](docs/ui002-product-polish.md) · [서버 구현·재현](docs/rr006-backend.md) · [음성·차량 UX 재현](docs/rr005-voice-ux.md) · [Pleos 결과](evidence/ui002-device-ReplyRadio_RR003_API34.json) · [Android 결과](evidence/ui002-device-ReplyRadio_RR003_API37.json)

폰트는 [Pretendard v1.3.9](https://github.com/orioncactus/pretendard/tree/v1.3.9/packages/pretendard)를 사용합니다. Regular·SemiBold 원본과 OFL 라이선스를 앱에 포함했으며, 실행 중 폰트를 내려받지 않습니다. [출처·라이선스](THIRD_PARTY_NOTICES.md)

## 직접 실행하기

앱을 열면 바로 라디오 화면이 나옵니다. **`음악 서랍` → `＋ 음악 추가` → `‹ 라디오` → `DJ 켜기`(선택) → `재생`** 순서로 실행합니다. 화면 진입만으로 자동 재생하지 않습니다. TITLE·ARTIST·YEAR가 채워진 MP3와 동작하는 Android 파일 선택기가 필요합니다. 차량에서는 플랫폼이 상세 조작을 허용해야 하며, 한국어 음성 미지원 시 음악만 이어집니다.

<details>
<summary><strong>개발 환경과 빌드 명령</strong></summary>

JDK17 · Android SDK35 / build-tools35 · Python3.11이 필요합니다. Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, Media3 1.6.1, Pleos TTS 3.3.1을 고정했습니다. 서버는 FastAPI·Pydantic·Uvicorn 및 전이 의존성의 버전과 hash를 고정합니다.

```powershell
git clone https://github.com/Branchman555/Answer-Me-Radio.git
cd Answer-Me-Radio

# 최초 1회: 테스트 APK에만 사용할 합성 MP3 생성
python -m pip install --target app/build/rr004-tools lameenc==1.8.5
python -X utf8 tools/generate_rr004_audio.py

# 로컬 Mock API 계약 검증 · 실제 HTTP 자료 생성
py -3.11 -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install --require-hashes -r backend/requirements.lock
backend/.venv/Scripts/python.exe -X utf8 tools/check_backend.py --evidence-prefix ui002

# JAVA_HOME / ANDROID_HOME을 설정한 환경
./tools/check.ps1 -Offline
```

의존성 캐시가 없으면 `-Offline`을 생략합니다. 검증 스크립트에 `-JavaHome`, `-AndroidSdk`를 직접 전달할 수도 있습니다. 빈 캐시의 새 머신 빌드는 아직 검증하지 않았습니다.

- 앱: `app/build/outputs/apk/debug/app-debug.apk`
- 테스트 APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- 장치 재현: [RR-005 문서](docs/rr005-voice-ux.md#검증과-재현)
- 서버 시작·토큰 설정: [RR-006 문서](docs/rr006-backend.md#서버-실행)

본 앱 APK에는 음원을 포함하지 않습니다. 개인 MP3·키·로컬 설정·빌드 캐시는 저장소에 올리지 않습니다. 공개 배포용 release 빌드는 아직 제공하지 않습니다.

</details>

<details>
<summary><strong>코드와 문서 탐색</strong></summary>

```text
app/src/main/kotlin/radio/reply/
├── catalog/        SAF · 메타데이터 · URI 권한
├── planner/        시대별 선곡
├── orchestrator/   상태 · 이벤트 · 단일 Reducer
├── playback/       Media3 · 실제 TTS · 단일 출력 · 표준 제어
├── ux/             CarUxRestrictions · UNKNOWN 상세 잠금
├── ui/             레트로 다이얼 · 공통 색상·타이포·버튼
├── context/        상황정보 · 날씨 캐시 계약
├── dj/             DJ 요청·응답 검증 · 로컬 폴백
├── story/          역사 콘텐츠 검수·권리·구간 게이트
└── demo/           명시적 무음 시뮬레이터

backend/            FastAPI · 엄격한 v0.5 모델 · Mock · 검증 · 폴백
├── tests/          계약 · 제한 · 취소 · 실제 HTTP 검사
└── fixtures/       Kotlin과 대조하는 합성 HTTP 응답
```

[현재 개발 기록](Archive.md) · [설계 읽기 순서](docs/design/CODEX_README_v0.8.md) · [RR-001](docs/rr001-foundation.md) · [RR-002](docs/rr002-context-dj.md) · [RR-003](docs/rr003-local-catalog.md) · [이미지 출처·제작 기록](docs/assets/README.md)

</details>

## 개발 로드맵

| 단계 | 내용 | 상태 |
| :--- | :--- | :--- |
| RR-001 | 프로젝트 기반 · 선곡 · 단일 재생 전이 | 완료 |
| RR-002 | 상황정보 · 일반 DJ 계약 | 완료 · Mock/로컬 템플릿 |
| RR-003 | SAF 로컬 MP3 카탈로그 | 완료 · Pleos 선택기 제한 공개 |
| RR-004 | Media3 · 표준 제어 · 실제 재생 오류 처리 | 완료 · 에뮬레이터 검증 |
| RR-005 | 실제 한국어 음성 · 차량 UX 제한 | 완료 · Pleos/Android 에뮬레이터 검증 |
| RR-006 | FastAPI · Mock LLM · 계약 검증 | 완료 · 로컬 API / 앱 HTTP 연결은 별도 |
| **UI-002** | **제품 UI · 한글 문구 · Pretendard** | **완료 · 새 화면으로 기존 기능 재검증** |
| RR-007 | 실제 역사 콘텐츠 · 위치 | 사용자 요청으로 중단 · 대관령 예시 계획 유지 |
| RR-008~009 | 기록 · 통합 시연 | 예정 |

[상세 완료 조건과 남은 과제 →](docs/roadmap.md)

---

<div align="center">

**서로 다른 시대의 노래가, 같은 여행의 기억이 되도록.**

<sub>Answer Me Radio · Development Prototype · <a href="LICENSE">MIT License</a></sub>

</div>
