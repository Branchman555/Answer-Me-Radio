# RR-004 — Media3 재생과 표준 제어

이 단계는 SAF에 등록된 로컬 MP3를 실제 Media3/AudioTrack 경로로 재생한다. 기존 무음 시뮬레이터와 별도로 `실제 로컬 음악 재생 (DJ OFF)` 화면을 제공한다. 실제 TTS와 차량 상세 UX는 RR-005이므로 DJ와 역사 음성은 명시적으로 OFF다.

## 구현 경계

- `playback/PlaybackEngine.kt`: UI·미디어 세션·디코더 콜백을 하나의 큐에서 RadioReducer에 전달한다. 각 명령 전에 현재 출력 token에 해당하는 재생 위치를 반영한다.
- `playback/Media3Output.kt`: ExoPlayer에 현재 MP3 한 곡만 로드한다. 자연 종료만 Ended로 보내고, 권한·파일·디코딩 오류는 Failed로 보낸다. 정지 시 playback thread와 AudioTrack을 해제하는 release가 완료된 뒤 OutputStopped를 보낸다. 해제 timeout은 정상 정지 확인으로 간주하지 않는다. PAUSE/포커스 중단 뒤에는 보존한 위치와 새 token으로 재개한다.
- `playback/SessionPlayer.kt`: Media3 SimpleBasePlayer로 읽기 상태를 노출하고 허용한 변경 명령만 Reducer에 전달한다. 직접 URI·재생목록·반복 설정을 주입할 수 없다. 일시정지 중 이전 곡으로 바꿔도 metadata와 위치만 바뀌며 재생하지 않는다.
- `playback/RadioService.kt`: MediaLibraryService/MediaLibrarySession, 시대별 browse·검색, 기존 Android MediaBrowser 호환, foreground notification, 단일 오디오 포커스 소유자다. 앱 UID 또는 OS가 신뢰하는 컨트롤러만 `onConnect`에서 허용한다. legacy 바인딩의 초기 placeholder는 실제 호출자 신원이 아니므로 그 단계에서 UID를 추측하지 않는다.
- `playback/PlaybackActivity.kt`: 표준 MediaBrowser를 통해 재생/일시정지/이전/다음/정지를 보낸다. 화면을 닫아도 재생 중 서비스는 유지하며, 정지하고 마지막 컨트롤러까지 해제되면 서비스를 종료한다.

PREVIOUS는 재생 위치 5초 초과면 현재 곡 처음으로, 이하이면 접근 가능한 직전 곡으로 이동한다. 이전 곡을 탐색한 뒤 NEXT는 원래 순서로 돌아간다. 수동 NEXT/PREVIOUS/seek는 정상 완료 횟수를 증가시키지 않는다. 음악 오류는 해당 후보를 제외하고 보충하며, 연속 3회면 중단한다. 오래된 완료·위치·정지 콜백은 token으로 폐기한다.

실제 재생 기본 시대는 기존 설정의 1980년대·2010년대다. 서비스는 로컬 카탈로그 밖의 mediaId를 재생하지 않으며, 외부 컨트롤러에 content URI를 배포하지 않는다. 네트워크·광범위 저장소 권한은 추가하지 않았다. 추가 권한은 FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK뿐이다. 디코더·위치 상태는 메모리에만 두며 프로세스 강제 종료 후 재생 위치 자동 복원은 제공하지 않는다.

## 의존성과 재현

기존 compileSdk35/AGP8.8.2 도구 체인에 맞춰 Media3 ExoPlayer/Session **1.6.1**을 고정했다. 최신 버전이라는 의미가 아니다. 전이 의존성 lock과 SHA-256 verification metadata를 갱신했고 이후 빌드는 strict/offline으로 검사한다. 새 의존성은 공식 Google Maven/Maven Central에서 받았으며 최초 관측 hash는 공급자 서명 검증을 대신하지 않는다.

테스트 음악은 직접 합성한 12초 아르페지오를 MP3로 인코딩한 것이다. 사용자 음원·외부 녹음·다운로드한 음악을 사용하지 않는다. MP3는 무시되는 build 폴더와 테스트 APK에만 생성하며 본 앱 APK에는 포함하지 않는다. 테스트 공급자가 이 파일을 SAF content URI로 제공한다.

```powershell
# 새 머신에서 테스트 음악 생성 도구를 한 번 준비한다.
python -m pip install --target app/build/rr004-tools lameenc==1.8.5
python -X utf8 tools/generate_rr004_audio.py
./tools/check.ps1 -Offline

# 이름이 일치하는 전용 AVD가 부팅된 상태에서 실행한다.
python -X utf8 tools/check_rr004_device.py --serial emulator-5584 --avd ReplyRadio_RR003_API34
python -X utf8 tools/check_rr004_device.py --serial emulator-5586 --avd ReplyRadio_RR003_API37 --picker
$buildLog = (Get-Content evidence/latest.json -Raw | ConvertFrom-Json).buildLog
python -X utf8 tools/verify.py --report --require-devices --log $buildLog
```

검증 도구는 실제 AVD 이름을 확인하고 debug/test APK를 설치한다. RR-003의 메타데이터·권한 수명 검사도 다시 수행한다. 기존 127개 JVM 테스트는 파일 hash와 이름별 실행으로 보존을 검사한다. 개별 결과, 테스트 수, APK hash, 디코더 출력 버퍼와 AudioTrack 진행 횟수는 [RR-004 증거](../evidence/rr004.json)와 장치별 로그에 기록한다.

## 검증 해석과 남은 조건

2026-11-15 최종 결과: **JVM 144개(기존 127개 + 신규 17개), 실패·스킵 0, lint 오류·경고 0**. Pleos API34에서 카탈로그 12 + 재생 14 = **26개**, 일반 Android API37에서 카탈로그/선택기 13 + 재생 14 = **27개** 네이티브 검사를 통과했다. 최종 빌드와 두 장치 결과의 APK SHA-256 일치를 확인했다. 재생 검사에는 별도 UID 브라우저 거부, 플랫폼 legacy transport 제어, 실제 포커스 경쟁, 삭제/권한 철회 오류 2건과 손상 MP3 오류 3건도 포함된다.

합성 MP3의 실제 디코더 출력 버퍼, AudioTrack 진행, 재생 위치 증가, 자연 종료를 확인하는 검증이다. 에뮬레이터는 호스트에 소리를 내지 않도록 실행하므로 **물리 스피커 청취 검증을 의미하지 않는다**. 실제 사용자 음악의 발매연도·재생 품질·배포 권리는 별도 검증 대상이다.

파일 삭제·권한 철회 후 다시 열기, 유효 태그로 등록한 파일의 손상, 실제 Media3 오류 콜백과 3회 제한을 검사한다. 이미 열린 파일의 권한 철회가 곧바로 재생을 끊는다고 가정하지 않는다. 물리 USB 분리나 USB 드라이버의 재생 중 읽기 오류(AC-005 전체), 실제 차량 버튼·주행 UX, TTS·GPS는 미검증이다. Pleos 이미지의 파일 선택기 stub 제한도 그대로 남는다. 원본 인수표 54+40개를 일괄 PASS로 변경하지 않는다.

공식 근거: [Media3 릴리스](https://developer.android.com/jetpack/androidx/releases/media3), [MediaLibraryService](https://developer.android.com/media/media3/session/serve-content), [백그라운드 재생](https://developer.android.com/media/media3/session/background-playback), [Android 오디오 포커스](https://developer.android.com/media/optimize/audio-focus). 실제 사용한 1.6.1 API는 같은 버전의 공식 sources JAR로 확인했다.
