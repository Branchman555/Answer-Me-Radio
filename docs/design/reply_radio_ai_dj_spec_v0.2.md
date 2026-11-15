# 응답하라 라디오 — AI DJ 상세 설계 v0.2

작성 기준: 2026-11-14. 개발환경 없이 확정한 설계 계약이며 실제 Pleos SDK 및 차량 테스트 전.  
연관 문서: `reply_radio_codex_handoff_v0.1.md`. 본 문서는 해당 문서의 방송 주기·세대 편성·DJ 모델을 최신 결정으로 대체한다.

## 1) 확정 결정 (ADR)

1) ADR-007: 시대 우선 편성
	- 사용자 선택 시대(예: 1980~1989, 2010~2019)는 하드 필터다. 날씨·취향은 같은 시대의 후보를 정렬하는 용도다.
	- 데모는 사용자가 준비한 로컬 MP3의 `TITLE`, `ARTIST`, `DATE/YEAR`가 정확히 존재한다고 가정한다. 실제 입력 검증·누락값 처리는 남긴다.
	- 재발매·리마스터 등 실제 음악사의 원 발매연도와 달라도 MVP는 태그값을 기준으로 한다.
2) ADR-008: 2~3곡 단위 DJ 편성
	- 사용자가 `PLAY`를 명시적으로 요청할 때 오프닝 DJ를 재생한다. DJ 사용이 꺼져 있으면 음악부터 시작한다.
	- 일반 채널: 같은 시대 곡 2~3개 정상 완료 후 브리지 멘트.
	- 가족 채널: 시대 A 곡 2~3개 정상 완료 → DJ 시대 연결 멘트 → 시대 B 곡 2~3개 완료 → DJ 멘트 → 반복.
	- 첫 오프닝 15~25초, 브리지 10~20초 목표. 실제 TTS 발화 길이로 조정하며 임계값은 UX 테스트 전 잠정값.
	- 곡을 수동으로 건너뛴 것은 `completedTrackCount`에 포함하지 않는다. 해당 시대의 후보를 소진하면 다음 단계로 넘어가거나 라이브러리 부족을 안내한다.
	- 양쪽 시대 모두 최소 2곡이 있으면 각 시대 블록을 동수(2 또는 3곡)로 편성한다. 2곡 미만이면 축소 편성임을 표시하고 가능 범위에서 진행한다.
3) ADR-009: 독자적 DJ 페르소나
	- 1980~1990년대 FM 음악방송의 진행 구조(따뜻한 사연, 음악 이야기, 담백한 유머)를 참조하되 특정 진행자/방송의 실제 대본·시그널·식별 가능한 음성을 복제하지 않는다.
	- 고유 캐릭터 `retro_fm_90_v1` 1명만 MVP에 구현. 목소리 학습·음성 복제는 제외.
4) ADR-010: 책임 분리
	- `RadioPlanner`가 실제 선곡과 재생 순서를 결정한다.
	- `DJScriptGenerator`는 확정된 선곡 및 검증된 컨텍스트로 대사만 생성한다.
	- `RadioOrchestrator`가 TTS/Media3의 순서와 취소·오류·세션 유효성을 관리한다.
	- `VoiceOutput`은 Pleos TTS 등 구체 엔진을 숨기는 어댑터. 기본은 음악과 DJ 음성을 순차 재생(동시 믹싱 없음).

## 2) 컴포넌트와 책임

| 컴포넌트 | 입력 | 출력 | 책임 경계 |
|---|---|---|---|
| `LocalMusicCatalog` | 사용자가 허용한 URI, 메타데이터 | `Track[]` | 파일 스캔·태그·재생 가능성, AI 호출 없음 |
| `RadioPlanner` | 선택 시대, `Track[]`, `Preferences`, `ContextSnapshot`, 재생 이력 | `MusicBlock` | 시대 필터, 곡 순서, 균형, 중복 방지 |
| `DJPersona` | `personaId`, `type` | 말투/문체 제약 | 고정 정책·어휘·금지사항 |
| `DJScriptGenerator` | `DJScriptRequest` | `DJScriptResponse` | 검증된 데이터에 근거한 오리지널 대본 생성, 음원 선택 불가 |
| `VoiceOutput` | 대본 텍스트, `delivery` 힌트 | 시작/완료/중단/오류 이벤트 | TTS SDK 실행·자원관리. 힌트의 실제 지원은 엔진별 다름 |
| `RadioOrchestrator` | 사용자 명령·Media3/TTS 이벤트 | 방송 이벤트 큐·세션 상태 | 오프닝/음악/브리지 스케줄, 낡은 응답 폐기, 오류 대체 |

