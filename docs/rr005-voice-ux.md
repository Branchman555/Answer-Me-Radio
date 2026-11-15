# RR-005 — 실제 한국어 음성과 차량 UX

RR-005는 로컬 라디오에 선택형 한국어 DJ 음성과 플랫폼 주행 제한을 연결한다. 기본값은 DJ OFF다. 상세 조작이 허용될 때 `DJ 켜기`를 누르면 다음 방송 시작의 오프닝과 정상 완료 2~3곡 경계의 시대 전환 멘트를 실제로 재생한다. DJ 설정은 현재 서비스 세션에 적용하며 프로세스 재시작 후 자동 재생하지 않는다. 서버·자유 생성 LLM·실제 위치·역사 음성은 이번 범위가 아니다.

## 구현과 실패 처리

- `PlatformVoice.kt`: 자동차 환경에서는 Pleos TextToSpeech **3.3.1 / ON_DEVICE / Locale.KOREA**, 일반 Android에서는 설치된 **한국어 오프라인 Android TTS voice**를 사용한다. 영어 음성이나 네트워크 voice로 조용히 대체하지 않는다. 한국어 데이터가 없거나 초기화가 실패하면 DJ를 생략하고 음악을 이어간다.
- `VoiceOutput.kt`: 발화마다 별도 드라이버·리스너·출력 token을 소유한다. 콜백은 메인 looper를 거쳐 기존 Reducer에만 전달한다. `onReady`는 완료가 아니다. `onDone`은 정상 완료, `onStop`은 정지 확인이다. 정지 대기 중 도착한 done은 stop으로 처리하고 error만으로 무음을 확정하지 않는다. 이전 리스너의 늦은 이벤트는 새 발화에 귀속되지 않는다.
- 초기화 대기는 6초, 전체 발화는 45초로 제한한다. 음성 실패 후 정지를 요청하고 **2초 내 정지 확인이 없으면 방송을 중단**한다. 실제 정지가 확인되기 전에는 다시 PLAY를 눌러도 다음 출력을 시작하지 않는다. 아직 `speak()`를 호출하지 않은 초기화 취소만 즉시 무출력으로 처리한다.
- `RadioOutput`은 음악/음성 중 하나에만 출력 권한을 준다. Media3 자연 종료에서도 AudioTrack 해제를 마친 뒤 종료 이벤트를 전달하므로 곡 뒤에 음성이 겹치지 않는다. PAUSE·NEXT·STOP·포커스 상실·DJ OFF는 같은 Reducer 경로를 사용한다.
- `RadioService`는 단일 오디오 포커스 소유자다. 실제 음성 중 일시적 포커스 상실 시 정지하고, 복귀 때 사용자의 재생 의도가 남아 있을 때만 다시 발화한다. 사용자 PAUSE 이후의 포커스 복귀는 재생을 일으키지 않는다. 실제 DJ는 검증된 로컬 폴백 대본이며, 서버 연결은 RR-006이다.
- MediaSession에는 음악과 별개인 `DJ_SPEAKING` 상태를 extras로 전달한다. 실제 재생 화면에는 음성 상태와 5개 표준 버튼을 보여 주며, SDK 내부 오류 코드나 대본 전문을 주행 화면에 표시하지 않는다.

## 차량 UX 경계

`VehicleUx`는 optional `android.car` 라이브러리의 `CarUxRestrictionsManager`를 구독한다. 초기값·연결 상실·조회 실패는 **UNKNOWN**이다. `UxSafetyGate`는 플랫폼이 제한 없음으로 확인한 경우만 상세 조작을 허용한다. 차속, 기어, debug intent로 권한을 추정하거나 우회하지 않는다. 일반 Android는 `NON_VEHICLE`로 구분하며 차량 주차 상태라고 표현하지 않는다.

파일 선택·카탈로그 내용·목록 제거·DJ 설정·무음 시뮬레이터는 상세 조작 영역이다. 제한이 생기면 내용과 버튼을 숨기고 진행 중인 선택 결과도 폐기한다. 제한 후 다시 허용되더라도 이전 선택 결과는 revision 검사로 차단한다. UI 버튼뿐 아니라 서비스의 DJ 설정 명령도 현재 gate를 확인하며 앱 UID 외에는 명령을 허용하지 않는다.

`PlaybackActivity`만 `distractionOptimized`를 선언하며, 제한 중에는 간결한 상태와 재생·일시정지·정지·이전·다음 버튼을 제공한다. 카탈로그·시뮬레이터에는 DO 선언이 없다. **이는 구현 선언이며 플랫폼 심사·안전 인증·실차 검증 완료를 의미하지 않는다.** 세부 국가별 UX 및 실제 차량 레이아웃은 제출 전 검증 조건이다.

## SDK·권한·의존성 확인

공식 Pleos Maven의 `ai.pleos.playground:TextToSpeech:3.3.1` AAR을 고정하고 실제 클래스의 `initialize`, `EventListener`, `ErrorType`, `setLocale(java.util.Locale)`, `setAudioAttributes` 시그니처를 확인했다. 이 버전의 sources JAR은 저장소에 없어 AAR의 공개 클래스와 공식 문서를 대조했다. 의존성 lock과 SHA-256 검증을 유지한다.

