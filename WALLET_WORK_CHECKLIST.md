# KYvC Android Holder Wallet SD-JWT 체크리스트

작성일: 2026-05-06  
기준: 신규 legal entity KYC wallet은 `dc+sd-jwt` + SD-JWT+KB 흐름을 기본으로 한다. 기존 `vc+jwt`/`vp+jwt`는 호환용으로 유지한다.

## Testnet 전환 체크

- [ ] Core base URL을 testnet 연동 서버 주소로 교체
- [ ] holder/issuer 계정 testnet funding 완료
- [ ] `CredentialCreate`, `CredentialAccept`, `DIDSet` testnet 반영 확인
- [ ] `CHECK_CREDENTIAL_STATUS`의 `active/accepted`를 testnet 기준으로 재검증
- [ ] `VERIFY_CREDENTIAL_WITH_SERVER`, `SUBMIT_TO_VERIFIER`를 testnet 기준으로 재검증

## 완료

- [x] XRPL holder seed와 holder authentication key 분리
- [x] holder seed를 Android Keystore wrapping key로 암호화 저장
- [x] holder seed import 기반 지갑 복구 1차 구현
- [x] holder seed export 1차 구현
- [ ] holder auth key 포함 백업 export / 완전 복구 (보안 정책상 웹 노출 보류)
- [x] XRP 잔액 / trust line 자산 조회 1차 구현
- [x] 입금 주소 / 주소 복사 / QR 미리보기 1차 구현
- [x] XRP 송금 1차 구현
- [x] XRP 송금 직전 네이티브 재인증 연결
- [x] 거래내역 조회 1차 구현
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
- [x] holder DID Document를 `signMessage`/VP 로그인 submit 응답에 포함
- [x] VP 로그인 QR native resolve/submit 연결
- [x] VP 제출 전 holder binding 진단 로그 추가(`vp.login.holder.binding`)
- [x] 증명서 상세 화면 로컬 credential 삭제 처리
- [x] 발급 확인 화면 XRP fee drops/XRP 단위 정규화
- [x] VP 제출 전 별도 credential picker 제거, 증명서 제출 화면의 발급기관 선택으로 credential 선택 통합
- [x] 같은 발급기관 credential 후보는 최신 1개만 제출 화면에 표시
- [x] QR 스캔 화면을 프레임 내부 카메라/외부 남색 불투명 배경으로 정리
- [x] VC 저장 시 `documentAttachments[].contentBase64`를 암호화 저장하고 manifest 메타를 credential과 연결
- [x] multipart VP 제출 시 `attachmentManifest` part와 `attachmentRef` 파일 part 전송
- [x] 증명서 제출 화면의 발급기관 선택명을 `GET /api/common/dids/{issuerDid}/institution` 기반 실제 기관명으로 표시
- [x] 증명서 상세/발급확인 화면의 발급기관명을 `credentialId` -> 로컬 credential `issuerDid` -> 기관 조회 API 순서로 보강
- [x] 발급기관명 UI에서 테스트 fallback 이름(`KYvC 인증기관`, `법원행정처`, `발급기관 N`) 제거
- [x] WebView 테스트 UI를 SD-JWT/nonce/KB-JWT 용어로 갱신
- [x] `Wallet Implimentation Guide.md`를 SD-JWT 기준으로 갱신

## 남은 필수 확인

| 우선순위 | 업무명 | 업무내용 | 완료 기준 |
| --- | --- | --- | --- |
| 1 | 실서버 SD-JWT 발급 확인 | `/issuer/credentials/kyc`에 `format: dc+sd-jwt`, `holder_key_id` 포함 요청 | `ISSUER_CREDENTIAL_RECEIVED -> ok: true`, `format: dc+sd-jwt`, `sdJwt` 존재 |
| 2 | Accept 후 status 확인 | 발급된 SD-JWT의 `credentialStatus.credentialType`으로 `CredentialAccept` 제출 | `CHECK_CREDENTIAL_STATUS -> active: true`, `accepted: true` |
| 3 | 서버 credential 검증 | `credential` 원문 SD-JWT를 `/verifier/credentials/verify`에 제출 | `VERIFY_CREDENTIAL_WITH_SERVER -> ok: true` |
| 4 | Holder DIDSet 검증 | holder DID Document hash를 XRPL DIDSet에 등록/재등록 | `didDocumentHashMatches=true` |
| 5 | SD-JWT holder binding 확인 | `cnf.kid`, KB-JWT `kid`, DID Document key id 비교 | 모두 `did:xrpl:1:{holderAccount}#holder-key-1`로 일치 |
| 6 | VP 로그인 QR 제출 | PC QR의 `VP_LOGIN_REQUEST`를 native가 resolve/submit | backend status가 VALID, PC polling 완료 |
| 7 | verifier 정책 확인 | VC `vct`와 verifier `acceptedVct` 비교 | `vct is not accepted` 오류 없음 |
| 8 | 제출 화면 선택 확인 | 발급기관 드롭다운 선택값이 submit `credentialId`로 사용되는지 확인 | 별도 credential picker 없이 선택한 발급기관 credential로 VP submit |
| 9 | 원본 문서 첨부 제출 | prepare 응답의 `documentAttachments` 저장 후 `with-attachments` multipart 제출 | `presentation`, `attachmentManifest`, attachmentRef 파일 part가 backend에 도착 |
| 10 | 실패 케이스 확인 | 같은 nonce 재사용, disclosure 변조, Accept 전 제출 | 각각 expected failure가 뜨는지 확인 |

## 이후 고도화

- selective disclosure UI: verifier `presentationDefinition.requiredDisclosures` 기준으로 제출 항목 선택
- disclosure digest가 issuer payload `_sd`에 실제로 reference되는지 전체 경로 검증
- multipart attachment 제출: documentEvidence 요구 시 원본 PDF/image digest 검증
- SD-JWT/KB-JWT 고정 테스트 벡터 추가
- 운영용 백업 UX, auth key 보관 정책, 원격 백업 금지 정책 확정
- DIDSet 등록 문서와 VP 제출 DID Document의 canonical hash 일치 자동 검증 UX

## 성공 판단 기준

- 샘플 placeholder로 인증을 통과시키지 않는다.
- Android 로그에 holder seed, raw disclosure, 원본 문서 bytes를 남기지 않는다.
- `credentialType` 문자열은 issuer JWT payload 값을 그대로 사용하고 대소문자를 바꾸지 않는다.
- SD-JWT presentation은 `vp+jwt`가 아니라 `<issuer-jwt>~<selected-disclosures>~<kb-jwt>` 형식이다.
- DID Document는 VP 제출 시점에 임의 alias를 추가하지 않는다. XRPL DIDSet Data hash와 같은 기본 문서를 제출한다.
- `cnf.kid`가 DID prefix를 중복 포함하면 제출 대상 VC가 아니므로 발급 로직 수정 후 재발급한다.
