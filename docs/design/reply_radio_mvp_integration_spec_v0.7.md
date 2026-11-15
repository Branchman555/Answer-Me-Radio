# 응답하라 라디오 — MVP 통합 시퀀스·인수 설계 v0.7

작성 기준: 2026-11-15 / 상태: 구현 전 통합 명세 / 적용 대상: 1인 개발 + 1인 기획·검증, Pleos Connect 차량용 앱
문서 우선순위: 본 문서(v0.7) > 동일 도메인 상세 명세(v0.6 Context / v0.5 DJ / v0.4 State / v0.2 RadioPlanner) > 초기 제안(v0.1). 단, v0.5 API JSON 스키마와 v0.6 ContextSnapshot JSON 스키마는 명시적으로 변경하지 않는다.
주의: 문서·스키마·테스트 데이터의 검증과 에뮬레이터/실차에서 기능이 동작한다는 증명은 별개다. 이 문서만으로 SDK 사용 승인·저작권 확보·플랫폼 심사 통과가 확인되는 것은 아니다.

1) 프로젝트 목표와 현재 결정
	1-1) 제품
		- 앱: 응답하라 라디오. 세대 공감을 중심으로, 서로 다른 음악 시대를 AI DJ가 이야기로 연결하는 차량 특화 레트로 라디오.
		- 사용자: 세대가 다른 동승자를 핵심 사용 시나리오로 삼고, 1인 청취도 SINGLE 모드로 지원한다.
		- 감성 표현: 1980~1990년대 한국 FM 음악 방송의 일반적 진행 방식. '별이 빛나는 밤에', '배철수의 음악캠프'를 분위기 참고로만 사용하며 방송 원문·식별 가능한 실존 DJ의 목소리/말버릇은 복제하지 않는다.
	1-2) 인력
		- 개발 담당 1명: 아키텍처·Kotlin/Android·Pleos·미디어·백엔드·AI 통합·테스트.
		- 기획 담당 1명: 이용 시나리오·대본 검수·일반 사용자 평가·발표·콘텐츠 권리 증빙.
	1-3) MVP에 포함
		- 사용자가 직접 준비해 선택한 로컬 MP3 → 메타데이터 추출/연도 분류 → SINGLE/FAMILY 편성 → 미디어 재생.
		- 시대 우선·취향/날씨/시간대 보조 선곡, 독자적 DJ 캐릭터 1명, 2~3곡 단위 DJ, 생성 대본 + TTS, 로컬 대본 폴백.
		- Media3 표준 미디어 제어, 플랫폼 안전 UX, 포커스·비동기·재생 실패 복구, 권한/비밀정보 보호.
	1-4) 조건부·제외
		- 날씨는 외부 제공업체와 권한·약관이 검증될 때만 실제 조회. 미연동에서도 음악 재생 가능; 시연용 모의 날씨는 반드시 명시.
		- Vehicle SDK 값은 지원 여부·승인된 권한 검증 후 별도 기능으로 추가. 차속=정체, 시간=퇴근, 두 시대=부녀 관계로 추정하지 않음.
		- MVP 제외: 스트리밍 서비스 실연동, 커스텀 음성 학습, 음성 사연·마이크 녹음, 가족 카세트 저장, 복수 DJ, 서버 전송형 청취 기록, 음악과 TTS의 동시 믹싱·덕킹, AI 곡 선정.
		- 상용화 방향: MusicSource 공급원 어댑터로 합법적 스트리밍 제휴 연동 가능성을 열어두되 계약·정책·DRM 제약을 별도 검증.

