# RR-002 — 상황정보와 일반 DJ 로컬 계약

이 문서는 RR-002 완료 시점의 기록이다. 이후 로컬 MP3 등록과 장치 검증은 [RR-003](rr003-local-catalog.md)에서 추가했다.

## 결과와 실행 범위

상황정보를 v0.6 스냅샷으로 정규화하고, Planner가 결정한 이전/다음 블록을 v0.5 DJ 요청에 투영한다. 기본 음악 블록의 마지막 곡이 시작될 때 Mock 대본을 선행 요청한다. 유효 응답만 다음 곡 경계에서 사용하며, 미도착·3초 만료·검증 실패 시 시대 정보만 사용하는 로컬 대본으로 진행한다. 오프닝은 즉시 로컬 대본을 사용한다.

Android 앱은 계속 무음 시뮬레이터다. 이번에는 네트워크 권한·서버·외부 날씨 공급자·실제 LLM·TTS를 추가하지 않았다. 별도 런타임 의존성 없이 기존 Gson/JUnit과 순수 Kotlin을 사용한다.

## 코드와 책임

| 파일 | 책임 |
|---|---|
| [ContextEngine](../app/src/main/kotlin/radio/reply/context/ContextEngine.kt) | DTO, 시간대 분류, 날씨 최신성/권한/모의 조건, canonical JSON/SHA-256, 좌표 없는 불변 스냅샷 |
| [WeatherCache](../app/src/main/kotlin/radio/reply/context/WeatherCache.kt) | 향후 gateway용 single-flight, 정상 15분·실패 5분 간격, 철회 시 캐시·과거 콜백 무효화 |
| [DjContract](../app/src/main/kotlin/radio/reply/dj/DjContract.kt) | 요청/응답 DTO, 5필드 context 투영, 메타데이터 정규화, strict 응답 읽기, 로컬 템플릿 검증·폴백 |
| [RadioReducer](../app/src/main/kotlin/radio/reply/orchestrator/RadioReducer.kt) | 블록 경계 채택, 선행 요청/취소/기한, 상관관계와 발화 직전 재검증, 음악 흐름 유지 |
| [FakeDjScriptProvider](../app/src/main/kotlin/radio/reply/demo/FakeDjScriptProvider.kt), [Simulation](../app/src/main/kotlin/radio/reply/demo/Simulation.kt) | 로컬 Mock 요청/응답, 성공·실패·잘못된 JSON·미응답, 가상 monotonic deadline 큐 |

## 고정한 경계

- 원본 v0.5/v0.6 스키마는 변경하지 않는다. JSON의 명시적 null을 보존한다. DJ API context는 `timeOfDay`, `weather`, `mode`, `previousEra`, `nextEra`만 갖는다. 지문·설정·여행 목적·차량·위치·파일 URI·역사 콘텐츠는 전송 객체에서 제외한다.
- 날씨 유효성은 `observedAt` 기준 60분까지, 미래 5분까지 허용한다. 정확한 경계와 1초 초과를 테스트했다. `fetchedAt` 갱신은 오래된 관측을 살리지 못한다. 요청 투영·응답 수용·발화·일시정지 후 재발화에서 다시 확인한다.
- `ContextInput`은 이미 수집된 값이다. 실제 clock/권한/위치 공급은 후속 어댑터 책임이다. 불확실한 clock/timezone은 UNKNOWN, Vehicle은 DISABLED다. 위치 권한 철회 시 위치 기반 날씨 입력은 Reducer에서도 제거한다. 별도 동의한 선택 지역은 위치 권한과 구분한다.
- 모의 날씨는 개발 빌드 허용과 명시 선택이 모두 있어야 하며 로컬 Mock 대본에 모의 날씨임을 표시한다. release 활성화나 실제 공급자 전송은 이번 범위가 아니다.
- 지문은 키 사전순·공백 없는 UTF-8 JSON의 SHA-256이다. 장르만 정렬하고 시대 순서는 유지한다. snapshotId/capturedAt/observedAt/fetchedAt/vehicle/locationStatus는 제외한다. 같은 유효 날씨의 수신 시각 변경은 재요청하지 않는다.
- 현재 블록의 `activeContext`는 유지하고 새 값은 다음 블록 또는 DJ 요청에서 사용한다. 의미 있는 맥락이 바뀌면 기존 요청을 취소하고 새 generation으로 선행 요청할 수 있다. 같은 요청의 실패·시간 초과 자동 재시도는 0회다.
- 마지막 곡의 예상 정상 완료를 포함해 다음 블록을 미리 계획한다. 실제 경계에서 도착한 대본의 트랙 순서와 실제 다음 블록을 비교한다. NEXT·설정·카탈로그 변경·STOP·역사 코너가 무효화한 응답은 재생 권한을 얻지 못한다.
- 기한은 wall clock이 아닌 monotonic 3,000ms이며 정확히 기한에 도착한 응답도 폐기한다. 가상 scheduler가 deadline을 발생시키고, Reducer도 수신 시 직접 기한을 검사한다. 곡 경계가 더 빠르면 즉시 폴백하여 남은 기한을 기다리지 않는다.
- 기존 `ScriptRequested/ScriptReady`는 RR-001 상관관계 회귀용 캐시로 유지한다. 원시 문자열은 실제 발화 경로에 연결하지 않는다. 새 `DjResponseReceived` 경로만 strict JSON·계약·상관관계·내용 검증을 거친다.

