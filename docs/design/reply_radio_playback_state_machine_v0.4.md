# 응답하라 라디오 — 방송 재생 상태 머신 v0.4

기준일: 2026-11-15 / 상태: 설계 확정안(플랫폼 실기 검증 전)  
우선 적용: 이 문서는 기존 `reply_radio_ai_dj_spec_v0.2.md`의 6장과 대화 중 RadioPlanner v0.3에 대한 상세 전이·동시성 정책을 보완한다.  
기본 제약: 1인 개발 MVP, 로컬 MP3, 시대 메타데이터 보장, 2세대 가족 모드, DJ 2~3곡 간격, 음악과 DJ 순차 재생.

## 1) 결정 사항 (ADR)

1) ADR-011 — 단일 재생 제어자
	- `RadioOrchestrator`가 방송의 유일한 상태 변경 주체다.
	- 모든 앱 UI/MediaSession/차량 하드키 명령, Media3/TTS/AI 콜백을 직렬 이벤트 큐로 변환한다.
	- Android UI가 Player/TTS를 직접 제어하지 않는다. 상태 변경은 순수 `reduce(state, event)` + 명령 실행(effect)으로 구분한다.

2) ADR-012 — 음악과 음성은 순차 출력
	- MVP에서 DJ 음성 출력 중에는 Media3 음악이 재생되지 않는다. 덕킹·오디오 합성은 후속 검증.
	- Pleos TTS는 SDK 내부에서 음성 합성·출력을 담당하므로 PCM/파일/음성 ID 반환을 가정하지 않는다.

3) ADR-013 — DJ 호출 정책
	- 최초 명시적 `PLAY` 시 오프닝 DJ 1회(활성화된 경우). 예시 대본 목표 길이 15~25초.
	- 일반 채널에서는 정상 완료 2~3곡 후 DJ 멘트. 가족 모드에서는 시대 A와 B가 2~3곡 블록 단위로 교차한다.
	- 기본 `targetCompletedPerBlock=3`, 후보곡이 2개이면 2곡, 1개이면 1곡 블록 허용.
	- `SKIP`·재생 실패는 완료 수에 포함하지 않는다. 같은 시대의 미시도 곡을 찾아 보충하고, 없다면 블록 종료.
	- 정상 완료곡 0개인 블록 뒤에는 DJ를 생략한다. 축약 1곡 블록은 누적 정상 종료곡 2곡 이상이면서 마지막 DJ 종료 후 180초 이상일 때만 멘트한다(오프닝 제외). 이는 테스트용 잠정 정책.
	- 세대별 0곡이면 해당 세대를 건너뛰며 가족 교차가 이뤄졌다고 표시하지 않는다. 두 세대 모두 0곡이면 오류 상태로 안내한다.

4) ADR-014 — 비동기 안전
	- `sessionId`: 새 방송마다 갱신.
	- `generation`: 편성이나 AI 대본의 의미가 바뀌는 명령(`NEXT`, `PREVIOUS`, 시대/취향 변경, 새 편성, `STOP`)에서 증가.
	- `playbackEpoch`: 출력 중지·재시작·다음 곡·새 음성 요청마다 증가하여 이전 Media3/TTS 콜백 무효화.
	- DJ 응답 승인 조건: `sessionId + generation + requestId + segmentId`와 `contextFingerprint`가 모두 일치.
	- `PAUSE`는 유효한 선행 생성 DJ 텍스트를 보존할 수 있으나, 오래된 출력 콜백은 무효화한다.

5) ADR-015 — 사용자/시스템 일시정지 분리
	- `PAUSED`는 사용자 명시적 조작. 시스템 포커스 복귀만으로 재개하지 않는다.
	- `SUSPENDED`는 오디오 포커스에 의한 강제 중단. 일시적 중단일 때만 정책상 허용되는 자동 재개를 검토하고, 영구 손실은 사용자 `PLAY`를 기다린다.
	- 네비게이션 안내·통화·안전 음성은 DJ보다 우선한다. `CAN_DUCK` 시 DJ는 중단, 음악은 플랫폼 정책에 따라 duck 또는 pause(검증 전 기본 pause).