2) 용어 및 소유권
	2-1) `Track`: 선택한 로컬 URI에 연결된 한 오디오 항목. `id,title,artist,releaseYear,genre?,durationMs?,contentUri,isAccessible`.
	2-2) `Era`: 발매연도 10년 범위(예: 1980s=1980~1989). 데모 MP3의 TITLE/ARTIST/YEAR 메타데이터는 준비자가 채운다고 가정한다. 연도 누락·깨진 값은 자동 추측하지 않고 미분류/오류로 보낸다.
	2-3) `MusicBlock`: 1개 Era의 실제 곡 1~3개 편성 결과. FAMILY는 A와 B의 시대 블록 교차. 시대 A/B가 동일하면 SINGLE로 정규화.
	2-4) `ContextSnapshot`: 앱 로컬의 불변 상황정보 DTO. 최초/블록 경계에서 채택. 원본 정밀위치·파일 URI·차량 식별자를 포함하지 않는다.
	2-5) `DJScriptRequest/Response`: FastAPI 대본 API의 JSON v1.0 계약. 기존 v0.5 스키마 유지. ContextSnapshot 전체 업로드 금지.
	2-6) `RadioOrchestrator`: 유일한 방송 상태 및 이벤트 전이 소유자. 다른 모듈은 재생 상태를 직접 변경하지 않는다.
	2-7) `generation`: 다음 편성/대본 맥락이 달라지면 증가. `playbackEpoch`: 다음 출력 세그먼트로 바뀌거나 재시작 시 증가. `sessionId`: 새 방송 세션마다 새 식별자.
	2-8) `contextFingerprint`: v0.6의 의미 있는 컨텍스트 투영을 canonical JSON UTF-8/SHA-256으로 계산. 서버 응답 필드는 아니며 앱의 `pendingRequest`에 저장한 요청 당시 fingerprint로 콜백 적합성을 판정.

3) 전체 시스템 구성

```text
[설정/UX 화면]   [시스템 미디어 컨트롤(MediaLibraryService + MediaLibrarySession)]
       \                   /
        \                 /
        [RadioOrchestrator: single reducer + effect runner]
             |             |                |
      [LocalMusicCatalog] [RadioPlanner]  [ContextEngine] ---- [UxSafetyGate]
             |             |                |                 (CarUxRestrictions)
      [SAF/MediaStore]  [MusicBlocks]     [Clock/Weather/Vehicle optional]
             |             |                |
             +----------[Next DJ Request]--+
                           |
             [DJScriptProvider: local fallback / FastAPI]
                           |
                 [LLM Script Validator]
                           |
                 [VoiceOutput: Pleos TTS]
                           |
             [Media3 Player / audio focus]
                           |
                 [차량 스피커 / 사용자]
```

	3-1) Android는 단일 `app` Gradle 모듈에서 패키지를 분리한다(`catalog`, `planner`, `orchestrator`, `context`, `voice`, `media`, `vehicle`, `ui`). 실제 요구가 없으면 초기 멀티모듈 금지.
	3-2) 재생 계층 `Media3 Player`와 `VoiceOutput`은 둘 다 Orchestrator의 effect runner만 실행한다. 일시에 실제 출력되는 앱 오디오 소스는 하나 이하(`activeAudioOutputs <= 1`).
	3-3) 차량 표준 탐색·제어는 Media3 `MediaLibraryService/MediaLibrarySession`을 우선 사용하고 MBS 호환성, 검색·재생·일시정지·이전·다음·시스템 버튼을 검증한다. 자체 레트로 UI와 차량 시스템 미디어 화면을 동일 UI로 가정하지 않는다.
	3-4) 백엔드는 FastAPI 하나로 요청 인증·입력검증·비밀키 중계·타임아웃·출력검증·요청 제한·폴백을 수행한다. 재생 가능한 곡을 선택하거나 로컬 파일을 수신하지 않는다.
	3-5) 설정 우선: `radio_policy.example_v0.7.json`을 비밀 없는 제안 설정 템플릿으로 사용하고 실제 구현 시 `radio_policy.json`으로 확정하여, API 비밀키만 서버 시크릿 저장소에서 관리한다.

4) 로컬 음악 처리 및 선곡의 불변 규칙
	4-1) 등록: 사용자 명시적 선택으로 시스템 파일 선택기(SAF)가 반환한 URI 접근 권한 확보 → 메타데이터 읽기 → `Track` 카탈로그 생성. Pleos 파일 선택 UI 가용성은 실기 검증 전 미확인.
	4-2) 권한: URI 영속 권한이 가능한 형태인지 확인하고, 재시작 시 다시 접근 가능해야 한다. USB 제거/파일 삭제/권한 철회는 정상적인 오류 흐름.
	4-3) 필터: `isAccessible && isPlayable && releaseYear in eraRange`. 시대 범위를 점수로 완화하지 않는다.
	4-4) 순위: 시대 필터 이후 선호 장르·아티스트, 유효한 날씨/시각(음악 분위기 태그가 검증되었을 때), 최근 곡·아티스트 반복 회피. 메타에 moodTags가 없으면 분위기를 AI가 임의 추측하지 않는다.
	4-5) 초기 실험 가중치(비확정): 선호 0.45, 상황 0.25, 다양성 0.20, 반복 0.10. 사용 가능한 특성이 없으면 해당 항목을 제거하고 나머지를 재정규화한다. 동점일 때는 `(seed,trackId)`로 재현 가능한 순서를 만든다.
	4-6) 반복: 같은 곡 즉시 연속 재생 금지(후보가 1곡뿐인 경우에는 사용자에게 반복 상태 안내). 후보가 충분할 때 최근 3곡을 억제한다.
	4-7) FAMILY: 시대 A 3곡 정상 완료를 목표 → DJ → 시대 B 3곡 정상 완료를 목표 → DJ. 시대 후보가 2개뿐이면 2곡 블록; 1개뿐이면 1곡 축약 블록, DJ 매번 반복 금지.
	4-8) 수동 NEXT는 정상 완료로 계산하지 않는다. 같은 시대 미시도 후보로 보충하되 후보가 소진되면 블록 종료한다. 모두 스킵된 블록은 DJ 없이 다음 블록으로 이동.
	4-9) 한 시대 후보 0개면 사용자에게 안내하고 그 시대를 건너뛴 축소 방송임을 표시. 두 시대 모두 후보 0개면 시작 차단. 어떤 경우에도 다른 시대 곡을 자동으로 대체하지 않는다.

