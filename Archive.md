# 현재 개발 기록

## 현재 상태 — RR-004 구현·검증 완료 (2026-11-15)

Media3/MediaLibraryService와 실제 MP3 재생·표준 제어를 구현했다. **다음 티켓은 RR-005: 실제 음성·차량 UX**다. 사용자 지정 [원격 저장소](https://github.com/Branchman555/Answer-Me-Radio)에 현재 개발 내용과 이후 검증된 작업을 반영한다. 원격의 기존 MIT LICENSE와 초기 커밋을 보존한다.

- `playback/PlaybackEngine.kt`: 단일 이벤트 큐와 RadioReducer. 기존 모델에 token별 위치·PREVIOUS/seek/등록 곡 선택을 추가했다.
- `Media3Output.kt`: ExoPlayer 한 곡 재생, 실제 종료·오류, release 완료 후 정지 확인, 위치 복원. DJ·역사 음성은 OFF이며 가짜 음성 완료를 만들지 않는다.
- `SessionPlayer.kt`, `RadioService.kt`: Media3 및 구형 Android MediaBrowser 탐색·검색·표준 제어, URI 비공개, 신뢰 컨트롤러 확인, foreground/오디오 포커스/서비스 종료.
- `PlaybackActivity.kt`: 실제 재생 화면. 기존 무음 시뮬레이터와 명확히 구분한다. versionCode 4 / `0.4.0-playback`, Media3 1.6.1 고정, 의존성 lock/checksum 갱신.

`./tools/check.ps1 -Offline` 성공: **JVM 144개 = 기존 127개 + 신규 17개**, 실패·스킵 0, debug APK/테스트 APK 생성, lint 오류 0·경고 0. [기존 테스트 해시](evidence/rr003-test-hashes.json)와 테스트 이름별 실행을 검사한다. 원본 설계 27개 hash와 54+40개 인수 상태를 보존했다.

최종 APK로 **Pleos API34 26개, 일반 Android API37 27개** 네이티브 검사를 통과했다. 각각 RR-003 회귀 12/13개와 RR-004 재생 14개다. 직접 생성한 MP3로 디코더 출력 버퍼·AudioTrack 진행·자연 종료, 표준/legacy 컨트롤, 실제 포커스 중단/복귀, PAUSE 우선, 삭제·권한 철회 후 읽기 오류, 손상 파일 3회 중단, 서비스 해제를 확인했다. 호출자 검증은 legacy 초기 placeholder가 아닌 실제 onConnect에서 수행하며, 별도 UID의 무단 브라우저 거부도 검사했다.

증거: [RR-004 집계](evidence/rr004.json), [Pleos](evidence/rr004-device-ReplyRadio_RR003_API34.json), [일반 Android](evidence/rr004-device-ReplyRadio_RR003_API37.json), [구현·재현·공식 근거](docs/rr004-playback.md). 두 장치 결과의 APK hash가 현재 빌드와 일치한다.

호스트 무음 에뮬레이터의 실제 디코더/AudioTrack 검증이며 물리 스피커 청취는 아니다. 물리 USB 분리와 재생 중 USB 드라이버 읽기 오류, 실제 차량 버튼·주행 UX·TTS는 미검증이다. Pleos 파일 선택기는 여전히 stub이다. 소개용 대표 이미지는 AI 생성 콘셉트이며 README의 실제 앱 캡처와 구분한다. 개인 음원·빌드 캐시·비밀정보는 업로드하지 않는다.

## 이력: RR-003 구현·검증 완료 (2026-11-15)

SAF 로컬 MP3 카탈로그를 구현했다. **다음 티켓은 RR-004: Media3/MediaLibraryService와 표준 제어**다. 차량 대상 파일 선택 완료를 주장하지 않으며 아래 Pleos 플랫폼 제한은 남아 있다.

- `catalog/LocalMusicCatalog.kt`: 명시적 선택, 태그 정규화·누락/잘못된 연도 격리, URI 중복·안정 ID, 권한 철회/재선택/읽기 오류, 저장 실패 복구.
- `catalog/AndroidCatalog.kt`: 실제 SAF 읽기 권한, MediaMetadataRetriever, AtomicFile/noBackup 저장, 직렬 IO 소유자. 원본 음원 복사·광범위 저장소 권한 없음.
- `catalog/CatalogActivity.kt`, `demo/DemoActivity.kt`: 내부 개발용 등록·확인·목록 제거, 실제 태그의 무음 편성. 모드 변경 시 정지하고 CatalogChanged로 후보를 전달한다.
- debug `0.3.0-catalog` / versionCode 3. 의존성 추가 없음. 실제 음악·음성 출력은 미연결이다.

검증 명령 `./tools/check.ps1 -Offline` 통과. **JVM 127개 = 기존 103개 + 신규 24개**, 실패·스킵 0. 기존 테스트 파일은 수정하지 않았으며 [RR-002 테스트 해시](evidence/rr002-test-hashes.json)와 이전 결과의 테스트 이름으로 보존·실행을 검사한다. debug APK/테스트 APK 생성, lint 오류 0·경고 0. 설계 원본 27개 해시와 54+40개 인수 상태를 보존했다.

전용 Pleos API34 AVD에서 네이티브 검사 12개, 일반 Android API37 AVD에서 13개를 통과했다. 테스트 APK가 생성한 합성 태그·무음 MPEG 파일을 사용했다. 실제 네이티브 태그 추출, 프로세스 강제 종료 후 영속 권한·저장 복원, 철회·재선택·삭제·원본 보존을 확인했고, 일반 Android에서는 실제 DocumentsUI 파일 선택 왕복도 확인했다. 개인 음악이나 물리 장치는 사용하지 않았다. 재실행 때 DocumentsUI가 기억하는 폴더·격자 모드를 테스트에서 처리하고 PNG 수집의 run-as 인자 순서를 수정했다.

**Pleos의 ACTION_OPEN_DOCUMENT는 DocumentsUIStubWithResult로 연결된다.** 해당 이미지의 실제 선택 왕복은 BLOCKED_PLATFORM_STUB다. 실제 선택기가 있는 대상 이미지/장치가 필요하다. 물리 USB 분리·장치 재부팅·음악 출력·주행 UX는 미검증이며, AC-005의 재생 통합은 RR-004에 남긴다. YEAR는 준비자가 입력한 태그이며 외부 발매연도 검증이 아니다.

[구현·재현·공식 계약](docs/rr003-local-catalog.md), [RR-003 결과](evidence/rr003.json), [최신 증거](evidence/latest.json)에 테스트와 APK 해시, 장치별 제한을 기록했다. 과거 결과는 아래에 보존한다.

## 이력: RR-002 완료 (2026-11-15)

사용자 요청에 따라 RR-002 상황정보·일반 DJ 계약 구현과 검증을 완료했다. **다음 티켓은 RR-003: SAF 로컬 MP3 카탈로그**다.

- `context/ContextEngine.kt`: v0.6 DTO, 현지 시간대, 날씨 observedAt 60분/미래 5분, 모의 입력·권한 조건, canonical SHA-256. 입력은 좌표/URI/차량 식별자를 갖지 않는다.
- `context/WeatherCache.kt`: 순수 gateway용 single-flight, 갱신·실패 간격, 철회/늦은 콜백 무효화. 실제 공급자는 미연결이다.
- `dj/DjContract.kt`: v0.5 요청·응답, 허용된 context 5필드, metadata 인코딩, strict JSON, 상관관계/로컬 템플릿 검증, 시대 폴백.
- `RadioReducer`와 `demo/Simulation`: 마지막 곡 시작 시 Mock 선행 요청, monotonic 3초 제한, 변경·중단·역사 코너 우선 취소, 곡 경계 및 재발화 시 맥락/가용성 재검증. 원시 문자열 캐시는 발화 권한을 갖지 않는다.
- debug 버전: `0.2.0-context-dj` / versionCode 2. 의존성 추가 없음. 무음 시뮬레이터를 유지한다.

### 검증 결과

명령 `./tools/check.ps1 -Offline` 성공. **JVM 103개 = 기존 58개 + 신규 45개**, 실패/스킵 0건. debug APK 생성, lint 오류 0·경고 0. 원본 설계 manifest 27개 hash와 54+40개 인수 상태를 보존했다.

기존 테스트 파일 4개는 수정하지 않았다. [원본 해시](evidence/rr001-test-hashes.json)와 [RR-001 결과](evidence/rr001.json)를 기준으로 파일 무결성 및 기존 58개 이름의 실행을 검증 스크립트가 검사한다. [RR-002 증거](evidence/rr002.json)와 [최신 결과](evidence/latest.json)에 개별 테스트·APK SHA-256·빌드 로그를 기록했다. SDK 도구 XML 버전 경고는 기존 환경과 같으며 빌드는 통과했다.

새 테스트는 fixture 8개의 정확한 지문, 보존 스키마의 양성/음성 사례, 허용 필드·개인정보 경계, 마지막 곡 요청, 2999/3000ms 경계, 미도착 폴백, PAUSE/STOP/NEXT/콘텐츠 코너 취소, 권한 철회 및 캐시 제거를 검증한다. 테스트용 schema 해석기는 해당 계약의 키워드 범위이며 범용 스키마 라이브러리라고 주장하지 않는다.

### 남은 범위와 결정

대본은 알려진 로컬 템플릿과 일치하는 내용만 허용한다. JSON/groundingFields가 임의 모델 문장의 사실성을 증명한다고 보지 않는다. 실제 HTTP·LLM/서버 인증·자유 생성 대본 검증은 RR-006이다. 외부 날씨·Pleos·MP3·TTS·차량 UX·실차는 연결하지 않았다. 에뮬레이터 및 실제 오디오 검증은 NOT_RUN이다. [RR-002 상세](docs/rr002-context-dj.md)와 [roadmap](docs/roadmap.md)을 참조한다.

RR-003에서는 사용자가 선택한 로컬 MP3의 TITLE/ARTIST/YEAR와 SAF URI 권한 수명을 구현한다. 테스트 파일은 실제 선택과 사용 범위를 확인하고 저장소에 자동 포함하지 않는다.

## 이력: 2026-11-15 — 프로젝트 재시작 / RR-001

사용자가 `reply_radio_codex_design_bundle_v0.8.zip`과 길 위의 타임머신 확장 문서로 프로젝트를 완전히 다시 시작하고 실제 개발에 착수하도록 요청했다. 기존 촬영·회상 Revia와 새 제품의 범위가 다르고 기존 저장소에 미커밋 작업이 많으므로, 기존 폴더를 수정하지 않고 형제 폴더 `ReplyRadio`에 독립 Git 저장소를 초기화했다. 아직 커밋·원격 저장소·배포는 없다.

확장 문서는 실제로 `.md` 파일이다. 사용자 지정 `.txt`는 현재 폴더에서 발견되지 않았으며 동일 이름 `.md`와 ZIP 내부 `.md`의 SHA-256이 동일함을 확인했다.

- 원본 ZIP SHA-256: `db7637315187ed0ad5ab76e3ed89c46c425f64388194d8dd25fa3adc0e57e715`
- 확장 문서 SHA-256: `186f5fac1b3e52cc17539a7709dfe8dde9f19c8bbbc19a8113a25dc1bcb3fd2c`
- [설계 원본](docs/design/CODEX_README_v0.8.md)은 추출 그대로 보존한다. manifest 27개 파일 checksum 및 기존 54개+신규 40개 인수 케이스 보존을 `tools/verify.py`로 검사한다.

## RR-001 기반 구현 위치

| 파일 | 책임 |
|---|---|
| [RadioPlanner](app/src/main/kotlin/radio/reply/planner/RadioPlanner.kt) | Track/Era/설정, 시대별 후보, 단축·축소 방송, 결정적 순위 |
| [RadioState](app/src/main/kotlin/radio/reply/orchestrator/RadioState.kt), [RadioReducer](app/src/main/kotlin/radio/reply/orchestrator/RadioReducer.kt) | 이벤트·effect, 정상 완료, 스킵·중단·포커스·콜백 토큰, 단일 출력 |
| [StoryContent](app/src/main/kotlin/radio/reply/story/StoryContent.kt), [RoadStoryPlanner](app/src/main/kotlin/radio/reply/story/RoadStoryPlanner.kt) | 로컬 JSON 계약, 최신 버전/철회, 검수·구간·시각·음원 연도 gate |
| [Simulation](app/src/main/kotlin/radio/reply/demo/Simulation.kt), [DemoActivity](app/src/main/kotlin/radio/reply/demo/DemoActivity.kt) | 무음 Fake 이벤트 큐와 개발 조작 화면 |
| [JUnit 테스트](app/src/test/kotlin/radio/reply/) | Planner/Reducer/역사 코너 + 20 seed × 100 이벤트 순서 검사 |
| [검증 스크립트](tools/check.ps1), [증거 수집](tools/verify.py) | strict dependency verification, test/debug APK/lint 및 원본 보존 |

## RR-001 당시 실행 상태

실행 명령: `./tools/check.ps1 -Offline`.

JVM 58개 테스트 통과, debug APK 생성 확인. 당시 세부 테스트·APK hash·lint 결과·빌드 로그는 [RR-001 증거](evidence/rr001.json)에 보존했다. 이 검증은 로컬 캐시와 JDK17/SDK35에서 수행했으며, 최초 checksum 고정은 공급자 서명 검증이 아니다. SDK 도구 XML 버전 차이 경고가 출력되었으나 빌드 성공 여부와 별도로 기록한다. 빈 캐시 빌드는 미실행이다.

기존 인수 테스트 94개 상태는 원본 그대로 NOT_RUN이다. 관련 단위 테스트의 연결만 evidence에 기록하며 전체 P0 통과로 바꾸지 않았다. 에뮬레이터 설치·UI 실행·실제 MP3/TTS·실차·AI 호출은 **NOT_RUN**이다.

## RR-001 당시 남은 조건

현재 APK는 무음 시뮬레이터다. 실제 Media3/SAF/Pleos/위치/서버/차량 UX는 아직 연결하지 않았다. 역사 기록은 프로세스 내 50건만 지원하며 7일 영속 보존·다시듣기는 미구현이다. 방송/차트 근거 유형은 필요한 추가 근거 계약이 없어 비활성이다. [세부 경계](docs/rr001-foundation.md)를 따른다.

당시 후속 티켓이었던 **RR-002: ContextEngine + 일반 DJ 계약**은 위의 현재 상태와 같이 완료했다. 전체 순서는 [roadmap](docs/roadmap.md)에 있다.