6) ADR-016 — 기본 실패 정책
	- 생성형 AI 응답은 선행 생성하고 3초 타임아웃 / 자동 재시도 0회(초기 제안). 기한 내 준비되지 않으면 로컬 기본 멘트 또는 DJ 생략.
	- TTS 오류/타임아웃이면 동일 블록에서 반복 재시도하지 않고 음악으로 진행.
	- 재생 가능한 파일이 없거나 동일 세션에서 연속 3회 음원 실패하면 사용자에게 오류 표시 후 중단. 무한 재시도 금지.

## 2) 단일 상태 머신

| 상태 | 의미 | 주요 전이 |
|---|---|---|
| `IDLE` | 방송 미생성 | `PLAY` → `PREPARING` |
| `PREPARING` | 음원/첫 블록/발화 준비 | 유효 준비 완료 → `DJ_SPEAKING` 또는 `MUSIC_PLAYING`; 실패 → `RECOVERING` |
| `DJ_SPEAKING` | TTS 오디오 출력 | `VOICE_DONE` → `MUSIC_PLAYING`; `PAUSE` → `PAUSED`; `NEXT` → `MUSIC_PLAYING` |
| `MUSIC_PLAYING` | 현재 음악 출력(버퍼링 포함) | `TRACK_ENDED` → 다음 곡 또는 브리지; `PAUSE` → `PAUSED` |
| `PAUSED` | 사용자가 중단 | `PLAY` → 저장된 세그먼트 재개; `STOP` → `STOPPED` |
| `SUSPENDED` | 외부 오디오 포커스로 중단 | 일시 중단 후 GAIN+사용 의도 활성 → 원래 단계 복구, 영구 손실은 `PLAY` 필요 |
| `RECOVERING` | 음악/TTS/환경 오류 처리 | 다음 가용 세그먼트로 이동; 복구 불가 → `STOPPED` |
| `STOPPED` | 세션 정리 완료 | 새 `PLAY` → 새 세션 `PREPARING` |

주의: `MUSIC_PLAYING`은 도메인 상태이며 Media3 `STATE_BUFFERING`과 별개다. 재생 준비·버퍼링은 현재 음악 항목의 부가 상태로 표시한다. `MEDIA3 STATE_ENDED`는 재생목록 전체 종료 이벤트이므로, MVP는 한 번에 현재 곡 하나만 Player에 로드해 자연 종료 여부를 확인한다.

### 2.1 보조 상태

```kotlin
// 설계 계약용 Kotlin 유사 모델. 실제 SDK 시그니처와 독립적.
enum class Phase { IDLE, PREPARING, DJ_SPEAKING, MUSIC_PLAYING, PAUSED, SUSPENDED, RECOVERING, STOPPED }
enum class HoldReason { NONE, USER, FOCUS_TRANSIENT, FOCUS_PERMANENT }
enum class OutputKind { NONE, MUSIC, DJ }

data class BroadcastSnapshot(
    val sessionId: String,
    val generation: Long,
    val playbackEpoch: Long,
    val phase: Phase,
    val holdReason: HoldReason,
    val desiredPlaying: Boolean,
    val mode: String,            // SINGLE | FAMILY
    val currentEra: String?,
    val activeTrackId: String?,
    val activeDjSegmentId: String?,
    val completedInBlock: Int,
    val attemptedTrackIdsInBlock: Set<String>,
    val consecutivePlaybackErrors: Int,
    val pendingRequestId: String?,
    val resumeTarget: OutputKind
)
```

추가 런타임 데이터: `activeBlock`(목표 3곡, 후보 목록 및 순서), `completedSinceLastDj`, `lastDjAt`, `recentTopics`, `voiceText`, `trackPositionMs`, `pendingBlock`, `requestedContextFingerprint`. 수명은 앱 프로세스 내에서 관리; 장기 복구는 별도 범위로 둔다.