5) DJ 방송 정책 및 음성
	5-1) 오프닝 `OPENING` 15~25초 목표. 중간 멘트 `ERA_BRIDGE`/`SAME_ERA_BRIDGE` 10~20초 목표. 문자 수는 v0.5의 휴리스틱이며 실제 TTS 재생 길이로 조정.
	5-2) 사용자가 지정한 DJ ON/OFF를 존중한다. 기본 3곡 정상 완료 후 DJ; 후보 부족이면 2곡 정상 완료에도 DJ 가능. 2곡 미만이면 DJ 생략하거나 반복 쿨다운을 적용한다.
	5-3) 이전 기획의 '첫 30분 오프닝 포함 최대 3회'는 사용자의 최신 결정인 '2~3곡마다'와 충돌하므로 강제 횟수 상한에서 제거한다. 단, 같은 멘트/한 곡 반복 상황에서 과도한 발화를 막는 쿨다운과 DJ OFF는 유지한다.
	5-4) 오리지널 `retro_fm_90_v1`, 따뜻하고 담백한 존댓말, 짧은 감성·음악 이야기·가벼운 유머. 실존 인물의 고유 멘트·화법·음성 복제 금지.
	5-5) LLM은 `RadioPlanner`가 이미 선택한 곡의 검증된 `id,title,artist,year`, 이전/다음 시대, 유효한 시간·날씨만 보고 대사 작성. 차트·가사·가수 일화·가짜 청취자 사연을 만들지 않는다.
	5-6) API: `POST /api/v1/dj/scripts`, v0.5 schemaVersion 1.0. `OPENING`, `ERA_BRIDGE`, `SAME_ERA_BRIDGE` 3종. `contextFingerprint`는 API 응답에 추가하지 않는다.
	5-7) 서버 정책: LLM 응답을 JSON·스키마·근거·금지 표현·예상 길이 검사. 실패 또는 3초 타임아웃(초기값) 시 추가 LLM 재시도 0회, 로컬 폴백 텍스트 또는 DJ 생략.
	5-8) 다음 DJ 요청은 현 블록 마지막 곡 시작 때 미리 발생. 생성이 곡 종료까지 오지 않으면 대기하지 않고 폴백. 늦은 대본은 `sessionId,generation,requestId,segmentId,contextFingerprint`가 기존 대기 요청과 정확히 일치할 때만 수용.
	5-9) TTS 자체 오디오 파일 다운로드·캐싱·억양/감정 제어를 보장하지 않는다. Pleos TTS의 최신 SDK v3.3.1에서 오류 콜백 `ErrorType` 등 실제 API와 권한을 검증. 미지원 시 사용 가능한 합법적 기본 TTS 또는 DJ 생략 경로로 축소.
	5-10) 음악과 TTS는 순차 출력. 기본은 곡 완주 → DJ → 다음 곡. 시그널 효과음은 권리 확보와 출력 안정성 검증 뒤 추가. 동시에 음악을 낮추면서 DJ를 겹치는 덕킹/믹싱은 MVP에 포함하지 않음.

