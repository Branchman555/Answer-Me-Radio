# 응답하라 라디오 — ContextEngine 상세 설계 v0.6

작성일: 2026-11-15 / 상태: 구현 전 계약 초안 / 대상: 1인 개발 MVP · Pleos Connect

적용 우선순위: 본 문서는 `reply_radio_codex_handoff_v0.1.md`의 ContextProvider, `reply_radio_ai_dj_spec_v0.2.md`의 ContextSnapshot 및 `reply_radio_dj_script_generator_v0.5.md`의 ScriptContextBuilder 입력을 구체화한다. DJ API 엔드포인트와 페르소나는 v0.5, 재생 이벤트·세션/세대 무효화·상태 전이는 `reply_radio_playback_state_machine_v0.4.md`를 따른다. 본 문서의 로컬 `ContextSnapshot` JSON은 DJ API 요청 본문과 **다르다**.

## 1) 목적·범위

1) 목적
	- 선호 시대·장르, 로컬 시각, 날씨, 사용자가 직접 선택한 여행 목적, 선택적 차량 데이터를 단일 `ContextSnapshot`으로 정규화한다.
	- 수집 가능 여부·최신성·동의 상태를 함께 검사한 후 `RadioPlanner`와 `DJScriptGenerator`에 필요한 값만 배포한다.
	- AI/네트워크/차량 SDK 장애가 라디오 재생을 막지 않도록 한다.
2) MVP 포함
	- 장치 시각, 로컬 저장된 선호 시대/장르, 날씨 조회(권한 허용 또는 사용자가 명시적으로 지정한 지역), 유효기간, 로컬 스냅샷, 개인정보 최소화.
3) MVP 제외 및 조건부
	- 주행 속도 추론, 교통 정체 감지, 운전자 피로·감정·탑승자 추정, 정밀 위치 기반 추천, 차량 주행이력 서버 업로드, 날씨 기반 강제 곡 전환.
	- Vehicle SDK는 승인 권한·Capability가 검증되기 전까지 비활성화. 향후 트립 시간 등 데이터로 확장하더라도 지원/동의 확인 및 버전 변경이 선행되어야 함.

## 2) 설계 결정(ADR)

1) ADR-022 — 시대 필터가 절대 우선
	- `RadioPlanner`의 시대 필터를 ContextEngine이 완화할 수 없다. 날씨·시간·장르 가중치는 후보 시대 내부에서만 사용한다.
2) ADR-023 — 스냅샷 불변성과 블록 경계 반영
	- `ContextSnapshot`은 생성 후 변경하지 않는다. 음악 블록 시작 시 유효한 스냅샷 하나를 고정한다.
	- 새로운 날씨/시간 값은 대기 스냅샷에 저장하고 다음 **블록 편성 또는 DJ 대본 요청 생성 시점**에 반영한다. 재생 중인 곡은 중단하지 않는다.
	- 블록 편성에 영향을 주는 선호 시대/장르 변경은 명시적 UI 요청에 따라 별도 새 편성을 시작하고 재생 상태 머신 v0.4의 generation 정책을 따른다.
3) ADR-024 — 날씨는 선택적이며 유효한 경우에만 대사로 사용
	- 기본 재조회 간격 15분, 관측/모델 유효시각(`observedAt`)에서 60분 경과하면 날씨를 null로 처리한다.
	- `fetchedAt`는 네트워크 수신 시각일 뿐이므로 오래된 `observedAt`을 갱신할 수 없다.
	- `observedAt`이 현재보다 5분 초과 미래거나 파싱 불가능하면 무효 처리한다(시계 이상 감지).
	- API 날씨는 모델/공급자에 따른 추정값일 수 있으므로 정확한 현장 실측으로 표현하지 않는다.
4) ADR-025 — 위치 데이터 경계
	- 위치 좌표는 일시적인 WeatherLocationAdapter 내부에서만 처리하고 `ContextSnapshot`, DJ API, 일반 로그, 장기 저장소에 넣지 않는다.
	- 위치 권한은 명시적 동의를 받은 이후에만 사용; 거부·철회·위치 미수신 시 날씨 정보 비활성화 또는 사용자가 별도로 선택한 지역으로 조회한다.
	- 선택 지역 저장은 선택사항이며 시연 MVP는 날씨 없이도 동작 가능해야 한다.
5) ADR-026 — 주행 안전 신호와 컨텐츠 맥락을 분리
	- `CarUxRestrictions`는 UI 제한 제어(`UxSafetyGate`)의 입력이며 DJ 대본에 전달하지 않는다.
	- `VehicleContextAdapter`의 데이터가 없더라도 방송 작동에 영향 없음. 저속=정체, 저녁=퇴근, 두 시대=부녀 관계 등 추론 금지.
