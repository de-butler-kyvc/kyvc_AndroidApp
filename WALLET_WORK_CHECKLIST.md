# KYvC Android Holder Wallet SD-JWT 체크리스트

작성일: 2026-05-06  
기준: 신규 legal entity KYC wallet은 `dc+sd-jwt` + SD-JWT+KB 흐름을 기본으로 한다. 기존 `vc+jwt`/`vp+jwt`는 호환용으로 유지한다.

## 완료

- [x] XRPL holder seed와 holder authentication key 분리
- [x] holder seed를 Android Keystore wrapping key로 암호화 저장
- [x] 앱 잠금: PIN / 패턴 / 지문 로그인
- [x] 앱 잠금: 실패 횟수 5회 공용 집계 및 이메일 인증 요구
- [x] 앱 잠금: 인증 성공 후 30분 세션 유지
- [x] holder DID / DID Document 생성
- [x] XRPL `CredentialAccept` 제출 및 status active 조회
- [x] 기존 `vc+jwt`/`vp+jwt` 호환 흐름 유지
- [x] SD-JWT credential 원문 저장 경로 추가
- [x] issuer JWT payload를 status/accept용 내부 payload로 정규화
- [x] SD-JWT disclosure malformed/duplicate 1차 검증
- [x] verifier challenge의 `nonce`/`aud` 수신 및 로컬 재사용 방지
- [x] SD-JWT+KB presentation 생성
- [x] KB-JWT `sd_hash`, `nonce`, `aud`, `iat` 생성 및 ES256K 서명
- [x] verifier 제출 payload를 `kyvc-sd-jwt-presentation-v1` 포맷으로 분기
- [x] holder DIDSet URI/Data hash 등록 브릿지 추가
- [x] WebView 테스트 UI를 SD-JWT/nonce/KB-JWT 용어로 갱신
- [x] `Wallet Implimentation Guide.md`를 SD-JWT 기준으로 갱신

## 남은 필수 확인

| 우선순위 | 업무명 | 업무내용 | 완료 기준 |
| --- | --- | --- | --- |
| 1 | 실서버 SD-JWT 발급 확인 | `/issuer/credentials/kyc`에 `format: dc+sd-jwt`, `holder_key_id` 포함 요청 | `ISSUER_CREDENTIAL_RECEIVED -> ok: true`, `format: dc+sd-jwt`, `sdJwt` 존재 |
| 2 | Accept 후 status 확인 | 발급된 SD-JWT의 `credentialStatus.credentialType`으로 `CredentialAccept` 제출 | `CHECK_CREDENTIAL_STATUS -> active: true`, `accepted: true` |
| 3 | 서버 credential 검증 | `credential` 원문 SD-JWT를 `/verifier/credentials/verify`에 제출 | `VERIFY_CREDENTIAL_WITH_SERVER -> ok: true` |
| 4 | Holder DIDSet 검증 | holder DID Document hash를 XRPL DIDSet에 등록 | holder binding에서 `DID ledger entry not found`가 사라짐 |
| 5 | SD-JWT+KB 제출 | nonce/aud 발급 후 KB-JWT 생성 및 verifier 제출 | `SUBMIT_TO_VERIFIER -> ok: true` |
| 6 | 실패 케이스 확인 | 같은 nonce 재사용, disclosure 변조, Accept 전 제출 | 각각 expected failure가 뜨는지 확인 |

## 이후 고도화

- selective disclosure UI: verifier `presentationDefinition.requiredDisclosures` 기준으로 제출 항목 선택
- disclosure digest가 issuer payload `_sd`에 실제로 reference되는지 전체 경로 검증
- multipart attachment 제출: documentEvidence 요구 시 원본 PDF/image digest 검증
- SD-JWT/KB-JWT 고정 테스트 벡터 추가
- holder seed/auth key 백업 및 복구 정책 확정

## 성공 판단 기준

- 샘플 placeholder로 인증을 통과시키지 않는다.
- Android 로그에 holder seed, raw disclosure, 원본 문서 bytes를 남기지 않는다.
- `credentialType` 문자열은 issuer JWT payload 값을 그대로 사용하고 대소문자를 바꾸지 않는다.
- SD-JWT presentation은 `vp+jwt`가 아니라 `<issuer-jwt>~<selected-disclosures>~<kb-jwt>` 형식이다.