## 3) 이벤트-전이 계약

| 이벤트 | 출발 상태 | 필수 처리 | 종료 상태 |
|---|---|---|---|
| `PLAY` | `IDLE`,`STOPPED` | 새 세션·재생목록 준비, 오프닝 DJ 정책 적용 | `PREPARING` |
| `PLAY` | `PAUSED` | 사용자 중단 해제, 정지한 음악 위치에서 재개 또는 DJ 대본 처음부터 발화 | 이전 활성 상태 |
| `PLAY` | `SUSPENDED` | 영구 포커스 손실이라면 사용자 입력으로만 재개 시도 | `PREPARING` |
| `PAUSE` | `MUSIC_PLAYING` | Media3 pause, 위치 보존, `desiredPlaying=false` | `PAUSED` |
| `PAUSE` | `DJ_SPEAKING` | TTS stop, 대본 보존, 다음 출력은 명시적 PLAY 이후 | `PAUSED` |
| `PAUSE` | `PREPARING`,`SUSPENDED` | 재생 대기 해제, 자동재생 금지 | `PAUSED` |
| `NEXT` | `MUSIC_PLAYING` | 해당 곡 skip(완료 수 불변), epoch/generation 증가, 같은 시대 미시도 곡 선택 | `MUSIC_PLAYING` 또는 `PREPARING` |
| `NEXT` | `DJ_SPEAKING` | 현재 DJ 중단·무효화, TTS 정지 확인 후 예정된 첫 곡 재생(이 첫 곡 자체는 건너뛰지 않음) | `PREPARING` → `MUSIC_PLAYING` |
| `NEXT` | `PAUSED` | 다음 재생 위치만 변경, 재생은 하지 않음 | `PAUSED` |
| `PREVIOUS` | `MUSIC_PLAYING`,`PAUSED` | 현재 곡 5초 초과 시 처음부터; 그 외 직전 곡으로 이동. PAUSED면 출력하지 않음 | 출발 상태 유지 |
| `TRACK_ENDED` | `MUSIC_PLAYING` | 활성 곡/epoch 일치 시 완료곡 +1, 남은 곡 또는 다음 블록 | `MUSIC_PLAYING` / `PREPARING` |
| `VOICE_DONE` | `DJ_SPEAKING` | 활성 음성 요청 일치 시 다음 음악 시작(정확히 한 번) | `MUSIC_PLAYING` |
| `VOICE_STOP`,`VOICE_ERROR` | `DJ_SPEAKING`,`PREPARING` | 사용자 중단이면 출력 정지 확인 후 대기, 실제 음성 오류이면 음성 비활성화·음악 진행 | `PAUSED` / `MUSIC_PLAYING` |
| `AI_SCRIPT_READY` | 모든 상태 | 현재 세션+generation+segment+requestId+컨텍스트 일치할 때만 보관 | 상태 유지 |
| `FOCUS_LOSS_TRANSIENT` | 출력 중 | 현재 세그먼트/위치 저장, 음악 pause 또는 TTS stop | `SUSPENDED` |
| `FOCUS_LOSS_PERMANENT` | 출력 중 | 즉시 출력 중단, `desiredPlaying=false` | `SUSPENDED` |
| `FOCUS_GAIN` | `SUSPENDED` | 일시 중단+사용자 재생 의도 유지인 경우에만 재개. 사용자 PAUSE 기록이 있으면 불가 | 이전 상태 / `SUSPENDED` |
| `STOP` | 모든 상태 | 전 자원 중지, 요청 취소, epoch/generation 무효화, 포커스 반환 | `STOPPED` |
| `FILE_UNAVAILABLE` | `MUSIC_PLAYING`,`PREPARING` | 해당 곡 제외·동일 시대 재편성, 연속 3회 실패 시 중단 | `RECOVERING` → 다음/정지 |