권장 Android 패키지: `domain.radio`, `domain.dj`, `data.music`, `data.context`, `audio`, `vehicle`, `ui`. 초기에는 단일 Gradle 앱 모듈로 시작한다.

## 3) DJPersona v1

### 3.1 프로파일

```yaml
id: retro_fm_90_v1
locale: ko-KR
identity: "응답하라 라디오의 독자적인 추억의 FM DJ"
personality: [따뜻함, 담백함, 은근한 유머, 음악에 대한 호기심]
language_rules:
  - "과장된 복고체·유행어 남발 금지"
  - "청취자 실명·가족관계·감정 상태를 임의로 추측하지 않기"
  - "사용자가 제공하지 않은 사연을 실제 사연처럼 소개하지 않기"
  - "검증되지 않은 발매·차트·가수 일화·역사적 사실을 만들어내지 않기"
  - "원곡 방송 대사·고유 멘트·실존 DJ 목소리 또는 억양을 식별 가능하게 복제하지 않기"
  - "음악을 설명하되 음악 사이에 과도하게 끼어들지 않기"
opening_target_seconds: [15, 25]
bridge_target_seconds: [10, 20]
intervention_tracks: [2, 3]
voice_intent:
  pace: relaxed
  register: warm-midlow
  delivery: conversational
```

### 3.2 소재 로테이션

`ERA_TRANSITION`, `WEATHER`, `GENERAL_NOSTALGIA`, `MUSIC_METADATA`, `LIGHT_HUMOR` 중 하나를 선택한다. 같은 소재가 연속 2번 이상 반복되지 않도록 `recentTopics`와 비교한다.

- `WEATHER`: 조회시각이 유효하고 기상 데이터가 있을 때만. 비·눈 등을 임의로 언급하지 않는다.
- `MUSIC_METADATA`: 제공된 제목·아티스트·발매연도까지만. 해당 곡의 인기도·사연·가사·제작 배경은 증빙 없이 언급 금지.
- `GENERAL_NOSTALGIA`: 일반적인 기억/공감 질문은 가능하나 사실로 단정하지 않는다.
- `LIGHT_HUMOR`: 운전 안전을 방해하거나 운전자에게 대답을 요구하는 질문 금지.
- `ERA_TRANSITION`: 가족 모드에서 시대 A에서 B로 바뀌는 이유를 자연스럽게 소개한다.

### 3.3 오리지널 대본 예시

- 오프닝: "반갑습니다. 여기는 추억이 흐르는 응답하라 라디오입니다. 오늘은 서로 다른 두 시대의 노래를 한 자리에서 만나볼게요. 먼저 여든 년대의 음악으로 출발합니다."
- 브리지(날씨 확인됨): "빗소리가 어울리는 저녁이네요. 잠깐 시간을 앞으로 돌려, 이번엔 이천십 년대의 노래를 만나보겠습니다. 시대는 달라도 마음에 남는 멜로디는 비슷하니까요."
- 브리지(날씨 없음): "방금 들은 노래들, 좋으셨나요? 다음은 이천십 년대 차례입니다. 같은 차 안에서 만나는 서로 다른 청춘, 계속 이어가겠습니다."

## 4) RadioPlanner 세대별 음악 블록

`MusicBlock`은 DJ 멘트 사이에 재생되는 2~3곡 묶음이며, DJ와 곡 선정 책임은 분리한다.

```json
{
  "blockId": "block-002",
  "mode": "FAMILY",
  "era": "2010s",
  "trackIds": ["local-2011", "local-2014", "local-2017"],
  "previousEra": "1980s",
  "nextEra": "1980s",
  "djBeforeBlock": true
}
```

