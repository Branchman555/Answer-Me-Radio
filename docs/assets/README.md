# README 이미지 기록

| 파일 | 유형 | 출처 |
|---|---|---|
| `reply-radio-hero.png` | AI 생성 콘셉트 일러스트 | 2026-11-15, 내장 ImageGen 도구로 신규 생성. 실제 앱 UI·차량 사진이 아님. 로고/브랜드 표식 없음. |
| `catalog-android.png` | 실제 앱 캡처 | `evidence/rr003-catalog.png`의 원본 복사. Android API37, 합성 테스트 파일 등록. |
| `playback-android.png` | 실제 앱 캡처 | `evidence/rr004-ReplyRadio_RR003_API37.png`의 원본 복사. 실제 Media3 재생 중. |

프로젝트 소비용 이미지는 이 폴더에 저장하며 사용자 홈의 생성 경로에 의존하지 않는다. 캡처의 색상·텍스트·레이아웃을 합성하거나 수정하지 않았다. 실제 APK의 UI가 대표 일러스트처럼 구현되었다고 표현하지 않는다.

## 대표 이미지 최종 프롬프트

도구: **내장 `image_gen.imagegen`**, `transparent_background=false`. CLI/API fallback은 사용하지 않았다.

> Create a premium editorial hero illustration for a GitHub competition project called Reply Radio, a Korean in-car radio concept connecting generations through music and the history of roads. Wide panoramic composition roughly 3:1. A sculptural warm ivory and brushed copper retro FM radio with two tactile circular knobs in the left third, its softly amber lit analog tuning scale subtly blending into a winding road flowing through layered deep teal Korean mountain silhouettes and a tiny distant car, golden dusk light, restrained cinematic grain, delicate concentric radio waves echoed by road contours. Elegant modern Korean automotive design presentation, tactile paper and metal, refined understated composition, deep midnight teal #142F32, pale cream #F5EBD8 and burnt amber #D9A058, beautiful generous negative space in the upper/right half, clean edges suitable for a README header, sharp professional rendering. No text, no letters or numbers, no logos or car brand emblems, no UI mockup, no watermark, no people. This is a conceptual illustration, not an app screenshot.
