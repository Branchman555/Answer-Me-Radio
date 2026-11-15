# 응답하라 라디오 — 개발 사전설계 및 Codex 인수인계 v0.1

작성 기준: 2026-11-14 / 설계 초안. 실행·SDK 호환성·라이선스 검증 전.

## 1) 결정사항(ADR)

1) ADR-001 — MVP 음원 공급원
	- 사용자가 명시적으로 선택한 로컬 오디오 파일만 재생한다.
	- 스트리밍 서비스 연동·음원 판매·앱 번들 음원 배포는 MVP에서 제외한다.
	- 음원 공급원 추상화는 유지해 향후 라이선스/제휴 공급원을 붙인다.
2) ADR-002 — 편성 책임
	- 재생 가능한 곡과 재생 순서는 결정론적 Radio Planner가 선택한다.
	- LLM은 이미 확정된 곡 정보와 사용자가 제공한 상황을 근거로 DJ 멘트만 만든다.
	- 재생 명령 실행 권한은 LLM이 아닌 Playback Coordinator에 둔다.
3) ADR-003 — AI/음성 실패 대응
	- LLM 실패/타임아웃 시 사전 작성한 기본 멘트로 대체한다.
	- TTS 미지원/실패 시 DJ 멘트를 생략하고 음악은 계속 재생한다.
	- 음악 파일 접근 실패 시 그 곡을 건너뛰거나 사용자가 재등록하도록 안내한다.
4) ADR-004 — 차량 안전
	- CarUxRestrictions 등 플랫폼 규제를 기준으로 파일 등록·세대 상세 설정을 제한한다.
	- 주행 중 기본 제어는 미디어 표준 인터페이스를 우선한다.
	- 앱/서비스 연결만으로 자동 재생하지 않는다. 사용자 명시적 재생 또는 시스템의 적법한 재개 요청에서 시작한다.
5) ADR-005 — 개인정보 및 비밀키
	- LLM 요청에는 선택된 시대, 유효한 날씨 요약, 검증된 음악 메타데이터만 전달한다.
	- 정밀 위치·실명·가족 관계·음원 바이너리는 기본적으로 서버에 보내지 않는다.
	- 외부 LLM API 키는 앱 APK가 아닌 백엔드에서 보관한다.
6) ADR-006 — 초기 카테고리
	- Pleos Music/Audio의 하위 유형은 우선 Music(local player)로 가정하고, 방송형 탐색 구조 및 최종 등록 분류는 검증한다.

## 2) 논리적 아키텍처

```
UI/Settings  ──>  RadioUseCase  ──>  BroadcastCoordinator
                        │                   ├── MusicCatalog (LocalFileSource)
                        │                   ├── RadioPlanner (세대/분위기/반복)
                        │                   ├── DjScriptProvider (Backend/Template)
                        │                   ├── VoiceOutput (Pleos/시스템 TTS/Skip)
                        │                   └── Media Playback (Media3)
                        └── ContextProvider (선호 시대, 시간, 날씨, 지원 차량 정보)

VehicleMediaAdapter: MediaLibraryService/MediaSession 및 UX restriction 이벤트
Backend: DJ 대본 생성, 입력 검증, LLM 키·비용 제한·타임아웃 관리
```

원칙: Android 단일 app 모듈 + 내부 패키지 분리로 출발. 필수 API가 검증되기 전 Gradle 다중 모듈화 금지.

## 3) 도메인 데이터 계약(개념 모델; 특정 SDK 시그니처 아님)