1) 후보: `year ∈ era` AND `isPlayable=true`.
2) 제한: 연도가 없거나 파일 접근이 불가능하면 후보 제외. 가용곡 중 직전 곡 반복 금지. 충분한 후보가 있을 때 최근 3곡 반복 억제.
3) 정렬: 선호 장르 → 날씨/시간 적합도(메타 태그가 있을 때) → 재생 다양성. 분위기 정보가 없으면 임의로 추측하지 않고 균등 셔플.
4) 가족 모드: A/B를 블록 단위로 번갈아 편성. 양쪽 시대의 곡 수가 충분하면 동일한 블록 크기를 적용해 세대 노출 균형 유지.
5) 후보가 1개뿐이면 그 곡으로 축소 편성하고, 0개면 해당 시대의 음원 추가 안내. 무단으로 다른 시대로 대체하지 않는다.
6) `skip`은 정상 완료 수에 넣지 않는다. 같은 블록의 남은 후보로 채우되 후보가 고갈되면 조기 종료하고 다음 블록으로 이동한다.

## 5) DJScriptGenerator API 계약

경로: `POST /api/v1/dj/scripts`. HTTP·도메인 스키마 초안이며 구현 전 고정할 v1 계약.

### 5.1 요청 예시

```json
{
  "schemaVersion": "1.0",
  "requestId": "req-00017",
  "sessionId": "session-001",
  "generation": 12,
  "segmentId": "dj-before-block-002",
  "type": "ERA_BRIDGE",
  "personaId": "retro_fm_90_v1",
  "locale": "ko-KR",
  "maxSpokenSeconds": 18,
  "context": {
    "timeOfDay": "EVENING",
    "weather": {"condition": "RAIN", "observedAt": "2026-11-14T17:00:00+09:00", "valid": true},
    "mode": "FAMILY",
    "previousEra": "1980s",
    "nextEra": "2010s"
  },
  "previousTracks": [
    {"id": "local-1987", "title": "등록된 곡 제목", "artist": "등록된 아티스트", "year": 1987}
  ],
  "nextTracks": [
    {"id": "local-2014", "title": "다음 곡 제목", "artist": "다음 아티스트", "year": 2014}
  ],
  "recentTopics": ["GENERAL_NOSTALGIA"]
}
```

데모 요청에 실명, 출생일, 정확한 좌표, 음원 원본 파일, URI, 이용자별 고유식별자는 보내지 않는다. `sessionId`는 임의로 발급한 단기 세션 값으로 관리한다. `generation`은 해당 세션에서 재생 큐가 변경될 때 증가한다.

### 5.2 응답 예시

```json
{
  "schemaVersion": "1.0",
  "requestId": "req-00017",
  "sessionId": "session-001",
  "generation": 12,
  "segmentId": "dj-before-block-002",
  "type": "ERA_BRIDGE",
  "personaId": "retro_fm_90_v1",
  "text": "비가 내리는 저녁입니다. 잠깐 시간을 앞으로 돌려 볼까요? 이번에는 이천십 년대의 음악으로 이어가겠습니다.",
  "topic": "ERA_TRANSITION",
  "delivery": {"tone": "WARM", "pace": "RELAXED"},
  "groundingFields": ["context.weather.condition", "context.nextEra"],
  "isFallback": false
}
```

- `type` 허용값: `OPENING`, `ERA_BRIDGE`, `SAME_ERA_BRIDGE`.
- 서버는 `text`만 작성할 수 있으며 `nextTracks`, `blockId`, `generation`을 변경할 수 없다.
- 앱은 `sessionId`, `generation`, `segmentId`, `requestId`가 현재 대기 중인 요청과 모두 일치하는지 확인한다. 아니면 폐기한다.
- 응답은 JSON 스키마, 허용 타입, 길이, 금지 표현·개인정보·허용 가능한 근거 필드에 대해 검증한다.
- 문장 길이 기준(잠정): OPENING 55~120자, BRIDGE 35~90자. 실제 음성 길이 측정 후 조정.
- 허용 사실: 선택 시대, 제공된 곡의 제목·아티스트·연도, 현재 확인된 날씨. 음원 가사 인용이나 실존 방송 대본 인용은 금지.
- 모델의 `groundingFields`는 자체 주장일 뿐이다. 실제 언급된 사실이 입력에 존재하는지 서버의 별도 검증이 필요하다.

