# KYvC Android Holder Wallet 작업 체크리스트

작성일: 2026-05-04  
마감 기준: 2026-05-09 전 완료 목표  

## 현재 완료된 부분

- Holder 지갑 생성/조회 구현
  - Android 내부에서 XRPL holder account와 DID를 생성한다.
  - seed는 Keystore 기반 암호화 저장소에 보관하고 WebView에는 노출하지 않는다.

- VC 저장 및 목록 조회 구현
  - issuer가 발급한 VC JSON을 Room DB에 저장한다.
  - 저장된 VC 목록 조회와 XRPL 상태 일괄 갱신을 지원한다.

- XRPL devnet Credential flow 구현
  - issuer-side `CredentialCreate` 제출 가능
  - holder-side `CredentialAccept` 제출 가능
  - XRPL credential status 조회 가능

- 실서버 issuer/verifier 연동 확인
  - `https://dev-core-kyvc.khuoo.synology.me` 기준 실제 VC 요청 성공
  - 실제 VC 인증 성공
  - verifier challenge 수신 성공
  - VP 생성 및 verifier 제출 성공

- WebView 테스트 UI 정리
  - 실제 테스트 흐름 기준으로 버튼 순서 정리
  - VC가 바뀌면 기존 VP를 폐기하도록 처리
  - VP 내부 VC와 현재 VC가 다르면 제출 차단

## 남은 작업 권장 순서

아래 순서는 실제 달력 일정이 아니라, 5월 9일 전 완료를 목표로 한 권장 진행 순서다.

| 순서 | 업무명 | 업무내용 | 완료 기준 |
| --- | --- | --- | --- |
| 1 | JWT 가이드 재정의 | 새 Android wallet 가이드 기준으로 VC/VP 기본 포맷을 `vc+jwt`/`vp+jwt`로 재정의한다. | `Wallet Implimentation Guide.md` JWT 기준 갱신 |
| 2 | VC JWT 수신/저장 | issuer 응답의 compact `vc+jwt`를 decode하고 payload 기반으로 저장/상태조회/Accept에 연결한다. | `vc+jwt` 발급 응답 저장 및 `CredentialAccept` 가능 |
| 3 | VP JWT 생성 및 키 분리 | Enveloped VC를 포함한 `vp+jwt`를 ES256K로 생성하고, XRPL account key와 VP auth key를 분리한다. | `SIGN_MESSAGE` 결과에 `presentationJwt` 생성, DID Document가 auth key 공개키 사용 |
| 4 | JWT 검증/오류 처리 | VC JWT header 검증, issuer DID Document 기반 signature 검증, 실패 메시지 세분화를 진행한다. | alg/typ/cty/kid/status 오류 분리 표시 |
| 5 | 최종 점검 | JWT 기준 전체 플로우를 재실행하고 README/체크리스트를 최종 갱신한다. | `vc+jwt` 수신부터 `vp+jwt` 제출까지 재검증 |

## 5월 9일 전까지 꼭 끝낼 항목

- [x] issuer 응답 `credential` 문자열 JWT 수신/저장
- [x] VC JWT header `alg/typ/cty/iss` 검증
- [x] Enveloped VC 기반 `vp+jwt` 생성
- [x] XRPL account key와 VP holder authentication key 분리
- [x] verifier 제출을 `vp+jwt` 중심 payload로 전환
- [x] JWT signature raw R||S 변환 구현
- [x] verifier 제출 실패 메시지 세분화
- [x] QR 요청 payload 처리 초안 구현
- [x] ES256K `vp+jwt` 생성/검증 JVM 테스트 추가
- [x] JWT 기준 UI 흐름 정리: 로컬 expanded JSON `VC 인증`/`canonicalHash 반영` 제거
- [x] README와 이 체크리스트 최신화
- [ ] 새 UI 흐름 기준 실제 VC 요청 -> 저장 -> CredentialAccept -> 서버 VC 인증 -> Challenge -> VP JWT 생성 -> Verifier 제출 전체 재검증

## 이후로 넘겨도 되는 항목

- VC 목록 전용 화면 고도화
- 상태 자동 갱신 주기 설계
- holder seed 백업/복구 정책
- 운영용 생체 인증/보안 정책
- core와 공유할 고정 JWT 테스트 벡터 확정

## 현재 테스트 성공 기준

- `SAVE_VC -> ok: true`
- `SUBMIT_TO_XRPL -> tesSUCCESS` 또는 `tecDUPLICATE`
- `CHECK_CREDENTIAL_STATUS -> active: true`, `accepted: true`
- `VERIFY_CREDENTIAL_WITH_SERVER -> ok: true`, `errors: []`
- `SIGN_MESSAGE -> ok: true`
- `SIGN_MESSAGE -> presentationJwt 존재`
- `SUBMIT_TO_VERIFIER -> ok: true`

## 참고

- 신규 VC/VP 기본 포맷은 expanded JSON이 아니라 compact JWT다.
- 실서버 VC JWT는 issuer가 서명한 원본이므로 앱에서 payload를 임의 수정하면 안 된다.
- 샘플 VC는 인증 통과용으로 사용하지 않는다.
- seed, private key, PEM 값은 문서나 로그 파일에 저장하지 않는다.