6) ContextEngine: 수집·갱신·공개 범위
	6-1) 취향: 사용자 선택한 1~2개 시대/선호 장르, DJ ON/OFF, 명시적 여행 목적. 출생연도·성별·가족관계 자동 추론 금지.
	6-2) 시간: 장치 로컬 시각 기준 MORNING(05~10:59), DAY(11~16:59), EVENING(17~20:59), NIGHT(21~04:59). 시각/타임존 불확실하면 UNKNOWN.
	6-3) 날씨: 재조회 15분(초기값), `observedAt`으로 60분 이내만 유효, 5분 넘게 미래인 값 무효. `fetchedAt` 갱신만으로 유효기간 연장하지 않음. 요청 실패 시 유효 캐시 사용, 아니면 null.
	6-4) 위치: 명시적 동의 후 날씨 조회를 위한 일시 처리. 원본 좌표·정밀위치·차량 식별정보를 ContextSnapshot/LLM/일반 로그에 포함하지 않음. 위치 미수신 시 수동 선택 지역 또는 날씨 제외.
	6-5) 차량: Vehicle SDK는 기본 DISABLED, 지원/승인/Capability 확인 후에만 조건부 확장. 안전 제약 `CarUxRestrictions`는 `UxSafetyGate`가 독립 처리하고 LLM에 전달하지 않음.
	6-6) ContextSnapshot은 곡 사이에 강제 갱신하지 않고 다음 블록 편성·DJ 요청 시점에 채택한다. 날씨가 바뀌어도 재생 중인 곡은 중단하지 않음.
	6-7) 실제 날씨 API 제공사는 미선정. DEMO simulationMode=true는 발표용 빌드에서만 허용하고 화면·발표에 시뮬레이션임을 명시; Release에서 실제 날씨처럼 가장하지 않음.
	6-8) DJ API로의 projection은 v0.5 `context.timeOfDay, weather, mode, previousEra, nextEra`만. `preferences, vehicle, tripIntent, URI, GPS` 전달 금지. 자세한 정규화·fingerprint는 v0.6 준수.

7) 통합 시퀀스 — 사용자 기본 경로
	7-1) 앱 실행 / 차량 미디어 연결
		- 앱은 설정과 로컬 카탈로그를 복원하고 UX 제한 상태를 구독한다. 앱 실행/서비스 바인드만으로 자동 재생하지 않는다.
		- MBS/MediaLibraryService는 로컬 카탈로그 및 시대별 브라우즈를 공개하되, 사용자 허용 범위 밖의 파일을 열지 않는다.
	7-2) 음원 등록
		- 플랫폼이 상세 조작을 허용한 경우만 사용자가 MP3 여러 파일을 선택한다.
		- URI 접근 권한·재생 가능성·TITLE/ARTIST/YEAR 검사 후 카탈로그에 저장한다. 실패 파일은 개별 오류 표시, 전체 등록 중단 최소화.
	7-3) 가족 라디오 설정
		- 사용자가 A=1980s, B=2010s 등 선호 시대 2개를 선택. 동일 시대면 SINGLE, 한 시대 곡 없음은 축소 상태로 명시.
	7-4) 명시적 PLAY
		- `IDLE/STOPPED → PREPARING`: 신규 sessionId, 초기 generation/epoch, 재생 의도=true, 접근 가능한 시대별 후보 확인.
		- `ContextEngine`이 로컬 설정·시간을 즉시 반영해 스냅샷 제공. 날씨 요청은 비동기 진행; 결과를 기다리느라 오디오 시작을 무기한 블로킹하지 않는다.
		- `RadioPlanner`가 첫 MusicBlock과 다음 MusicBlock 후보를 결정; 기본 DJ 오프닝을 미리 준비. 아직 AI 응답이 없으면 로컬 오프닝으로 전환.
	7-5) DJ 오프닝
		- `PREPARING → DJ_SPEAKING`: 음악 출력은 꺼져 있고 TTS만 출력. onDone이 현재 voiceToken/epoch에 맞을 때만 첫 음악 진행.
		- TTS 초기화 실패/미지원 시 DJ 생략 후 `MUSIC_PLAYING`으로 진행.
	7-6) 음악 블록 A
		- `MUSIC_PLAYING`: 한 곡씩 Media3에 로드, 정상 종료만 completedInBlock 증가. 2~3곡 목표, 수동 skip은 카운트하지 않고 후보로 보충.
		- 마지막 곡 시작 시 다음 블록과 브리지 대본 선행 생성. 음악 종료 시 DJ 준비 여부를 확인; 타임아웃이면 폴백/생략.
	7-7) DJ 세대 전환
		- 완료 곡 수 ≥2이고 쿨다운을 만족할 때 `ERA_BRIDGE` 발화. A→B 전환 정보를 DJ API에 전달; 동시에 두 오디오 출력 금지.
	7-8) 음악 블록 B
		- B시대 곡 2~3개 정상 재생. 다음 A 블록을 예약하고 반복. ContextSnapshot의 새 값은 블록 경계에서만 채택.
	7-9) 사용자 조작
		- NEXT: 현재 곡은 skip, 같은 시대 후보 보충. DJ 중 NEXT는 현재 TTS 중지 확인 후 예정한 다음 곡으로 진행(음악까지 이중 skip하지 않음).
		- PAUSE: 음악이면 위치 보존, DJ이면 텍스트 보존 후 TTS stop. 사용자 PLAY 외에는 재개하지 않음.
		- PREVIOUS: 음악 재생 5초 초과면 현재 곡 처음부터, 이하라면 직전 곡. PAUSED에선 위치만 바꿔 출력하지 않음.
		- STOP: 출력 및 pending 요청 무효화, 오디오 포커스 정리, 세션 종료.
	7-10) 앱 종료·복구
		- 앱 재실행은 설정과 카탈로그를 복원하지만 사용자 의도 확인 없이 재생하지 않는다. URI 접근권이 사라졌다면 재선택을 요구한다.

