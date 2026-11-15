# RR-006 — FastAPI + Mock LLM 계약·검증

2026-11-15. v0.5 원본 API를 보존하는 로컬 서버를 구현했다. 외부 모델을 호출하지 않는 **Mock 서비스**이며, Android의 현재 재생 경로는 계속 로컬 대본을 사용한다. 네트워크 연결·실서비스 인증·외부 모델 품질을 완료했다고 표시하지 않는다.

## 구현 범위

| 파일 | 책임 |
|---|---|
| `backend/models.py` | 필수 필드·열거값·길이·추가 필드 금지, Pydantic v2 모델, 중복 키/비유한 수/과도한 중첩 차단 |
| `backend/scripts.py` | 버전별 프롬프트, 소재 선택, Mock, 날씨 재검사, 대본 검증, 3초 timeout, 서버 소유 폴백 |
| `backend/app.py` | FastAPI 엔드포인트, Bearer 인증, 요청 크기/속도/동시 처리 제한, 비식별 운영 로그 |
| `backend/tests/` | 원본 스키마/fixture 교차 검사, 실패 주입, ASGI와 실제 Uvicorn HTTP 검사 |
| `BackendContractTest.kt` | HTTP로 수신한 9개 응답을 기존 Android decoder·내용 검증기·폴백과 대조 |
| `tools/check_backend.py` | 잠긴 의존성 확인, 테스트 실행, HTTP fixture와 서버 증거 생성 |

`POST /api/v1/dj/scripts`의 필드와 정상 응답 HTTP 200을 유지한다. 오류·timeout·규격 위반은 `isFallback=true`인 유효한 대본을 반환한다. 모델이 `isFallback=true`를 주장하거나 재생 명령/새 트랙을 추가하면 승인하지 않는다. 서버는 음악 큐·재생·TTS를 제어하지 않는다.

요청 스키마 오류는 422, 인증 실패는 401, 크기 초과는 413, 미지원 content type/encoding은 415, 본문 수신 timeout은 408, 사용량·동시 처리 초과는 429다. 응답 본문이나 예외에 요청 데이터를 복사하지 않는다. HTTP 실패 시 앱이 사용할 로컬 폴백과 3초 deadline은 기존 RR-002 테스트로 유지하며, 이번 티켓에서 앱 HTTP 전송을 활성화하지 않았다.

## 계약과 검증 정책

- 원본 `docs/design/reply_radio_dj_script_schema_v0.5.json`과 fixture를 수정하지 않았다. Python 모델과 Draft 2020-12 검증기를 교차 확인하고, 원본의 4개 negative fixture 오류 코드를 재현한다.
- 요청 식별자·세대·종류·페르소나를 보존한다. Android Long 범위를 넘는 generation, URI/경로 형태 트랙 ID는 런타임에서 추가로 거부한다. 이는 스키마 개정이 아닌 기존 Android에 맞춘 입력 제한이다. JSON의 정수 값 `12.0`은 허용하고 `"12"`·`true`·소수는 거부한다.
- 제목/아티스트는 NFC 정규화·제어 문자 제거·100자 제한을 적용한다. 프롬프트 규칙과 JSON 데이터를 별도로 보관하며 태그 안의 지시를 실행하지 않는다.
- `valid=true`를 신뢰하지 않는다. 미래 시각, UNKNOWN, invalid, 60분 초과 날씨는 모델 입력에서 제거하고 응답 승인 시 다시 확인한다. 정확히 60분은 허용한다. 서버의 미래 시각 정책은 앱의 최대 5분 허용보다 보수적이다. 실제 날씨 서비스 조회는 구현하지 않았다.
- 최근 2회에 WEATHER가 있으면 날씨를 반복하지 않는다. Mock은 기존 앱이 승인하는 시대 안내와 날씨 접두사만 생성한다. 새로운 음악 사실·가족관계·사연·운전 조작·실존 DJ 모사는 승인하지 않는다.
- `groundingFields`만으로 사실을 승인하지 않는다. 실제 허용된 텍스트·소재·근거 경로 조합과 일치해야 한다. 원본 예제 문장은 **스키마 호환성 자료**이며 자유 생성문에 대한 포괄적 승인 목록이 아니다. 두 시대를 모두 소개하는 새 오프닝이나 자유로운 곡 소개는 추후 앱/서버 공동 검수 대상이다.
- 모델 호출 timeout은 **실제 3초, 재시도 0회**다. 취소 가능한 async provider 계약을 사용한다. 외부 취소는 폴백으로 삼키지 않고 전달한다. 임의의 동기·취소 무시 플러그인을 강제 종료하는 샌드박스는 아니다.
- 160자와 초당 6자 기준의 사전 길이 검사는 휴리스틱이다. 폴백은 재생 연속성을 위한 고정 문구로 예외 처리하며, 실제 발화 시간은 기기 TTS가 관리한다. 사용자 청취 평가를 자동 테스트로 대체하지 않는다.

## 서버 실행

검증한 런타임은 **Python 3.11**이다. FastAPI 0.163.1, Pydantic 2.14.3, Uvicorn 0.54.0과 전이 의존성 30개를 `backend/requirements.lock`에 버전·배포 파일 SHA-256으로 고정했다. hash 검사를 켠 재설치와 `pip check`를 수행했다. 외부 LLM SDK·API 키는 필요하지 않다.

저장소 루트에서 실행한다.

