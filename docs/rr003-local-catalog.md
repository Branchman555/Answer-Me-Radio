# RR-003 — SAF 로컬 MP3 카탈로그

2026-11-15. 구현 범위는 사용자가 선택한 MP3의 메타데이터 등록·권한 수명·선곡 후보 연결이다. 실제 음악 출력은 RR-004에서 연결한다.

## 구현과 사용

개발 APK의 `개발용 로컬 MP3 관리` 버튼에서 파일을 선택하고, 메인 화면의 `등록한 로컬 MP3로 무음 편성` 옵션을 켠다. 선택 가능한 파일 탐색기가 설치된 Android가 필요하다. 카탈로그는 debug 빌드와 명시적인 내부 개발 진입을 모두 요구하며 exported=false다. 이 진입 조건은 차량의 상세 UX 허용 판정이 아니다. 실제 차량 UX UNKNOWN 차단과 정차 상태 연동은 RR-005에서 검증해야 한다.

- `catalog/LocalMusicCatalog.kt`: 선택된 content URI만 등록한다. URI 중복은 같은 ID로 갱신하며, 한 번에 100건·전체 500건으로 제한한다. 자동 저장소 탐색이나 폴더 접근은 없다.
- `catalog/AndroidCatalog.kt`: ACTION_OPEN_DOCUMENT, OPENABLE, audio/mpeg, 다중 선택, LOCAL_ONLY와 읽기 권한만 요청한다. 실제 반환 권한을 확인하고 가능한 경우 읽기 권한을 영속화한다. MediaMetadataRetriever로 TITLE/ARTIST/YEAR/GENRE/길이/MIME를 추출한다.
- `catalog/CatalogActivity.kt`: 비동기 등록·새로 확인·목록 제거와 파일별 제외 사유를 표시한다. 취소·선택기 부재·저장 실패를 처리하고 파괴된 화면의 늦은 콜백을 무시한다.
- `demo/DemoActivity.kt`: 명시적으로 로컬 모드로 바꾸면 방송을 정지하고 기존 가상 곡·역사 팩을 비운 뒤 CatalogChanged 이벤트로 실제 태그의 후보를 전달한다. 자동 재생하지 않으며 출력은 계속 무음이다.

TITLE/ARTIST를 NFC로 정규화하고 공백을 제거한다. 필수 태그 누락, 제어 문자, 과도한 길이, 1900~2099의 네 자리 숫자가 아닌 YEAR, MP3가 아닌 MIME, 잘못된 길이·읽기 실패는 선곡 대상에서 제외한다. 파일명이나 AI로 연도를 보완하지 않는다. Android의 YEAR는 생성/수정 연도로 기술된 필드이므로 **사용자가 발매연도로 준비한 태그**를 읽는 계약이다. 외부 자료로 실제 발매연도를 검증한 결과가 아니다.

## 저장과 권한

메타데이터·URI만 앱 전용 noBackupFilesDir에 AtomicFile로 저장한다. 원본 MP3를 복사하거나 진단 로그·서버·앱 패키지에 포함하지 않는다. 광범위한 저장소·미디어·네트워크 권한은 추가하지 않았다.

영속 읽기 권한이 없으면 현재 세션에서만 사용한다. 프로세스 복원 때 영속 권한 없는 행은 재선택 상태가 된다. 새로 확인할 때 다시 열어서 권한 철회, 삭제, 분리된 저장소를 확인한다. 한 파일의 오류가 다른 정상 파일을 막지 않는다. 같은 URI를 다시 선택하면 ID를 유지한다. 목록에서 제거하면 해당 영속 읽기 권한을 해제하며 원본 파일은 삭제하지 않는다. 저장 실패 시 기존 카탈로그와 기존 권한을 유지하고 이번에 새로 얻은 영속 권한만 되돌린다. 손상된 저장 파일을 빈 목록으로 덮어쓰지 않는다.

## 재현과 증거

```powershell
./tools/check.ps1 -Offline
python -X utf8 tools/check_rr003_device.py --serial emulator-5584 --avd ReplyRadio_RR003_API34
python -X utf8 tools/check_rr003_device.py --serial emulator-5586 --avd ReplyRadio_RR003_API37 --picker
$buildLog = (Get-Content evidence/latest.json -Raw | ConvertFrom-Json).buildLog
python -X utf8 tools/verify.py --report --require-devices --log $buildLog
```

장치 명령은 해당 전용 AVD가 부팅된 상태에서 실행한다. 스크립트는 일치하는 AVD 이름을 확인하고 debug 앱과 테스트 APK를 설치한다. 개인 음악이나 실제 장치는 사용하지 않는다. 테스트 APK의 DocumentsProvider가 합성 ID3 태그와 무음 MPEG 프레임을 생성한다. 이 공급자·테스트 Activity·음원은 본 앱 APK에 포함되지 않는다.

- JVM **127개 = 기존 103개 + 신규 24개**, 실패·스킵 0. 기존 테스트 소스 해시와 이름별 실행을 검사한다.
- debug APK와 테스트 APK 생성, lint 오류 0·경고 0, 설계 원본 27개 해시와 54+40개 인수 상태 보존.
- Pleos API34: 네이티브 검사 12개. 실제 메타데이터 추출, 영속 저장, 프로세스 강제 종료 후 복원, 권한 철회·재선택, 삭제된 파일 제외, 목록 제거 후 원본 보존.
- 일반 Android API37: 같은 검사 12개와 실제 DocumentsUI → CatalogActivity 등록 1개. 선택하지 않은 파일의 접근 권한이 없음을 함께 확인한다.
- [최종 집계](../evidence/rr003.json), [Pleos 결과](../evidence/rr003-device-ReplyRadio_RR003_API34.json), [일반 Android 결과](../evidence/rr003-device-ReplyRadio_RR003_API37.json), [등록 화면](../evidence/rr003-catalog.png). 장치별 APK 해시가 현재 빌드와 일치하는지도 집계한다.

## 남은 플랫폼 조건

설치된 Pleos API34 이미지의 ACTION_OPEN_DOCUMENT는 `com.android.car.frameworkpackagestubs/.Stubs$DocumentsUIStubWithResult`로 연결된다. **Pleos에서 사용자가 실제 파일을 선택하는 왕복은 BLOCKED_PLATFORM_STUB**다. 임의 경로 접근으로 우회하지 않았다. 실제 파일 선택기가 제공되는 대상 이미지/장치에서 추가 검증해야 한다.

프로세스 재시작은 검증했지만 장치 재부팅, 물리 USB 분리, 실제 음악 출력, 차량 주행 UX는 미검증이다. 삭제된 문서와 읽기 실패 검증을 물리 USB 검증으로 간주하지 않는다. AC-001~004의 카탈로그 관련 부분을 검사했으며 AC-005의 재생 중 USB 분리·다음 곡 진행은 RR-004 통합 조건이다. 원본 인수표 94개 상태를 일괄 PASS로 변경하지 않는다.

공식 계약: [Android SAF와 영속 URI 권한](https://developer.android.com/training/data-storage/shared/documents-files), [MediaMetadataRetriever 필드](https://developer.android.com/reference/android/media/MediaMetadataRetriever).