### 5.3 LLM 시스템 프롬프트(초안)

```text
당신은 '응답하라 라디오'의 대본 작가다. 1980~1990년대 한국 FM 음악방송의 따뜻한 진행 구조, 담백한 유머, 편안한 구어체를 일반적인 장르 특성으로만 참고한다. 실존 진행자의 고유 대사, 말버릇, 식별 가능한 목소리, 방송 대본은 재현하지 않는다.

절대 규칙:
1. 응답은 지정된 JSON 객체 하나만 반환한다. 코드 블록이나 부연 설명은 금지한다.
2. 곡 선정·순서 결정·재생 명령을 하지 않는다. nextTracks는 읽기 전용이다.
3. 제공된 곡 메타데이터와 검증된 context 외의 역사·차트·날씨·사연을 사실처럼 말하지 않는다.
4. 날씨가 null이거나 valid=false이면 날씨를 언급하지 않는다.
5. 시간대가 EVENING이어도 퇴근길·피로·혼자 운전 중임을 추정하지 않는다.
6. 가족관계·나이·성별을 추정하지 않는다. '부모님/따님' 같은 호칭은 명시적인 설정이 없으면 사용하지 않는다.
7. 운전자에게 즉시 답하거나 화면을 조작하라고 요청하지 않는다.
8. 최근 소재 recentTopics와 중복을 피한다.
9. type=OPENING이면 인사+오늘의 방송 방향, ERA_BRIDGE이면 이전 세대에서 다음 세대로 연결, SAME_ERA_BRIDGE이면 같은 시대의 다음 블록으로 연결한다.
10. 대사는 자연스러운 한국어 구어체로 작성한다. 짧고 명료하게, 지루한 수식어를 줄인다.

출력 필드: schemaVersion, requestId, sessionId, generation, segmentId, type, personaId, text, topic, delivery, groundingFields, isFallback=false. 입력 식별 필드는 정확히 복사한다.
```

주의: 곡명/아티스트 등 메타데이터에는 악의적인 문자열이 들어올 수 있으므로 프롬프트에서 '신뢰할 수 없는 데이터'로 취급하고 실행 지시로 해석하지 않는다.

## 6) RadioOrchestrator 방송 상태 머신

상태: `IDLE` → `PREPARING` → `DJ_SPEAKING` 또는 `MUSIC_PLAYING` → `PAUSED` / `STOPPED`.

세션마다 `(sessionId, generation)`을 유지하고, 재생 큐가 바뀌면 `generation`을 증가시킨다. DJ/TTS 요청은 항상 현재 세션 스냅샷에 결부된다.

| 이벤트 | 처리 |
|---|---|
| `PLAY` | 사용자 명시적 시작 시 세션 생성, 첫 블록 편성, DJ ON이면 오프닝 텍스트 준비 및 발화 후 곡 시작. 준비 실패 시 음악 시작 |
| `TRACK_ENDED` | 정상 종료곡 누적. 블록이 끝나면 다음 DJ와 블록을 예약하고 전환. 아니라면 다음 곡 |
| `NEXT` | 현 음악/발화 중단, 진행 중 요청 취소/무효화. 다음 유효 곡. 수동 스킵은 완료곡 수에서 제외 |
| `PAUSE` | Media3 일시정지, TTS는 SDK 지원에 따라 중지하고 발화 텍스트/복원 지점만 기록. 자동 재개 금지 |
| `RESUME` | 사용자 명시적 재생 시 재개. 중단된 TTS를 정확히 이어 말할 수 없으면 동일 대본 처음부터 재발화하거나 건너뛰는 정책 적용(기본: 처음부터) |
| `TTS_DONE` | 현재 세션에서 정상 발화 완료한 경우에만 예정된 음악 재생 |
| `TTS_STOP`/`TTS_ERROR` | `onDone`과 구분. 사용자 일시정지/스킵이면 정지 유지, 실제 오류이면 음악으로 대체 |
| `AUDIO_FOCUS_LOSS` | 시스템/내비게이션/통화 정책대로 중단 또는 덕킹; 사용자 PAUSE로 분기하지 말고 `pausedBySystem` 별도 추적 |
| `STOP` | 음악/TTS 중단, 백그라운드 요청 취소·generation 증가, 자원 정리 |
| `FILE_UNAVAILABLE` | 해당 곡 제외 후 재편성. 시대 후보가 없으면 알림. 무음·무한 재시도 금지 |