추가 권한은 `pleos.car.permission.TTS_SERVICE`다. SDK 전이 의존성의 서버 등록 모듈이 추가한 INTERNET/ACCESS_NETWORK_STATE 권한은 manifest merger에서 제거한다. 최종 병합 manifest의 허용 목록을 자동 검사한다. AndroidX가 만드는 앱 전용 signature receiver 권한 외에 위치·마이크·차량 속도·광범위 저장소 권한은 없다. `ON_DEVICE`에는 앱 등록·서버 키가 필요하지 않으며 앱에 키를 넣지 않는다.

검사 환경: Pleos API34의 CAAS **4.2.10-coral-aosp**, 일반 Android API37의 설치된 시스템 TTS. 호스트 무음 에뮬레이터를 사용하며 사용자 음원·녹음·이동 데이터는 사용하지 않는다.

## 검증과 재현

2026-11-15 결과: **JVM 174/174, Pleos 38/38, 일반 Android 38/38, lint 오류·경고 0**. 두 장치의 실행 APK hash와 최종 빌드가 일치한다. Pleos 38개는 기존 카탈로그/재생 26개 + 음성/UX 12개, 일반 Android 38개는 기존 27개 + 음성 11개다.

기존 **144개 JVM 테스트의 실행 이름과 해당 소스 파일 hash를 보존**하고, 음성 직렬화 21개·UX gate 9개를 추가했다. 정상/중복/늦은 콜백, stop 중 error, stop timeout, 초기화 실패, 출력 중첩 차단, PAUSE/STOP/NEXT/DJ OFF/포커스 순서와 UNKNOWN·권한 철회 후 선택 결과 차단을 검사한다. 오류와 callback reorder는 제어 가능한 JVM 드라이버로 검증하며 플랫폼 장애가 실제 발생했다고 표현하지 않는다.

네이티브 검사는 이전 카탈로그·Media3 검사를 유지한 상태에서 두 실제 TTS 경로의 시작·완료·중단 확인, NEXT 예정 곡 보존, 실제 오디오 포커스 경쟁, 사용자 PAUSE 우선, 정상 음악 블록 뒤 DJ 전환과 DJ OFF를 확인한다. Android의 음성 PCM byte 수와 Pleos의 이벤트/RMS 관측치를 별도로 기록한다. 소리 파형이나 음성 파일은 저장하지 않는다.

```powershell
python -m pip install --target app/build/rr004-tools lameenc==1.8.5
python -X utf8 tools/generate_rr004_audio.py
./tools/check.ps1 -Offline

# 전용 AVD 두 개가 부팅된 상태. Pleos는 Virtual Console의 P에서 시작한다.
adb -s emulator-5584 forward tcp:8088 tcp:8088
python -X utf8 tools/check_rr005_device.py --serial emulator-5584 --avd ReplyRadio_RR003_API34
python -X utf8 tools/check_rr005_device.py --serial emulator-5586 --avd ReplyRadio_RR003_API37 --picker

$buildLog = (Get-Content evidence/latest.json -Raw | ConvertFrom-Json).buildLog
python -X utf8 tools/verify.py --report --require-devices --log $buildLog
```

Pleos 실행 중 `evidence/rr005-ReplyRadio_RR003_API34-voiceux.log`에 `RR005_WAIT_CONSOLE_D`가 기록되면 공식 Virtual Console `http://127.0.0.1:8088/`에서 D를 선택한다. `RR005_WAIT_CONSOLE_P`가 기록되면 P로 복원한다. 각각 최대 120초 대기하며 관측 실패를 PASS로 대체하지 않는다. D에서 속도 0인 상태에도 플랫폼 제한이 활성화되는지 검사한다. 이 동작은 **에뮬레이터 입력으로 유발한 실제 플랫폼 콜백**이며 실제 차량 주행 검사가 아니다.

이 이미지에서는 공식 ADB 예제의 `hmg_car_service`가 없어 해당 명령은 사용할 수 없다. 일반 `car_service`의 주행 주입 명령도 user 빌드에서 거부한다. 권한을 우회하지 않고 공식 Virtual Console을 이용한다. 실패하거나 중단한 검사 뒤에는 Console에서 P로 복원한다.

최종 결과와 APK hash는 [RR-005 집계](../evidence/rr005.json), [Pleos](../evidence/rr005-device-ReplyRadio_RR003_API34.json), [일반 Android](../evidence/rr005-device-ReplyRadio_RR003_API37.json)에 기록한다. `--voice-only`는 개발 중 진단용이며 전체 완료 증거를 대신하지 않는다. 과거 RR-001~004 결과와 원본 54+40개 인수 상태는 덮어쓰지 않는다.

## 남은 범위

물리 스피커 청취, 물리 USB 분리/드라이버 오류, 실차 미디어 버튼·주행·통화/내비 정책, 국가별 UX 심사는 미검증이다. Pleos SAF 선택기 stub 제한도 유지한다. 실제 역사 자료·위치·음원 권리·7일 기록·자유 생성 대본 검증은 후속 티켓이다. 빈 캐시 빌드와 공급자 서명 검증은 이번 실행에 포함하지 않았다.

## 공식 근거

- [Pleos TTS 모드·진입점](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [Maven 가져오기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions)
- [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners), [시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop), [오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/error-code)
- [Pleos 주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), [CarUxRestrictionsManager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager), [CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions)
- [Virtual Console](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [공식 ADB 예제](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/adb-command-reference-guide)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)