6) ADR-027 — 데이터 노출 최소화
	- `RadioPlanner`에는 시대/장르/유효 날씨 등 추천에 필요한 값만 전달.
	- DJ API v0.5에는 `timeOfDay`, `mode`, `weather`, `previousEra`, `nextEra`만 전달. `preferences`, `vehicle`, `locationStatus`, 내부 오류 사유, 좌표, 음원 URI는 전달하지 않는다.
7) ADR-028 — 재현성 및 지문
	- `contextFingerprint`는 의미있는 편성·대본 입력을 정규화해 SHA-256 계산(정의는 §7). `snapshotId/capturedAt/fetchedAt` 등의 수집 시각 변동만으로 fingerprint를 바꾸지 않는다.
	- LLM 응답 유효성은 v0.4대로 `sessionId + generation + requestId + segmentId + contextFingerprint`와 일치해야 한다.

## 3) 구성 요소

```text
[Settings / Local clock] ---+\
[FusedLocation] -> [WeatherGateway] +--> ContextEngine --> Immutable ContextSnapshot
[Vehicle SDK (optional)] ---+ /                         |            |
                                                       |            +--> ScriptContextBuilder --> DJ API v0.5
                                                       +--> RadioPlanner (era-filter first)
[CarUxRestrictions] ------------------> UxSafetyGate (independent of LLM)

RadioOrchestrator: snapshot adoption at the block/segment boundary, generation/epoch ownership
```

| 컴포넌트 | 책임 | 실패 처리 |
|---|---|---|
| `PreferenceRepository` | 선호 시대(1~2개), 장르, DJ on/off, 명시적 이동 목적 | 최소 기본값 제공; 시대 미선택이면 초기 설정 필요 |
| `ClockProvider` | 장치 시각/타임존 | 시각 불확실하면 `UNKNOWN` |
| `WeatherLocationAdapter` | 권한/설정 확인, 마지막 위치 또는 명시적 지역 제공 | 위치/지역 없으면 날씨 비활성화 |
| `WeatherGateway` | 외부 API 조회, 조건값 정규화·TTL·실패 격리 | 유효 캐시 또는 null |
| `VehicleContextAdapter` | 승인된 차량 정보만 접근, capability 확인 | 기본 `DISABLED`, 사용자 기능에 영향 없음 |
| `ContextEngine` | 최종 스냅샷 생성, 데이터 신뢰성 판정, fingerprint | 외부 호출이 느려도 기본 스냅샷 생성 |
| `ScriptContextBuilder` | 로컬 스냅샷을 DJ API v0.5의 `context`로 투영 | 무효 날씨는 null |
| `UxSafetyGate` | `CarUxRestrictions`에 따른 화면·설정 제어 | 데이터 맥락과 독립. 규제가 확인되지 않으면 제한적 UI 우선 |

## 4) 데이터·최신성 정책

| 정보 | 취득/갱신 | 유효 판정 | 다운그레이드 |
|---|---|---|---|
| 선호 시대 | 설정 변경 즉시 | 1900~2099 내 10년대 경계 값 | 선택값 없으면 채널 선택 요구 |
| 선호 장르 | 설정 변경 즉시 | 허용된 로컬 장르 라벨 | 비어 있으면 중립 점수 |
| 시간대 | 스냅샷 생성 시 | 장치 타임존·시각 확인 | `UNKNOWN` |
| 날씨 | 시작 시/15분 간격, 중복 요청 병합 | `now - observedAt`이 0~60분 범위; 5분 미래 허용 | null, 날씨 소재·가중치 제거 |
| 마지막 위치 | 날씨가 필요할 때만 조회 | 기준 시각이 있으면 15분 이내; 위치 없음·미확인 시 무효 | 지역 선택 또는 날씨 null |
| 차량 주행 관련 | 기본 OFF | 승인된 퍼미션·해당 차량 Capability·API 성공 시에만 | `DISABLED/UNAVAILABLE` |
| UX 제약 | 플랫폼 변경 즉시 | 플랫폼에서 제공한 활성 제한 플래그 | `UxSafetyGate`가 보수적으로 차단 |

시간대 구간(앱 설계 가정, 장치 현지시각): `MORNING=05:00~10:59`, `DAY=11:00~16:59`, `EVENING=17:00~20:59`, `NIGHT=21:00~04:59`. 장치 시간대/시각 불확실 시 `UNKNOWN`. 계절·일출 기준이 아님.