8) 통합 이벤트 순서 (정상 FAMILY 시연)

```mermaid
sequenceDiagram
    participant U as 사용자/차량 컨트롤
    participant O as RadioOrchestrator
    participant C as ContextEngine
    participant P as RadioPlanner
    participant A as DJScriptProvider
    participant V as VoiceOutput
    participant M as Media3
    U->>O: PLAY (명시적 요청)
    O->>C: snapshot()
    C-->>O: immutable snapshot (weather optional)
    O->>P: plan(A=1980s,B=2010s,seed)
    P-->>O: MusicBlock A and B
    O->>A: OPENING request (or fallback)
    A-->>O: valid script or fallback
    O->>V: speak(opening)
    V-->>O: VOICE_DONE (active token)
    loop 2~3 completed tracks in Era A
      O->>M: play(tracked local URI)
      M-->>O: TRACK_ENDED (active epoch)
    end
    O->>A: ERA_BRIDGE prepared before last track end
    A-->>O: validated script or fallback
    O->>V: speak(A→B)
    V-->>O: VOICE_DONE
    loop 2~3 completed tracks in Era B
      O->>M: play(tracked local URI)
      M-->>O: TRACK_ENDED
    end
    Note over O,M: repeat; focus/pause/skip and late responses checked each event
```

9) 상태 머신 / 이벤트 안전 규약
	9-1) 상태: `IDLE`, `PREPARING`, `DJ_SPEAKING`, `MUSIC_PLAYING`, `PAUSED`, `SUSPENDED`, `RECOVERING`, `STOPPED`(v0.4 유지).
	9-2) 단일 이벤트 큐(reducer → side-effect runner)를 통해 처리한다. Media3/TTS/HTTP 콜백은 상태를 직접 변경하지 않고 이벤트만 발행한다.
	9-3) 완료 이벤트는 반드시 활성 `(sessionId,playbackEpoch,trackId 또는 voiceToken)`과 일치하며 1번만 소비한다. 중복 onDone/STATE_ENDED로 다중 곡 시작 금지.
	9-4) `PAUSED`는 사용자의 명시적 일시정지. `SUSPENDED`는 시스템 포커스 상실. 구분된 `desiredPlaying`을 유지한다.
	9-5) 일시적 FOCUS_LOSS는 지원 정책에 따라 중단하고 GAIN 후 사용자 의도가 살아 있을 때만 조건부 복구. 영구 상실은 자동 재개 금지.
	9-6) TTS STOP 요청 후 완료(onStop) 또는 안전한 자원 해제 확인 없이 음악 출력을 시작하지 않는다. 콜백 상관관계가 불명확하면 새 발화를 직렬화하고 필요시 세션 출력 중단(fail closed).
	9-7) 사용자 NEXT/시대 변경/STOP은 대본 generation을 갱신. PAUSE는 대본 자체를 살릴 수 있어도 오디오 epoch을 무효화. 오래된 AI 결과는 재생 권한 없음.
	9-8) Media3의 `STATE_ENDED`는 재생목록 전체 종료 상태임. MVP에서는 한 번에 현재 곡 1개씩 로드하고 해당 곡 자연 종료를 검증한다.
	9-9) 연속 3회 음원 재생 오류 또는 가용 음원 0개면 오류 표시 후 출력 중단. 오류 무한 반복 금지.