## 대본 검증의 의도적인 제한

RR-002는 **로컬에서 생성한 알려진 템플릿만 출력**한다. 일반 모델 문장은 JSON 스키마와 groundingFields를 통과해도 승인하지 않는다. 요청별 로컬 시대/날씨 템플릿과 문장·주제·근거 필드가 일치해야 한다. 템플릿 외 사실/행동 유도는 거부한다. 실제 자유 생성 문장의 사실성·금지 표현·길이 검증은 RR-006의 서버 및 콘텐츠 검수 작업이다. 이 제한을 실제 LLM 의미 검증 완료로 표현하지 않는다.

제공된 v0.5 성공 fixture는 역직렬화·스키마 호환성을 검증하는 자료다. 그 문장을 템플릿 승인 목록에 특별 등록하지 않았다. 실제 TTS 길이는 미측정이다.

`WeatherCache`는 순수 gateway 구성요소의 계약 시험까지만 수행했다. 실제 공급자 호출·timeout 전달·권한 콜백 연결은 아직 없다. `FakeDjScriptProvider`와 가상 scheduler는 Reducer에 연결되어 통합 검증했다. 실제 HTTP의 인증·취소·소켓 타임아웃은 RR-006이다.

## 검증과 원본 보존

명령: `./tools/check.ps1 -Offline`.

- 기존 RR-001 테스트 파일 4개의 SHA-256을 [보존 기록](../evidence/rr001-test-hashes.json)으로 고정했다. 기존 58개 테스트 이름이 모두 실행됐는지 검증한다.
- [ContextEngineTest](../app/src/test/kotlin/radio/reply/ContextEngineTest.kt): AC-036~042의 상황정보·변환 부분, 경계 시각, fixture 8개 지문, 권한/캐시/모의 입력.
- [DjContractTest](../app/src/test/kotlin/radio/reply/DjContractTest.kt): 보존 스키마 양성/음성 fixture, 허용 필드·누락·추가 필드·타입·중복키·후행 JSON·grounding·데이터 인코딩.
- [ContextDjIntegrationTest](../app/src/test/kotlin/radio/reply/ContextDjIntegrationTest.kt): 곡 도중 갱신, 선행 요청 중복, 2999/3000ms 응답, PAUSE/STOP/NEXT, stale 날씨, 콘텐츠 코너 우선, 음원 접근 철회.
- [ContractSchema](../app/src/test/kotlin/radio/reply/ContractSchema.kt)는 원본 두 스키마에 사용된 키워드를 해석하는 테스트 도구다. 범용 JSON Schema 2020-12 구현으로 배포하지 않는다. 원본 양성·음성 fixture로 테스트 도구도 검증한다.

실제 수치·APK hash·명령 로그는 [RR-002 실행 증거](../evidence/rr002.json)에 있다. 원본 54+40개 인수 상태는 일괄 승격하지 않았다. 에뮬레이터/실제 오디오/실차 검증은 NOT_RUN이다.

다음 티켓은 **RR-003: SAF 로컬 MP3 카탈로그**. 사용자가 선택한 파일의 TITLE/ARTIST/YEAR와 URI 권한 수명을 실제로 검증한다.