날씨 조건 정규화(MVP): `RAIN|SUN|CLOUD|SNOW|UNKNOWN`. 강수/폭설을 경험적으로 우선 분류하는 매핑은 WeatherGateway provider별 버전으로 관리한다. 공급자 코드 매핑표가 없으면 `UNKNOWN`을 null로 처리한다. 악천후를 안전 경보로 오인하게 해서는 안 된다.

외부 날씨 제공업체는 **미선정**. 예시로 Open-Meteo는 weather_code, precipitation 등 모델 기반 현재 값을 제공하지만 무료 플랜은 비상업용이고 상용화 시 별도 라이선스·표기 검토가 필요하다.

## 5) 데이터 계약

별도 파일 `reply_radio_context_schema_v0.6.json`에 Draft 2020-12 정의.

`ContextSnapshot` 예시:

```json
{
  "schemaVersion": "1.0",
  "snapshotId": "ctx-0001",
  "capturedAt": "2026-11-15T02:30:00+09:00",
  "simulationMode": false,
  "mode": "FAMILY",
  "eras": ["1980s", "2010s"],
  "timeOfDay": "NIGHT",
  "preferences": {"genres": ["BALLAD"], "djEnabled": true, "tripIntent": "TRAVEL"},
  "weather": {"condition": "RAIN", "observedAt": "2026-11-15T02:10:00+09:00", "fetchedAt": "2026-11-15T02:21:00+09:00", "source": "API", "valid": true},
  "locationStatus": "AVAILABLE",
  "vehicle": {"availability": "DISABLED", "tripDurationSeconds": null, "dataCapturedAt": null},
  "omissions": [],
  "contextFingerprint": "sha256:<64 hex digits>"
}
```

위 예시는 `contextFingerprint`만 설명용 자리표시자임. 테스트 Fixture에는 실제 계산된 64자리 해시를 기록한다.

**주의**: `simulationMode=true`의 시뮬레이션 날씨는 개발/발표 데모에서만 사용하고 UI에 `시뮬레이션`을 명시한다. 프로덕션에서 `source=SIMULATION`은 무조건 제거한다. 시뮬레이션에서 유효 날씨를 멘트로 읽어도 실제 현재 날씨를 확인했다고 표현하지 않는다.

## 6) DJ API v0.5 변환(호환 변경 없음)

1) `ScriptContextBuilder`는 생성 시점에 날씨 `valid`를 **재계산**한다. 스냅샷의 `valid=true`를 그대로 신뢰하지 않는다.
2) 출력(`request.context`):

```json
{
  "timeOfDay": "NIGHT",
  "weather": {"condition": "RAIN", "observedAt": "2026-11-15T02:10:00+09:00", "valid": true},
  "mode": "FAMILY",
  "previousEra": "1980s",
  "nextEra": "2010s"
}
```

3) `previousEra`·`nextEra`는 스냅샷의 배열만으로 추정하지 않고 **RadioPlanner가 확정한** 이전/다음 블록에서 가져온다.
4) `weather=null`이면 선곡 상황 가중치와 `WEATHER` DJ 소재를 동시에 비활성화한다.
5) 사용자 지정 `tripIntent`는 추론 방지를 위해 로컬 UI/편성 힌트에만 사용하며 DJ API v0.5에는 전달하지 않는다. 실제 AI 멘트에 여행 목적을 반영하려면 v0.6 이상 **새 DJ API 계약**을 별도로 제안·승인해야 한다.
6) `vehicle` 정보는 v0.5 DJ API로 절대 보내지 않는다. 주행 맥락 AI 기능은 후속 단계다.

## 7) fingerprint와 비동기 갱신 정책

1) 정규화 입력(순서 고정, JSON canonical serialization)
	- `{mode, eras, timeOfDay, preferences:{genres(sorted),djEnabled,tripIntent}, weather:{condition} 또는 null}`
	- `vehicle`은 MVP 비활성이라 제외(향후 실제 활용 시 명시적 계약 버전 상향).
	- `capturedAt`, `snapshotId`, 날씨 `observedAt`/`fetchedAt`, `locationStatus`, `omissions`, 원본 GPS, 실명 제외.