10) 장애·예외 처리 결정표

| 사건 | 판정 위치 | 사용자에게 보이는 결과 | 시스템 규칙 |
|---|---|---|---|
| 음원 0개/두 시대 모두 없음 | Catalog/Planner | 음원 추가 안내 | 방송 시작 불가 |
| 한 시대 0개 | Planner | 축소 방송 안내 | 가용 시대만; 세대 공감 완성으로 표현 금지 |
| 파일 삭제/USB 분리/권한 철회 | Catalog/Media | 해당 곡 제외 또는 재등록 안내 | 최대 3회 연속 실패 후 중단 |
| YEAR 누락/손상 | Catalog | 미분류/등록 오류 | 추정하지 않음 |
| 동일 곡 1개만 반복 | Planner | 반복 상태 표시 | 매 곡 DJ 방지 |
| WEATHER 60분 초과/위치 거부 | ContextEngine | 날씨 없이 방송 | 날씨 멘트·가중치 제거 |
| 날씨가 음악 재생 중 변경 | ContextEngine | 현재 곡 유지 | 다음 블록에서만 반영 |
| Vehicle SDK 미승인/미지원 | VehicleAdapter | 기본 방송 | 차량 데이터 없이 진행 |
| UX 제한 판정 불명 | UxSafetyGate | 상세 조작 제한 | 차속 추정 금지 |
| AI 3초 초과/잘못된 JSON | ScriptProvider | 기본 대본 또는 멘트 생략 | 추가 LLM 재시도 0회 |
| 오래된 AI 응답 | Orchestrator | 영향 없음 | correlation 일치 실패 시 폐기 |
| TTS 초기화/출력 오류 | VoiceOutput | DJ 없이 다음 곡 | 음악 출력 독립 유지 |
| TTS stop 완료 미확인 | VoiceOutput | 출력 중단·오류 표시 | 중첩 출력 감수하지 않음 |
| 통화·내비게이션 포커스 | AudioAdapter | 방송 일시 중단/정책상 제어 | 사용자 PAUSE와 구분 |
| HTTP/날씨 서버 장애 | WeatherGateway | 날씨 없는 방송 | 유효 캐시 또는 null; 과다 재시도 없음 |
| 사용자 재생 중 STOP | Orchestrator | 방송 종료 | 모든 콜백·요청 무효화 |

11) API 및 보안 계약 고정
	11-1) 앱 내부 ContextSnapshot: `reply_radio_context_schema_v0.6.json`. API로 그대로 보내지 않는다.
	11-2) 대본 API: `reply_radio_dj_script_schema_v0.5.json`의 request/response 객체를 정확히 따른다. `requestId,sessionId,generation,segmentId,type,personaId,locale,maxSpokenSeconds,context,previousTracks,nextTracks,recentTopics` 필수.
	11-3) `context`는 `timeOfDay,weather,mode,previousEra,nextEra`만 포함하며 개인정보·정밀위치·파일 경로·탑승자 수·차량 텔레메트리 없음.
	11-4) `response`는 대사·주제·전달 힌트 등만 반환, `trackIds`·재생 URL·오디오 제어 필드는 금지.
	11-5) `groundingFields`를 사실 검증 완료의 증거로 믿지 않는다. 입력에 없는 곡/날씨/역사적 사실은 차단 또는 제거.
	11-6) 파일 태그는 외부 유입 데이터(프롬프트 인젝션 가능). 문자열 길이 제한·Unicode 정규화·텍스트 데이터로만 인코딩, 시스템 명령으로 취급하지 않는다.
	11-7) FastAPI의 인증은 앱 공통 정적 키를 박아 넣는 방식으로 해결하지 않는다. 공급자 API 키 서버 보관, 요청량 제한/익명 남용 방지/액세스 회수 설계를 PoC 단계에서 검증한다.
	11-8) 사용자 로컬 음원·정밀위치·청취기록은 백엔드에 업로드하지 않는다. 로그는 요청 식별자·지연·실패코드·기능 버전 등 비식별 진단 중심.
	11-9) 실재 음원·방송 효과음·시안에 들어가는 이미지·목소리는 개인 재생·공개 시연 영상·APK 배포·GitHub 공개별 이용권한을 각각 확인한다.