### 3.1 선곡 블록과 DJ 조건

```text
onTrackNaturalEnd:
  verify(sessionId, playbackEpoch, activeTrackId)
  completedInBlock += 1
  completedSinceLastDj += 1
  consecutivePlaybackErrors = 0
  if completedInBlock < targetCompletedPerBlock AND sameEraUnattemptedTrackExists:
      playNextSameEraTrack()
  else:
      finalizeBlock()

onNextMusic:
  markActiveTrackSkipped()         # completedCount 증가 없음
  invalidatePlaybackEpochAndRelevantDjPlan()
  if sameEraUnattemptedTrackExists:
      playNextSameEraTrack()
  else:
      finalizeBlock()

finalizeBlock:
  nextBlock = planOtherEraOrNextSameEra()
  if djEnabled AND completedSinceLastDj >= 2
     AND noRecentDjCooldownViolation:
      tryUsePrefetchedScriptOrFallback()
  else:
      playNextBlockWithoutDj()
```

`targetCompletedPerBlock=3`이지만 후보가 부족하면 블록을 축소한다. 정상 완료가 0이면 DJ를 발화하지 않는다. 한 시대만 남은 경우 다른 시대의 곡을 임의로 삽입하지 않는다. 양쪽이 동일 시대를 선택한 경우는 `SINGLE` 편성으로 정규화하며 가족 교차 방송이라고 안내하지 않는다.

## 4) 오디오 세션/포커스 경계

1) Media3는 표준 `MediaLibraryService/MediaLibrarySession`에 연결해 차량 Browse·재생 제어·스티어링 휠 입력을 한 경로로 수렴시킨다.
2) 음악 재생 전 포커스 획득 및 반환은 Media3/Android 정책에 맞춰 처리한다. Media3 자동 관리와 앱의 수동 요청을 중복 적용하지 않는다.
3) DJ 발화 직전 Media3 재생을 확실히 중단한다. Pleos TTS는 별도 서비스에서 실제 음성 출력하므로 TTS의 내부 오디오 포커스 동작은 SDK 실기 검증이 필요하다.
4) 통화·안전 알림·내비게이션에 의한 포커스 손실을 임의로 무시하지 않는다. 자체 TTS가 일으킨 포커스 이벤트와 외부 알림의 구분도 검증 전 하드코딩하지 않는다.
5) MediaSession의 `isPlaying`은 실제 음악 출력 상태를 나타내되, DJ 발화 중 방송 UX는 별도의 `DJ_SPEAKING` 도메인 상태를 UI로 제공한다. 차량 센터의 표준 메타데이터/상태 표시 동작은 확인 후 조정.

## 5) TTS 콜백 식별 및 취소 규칙

Pleos TTS `EventListener.onDone/onStop/onError` 자체에는 요청 식별자 매개변수가 없다. 따라서 다음 경계를 반드시 지킨다.

1) `VoiceOutput`은 동시에 하나의 발화만 처리하고, 요청 시 앱 내부 `voiceToken`을 발급한다.
2) `stop()` 후 해당 발화의 중단 완료(`onStop`) 또는 안전한 재초기화가 확인되기 전에는 새 발화뿐 아니라 다음 음악의 출력도 시작하지 않는다. `NEXT` 중에는 `PREPARING` 상태에서 중지 확인을 기다린다.
3) 중지 중 `onDone`/`onError`가 뒤늦게 오면 해당 발화는 중단된 것으로 처리한다. 새 발화에 과거 콜백이 귀속되지 않게 직렬화한다.
4) 정지 확인이 지연되면 제한된 시간 내 재중지·자원 해제를 시도한다. 오디오 출력 중지가 확인되면 음악만 재개하며, 확인 불가능한 경우 출력을 중단하고 오류를 표시한다. 동시 출력을 감수하며 강제 진행하지 않는다.
5) TTS의 `onReady`는 플레이어의 최종 출력 완료 신호가 아니며, `onDone`만 정상 완료로 처리한다.