2) 문자열 JSON을 UTF-8로 인코딩해 `SHA-256` → `sha256:<hex64>` 생성. 런타임별 JSON serialization 차이를 제거하기 위해 JSON 객체 키 사전순/공백 없는 직렬화를 고정한다.
3) 정규화 결과가 동일하면 fingerprint 재사용 가능. 새 유효 날씨로 `RAIN→SUN`이 바뀌면 변경됨; `RAIN→STALE/null`도 변경됨.
4) 날씨 조회 완료 → 후보 스냅샷 생성 → 다음 블록 경계에서 채택. 아직 사용 중인 DJ 대본은 현재 편성 정책이 변경되는 경우에만 generation을 올려 무효화한다.
5) 미리 요청한 DJ 대본의 맥락과 채택 예정 컨텍스트가 달라지면 기존 `pendingRequest`를 폐기하고 재요청 또는 로컬 폴백. `RadioOrchestrator`가 generation 변경의 유일한 주체.
6) 날씨 갱신만으로 현재 음악을 멈추지 않고, 같은 날씨 반복 조회로 AI 요청을 중복 발행하지 않는다.

## 8) 오류·권한·안전 대응

| 상황 | 코드(로컬) | 정책 |
|---|---|---|
| 위치 사용 거부 | `LOCATION_DENIED` | 날씨 null; 반복 권한 요청 금지; 라디오는 정상 |
| SDK 미지원/위치 null | `LOCATION_UNAVAILABLE` | 지역 직접 지정 가능 또는 날씨 null |
| 60분 초과 날씨 | `WEATHER_STALE` | API와 DJ의 날씨 필드 null |
| 외부 날씨 타임아웃 | `WEATHER_TIMEOUT` | 60분 이내 정상 캐시 있으면 재사용, 없으면 null |
| 공급자 응답 오류/코드 미매핑 | `WEATHER_INVALID` | null, 재조회는 백오프 |
| 시각/시간대 불명확 | `TIME_UNKNOWN` | `UNKNOWN`으로 DJ 요청, 퇴근 추측 금지 |
| 차량 API 거절/미지원 | `VEHICLE_UNAVAILABLE` | 기본 방송 유지, 반복 호출 제한 |
| 주행 UX 제한 | (`UxSafetyGate`) | 상세 설정·파일 등록은 플랫폼 허용 시에만 |
| AI 응답 지연 | (`RadioOrchestrator`) | v0.4/0.5 일치 검증; 로컬 멘트 |

외부 조회 실패 시 기본 5분 이내 재시도 금지, 정상 주기는 15분(초기 운영 가정). 동일 시점 다중 호출은 single-flight로 병합하고, 방송 시작 자체를 네트워크 응답에 블로킹하지 않는다.

**위치 권한 철회**가 감지되면 GPS 관련 메모리와 위치 기반 날씨 캐시를 삭제하고 `weather=null`로 새 스냅샷을 생성한다. 사용자가 선택한 별도의 지역이 있다면 그 지역에 대한 날씨는 별도 동의 정책으로 취급한다.

`CarUxRestrictions`는 차속만으로 유추하지 않고 플랫폼이 실제 전달한 규제 상태를 적용한다. 상세 설정 허용 여부를 `속도 == 0`으로 판단하지 않는다.

## 9) 개인정보·보안·운영

1) 원본 위도/경도·정밀 위치·차량 식별자·사용자 실명·파일 URI를 AI API와 로그에 포함하지 않는다.
2) 외부 날씨 API가 필요한 경우 위치를 직접 전송한다면 사용자에게 제3자 전송 사실·목적·권한을 명확히 안내한다. 필요 최소 수준의 위치 정밀도만 사용한다.
3) `locationStatus`와 내부 오류 코드만 진단에 사용하며 원본 위치 값은 짧은 범위에서만 처리한다. 이용자 식별 가능한 이벤트 로그는 수집하지 않는다.
4) API 키가 필요한 외부 공급원을 사용할 경우 비밀키는 백엔드에 저장하고 앱에 포함하지 않는다. 공급자별 인증·호출 상한·약관 확인을 완료한 이후 활성화한다.
5) `simulationMode`는 디버그/발표 빌드에서만 활성화하고 Release 채널에 시뮬레이션 날씨를 주입하지 않는다.
6) 로그 메트릭: `weather_lookup_count`, `weather_unavailable_rate`, `stale_weather_drop_count`, `context_build_latency`, `context_change_applied_at_block`, `dj_weather_mention_with_valid_source`.

## 10) 수용 테스트(핵심 20개)

