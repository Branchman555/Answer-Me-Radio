# 응답하라 라디오 — Codex 인수인계 README v0.7

1) 목적
	- 사용자 직접 준비 로컬 MP3 + 시대별 세대 교차 + 독자적 AI DJ + 차량 미디어 제어의 설계 사양을 한곳에 묶었다.
	- 이 ZIP은 구현된 앱·테스트 실행 결과·저작권을 확보한 음원 파일을 포함하지 않는다.

2) 가장 먼저 읽을 자료
	1. `reply_radio_mvp_integration_spec_v0.7.md` — 최신 요구사항·정상/오류 시퀀스·품질 게이트. 충돌 시 이 문서 우선.
	2. `reply_radio_decision_log_v0.7.md` — 최신 결정과 과거 문서의 충돌 해결.
	3. `reply_radio_acceptance_matrix_v0.7.csv` — 54개 테스트. 상태는 전부 NOT_RUN.
	4. `reply_radio_integration_fixtures_v0.7.json` — 음원 메타데이터 11개, 18개 시퀀스 예제와 각 시나리오에 맞춘 ContextSnapshot 18개. 실제 MP3 불포함.
	4-1. `radio_policy.example_v0.7.json` — 정책 초기값. 운영 결정·실측 전 제안값이며 실제 공급자 설정/비밀키 없음.
	5. `reply_radio_playback_state_machine_v0.4.md` — 상태·이벤트·동시성 상세.
	6. `reply_radio_dj_script_generator_v0.5.md` + `reply_radio_dj_script_schema_v0.5.json` — 백엔드 API 계약.
	7. `reply_radio_context_engine_v0.6.md` + `reply_radio_context_schema_v0.6.json` — 상황정보 계약.
	8. `reply_radio_ai_dj_spec_v0.2.md` + `reply_radio_codex_handoff_v0.1.md` — 기획 배경 및 도메인 설계.
	9. `reference/reply-radio-review-v1.1.md` — 공모전 문장·권리·사업성 기술 검토.
	10. `reference/응답하라_라디오_차량_UI_3화면_시안_v0_1.png` — 시각 콘셉트 참고용; 실제 기능/가사/음원 확보 증빙 아님.

3) 불변 조건
	- AI는 선곡·URI·재생 명령을 생성할 수 없음. 오직 확정한 트랙에 대한 대사만 생성.
	- 발매연도 시대 필터는 하드 제약. 사용자가 준비한 TITLE/ARTIST/YEAR에 근거.
	- DJ는 기본 2~3곡 정상 완료 시 등장, 수동 NEXT는 완료 아님. TTS+음악 동시 출력 불가.
	- 사용자 PAUSE/STOP/시대 변경은 늦게 온 AI/미디어/TTS 이벤트보다 우선.
	- 정확한 위치, 차량 식별자, 음원 URI/바이너리는 DJ API에 전달 금지.
	- 날씨 API·Pleos TTS·차량 정보는 미검증. 공급원 실패해도 기본 MP3 재생은 유지.
	- `ContextSnapshot` v0.6은 로컬 타입이고 DJ API v0.5 request와 같지 않음.
	- Pleos SDK v3.3.1 최신 TTS ErrorType 등 실제 시그니처 확인 후 어댑터 작성.

4) 첫 Codex 작업 — 테스트 우선, 순수 Kotlin부터
```
이 ZIP을 기준으로 응답하라 라디오 MVP를 구현한다.
우선 최신 통합 명세 v0.7, 결정 로그, CSV 인수 테스트, JSON fixture를 읽어라.
DJ API는 v0.5 JSON Schema, ContextSnapshot은 v0.6 JSON Schema를 변경하지 마라.
1. 순수 Kotlin 도메인 모델(Track, Era, MusicBlock, RadioPlanner, ContextSnapshot,
   Orchestrator Reducer/Event/Effect)을 구현하고 Fake Player/TTS/HTTP를 만든다.
2. AC-007~015, AC-024~035, AC-036~042를 단위 테스트로 먼저 구현해 통과시킨다.
3. v0.5 FastAPI/Pydantic 대본 API를 Mock LLM로 검증한다.
4. 그다음 Media3(MediaLibraryService/Session), SAF, Pleos TTS, UxRestrictions 어댑터를 붙인다.
5. SDK 미검증 항목은 TODO와 BLOCKED로 기록하고 테스트 통과로 속이지 마라.
6. 외부 키·음원 파일·정밀 위치·실명은 저장소/로그/AI 요청에 포함하지 마라.
7. 작업별로 실행 명령, 테스트 결과, BLOCKED 근거와 다음 작업 1개를 보고하라.
```

5) 인수 테스트 상태 관리
	- CSV `status`: NOT_RUN(초기)/PASS/FAIL/BLOCKED만 사용. `evidence`: 테스트 로그·동영상·커밋·기기 정보.
	- 단위 테스트 PASS와 에뮬레이터 PASS를 같은 근거로 취급하지 않는다.
	- 실 기기·실차·배포 심사 통과를 코드 생성 결과로 주장하지 않는다.

6) 보안·권리 및 제출 안내
	- 로컬 MP3는 사용자 개인 준비. 라이선스·공개 영상·배포·Git 저장소 사용 범위는 별도 확인.
	- 공식 접수·개인정보 동의·서명은 팀의 기획/제출 담당자가 별도 수행한다.
	- 참고 UI 시안은 기능 증빙이 아닌 콘셉트 이미지이며 실제 콘텐츠 출처를 증명하지 않는다.
