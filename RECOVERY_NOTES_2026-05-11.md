# Recovery Notes (2026-05-11)

## 1) 이번에 변경했던 큰 작업

- WebView + 네이티브 인증 흐름 조정
  - `requestNativeAuth(method=biometric)`를 웹 화면 위에서 바로 BiometricPrompt 실행하도록 분기
  - PIN/패턴은 `UnlockActivity` 유지
- QR 인증 UX 변경
  - 기존 `QrScannerActivity` 전환 방식에서 WebView 위 오버레이 스캐너 방식 시도
  - 이후 요구사항에 맞춰 화면 전환/오버레이를 반복 조정
- WebView fallback 로직 강화
  - 네트워크/SSL/빈 화면 감지 시 `file:///android_asset/index.html` fallback
  - HTTP 404는 fallback으로 넘기지 않도록 정책 변경
- 앱 로고/팝업 로고 교체
  - `assets/logo.png` 기반으로 런처 아이콘/오버레이 로고 교체 시도
- 세로 고정
  - `MainActivity`, `QrScannerActivity`, `UnlockActivity` portrait 고정

## 2) 문제로 확인된 포인트

- 메인 URL `https://dev-kyvc.khuoo.synology.me/m/`가 404 또는 네트워크 이슈일 때 화면이 fallback으로 전환되며,
  이 과정에서 "흰 화면"처럼 보이는 케이스가 있었음.
- QR UI 요구사항(화면 전환 vs 웹 위 오버레이)이 여러 차례 바뀌면서 `MainActivity` 변경 범위가 커짐.
- 런처 아이콘은 이미지 소스 캔버스/투명도/런처 캐시 영향으로 반영이 불안정하게 보일 수 있었음.

## 3) 다시 구현할 때 권장 순서

1. 기준 UX 고정
   - 지문: 웹 페이지 유지 + BiometricPrompt만 표시
   - QR: (A) 전환형 or (B) 오버레이형 중 하나 확정 후 유지
2. WebView 로딩 정책 고정
   - fallback 조건 명확화(네트워크/SSL/blank only)
   - HTTP 404는 remote 그대로 표시할지 fallback할지 사전 확정
3. 로고 반영 분리
   - 런처 아이콘(적응형 아이콘 리소스)과
   - 앱 내 오버레이 로고(일반 drawable)를 분리 관리
4. 마지막에 문서 갱신
   - `WEBVIEW_BRIDGE_SPEC.md`
   - `README.md`

## 4) 체크리스트(재구현 검증)

- [ ] biometric 브리지 호출 시 Activity 전환 없이 Prompt 표시
- [ ] QR 브리지 호출 시 요구한 방식으로만 동작(전환형/오버레이형)
- [ ] WebView 메인 URL 실패 시 fallback 정책이 의도대로 동작
- [ ] 404 처리 정책이 문서/코드와 일치
- [ ] 아이콘/오버레이 로고가 동일 디자인 기준으로 보임
- [ ] 세로 고정 유지