| ID | 입력/사건 | 기대 결과 |
|---|---|---|
| CTX-01 | 취향 시대 1980s, 유효 비 | 1980s 안에서만 순위 변경 |
| CTX-02 | FAMILY: 1980s/2010s | 두 시대 스냅샷, LLM 시대는 Planner가 지정 |
| CTX-03 | 날씨 20분 경과 | `valid=true`, weather 전달 |
| CTX-04 | 날씨 61분 경과 | weather=null, `WEATHER_STALE` |
| CTX-05 | 위치 권한 거부 | 음악·DJ 계속, 날씨 null |
| CTX-06 | 위치 SDK null | 앱 크래시 없이 weather=null |
| CTX-07 | 위치 SDK 지원 안 됨 | 위치 기능 비활성화 |
| CTX-08 | 날씨 API 오류/시간 초과 | 유효 캐시만 사용, 아니면 null |
| CTX-09 | 클록 타임존 변경 | 다음 블록에서 새 시간대 반영 |
| CTX-10 | MID-TRACK 날씨 변경 | 재생 유지; 다음 블록부터 반영 |
| CTX-11 | MUSIC_BLOCK DJ 선행 요청 후 날씨 변경 | 맥락 불일치 대본 폐기/재요청 또는 폴백 |
| CTX-12 | 같은 날씨 재조회, 수신 시각만 변경 | fingerprint 동일 |
| CTX-13 | RAIN→SUN 변화 | fingerprint 변경 |
| CTX-14 | RAIN→STALE | fingerprint 변경, AI에 weather=null |
| CTX-15 | 사용자가 장거리 모드 선택 | 임의 주행 감지 없이 로컬 UI/편성만 반영 |
| CTX-16 | Vehicle SDK 미승인 | 차량 정보 접근 안 함, 방송 계속 |
| CTX-17 | CarUxRestrictions 활성 | 상세 설정 차단, 오디오 재생 규칙 별도 |
| CTX-18 | AI 요청 페이로드 검사 | 좌표/URI/실명/vehicle/preferences 미포함 |
| CTX-19 | 로컬 데모 시뮬레이션 날씨 | 명시적 `시뮬레이션` 표시, 릴리스에서 차단 |
| CTX-20 | 위치 권한 철회 | 위치 캐시 제거, 컨텍스트 날씨 무효화 |

## 11) Codex 작업 티켓

1) `CTX-DEV-01`: `ContextSnapshot`, Weather/Preference/Vehicle DTO, JSON schema/serializer 및 테스트.
2) `CTX-DEV-02`: 시간대 분류기와 테스트(23:59, 00:00, 타임존 전환).
3) `CTX-DEV-03`: WeatherGateway 인터페이스, TTL, 오류·캐시·위치 철회 처리. Mock 먼저.
4) `CTX-DEV-04`: `ContextEngine` 순수 함수(입력 수집 완료 값 → Snapshot), fingerprint canonical JSON.
5) `CTX-DEV-05`: `ScriptContextBuilder` v0.5 호환 투영 및 속성 기반 테스트.
6) `CTX-DEV-06`: `RadioOrchestrator` 이벤트 루프에 경계 채택, stale 요청 폐기 연결.
7) `CTX-DEV-07`: Pleos FusedLocation 권한/지원상태·생명주기 검증 후 Adapter 구현; 차량 adapter는 기본 OFF.
8) `CTX-DEV-08`: 부정 테스트 20개와 개인정보 페이로드 스냅샷 테스트.

## 12) Codex에 전달할 첫 구현 지시문

> 기존 `reply_radio_playback_state_machine_v0.4.md`, `reply_radio_dj_script_generator_v0.5.md`, `reply_radio_dj_script_schema_v0.5.json`과 본 `reply_radio_context_engine_v0.6.md`를 우선 읽고 충돌을 확인하라. 먼저 외부 SDK 의존성 없는 Kotlin `ContextSnapshot`, `TimeOfDayClassifier`, `WeatherValidityPolicy`, `FingerprintFactory`, `DjContextMapper`와 단위 테스트를 구현하라. `DJScriptRequest.context` 형식은 v0.5를 변경하지 말고 불필요한 데이터가 LLM에 노출되지 않도록 검사하라. Vehicle 연동은 미지원 폴백으로 시작하라. 실제 Pleos SDK 시그니처는 문서·에뮬레이터에서 확인한 뒤 Adapter 경계에 추가하라. 테스트 명령과 결과를 보고하라.

## 13) 공식 기술 근거(2026-11-15 문서 확인)

- Pleos FusedLocation 소개: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro
- Pleos FusedLocation 권한: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions
- Pleos Vehicle SDK 소개: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- Pleos Vehicle 권한: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- Pleos API/권한 승인: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/
- Pleos Driving Restrictions: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
- Open-Meteo API/라이선스(구현 후보일 뿐 공급자 미선정): https://open-meteo.com/en/docs / https://open-meteo.com/en/pricing

기술 검증 상태: 자료 기반 설계 완료 / 실제 SDK 사용권한·차량에서의 데이터 전달·외부 날씨 API·유효한 TTS 음성 출력은 아직 미검증.