12) 인수 테스트 계획과 커버리지
	12-1) 파일: `reply_radio_acceptance_matrix_v0.7.csv`(상세 54건), `reply_radio_integration_fixtures_v0.7.json`(입력/이벤트/예상 관측 예제 + 각 시나리오와 정합한 v0.6 ContextSnapshot), `radio_policy.example_v0.7.json`(미검증 초깃값), 기존 `v0.5`와 `v0.6`의 JSON 스키마/fixture.
	12-2) 우선 검증: 로컬 MP3 등록과 정상 재생, 1980s→2010s 시대 필터, DJ/TTS 순차 출력, 시스템 미디어 제어, 사용자 중지/늦은 콜백, AI 실패 폴백, 주행 제한.
	12-3) 개발용 단위 테스트: Planner, ContextSnapshot, request projection/fingerprint, DJValidator, 상태 Reducer/EffectRunner. Media3/TTS/HTTP는 Fake로 먼저 재현.
	12-4) 계약 테스트: Pydantic 요청·응답 vs JSON Schema 일치, 예외 fixture, 민감 필드 미포함, generation/fingerprint 상관관계.
	12-5) 장치 통합 테스트: Pleos Emulator에서 MediaBrowser browse, 미디어 버튼, 파일 선택, Audio Focus, TTS 초기화·stop 이벤트, 주행 제한 상태 등 실기 검증. 임의의 API 성공을 가정해 자동 테스트 통과 처리하지 않음.
	12-6) 수동 사용자 평가: 기획 담당이 두 세대 5쌍(제안 규모)을 대상으로 같은 곡·순서의 DJ ON/OFF 비교. 자연스러움·세대 공감·청취 방해·반복 피로도 평가; 결과는 실측 전 가설.
	12-7) 완료 기준: P0 기능 인수 테스트 전부 통과, 미확인 Pleos SDK·권리 제한 명시, 공개 시연에 부적절한 음원 포함 없음. 모든 테스트는 `NOT_RUN/PASS/FAIL/BLOCKED`와 증빙을 별도로 기록.

13) 개발 순서와 P0 차단 조건
	13-1) G0 계약 고정(PC 없이 가능): 이 문서, API v0.5, Context v0.6, 테스트 매트릭스 v0.7에 합의.
	13-2) G1 환경·권리 사전검증: Pleos SDK·에뮬레이터 접근, 재생 테스트 MP3 권한, 파일 선택기 지원, TTS 가용성 확인. 실패하면 대체 경로/범위 축소 판정.
	13-3) G2 순수 Kotlin 코어: Track 카탈로그 모델, 시대 필터/블록 편성/결정적 시드, ContextSnapshot, RadioOrchestrator Reducer, Fake EffectRunner. UI·LLM 호출 없이 단위 테스트.
	13-4) G3 로컬 오디오: SAF URI 등록, Media3 단일 곡 재생, MediaLibraryService/Session 탐색·버튼, 포커스/권한 예외.
	13-5) G4 DJ 출력: 고정 대본 + Fake/실제 TTS, 완료/중단/실패 이벤트, 유저 NEXT/PAUSE/STOP·오디오 충돌 테스트. 실제 TTS 지원 불가 시 기본 시스템 TTS 또는 DJ 음성 생략으로 축소하고 명시.
	13-6) G5 AI/상황: FastAPI·Mock LLM→실제 LLM, v0.5 스키마·폴백·보안, 날씨 optional·TTL·권한/시뮬레이션 표시.
	13-7) G6 통합 데모: 레트로 UI, A 3곡→DJ→B 3곡, 실제 앱에서 예외 테스트, 발표 시 사용 데이터 실제/모의 구분.
	13-8) G7 배포/심사 준비: 차량 UX 제한·MBS 호환성, 민감정보·권리·API 키·로그·서명키·디버그 플래그 검수, 최종 시연 빌드 고정.

14) 요구사항 추적성(상세 테스트는 CSV 참조)

| 요구사항 | 책임 | 구현/테스트 연결 |
|---|---|---|
| REQ-MUS-01 로컬 파일 등록·연도 분류 | Catalog | AC-001~006 |
| REQ-PLN-01 시대 하드 필터·세대 교차 | Planner | AC-007~015 |
| REQ-DJ-01 AI DJ 대본 생성·검증 | DJScriptProvider | AC-016~023 |
| REQ-AUD-01 TTS·Media3 단일 출력 | Orchestrator/Voice | AC-024~035 |
| REQ-CTX-01 날씨·시간·민감정보 제어 | ContextEngine | AC-036~043 |
| REQ-VHC-01 차량 미디어·주행 안전 | Media/Vehicle | AC-044~049 |
| REQ-OPS-01 장애·보안·운영 | Backend/Build | AC-050~054 |