```powershell
py -3.11 -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install --require-hashes -r backend/requirements.lock

# 실행 세션에서만 만들고 유지한다. 소스/앱/로그에 넣지 않는다.
$env:RR_API_TOKEN = (& backend/.venv/Scripts/python.exe -c "import secrets; print(secrets.token_urlsafe(32))")
backend/.venv/Scripts/python.exe -m uvicorn backend.app:create_app --factory --host 127.0.0.1 --port 8000 --workers 1 --no-access-log --no-proxy-headers --limit-concurrency 32
```

`GET /healthz`는 `provider=mock`과 정책 버전 `rr006-mock-v1`을 반환한다. `GET /openapi.json`에서 요청·응답 스키마와 Bearer 인증 계약을 볼 수 있다. API 요청 도구에는 서버 실행 세션과 동일한 토큰을 `Authorization: Bearer …`로 설정한다. 토큰 없이 서버를 시작하거나 요청할 수 없다. 토큰을 새로 만들어 재시작하면 이전 토큰은 더 이상 유효하지 않다.

요청 예제로 원본 `docs/design/reply_radio_dj_script_fixture_v0.5.json`의 `request` 객체를 사용할 수 있다. 해당 시각의 날씨는 현재 기준으로 만료될 수 있으므로 정상적으로 날씨 없는 응답이 나올 수 있다. 정상 응답과 폴백 모두 `X-RR-Provider: mock`, 진단용 `X-RR-Result`, `Cache-Control: no-store`를 반환한다.

### 운영 경계

로컬 단일 프로세스 전용이다. 단일 개발 자격증명에 **분당 30회**, 동시 처리 **4개**, 본문 **32 KiB**, 본문 수신 **3초**를 적용한다. 인증된 잘못된 요청도 사용량에 포함되며, chunked body도 누적 크기를 검사한다. 제한 상태는 메모리에만 있고 재시작하면 초기화된다. 여러 worker/인스턴스의 통합 한도나 사용자 계정·토큰 발급 시스템이 아니다.

운영 로그는 정책 버전·대본 종류·결과 코드·폴백 여부·소요 시간만 남긴다. requestId도 임의의 사용자 문자열일 수 있어 원문을 기록하지 않는다. 태그·대본·파일 경로·좌표·세션 ID·인증 값은 기록/캐시하지 않는다. Uvicorn access log와 proxy header 신뢰는 위 실행 명령에서 끈다. 테스트의 실패 provider는 테스트 코드에만 있으며 외부 요청으로 오류 모드를 선택하는 운영 endpoint는 없다.

외부 공개 전에는 HTTPS, 사용자별 인증·회수, 공유 사용량 저장소, 프록시 한도와 로그 정책, 외부 모델의 취소/비용/콘텐츠 검수, 앱의 비동기 전송·취소·세션 연결을 별도로 설계·검증해야 한다. Android APK에 개발 토큰을 넣거나 HTTP 허용 권한을 추가하지 않았다.

## 검증과 재현

```powershell
# 서버 115개: 경고도 실패 처리. 실제 TCP HTTP 9개 포함.
backend/.venv/Scripts/python.exe -X utf8 tools/check_backend.py

# 기존 174개 소스 hash/테스트 이름 유지 + 서버 호환성 3개
./tools/check.ps1 -Offline

# 현재 APK와 RR-005 장치 증거의 일치도 확인
python -X utf8 tools/verify.py --report --require-devices --log evidence/<이번-build-log>.log
```

결과: **서버 115/115**, **JVM 177/177(기존 174개 + 신규 3개)**, 실패·스킵 0, 서버 테스트 경고 0, Android lint 오류·경고 0. 원본 설계 27개 hash와 54+40개 인수 상태를 유지한다. Gradle의 SDK XML 버전 안내는 기존 SDK 도구 조합의 경고이며 lint 오류가 아니다.

실제 Uvicorn을 임시 localhost 포트에서 띄워 3종 대본 × 날씨 유무 6개, 잘못된 모델 응답·provider 장애·3초 timeout 3개를 요청했다. timeout 작업의 취소와 전체 모델 호출 9회(재시도 없음)를 확인했다. 응답을 `backend/fixtures/android-contracts.json`에 저장하고 Kotlin의 기존 엄격한 decoder와 내용 검증기에 전달했다. Gradle은 이 fixture를 테스트 입력으로 등록하므로 HTTP 자료가 바뀌면 다시 검사한다.

Android 실행 코드는 변경하지 않았다. APK와 test APK hash가 RR-005의 결과와 같음을 확인하고 **Pleos38/Android38 네이티브 증거를 재사용**한다. 이번에 장치 테스트를 다시 실행한 것으로 집계하지 않는다. 실제 모델 생성·클라우드 서버·앱→서버 네트워크 왕복·수동 30회 청취 평가(DJ-16)는 미검증이다. 기존 설계 인수 항목을 일괄 PASS로 승격하지 않는다.

증거: [서버](../evidence/rr006-backend.json), [전체 집계](../evidence/rr006.json), [174개 테스트 원본 hash](../evidence/rr005-test-hashes.json), [HTTP 응답 자료](../backend/fixtures/android-contracts.json).

## 확인한 공식 계약

- [FastAPI HTTPBearer](https://fastapi.tiangolo.com/reference/security/#fastapi.security.HTTPBearer): 헤더 기반 인증 의존성.
- [Pydantic strict mode](https://docs.pydantic.dev/latest/concepts/strict_mode/): 문자열·boolean의 암묵적 숫자 변환 차단.
- [Python asyncio timeout](https://docs.python.org/3/library/asyncio-task.html#asyncio.timeout): 취소와 TimeoutError 처리. 취소를 삼키지 않는 provider 계약이 필요하다.
