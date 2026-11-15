# README 이미지 기록

| 파일 | 유형 | 출처 |
|---|---|---|
| `reply-radio-hero.png` | AI 생성 콘셉트 일러스트 | 2026-11-15, 내장 ImageGen 도구로 신규 생성. 실제 앱 UI·차량 사진이 아님. 로고/브랜드 표식 없음. |
| `catalog-android.png` | 실제 앱 캡처 | `evidence/rr003-catalog.png`의 원본 복사. Android API37, 합성 테스트 파일 등록. |
| `voice-android.png` | 실제 앱 캡처 | `evidence/rr005-ReplyRadio_RR003_API37-speaking.png`의 원본 복사. 한국어 DJ 발화 중. |
| `vehicle-ux-pleos.png` | 실제 앱 캡처 | `evidence/rr005-ReplyRadio_RR003_API34-restricted.png`의 원본 복사. Virtual Console D 상태의 실제 UX 제한. |
| `playback-android.png` | 실제 앱 캡처 | `evidence/rr004-ReplyRadio_RR003_API37.png`의 원본 복사. 실제 Media3 재생 중. |

프로젝트 소비용 이미지는 이 폴더에 저장하며 사용자 홈의 생성 경로에 의존하지 않는다. 캡처의 색상·텍스트·레이아웃을 합성하거나 수정하지 않았다. 실제 APK의 UI가 대표 일러스트처럼 구현되었다고 표현하지 않는다.

## 대표 이미지 최종 프롬프트

도구: **내장 `image_gen.imagegen`**, `transparent_background=false`. CLI/API fallback은 사용하지 않았다.

> Create a premium editorial hero illustration for a GitHub competition project called Reply Radio, a Korean in-car radio concept connecting generations through music and the history of roads. Wide panoramic composition roughly 3:1. A sculptural warm ivory and brushed copper retro FM radio with two tactile circular knobs in the left third, its softly amber lit analog tuning scale subtly blending into a winding road flowing through layered deep teal Korean mountain silhouettes and a tiny distant car, golden dusk light, restrained cinematic grain, delicate concentric radio waves echoed by road contours. Elegant modern Korean automotive design presentation, tactile paper and metal, refined understated composition, deep midnight teal #142F32, pale cream #F5EBD8 and burnt amber #D9A058, beautiful generous negative space in the upper/right half, clean edges suitable for a README header, sharp professional rendering. No text, no letters or numbers, no logos or car brand emblems, no UI mockup, no watermark, no people. This is a conceptual illustration, not an app screenshot.

## UI-001 실제 화면 (2026-11-15)

`prototype-radio.png`, `prototype-dj.png`, `prototype-catalog.png`는 일반 Android API37의 실제 앱 캡처다. `prototype-pleos.png`, `prototype-restricted.png`는 Pleos API34의 실제 재생/차량 제한 화면이다. 테스트 APK의 합성 음원만 사용했다. 라디오 다이얼은 `RadioTheme.kt`에서 Canvas로 그린 로컬 벡터 장식이며 외부 사진·음원·폰트를 사용하지 않는다. UI 검증 단계가 생성한 PNG를 그대로 복사했고 합성하거나 보정하지 않았다. 기존 이미지와 제작 기록은 위에 보존한다.