### 6.1 최소 상태 전이 의사코드

```kotlin
fun onTrackEnded(trackId: String) {
    if (state != MUSIC_PLAYING || trackId != currentTrackId) return
    completedInBlock += 1
    if (completedInBlock < activeBlock.trackIds.size) {
        playNextTrack()
    } else {
        val nextBlock = radioPlanner.planNextBlock()
        val cue = pendingDjCue.takeIf { it.matches(sessionId, generation, nextBlock) }
            ?: fallbackDjCue(nextBlock)
        speakOrSkip(cue) { startBlock(nextBlock) }
    }
}

fun onDjResult(response: DjScriptResponse) {
    if (response.sessionId != sessionId || response.generation != generation ||
        response.segmentId != pendingSegmentId || response.requestId != pendingRequestId) return
    pendingDjCue = validate(response) ?: localFallbackCue()
}
```

이는 계약 이해용 의사코드로, 실제 Kotlin 빌드·SDK 시그니처와는 무관하다. 모든 이벤트는 단일 직렬 실행 컨텍스트에서 처리해 순서 경쟁을 방지한다.

### 6.2 선행 생성·타임아웃

1. 블록 마지막 곡이 시작되면 다음 블록을 예약하고 다음 DJ 대본을 비동기 생성한다.
2. 음악 종료 시 대본이 준비되지 않았으면 짧은 로컬 기본 멘트 또는 무음 전환을 선택한다. 음악 진행을 LLM 응답 대기 때문에 무기한 막지 않는다.
3. 온라인 요청 타임아웃 및 재시도 횟수는 운영 설정으로 분리(초기 제안 3초·재시도 0회). 실패 시 즉시 폴백.
4. TTS 엔진 내부 버퍼·음성 캐시·동시 음악 믹싱은 별도 SDK 검증 전 설계상 가정하지 않는다.
5. MP3가 권리·파일 접근 문제로 재생 불가능하면 조용히 다른 시대의 곡을 끼워 넣지 않는다.

## 7) Pleos 연동 경계

- 공식 TTS 문서에는 `speak()`/`stop()` 및 `EventListener.onDone()`, `onStop()`, `onError()`가 안내되어 있다. SDK는 합성 및 차량 오디오 재생을 직접 담당하므로 MIDI/PCM 파일 반환이나 감정 파라미터를 제공한다고 가정하지 않는다.
- TTS 모드: `HYBRID`, `SERVER`, `ON_DEVICE`; 실제 계정별 SDK 권한, 한국어 자연스러움, 내비게이션 오디오 포커스, 동작 지연은 에뮬레이터 검증 후 확정.
- 미디어 앱은 공식 Media3/MediaLibraryService, MediaSession 및 MBS 요구를 별도 구현한다.
- 다음 오디오 전환은 기본적으로 `음악 종료 → TTS 재생 → TTS onDone → 다음 음악`으로 시작한다. 음량 덕킹/크로스페이드는 후속 검증 사항.

문서:
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners
- https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide

## 8) 인수 테스트 (Codex 자동화 대상)

1) 등록된 1980s 3곡·2010s 3곡으로 가족 모드 재생 → 오프닝 → 1980s 세 곡 → DJ → 2010s 세 곡 → DJ 순서.
2) 같은 시대 후보에 다른 시대 곡이 있어도 날씨 점수 때문에 선택되지 않음.
3) DJ 생성에 실패해도 `fallback` 또는 직접 다음 곡 재생, 재생 중단/무한 대기 없음.
4) 시간대만 제공된 경우 '퇴근길'로 발화하지 않음. 날씨 유효성 false면 날씨 언급 없음.
5) LLM 응답이 완료 전 `NEXT`/새 세션 요청으로 generation 변경 → 오래된 응답 무시.
6) TTS `onStop`과 `onDone` 동시/반복 전달 → 다음 곡이 중복 시작되지 않음.
7) DJ 재생 도중 `PAUSE` → 자동 재개 없음; 사용자 `RESUME` 후 정책에 따라 재발화.
8) 가족 모드 시대 A 2곡/B 2곡 준비 → 같은 블록 크기로 교차되며 허용된 곡만 재생.
9) 한쪽 시대의 음원이 0개 → 두 세대가 정상 편성됐다고 표시하지 않음, 해당 시대 보완 안내.
10) 로컬 음원 접근 권한 해제 → 예외 처리, 사용 불가 파일 제외, 앱 크래시 없음.
11) DJ OFF → 대본/TTS 요청 없이 음악 재생.
12) 오디오 포커스·미디어 세션 제어 → 시스템 중단을 존중하고 사용자 PAUSE와 자동재개 원인 구분.