15) 제출 기획과 개발 범위의 정합성
	15-1) 공식 접수양식 1~6번 초안과 기존 UI 3화면 시안은 참조자료. 사용자·팀 개인정보 입력과 서명은 기획 담당자가 별도 처리.
	15-2) 공모전 문서의 '주행정보 기반 AI DJ'는 기술 확장 목표이지 MVP에서 실제 차량 데이터 확보가 검증된 기능이 아니다. 이번 구현에선 유효한 날씨와 사용자 선택값을 사용.
	15-3) 로컬 음원을 사용하는 MVP를 스트리밍 제휴가 완료된 서비스처럼 소개하지 않는다. 음원 카탈로그의 다양성/실제 7080·90년대 곡 수는 사용자 준비 상황과 권리 범위에 종속.
	15-4) 카세트 재생목록 저장·사연·전용 음성 학습은 '향후 발전'에만 명시. 미구현 기능을 화면이나 발표에서 동작 중이라고 표현하지 않는다.
	15-5) MAU·리텐션·AI 비용·세대 공감 향상은 실증 수치가 아닌 측정 계획이며, 임의 숫자를 실적이나 시장 규모로 제시하지 않는다.

16) 미해결 검증 사항 및 결정 주체
	16-1) Pleos Emulator 파일 선택기·로컬 URI 영속권한: 개발 담당이 G1/G3에서 검증. 실패 시 사전에 선택한 로컬 테스트 파일(정당한 배포 권한 범위) 또는 허용된 별도 공급원 경로 검토.
	16-2) Pleos TTS 권한·지원 모드·실제 SDK v3.3.1 콜백과 자체 미디어 포커스 간섭: 개발 담당이 G1/G4에서 검증. 실패 시 `VoiceOutput` 교체 또는 DJ OFF.
	16-3) Music/Audio 카테고리의 Music(Local) vs Radio 등록 분류와 시스템 UI 노출: 개발 담당이 공식 심사/미디어 가이드와 대조.
	16-4) 날씨 API 제공사·위치 동의·상업적 이용 약관: 개발 담당이 공급사 선정 시 검증. 검증 전 시연 모의값 표시.
	16-5) 공개 재생·영상·GitHub/APK 음원권리, DJ 효과음·이미지 권리: 기획 담당이 곡별 체크시트 작성. 준비한 로컬 개인 음원은 공개 제출물에 자동 포함하지 않음.
	16-6) 실제 UX·대본 품질과 세대 공감 효과: 기획 담당의 사용자 테스트로만 판단. 미실시 테스트를 통과로 표시하지 않음.

17) 관련 설계·근거(원본 파일 유지)
	17-1) `reply_radio_codex_handoff_v0.1.md`: 초기 ADR·모듈 구조.
	17-2) `reply_radio_ai_dj_spec_v0.2.md`: 시대 우선·세대 블록·DJ 페르소나.
	17-3) `reply_radio_playback_state_machine_v0.4.md`: 상태·epoch·TTS 중단 순서.
	17-4) `reply_radio_dj_script_generator_v0.5.md`, `reply_radio_dj_script_schema_v0.5.json`, `reply_radio_dj_script_fixture_v0.5.json`: DJ API·검증.
	17-5) `reply_radio_context_engine_v0.6.md`, `reply_radio_context_schema_v0.6.json`, `reply_radio_context_fixtures_v0.6.json`: 상황 수집·fingerprint.
	17-6) `reply-radio-review-v1.1.md`: 제출 전 기술·운영·사업성 수정 검토.
	17-7) 공식 참고(2026-11-15 검색 재확인):
		- Pleos Music/Audio: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide
		- Pleos TTS v3.3.1 소개/콜백: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro
		- Pleos TTS EventListener: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners
		- Pleos SDK 릴리스 노트: https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
		- Pleos Driving restrictions: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy

18) 변경 관리
	18-1) 본 문서 수정 시 v0.5 대본 API/v0.6 내부 ContextSnapshot 스키마에 영향을 주는지 명시적으로 검사하고 필요한 경우 별도 버전을 발행한다.
	18-2) 구현 담당은 기능 완료·시스템 통합 완료·시연 가능·출시 가능을 서로 다른 증빙 단계로 관리한다.
	18-3) 새 요구사항이나 정책은 `reply_radio_decision_log_v0.7.md`에 ADR로 누적한다. 불명확한 기능을 자동 활성화하지 않는다.