## 6) 선행 DJ 생성 정책

1) 현재 블록의 마지막 곡이 시작될 때 다음 블록 및 DJ 대본을 선행 요청한다.
2) 요청 키: `sessionId`, `generation`, `requestId`, `segmentId`, `contextFingerprint`.
3) 곡 스킵, 시대 변경, 다음 블록 변경 시 `generation`을 올려 과거 응답을 폐기한다.
4) `PAUSE` 이후 대본 맥락이 변하지 않았으면 기존 텍스트를 유지한다. 단, 재생 콜백/오디오 이벤트는 epoch로 무효화한다.
5) 대본이 준비되기 전에 음악 블록이 끝나면 로컬 기본 멘트(해당 시대/다음 시대만 언급) 또는 DJ 생략. LLM을 기다리며 무한 정지하지 않는다.
6) 날씨가 없거나 오래된 경우 날씨 멘트 생략. 생년·가족 관계·퇴근/정체 상황을 추정하지 않는다.

## 7) 단일 이벤트 루프 구현 예시(의사코드)

```kotlin
sealed interface BroadcastEvent {
    data object Play : BroadcastEvent
    data object Pause : BroadcastEvent
    data object Next : BroadcastEvent
    data object Previous : BroadcastEvent
    data object Stop : BroadcastEvent
    data class TrackEnded(val sessionId: String, val epoch: Long, val trackId: String) : BroadcastEvent
    data class VoiceDone(val sessionId: String, val epoch: Long, val voiceToken: Long) : BroadcastEvent
    data class ScriptReady(val sessionId: String, val generation: Long, val requestId: String,
                           val segmentId: String, val contextFingerprint: String, val text: String) : BroadcastEvent
    data class FocusChanged(val kind: String) : BroadcastEvent
}

// 실제 Kotlin 구현에서는 Channel<BroadcastEvent> + 단일 coroutine collector 권장.
// reducer는 상태/명령만 반환하고 Player, TTS, Network 호출은 effect runner에서 실행.
// effect 결과는 다시 BroadcastEvent로 보내며 직접 전이하지 않는다.
```

핵심 불변조건:
- `activeAudioOutputs <= 1` (MVP 순차 재생).
- 한 `(sessionId, epoch, trackId)`의 자연 종료는 정확히 한 번만 반영.
- `PAUSED`인 동안 API 응답·TTS 종료·FOCUS_GAIN으로 재생할 수 없음.
- 선택 시대 범위를 벗어난 `Track`은 Planner가 반환할 수 없음.
- `STOPPED` 세션은 타임아웃/오디오 콜백으로 부활할 수 없음.
- LLM이 큐를 직접 수정할 수 없음.

## 8) 인수 테스트 (자동화 우선)