## 9) Codex 작업 티켓

1) `DJ-01`: `DJPersona` 구성 파일 + 유닛 테스트. 완료 조건: 금지 대사 및 기본 톤·주기 설정 로드.
2) `DJ-02`: RadioPlanner의 `MusicBlock`(2~3곡/시대 교대)와 카탈로그 메타데이터 필터. 완료 조건: 시대 외 음원 편성 0건.
3) `DJ-03`: FastAPI `/api/v1/dj/scripts` 요청/응답 모델·모의 LLM·대본 검증. 완료 조건: 스키마·사실 제약·폴백 테스트 통과.
4) `DJ-04`: `VoiceOutput` 인터페이스·Fake TTS. 완료 조건: `done/stop/error` 구분 및 중복 이벤트 방어.
5) `DJ-05`: `RadioOrchestrator` 상태 머신. 완료 조건: 정상 진행·skip/pause/stop·늦은 응답 테스트 통과.
6) `DJ-06`: Pleos TTS 및 Media3 어댑터 실제 연결. 완료 조건: 에뮬레이터에서 세 곡+DJ+세 곡 시나리오 재현.

## 10) 범위 제외 및 향후 확장

- 제외: 실존 DJ 음성 복제, 원본 방송 대본 복사, 음성모델 자체 학습, 사용자 음성 사연, 상용 스트리밍 연동, 날씨만으로 시대를 바꾸는 선곡, 실시간 음성-음악 오버레이 믹싱.
- 후속: 기획자 피드백 기반 DJ 대사 품질 개선, 개인별 DJ 빈도 설정, 법적 권리 확보된 독자 음성모델, 음원 공급원 교체 인터페이스, 안전 조건에서의 UX 고도화.

---

상태: API 및 도메인 설계 초안. 실물 차량·Pleos SDK·TTS 음질·권리 확보는 아직 검증 전.

## 11) Codex 첫 작업 지시문(복사용)

```text
프로젝트: 응답하라 라디오. 첨부한 reply_radio_ai_dj_spec_v0.2.md를 최신 도메인 계약으로 사용한다. 이전 v0.1 문서와 방송 주기·편성 규칙이 충돌하면 v0.2를 우선한다.

1. 먼저 Android 앱과 무관한 순수 Kotlin 도메인 모델(Track, RadioProfile, MusicBlock, RadioPlanner, DJPersona)과 단위 테스트를 구현한다.
2. ERA는 하드 필터다. 데모 로컬 음원은 TITLE/ARTIST/YEAR 메타데이터가 정확하다고 가정하되 손상/누락 예외는 처리한다.
3. FAMILY 모드는 시대 A 2~3곡 블록과 시대 B 2~3곡 블록을 교대한다. LLM은 곡 선정에 참여하지 않는다.
4. DJ는 재생 시작 시 오프닝, 이후 각 블록이 정상 완료된 뒤에만 등장한다. 수동 NEXT는 정상 완료로 세지 않는다.
5. 로컬 Fake DJScriptProvider, Fake VoiceOutput으로 상태 머신 테스트를 먼저 통과시킨다. 실제 Pleos SDK 연동은 후속 작업에서 진행한다.
6. 코드 생성 후 테스트 명령, 테스트 통과 결과, 확인 불가능한 Pleos 의존성/권한을 명확히 보고한다.

필수 테스트: 시대 밖 곡 0건, 3곡 A → DJ → 3곡 B → DJ, LLM 실패 폴백, 늦은 응답 폐기, TTS onStop != onDone, PAUSE 시 자동재개 금지.
```