| 객체 | 필드 | 제약 |
|---|---|---|
| `Track` | `id`, `contentUri`, `title`, `artist?`, `year?`, `eraTag?`, `genre?`, `durationMs?`, `isAccessible` | 알 수 없는 연도를 추정하지 않고 `null` 유지. `eraTag` 수동 할당 허용 |
| `RadioProfile` | `eraA`, `eraB`, `djEnabled`, `djFrequency` | 가족 관계나 출생 연도를 추론하지 않음 |
| `ContextSnapshot` | `timeOfDay`, `weather?`, `tripMode?`, `vehicleContext?` | 날씨/차량 정보 없으면 `null`; 출처·갱신시간 확인 |
| `BroadcastSegment` | `id`, `type: DJ\|TRACK`, `trackId?`, `script?`, `sessionId` | 재생은 Segment 큐 순서로만 수행 |
| `DjScript` | `segmentId`, `text`, `tone`, `factRefs`, `isFallback` | JSON 스키마 검증 및 길이·표현 제한 |

### DJ 대본 API 예시(설계용 계약)

Request: `POST /api/v1/dj/scripts`

```json
{
  "sessionId": "session-001",
  "segmentId": "intro-001",
  "style": "warm-1990s-fm",
  "context": {
    "timeOfDay": "EVENING",
    "weather": {"condition": "RAIN", "observedAt": "2026-11-14T16:45:00+09:00"},
    "eraA": "1980s",
    "eraB": "2010s"
  },
  "nextTrack": {"id": "local-001", "title": "사용자가 등록한 곡명", "artist": "확인된 아티스트"},
  "maxSpokenSeconds": 15
}
```

Response:

```json
{
  "sessionId": "session-001",
  "segmentId": "intro-001",
  "text": "비가 내리는 저녁입니다. 오늘은 서로 다른 두 시대의 노래를 이어드릴게요.",
  "tone": "WARM",
  "factRefs": ["weather.condition", "context.eraA", "context.eraB"],
  "isFallback": false
}
```

응답의 `sessionId`와 `segmentId`가 현재 방송 큐와 다르면 폐기. 사용자 상태를 인식한 것처럼 꾸며 말하지 않음.

## 4) Radio Planner v0 규칙

1) 시작 조건
	- 로컬 음원 최소 1개, 접근 URI 유효, 사용자가 재생 요청.
	- 두 시대에 곡이 모두 있으면 A/B를 교차 편성한다.
2) 편성 규칙
	- 각 시대에서 실제 접근 가능한 음원만 후보로 사용한다.
	- 같은 곡 연속 편성 금지. 곡 수가 충분할 때 최근 3곡 중복을 피한다.
	- 한쪽 시대의 후보가 없으면 균형 편성을 주장하지 않고, 이용 가능한 곡만 재생한 뒤 보완 안내.
	- 발매연도 누락 시 미분류로 두고 사용자 수동 시대 지정 허용.
3) 분위기 규칙
	- 날씨/시간 데이터가 충분하고 최신일 때만 편성 가중치에 반영한다.
	- 시간대만으로 '퇴근길', 저속만으로 '정체'라고 추정하지 않는다.
4) DJ 규칙
	- 기본은 곡 사이 짧은 멘트, 사용자 OFF 가능.
	- 첫 30분 기준 오프닝 포함 최대 3회, 한 번에 약 10~15초는 테스트용 초기값이며 검증 후 변경.

## 5) 재생 상태 머신(초안)

- 상태: `IDLE`, `PREPARING`, `DJ_SPEAKING`, `MUSIC_PLAYING`, `PAUSED`, `RECOVERING`, `STOPPED`
- `PLAY`: 사용자가 명시적으로 요청했을 때 `PREPARING` 진입 → DJ 또는 음악 시작.
- `PAUSE`: 모든 출력 일시정지(지원하지 않는 TTS는 중단 처리) → `PAUSED`.
- `NEXT`: 현재 DJ/음악 중단, 지연된 AI 응답 무효화 → 다음 큐.
- `STOP`: 미디어·TTS 중단, 세션 해제.
- 오디오 포커스 상실: 중단 또는 정책상 허용된 방식으로 볼륨 조절; 통화·내비게이션 안내 우선.
- 사용자가 `PAUSE`한 경우 포커스 복귀나 AI 응답 완료만으로 자동 재개 금지.
- 네트워크 실패: 기존 곡 재생 유지, 기본 DJ 멘트 또는 멘트 생략.
- 권한 해제/파일 삭제: 접근 실패 곡 제외 및 재등록 안내.
- TTS와 음악 동시 믹싱은 SDK·오디오 포커스 정책 확인 전 구현 약속 금지. MVP는 순차 재생으로 시작.