| ID | 주입 조건 | 기대 결과 |
|---|---|---|
| SM-01 | 최초 PLAY, DJ ON | 오프닝 1회 후 첫 음악 |
| SM-02 | 1980s 3곡 정상 종료 | DJ 1회 → 2010s 블록 시작 |
| SM-03 | 첫 곡 NEXT, 같은 시대 남은 후보 충분 | 완료 수 0 유지·같은 시대 곡 보충 |
| SM-04 | 전 곡 NEXT로 후보 소진 | DJ 없이 다음 시대 진행 |
| SM-05 | 1곡만 등록 | 해당 곡 방송, 반복 DJ 폭증 없음 |
| SM-06 | DJ 중 NEXT | TTS 중지 후 다음 음악 1회 시작 |
| SM-07 | DJ 중 PAUSE → 늦은 onDone | PAUSED 유지, 음악 자동 시작 금지 |
| SM-08 | 음악 PAUSE → FOCUS_GAIN | PAUSED 유지 |
| SM-09 | 일시 FOCUS_LOSS→GAIN (사용자 중단 없음) | 정책 허용 시 중단 위치 복구 |
| SM-10 | 영구 FOCUS_LOSS→GAIN | 명시적 PLAY 없이는 자동 시작 금지 |
| SM-11 | NEXT 후 이전 LLM 응답 수신 | generation 불일치로 폐기 |
| SM-12 | STOP 후 이전 Media3 STATE_ENDED 수신 | session/epoch 불일치로 폐기 |
| SM-13 | 동일 곡 STATE_ENDED 중복 | 완료 수 1회만 증가 |
| SM-14 | TTS 오류 | 다음 음악 정상 진행, 반복 재시도 없음 |
| SM-15 | AI API 타임아웃 | 기본 멘트 또는 멘트 생략 후 음악 |
| SM-16 | 로컬 URI 접근 해제 | 가용곡으로 교체, 무한 루프 없음 |
| SM-17 | 3곡 연속 재생 실패 | 오류 표시, 음악 엔진 정리 |
| SM-18 | DJ OFF | LLM/TTS 호출 0회 |
| SM-19 | 일시정지 상태 NEXT | 큐 위치만 이동, 출력 없음 |
| SM-20 | 서로 다른 세대에 곡 0개 | 안내 후 시작 차단 |

테스트 구조: `FakeMediaOutput`, `FakeVoiceOutput`, `FakeDjScriptProvider`, `FakeRadioPlanner`, `TestCoroutineScheduler`로 이벤트 순서 및 효과 호출 횟수를 검증한다. 실제 SDK는 통합 테스트로 분리한다.

## 9) Codex 구현 작업 분해

1) `SM-P0` 도메인 이벤트/상태/Reducer 구현 — `Media3`/TTS 미연결, SM-01~05 테스트.
2) `SM-P1` FakeEffectRunner·실패/타임아웃 주입 — SM-06~15 테스트.
3) `SM-P2` Media3 Adapter 및 MediaLibraryService 연동 — `STATE_ENDED`/포커스/하드키 검증.
4) `SM-P3` Pleos TTS VoiceOutput 어댑터 — 실제 onStop 순서·포커스·중복 이벤트 테스트.
5) `SM-P4` AI ScriptProvider 연결 — 요청 유효성 검증·기본 멘트·취소 처리.
6) `SM-P5` 파일 접근 실패·차량 제한·통합 시연 — SM-16~20 및 Pleos 에뮬레이터 검증.

인수 조건: 핵심 도메인 테스트 전부 통과, 출력을 둘 이상 동시에 실행하지 않음, AI가 없을 때도 로컬 음원 재생, 주행 중 UI 정책을 준수하는 상태에서 차량 표준 제어 동작.

## 10) 실기 검증 전 미확정 항목

1) Pleos 계정/차종에서 TTS `speak/stop/onStop/onDone` 실제 동작 및 호출 순서.
2) Pleos TTS와 Media3 사이 오디오 포커스 소유권 및 전환 정책.
3) 사용자 선택 로컬 음원 URI 및 문서 선택기 접근성.
4) `MediaLibraryService` MBS/Media Center/스티어링 휠 구현 방식과 검색 범위.
5) Android 재부팅/프로세스 종료 후 세션 복원(이번 MVP에서는 사용자 새 PLAY 필요).

## 11) 공식 기술 근거

- Pleos Music/Audio 앱 필수 MediaBrowserService, 미디어 제어: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide
- Pleos TTS 이벤트(OnDone/OnStop/OnError): https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners
- Pleos TTS speak/stop: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop
- Android Media3 Player events: https://developer.android.com/media/media3/exoplayer/listening-to-player-events
- Android Audio focus: https://developer.android.com/media/optimize/audio-focus
- Android MediaLibraryService: https://developer.android.com/media/media3/session/serve-content

설계 문서이며 실제 APK/SDK 테스트 통과를 의미하지 않는다.