## 6) 필수 인수 테스트(예상 결과)

1) 로컬 파일 4곡(1980s 2곡, 2010s 2곡) 등록 → 두 시대가 교차 재생됨.
2) 음악 연도 메타데이터가 비어 있음 → 자동으로 임의 연도를 생성하지 않으며 수동 분류 가능.
3) 읽기 권한 취소 또는 USB 제거 → 크래시 없이 건너뛰거나 재등록 안내.
4) AI 서버 응답 실패 → 음악 재생 계속, 기본 멘트 또는 무음 전환.
5) 이미 다음 곡으로 넘어간 뒤 이전 곡 DJ 응답 수신 → 낡은 멘트 재생 안 됨.
6) 사용자가 일시정지 → TTS 이벤트·포커스 복귀로 자동재생 안 됨.
7) 음원 1곡뿐 → 끊기지 않고 재생하되 ‘양쪽 세대 교차 편성 완료’라고 표시하지 않음.
8) 날씨 정보 미확인/오래됨 → 현재 날씨를 아는 척하는 멘트 생성 안 됨.
9) 주행 제한 상태 → 복잡한 음원 등록/편집 설정 화면 노출 또는 조작 차단.
10) 차량 미디어 브라우저에서 탐색·재생·일시정지 등 기본 제어 동작 확인.

## 7) Codex 구현 순서 / 독립 완료 조건

1) `P0` Pleos Emulator 구동, 미디어 앱 서비스·MBS/MediaSession 연동 가능성 검증.
2) `P1` 로컬 파일 1개 선택 및 권한 유지, Media3 재생.
3) `P2` 여러 곡 메타데이터·시대별 수동 분류, 2시대 교차 편성.
4) `P3` DJ 대본 없이 고정 텍스트로 음성 삽입, 음악/음성 간 순서·오디오 포커스 검증.
5) `P4` FastAPI 대본 API, 스키마 검증, 타임아웃·기본 멘트.
6) `P5` 날씨 정보·사용자 선호 반영, 데이터 없음 시 안전한 기본 방송.
7) `P6` 차량 UX 제한, 반복 재생·예외·통합 테스트.

금지: 코딩 시작 전에 미지원 SDK를 가용하다고 가정하거나, 음원을 Git 저장소·APK에 무단 포함하거나, 외부 서비스 키를 앱에 삽입하지 않는다.

## 8) 구현 전에 다시 확인할 열린 문제

1) Pleos Music/Audio 서브카테고리: local Music vs Radio 방송형 서비스 최종 등록 분류.
2) 미디어 탐색 노출: Media Center 사용/미사용에 따른 런처·하드키 동작.
3) 파일 선택 UI: Pleos Emulator에서 Android 문서 선택기가 실제 제공되는지.
4) TTS: 실제 계정의 SDK 접근 가능 상태, 합성·재생·stop 및 오디오 포커스 동작.
5) 주행 상태: CarUxRestrictions, Vehicle Capability별 사용 가능 여부.
6) 시연용 음원: 권리 범위(개인 재생, 공개 영상, 앱 포함 배포 등) 별도 확인.

## 9) 공식 문서 참고

- Pleos Music/Audio: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide
- Pleos SDK API compatibility: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability
- Pleos TTS: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro
- Pleos SDK access: https://document.pleos.ai/en/docs/pleos-only/create-my-project
- Pleos driving UX restrictions: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
- Android SAF: https://developer.android.com/training/data-storage/shared/documents-files
