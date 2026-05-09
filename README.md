# kyvc_AndroidApp

kyvc의 안드로이드 전용 앱 개발용 레포지토리입니다.

## 문서 우선순위

- 웹에서 Android 브리지를 호출하는 최신 규격은 [WEBVIEW_BRIDGE_SPEC.md](./WEBVIEW_BRIDGE_SPEC.md)를 기준으로 본다.
- 웹 구현 흐름/예외처리 빠른 참고는 [WEB_DEVELOPER_INTEGRATION_GUIDE.md](./WEB_DEVELOPER_INTEGRATION_GUIDE.md)를 본다.
- 브리지 함수 시그니처나 응답 형식이 바뀌면 위 문서를 먼저 같이 갱신한다.

## 문서 갱신 규칙

- 새로운 기능을 구현하면 `README.md`의 구현 상태와 사용 흐름을 같이 갱신한다.
- 웹 또는 서버가 앱 기능을 브리지로 호출해야 하면 [WEBVIEW_BRIDGE_SPEC.md](./WEBVIEW_BRIDGE_SPEC.md)에 아래 항목을 같이 추가한다.
  - 메서드명
  - 요청 JSON 형식
  - 응답 JSON 형식
  - 호출 순서 / 선행 조건
  - 실패 시 처리 방식
- 브리지 요청/응답이 바뀌었는데 문서가 같이 안 바뀐 상태는 불완전 구현으로 본다.
- 웹/서버 브랜치 연동이 필요한 기능은 구현 후 README에 “외부 연동 필요 사항”으로 남긴다.

## 외부 연동 원칙

- 웹은 Android 브리지만 호출한다.
- 인증, 민감정보 처리, XRPL 서명, 백엔드 API 호출은 네이티브가 담당한다.
- 웹/서버에서 앱 기능을 움직여야 하는 경우에는 브리지 메서드로 노출하고, 직접 앱 내부 저장소나 키에 접근하지 않는다.
- 외부에서 호출 가능한 앱 기능은 반드시 호출 규격 문서와 함께 관리한다.

## 네트워크 전환 공지 (Devnet -> Testnet)

현재 프로젝트는 XRPL 네트워크 기준을 **Devnet에서 Testnet으로 전환**한다.

- 문서/테스트/운영 체크 기준 네트워크: `testnet`
- Core 서버도 testnet ledger 기준 응답을 반환해야 한다.
- Devnet 전용 성공 로그/주소/가정은 더 이상 기준으로 사용하지 않는다.

전환 시 필수 점검:
1. issuer/holder 계정이 testnet에서 funded/active인지 확인
2. `CredentialCreate`, `CredentialAccept`, `DIDSet`이 testnet ledger에 반영되는지 확인
3. `checkCredentialStatus`의 `active/accepted`를 testnet 기준으로 검증
4. verifier/credential verify API의 status 조회가 testnet을 바라보는지 확인

UI/브리지 기본값 규칙:
- `coreBaseUrl`은 필수 입력값이다. 미입력 시 issuer/verifier 계열 요청은 실패한다.
- `aud/domain` 미입력 시 `coreBaseUrl`을 기본값으로 사용한다.
- verifier endpoint 기본값은 `https://<core-testnet-base-url>/verifier/presentations/verify` 형태다.

## 🛠 기술 스택
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Design System**: Material 3
- **Min SDK**: 24
- **Target SDK**: 36

## 용어 정리
- **Holder Wallet**: VC를 보관하고, XRPL에서 `CredentialAccept`를 제출하는 지갑 앱입니다.
- **Holder Account**: XRPL 상의 holder 계정입니다. 이 계정으로 트랜잭션 서명과 상태 확인을 합니다.
- **DID**: 분산 신원 식별자입니다. 이 프로젝트에서는 `did:xrpl:1:{account}` 형식을 사용합니다.
- **VC(Verifiable Credential)**: 발급자가 만든 증명서 원문입니다. 앱은 이를 저장하고 검증합니다.
- **VP(Verifiable Presentation)**: holder가 verifier에게 제출하는 증명 묶음입니다. VC와 proof를 함께 담습니다.
- **Challenge**: verifier가 VP 제출 전에 주는 일회성 값입니다. 재사용하면 안 됩니다.
- **CredentialAccept**: holder가 XRPL ledger에서 VC를 수락하는 트랜잭션입니다.
- **CredentialStatus**: XRPL ledger에서 VC의 active/inactive 상태를 나타내는 정보입니다.
- **CredentialType**: XRPL ledger entry를 식별하는 타입 값입니다. VC와 XRPL status 조회에서 함께 사용합니다.
- **vcCoreHash**: VC 본문을 canonical JSON으로 정규화한 뒤 계산한 핵심 해시입니다.
- **Holder DID Document**: verifier가 VP proof 검증에 쓰는 holder 공개키 문서입니다.
- **Proof**: VC 또는 VP가 서명되었음을 증명하는 메타데이터입니다.
- **Canonical JSON**: 키 순서를 고정하고 공백을 제거한 표준화 JSON입니다. 해시와 서명 입력에 사용합니다.
- **SD-JWT**: 선택 공개가 가능한 JWT credential입니다. 현재 신규 legal entity KYC wallet의 기본 credential 포맷은 `dc+sd-jwt`입니다.
- **Disclosure**: SD-JWT 안에서 선택적으로 공개할 수 있는 claim 조각입니다. verifier에는 필요한 disclosure만 제출합니다.
- **KB-JWT**: Key Binding JWT입니다. holder authentication key로 `nonce`, `aud`, `sd_hash`를 서명해 SD-JWT presentation을 holder에게 묶습니다.
- **Nonce / Aud**: SD-JWT+KB에서 기존 challenge/domain을 대체하는 verifier 일회성 값과 audience입니다.

## 최신 Holder Wallet 계약

- 신규 기본 포맷은 `dc+sd-jwt` credential과 SD-JWT+KB presentation이다.
- 기존 `vc+jwt`/`vp+jwt`와 expanded JSON proof는 compatibility mode로 남겨둔다.
- Android holder 앱은 issuer seed/private key/PEM을 다루지 않는다.
- 앱은 XRPL holder seed와 holder authentication key를 분리한다. XRPL seed는 `CredentialAccept` 전용이고, holder auth key는 KB-JWT 서명 전용이다.
- SD-JWT credential 원문은 전체 disclosure를 포함하므로 전체 KYC 원문과 같은 민감도로 암호화 저장한다.
- verifier 제출은 `vp+jwt`가 아니라 `<issuer-jwt>~<selected-disclosures>~<kb-jwt>` 형식의 `sdJwtKb`를 사용한다.
- verifier challenge 요청은 `aud`를 보내고, 응답의 `nonce`를 KB-JWT payload에 넣는다.
- holder DID binding 검증을 통과하려면 holder DID Document 원문을 verifier 요청 `did_documents`에 포함하고, 같은 DID Document의 canonical hash를 XRPL DIDSet `Data`에 등록해야 한다.
- 앱 잠금은 PIN/패턴/지문 공용 실패 횟수 5회를 공유하며, 5회 초과 시 이메일 인증 완료 전까지 모든 인증 수단을 막는다.
- 인증 성공 시 30분 세션을 시작하고, 세션 만료 후 앱 복귀 또는 재진입 시 다시 인증한다.

## 📌 주요 작업 내용
### Wallet 및 Bridge 기능 구현 (2025-05-22)
- **Infrastructure**: Room DB, XRPL4J(6.0.0), JCS(Json Canonicalization), Kotlin Serialization 설정 완료
- **Wallet Core**: XRPL 계정 생성 및 `CredentialAccept` 트랜잭션 제출 기능 기초 구현
- **Storage**: `CredentialEntity`를 통한 VC 로컬 저장소 구축
- **Bridge**: 웹에서 안드로이드 기능을 호출할 수 있는 `Android` 브릿지 객체 등록
- **XRPL Submit**: `submitToXRPL` 브릿지에서 holder seed, issuer account, credential type을 검증한 뒤 XRPL testnet `CredentialAccept`를 제출하고 tx hash를 Room DB에 반영
- **Issuer CredentialCreate**: `submitCredentialCreate` 브릿지에서 issuer seed로 XRPL testnet `CredentialCreate`를 제출해 ledger credential entry를 생성
- **WebView Callback**: VC 저장 및 XRPL 제출 결과를 `window.onAndroidResult`로 반환하도록 테스트 페이지 연동
- **UI**: 지민님이 제공한 고도화된 브릿지 테스트 페이지(`index.html`) 적용
- **Build Fix**: AGP 9.2.0의 내장 Kotlin 지원과 KSP 간의 충돌 해결 (`android.disallowKotlinSourceSets=false`)
- **Runtime Fix**: `xrpl4j-keypairs/crypto-bouncycastle` 3.x와 `xrpl4j-core` 6.x 혼용으로 발생한 `NoClassDefFoundError`를 제거하기 위해 XRPL 런타임 의존성을 6.0.0 core/client 중심으로 정리
- **Wallet State**: 다중 지갑 관리 시스템 도입. 각 지갑은 별도의 시드와 인증 키를 가지며, Android Keystore AES-GCM으로 암호화 저장.
- **HD Wallet (BIP-39)**: 12개 단어 비밀 복구 구문(Mnemonic Phrase) 생성 및 복구 지원. 단일 비밀문구에서 여러 계정 파생(BIP-44 스타일) 가능.
- **Account Management**: 계정별 커스텀 이름 설정, 계정 삭제(목록 제거), Standalone 계정의 비밀문구 백업 생성 지원.
- **Auth Key Separation**: XRPL account key는 `CredentialAccept` 전용으로 유지하고, VP 서명용 holder authentication key를 별도 secp256k1 key로 생성/암호화 저장하도록 분리.
- **Session Sync**: 로그아웃 시 웹뷰 종료 및 잠금 화면 전환. 네이티브 인증 성공 시 30분 세션 자동 갱신.
- **VP Signing**: `signMessage` 브릿지에서 challenge/domain/VC를 받아 JCS 기반 `DataIntegrityProof` VP를 생성하고 holder DID Document와 함께 WebView 콜백으로 반환
- **QR Bridge**: `scanQRCode` 브릿지가 QR 요청/데이터를 `VC_ISSUE`, `VP_REQUEST`, `LOGIN_REQUEST`로 분류해 `SCAN_QR_CODE` 콜백으로 전달하도록 연결
- **VC Validation**: VC 저장/서명/상태조회 전에 `credentialSubject.id`, `credentialStatus.subject`, `validFrom/validUntil`을 검증하고 holder wallet과 맞지 않으면 차단
- **XRPL Status**: credential ledger entry index를 계산해 `ledger_entry`로 status를 조회하고 active 여부를 `CHECK_CREDENTIAL_STATUS` 콜백으로 반환
- **Verifier Submit**: `submitPresentationToVerifier` 브릿지에서 signed VP, holder DID Document, policy, XRPL status 요구조건을 묶어 `/verifier/presentations/verify` 요청을 POST
- **VC Verify**: 신규 JWT 흐름에서는 서버 verifier의 `VERIFY_CREDENTIAL_WITH_SERVER`를 최종 VC 검증 기준으로 사용
- **Issuer Proof Verify**: issuer DID Document가 포함된 VC에 대해 secp256k1/ECDSA proof 검증 지원
- **Credential List**: 저장된 VC 목록을 불러오고, XRPL 상태를 일괄 재조회해 저장 상태를 동기화
- **Challenge Guard**: verifier challenge를 로컬에 등록하고 만료/중복 사용을 차단
- **JWT Transition**: 신규 wallet 가이드에 맞춰 issuer 응답의 compact `vc+jwt` 수신/파싱/저장과 Enveloped VC 기반 `vp+jwt` 생성 1차 지원 추가
- **SD-JWT Transition**: `dc+sd-jwt` credential 파싱/저장, disclosure 1차 검증, nonce/aud challenge, KB-JWT 생성, SD-JWT+KB verifier 제출 분기 추가
- **Holder DIDSet**: holder DID Document 원문은 verifier 요청에 포함하고, JCS canonical DID Document hash(`1220` + SHA-256)를 XRPL DIDSet `Data`로 등록하는 브릿지 추가
- **Verifier Error Handling**: verifier 실패 응답을 challenge, signature, XRPL status, policy, DID Document 오류로 분류해 WebView에 `errorCode/errorTitle/errorHint`로 반환
- **JWT Regression Test**: `VpSignerTest`에서 ES256K compact JWS 생성, base64url no-padding segment, 64-byte raw `R||S` signature, secp256k1 검증을 확인

## 🚀 향후 작업 계획
- [x] **MCP 연동 환경 구성**: AI 어시스턴트 협업을 위한 `mcp-context.md` 및 환경 설정 완료
- [x] 베이스 아키텍처 설정 (Clean Architecture + MVVM)
- [x] **WebView Bridge 구현**: Web-to-Native 통신 인터페이스 추가 및 테스트 페이지 연동 완료
- [x] **Blockchain Wallet 연동**: 경량 지갑 기반 구축 (계정 생성, XRPL 통신 기초)
- [x] **CredentialAccept 제출 연동**: WebView 요청 기반 XRPL testnet 제출, holder account 검증, tx hash 저장
- [x] **Holder 지갑 상태 저장**: 다중 계정 지원, seed/mnemonic 암호화 저장, 계정별 이름 및 인덱스 관리
- [x] **VP 생성 기초 구현**: 저장된 holder auth key로 secp256k1 JCS proof 생성 및 WebView 콜백 연결
- [x] **HD Wallet 전환**: 12개 단어 생성, 복구 구문 기반 계정 파생, Standalone 계정 업그레이드
- [x] **세션 및 보안 강화**: 로그아웃 UI 연동, 인증 성공 시 세션 자동 연장 로직 추가
- [x] **QR 브릿지 연결**: QR 요청/결과를 WebView 콜백으로 전달하는 native bridge 추가
- [x] **VC(Verifiable Credentials) 인증 시스템**: canonical hash, proof 구조, XRPL active 상태 검증
- [x] **Verifier 제출 연동**: `/verifier/presentations/verify` 요청 구성 및 challenge 사용 상태 처리
- [x] **저장 VC 목록/상태 갱신**: 로컬 credential 목록 조회와 XRPL 상태 일괄 동기화
- [x] **Challenge Guard**: verifier challenge 등록, 만료 확인, 중복 제출 차단
- [x] **Issuer proof 검증 지원**: issuer DID Document가 있는 VC의 proof 서명 검증
- [x] **기본 아키텍처 분리**: AppContainer, CredentialRepository, MainViewModel로 데이터/진입점 구조화
- [x] **JWT 호환 포맷 전환**: `vc+jwt` 저장, `vp+jwt` 제출, ES256K 검증 경로 유지
- [x] **SD-JWT 기본 포맷 전환 1차 구현**: `dc+sd-jwt` 저장, KB-JWT 생성, SD-JWT+KB 제출 payload 분기
- [x] **Holder DIDSet 등록**: holder 계정으로 DIDSet URI/Data hash 등록 브릿지 추가
- [ ] **SD-JWT 실서버 전체 재검증**: 실서버에서 `dc+sd-jwt` 발급부터 verifier 제출까지 재확인

## 여기서 더 구현할 수 있는 것
- **Issuer proof 검증**
  - VC 저장 시 issuer 공개키로 VC proof를 직접 검증
  - 현재는 issuer DID Document가 포함된 VC에 한해 검증한다
- **Challenge 보관/만료 관리**
  - verifier challenge를 로컬에 저장하고 만료 전에만 VP 제출 허용
- **QR 요청 파서 강화**
  - 1차 초안은 구현 완료했다. 실제 운영 QR 스키마가 확정되면 필드명을 고정하고 자동 실행 범위를 정리한다
- **백업/복구**
  - holder seed/mnemonic export 및 import 1차 구현 완료
  - seed/mnemonic 외 민감 키(auth key/private key) export UI는 제거
- **VC 목록 화면**
  - 저장된 VC를 리스트로 보여주고, active/expired/revoked 상태를 한눈에 확인
- **상태 자동 갱신**
  - 앱 시작 시 또는 주기적으로 XRPL status를 재조회해서 저장된 VC 상태를 동기화
- **실제 verifier 서버 연동 안정화**
  - 현재는 검증 요청 포맷을 맞추는 수준이므로, 운영용 endpoint/응답 스키마에 맞춘 정교화 필요
- **오류 메시지 세분화**
  - verifier 제출/실제 VC 인증 응답은 challenge, signature, XRPL status, policy, DID Document 계열로 분리 표시한다
- **테스트 벡터 추가**
  - VC hash, VP proof, XRPL status 계산 결과를 고정 테스트로 만들어 회귀 방지

## 현재 구현 상태 요약
### 앱 잠금 / 인증
- PIN 로그인
- 패턴 로그인(탭형 3x3)
- 지문 로그인 활성화 및 로그인
- PIN/패턴/지문 공용 실패 횟수 집계
- 5회 실패 시 이메일 인증 필요 상태 전환
- 이메일 인증 완료 후 실패 횟수 초기화 브리지 (`completeEmailVerification`)
- 인증 성공 후 30분 세션 유지, 만료 후 재인증

### 앱 내부에서 성공한 것
- holder 지갑 생성/조회
- 다중 지갑 목록 조회 / 전환 / 계정 이름 변경
- 기존 계정의 비밀문구 백업 생성(`upgradeToMnemonic`)
- 비밀문구 기반 계정 파생 추가(`deriveNextAccount`)
- holder seed 열람 / 복사 구현(활성 세션 필요)
- 로그아웃 구현(세션 종료)
- holder seed 기반 지갑 복구 1차 구현
- XRP 잔액 / trust line 자산 조회 1차 구현
- 입금 주소 / 주소 복사 / QR 미리보기 1차 구현
- XRP 송금 1차 구현(활성 세션 + 송금 직전 네이티브 재인증 필요, XRP 소수 입력 / drops 입력 지원)
- 거래내역 조회 1차 구현(account_tx 기반 최근 내역)
- holder account / DID / DID Document 조회
- 복구 성공 후 Holder DIDSet 재등록 테스트 UI 연결
- VC 로컬 저장
- issuer / holder / credentialType 기본 정합성 검증
- issuer-side `CredentialCreate` 제출
- holder-side `CredentialAccept` 제출
- XRPL credential status 조회
- 저장 VC 목록 조회와 상태 일괄 갱신
- verifier challenge 등록, 만료 관리, 중복 사용 차단
- VP 생성 및 서명
- verifier 제출 요청 POST
- QR 스캔 브릿지
- WebView 버튼 흐름 정리
- 계정 삭제 버튼 비활성화(운영 정책 확정 전)
- dev-core 실서버 issuer VC 요청
- dev-core 실서버 VC 인증
- dev-core verifier challenge 수신
- 실제 VC 기반 VP 생성 및 verifier 제출

### 실제 dev-core 연동 검증 완료
- 실제 issuer VC 요청 후 앱 저장 성공
- 같은 VC의 `credentialStatus.issuer`, `credentialStatus.subject`, `credentialStatus.credentialType` 기준으로 XRPL testnet `CredentialAccept` 제출 성공
- XRPL 상태 조회에서 `credentialEntryFound: true`, `credentialAccepted: true` 확인
- `/verifier/credentials/verify` 응답에서 `ok: true`, `errors: []`, `policyErrors: []` 확인
- verifier challenge 기반 holder VP 생성 및 제출 성공

### 신규 Android wallet 가이드 반영 중
- 신규 기본 포맷은 expanded JSON이나 `vc+jwt/vp+jwt`가 아니라 `dc+sd-jwt`와 SD-JWT+KB다.
- 앱은 issuer 응답의 `credential` 문자열을 SD-JWT로 파싱하고, issuer JWT payload를 기존 status/accept flow에 연결하는 정규화 payload로 변환한다.
- `SIGN_MESSAGE`는 SD-JWT가 있으면 holder-signed `vp+jwt` 대신 KB-JWT를 생성하고 `sdJwtKb`를 반환한다.
- XRPL account key와 holder authentication key는 분리했다. DID Document의 `holder-key-1`은 KB-JWT 서명용 auth key 공개키를 사용한다.
- verifier challenge는 `nonce/aud`를 우선 사용하고, 기존 `challenge/domain`은 compatibility alias로 처리한다.
- 남은 작업은 required disclosure 선택 UI, disclosure reference 전체 검증, multipart attachment, SD-JWT 고정 테스트 벡터다.

### 샘플 데이터 사용 시 주의할 것
- VC 인증의 canonical hash 일치
  - `credentialStatus.vcCoreHash`가 `sample-vc-core-hash`인 샘플은 인증에서 실패한다.
  - 실서버 issuer가 발급한 VC에는 `canonicalHash 반영 후 저장`을 사용하지 않는다. VC 본문을 바꾸면 issuer proof 검증이 깨질 수 있다.
- issuer proof 검증
  - issuer DID Document가 없는 샘플 VC는 proof 서명 검증을 못 한다
- XRPL status active 판정
  - `CredentialCreate`와 `CredentialAccept`가 실제 ledger에 반영된 뒤에만 active로 보인다

### 외부에서 준비되어야 하는 것
- 실제 XRPL testnet classic address가 들어간 issuer 계좌
- issuer seed
- 실제 issuer가 발급한 VC 원문
- 실제 `credentialStatus.vcCoreHash`
- issuer DID Document 또는 issuer 공개키
- verifier가 발급한 실제 challenge
- verifier endpoint의 실제 응답 스키마
- holder / issuer 둘 다 testnet에서 funded 상태인 계좌

### 현재 테스트 판단 기준
- `SUBMIT_CREDENTIAL_CREATE -> tesSUCCESS`
- `SUBMIT_TO_XRPL -> tesSUCCESS`
- `SIGN_MESSAGE -> ok: true`
- `REGISTER_VERIFIER_CHALLENGE -> ok: true`
- `SAVE_VC -> ok: true`
- `GET_WALLET_INFO -> ok: true`
- `VERIFY_CREDENTIAL_WITH_SERVER -> ok: true`, `errors: []`
- `SUBMIT_TO_VERIFIER -> ok: true`
- JWT VC의 최종 판정은 로컬 expanded JSON 검증이 아니라 실서버 verifier 응답인 `VERIFY_CREDENTIAL_WITH_SERVER -> ok: true`, `errors: []`를 우선한다
- 샘플 VC는 인증 통과용 데이터가 아니다. `sample-vc-core-hash`, `sample-signature`, `rIssuerAccountForTestnet`, `rHolderAccountForTestnet` 값으로 실제 verifier/XRPL 인증을 성공 처리하지 않는다.

## WebView Bridge 요청 형식

## 민감정보 노출 정책

| 항목 | 웹 노출 | 비고 |
| --- | --- | --- |
| holder seed | 허용(제한적) | 활성 인증 세션에서만 1회 응답 |
| holder mnemonic(12단어) | 허용(제한적) | 활성 인증 세션에서만 응답 |
| holder auth key/private key | 비허용 | 브리지 export 금지 |
| wallet backup blob | 비허용 | 브리지 export 금지 |

## 웹팀 최소 호출 시퀀스

### 1) 로그인/세션

```text
getAuthStatus
-> requestNativeAuth(reason=wallet-login)
-> (emailVerificationRequired=true면) 웹 이메일 인증
-> completeEmailVerification
-> requestNativeAuth 재시도
```

### 2) 지갑 복구

```text
restoreWallet(seed 또는 mnemonic)
-> holderDidSetRegistrationRequired 확인
-> true면 submitHolderDidSet 호출
-> getWalletInfo로 반영 확인
```

### 3) SD-JWT 발급/수락/검증

```text
requestIssuerCredential
-> saveVC
-> submitToXRPL (CredentialAccept)
-> checkCredentialStatus (active=true, accepted=true 확인)
-> verifyCredentialWithServer
```

### 4) SD-JWT+KB 제출

```text
requestVerifierChallenge (nonce/aud 수신)
-> signMessage (sdJwtKb 생성)
-> submitPresentationToVerifier
```

## 사용 순서
실서버 issuer/verifier를 붙여 테스트할 때는 아래 `실서버 테스트` 섹션의 순서를 우선 사용한다. `XRPL 발급(디버그)` 카드는 issuer seed를 직접 넣는 개발용 보조 흐름이다.

`createWallet`은 Android 내부에서 secp256k1 holder seed를 만들고 Keystore 보호 AES-GCM 키로 암호화 저장한다. `getWalletInfo`는 seed를 반환하지 않고 holder account, public key, DID만 반환한다.

`exportWalletSeed`는 활성 인증 세션이 있을 때만 holder seed를 1회 응답으로 반환한다. 현재 웹 테스트 흐름에서는 이를 `Seed 보기`로 사용하고, 필요 시 네이티브 클립보드 복사만 지원한다. 이 값은 민감정보이므로 웹 로그, 분석 SDK, 원격 저장소에 남기면 안 된다.

`restoreWallet`은 seed 또는 12단어 mnemonic 기반 복구를 웹에 노출한다. 복구 후에는 holder auth key가 새로 생성되므로 `holderDidSetRegistrationRequired: true`가 반환되고, 이 경우 `submitHolderDidSet`를 다시 실행해야 한다.

`deriveNextAccount`는 현재 활성 계정이 mnemonic 백업 상태(`hasMnemonic=true`)일 때만 실행한다. standalone 계정이면 먼저 `upgradeToMnemonic`을 실행해야 한다.

`removeWallet` 브리지는 존재하지만 현재 운영 정책상 비활성화다. WebView 테스트 UI에서도 삭제 버튼은 비활성화되어 있으며 계정 삭제 플로우는 사용하지 않는다.

`submitToXRPL`은 아래 JSON을 받는다. seed는 WebView에서 전달하지 않는다. `credentialId`가 있으면 저장된 VC 메타데이터를 보강해서 사용하며, 요청의 `issuerAccount`, `holderAccount`, `credentialType`이 있으면 우선한다.

```json
{
  "credentialId": "vc-kyc-001",
  "issuerAccount": "rIssuer...",
  "holderAccount": "rHolder...",
  "credentialType": "56435F..."
}
```

Android 내부에 저장된 holder seed에서 복원한 account와 VC/request의 `holderAccount`가 다르면 제출하지 않는다. 성공/실패 결과는 `window.onAndroidResult` 콜백의 `SUBMIT_TO_XRPL` action으로 전달된다.

`submitCredentialCreate`는 issuer가 ledger에 `CredentialCreate`를 올릴 때 쓴다. 이 요청은 `issuerSeed`, `subjectAccount`, `credentialType`을 받으며, issuer seed는 앱에 저장하지 않는다. `vcJson`이 있으면 거기서 subject/type을 보강해서 사용한다. 성공/실패 결과는 `SUBMIT_CREDENTIAL_CREATE` action으로 반환된다.

`CredentialCreate`가 `tecNO_TARGET`로 끝나면 subject account가 XRPL testnet에서 아직 활성화되지 않았다는 뜻이다. 이 경우 holder wallet을 faucet으로 충전해 ledger에 실제 계좌를 만든 뒤 다시 시도해야 한다.

실제 testnet 값을 넣을 때는 `issuerAccount`에 XRPL testnet의 실제 classic address를 넣고, VC JSON의 `issuer` / `credentialStatus.issuer`도 같은 계정으로 맞춘다. DID는 `did:xrpl:1:{account}` 형태를 쓰되, `account` 부분은 반드시 실제 classic address여야 한다. `holderAccount`는 앱의 `getWalletInfo` 결과로 자동 채워지는 holder account를 쓰면 된다.

예를 들어 placeholder인 `rIssuerAccountForTestnet` 같은 문자열은 XRPL 주소가 아니므로 status 조회, verifier 제출, XRPL 제출 모두 실패한다. 샘플 VC를 쓸 때는 issuer 관련 필드를 실계정으로 교체하고, VC 저장 후 다시 status 조회를 해야 한다.

샘플 VC에서는 웹 화면의 `issuerAccount` 입력값으로 placeholder issuer를 교체할 수 있다. 단, 실서버가 발급한 `vc+jwt`는 issuer가 서명한 원문이므로 화면 입력값으로 `issuer`나 `credentialStatus.issuer`를 덮어쓰지 않는다. VC JWT payload의 issuer 값을 기준으로 입력칸을 맞추고, payload를 임의 수정하지 않는다.

`scanQRCode`는 발급자/검증자가 제시하는 요청 QR을 읽는 용도다. 현재 구현은 QR에 담긴 요청 JSON 또는 텍스트를 읽어 `requestId`, `purpose`, `endpoint`, `expiresInSec`, `qrData`를 추출하고, 스캔 결과를 `SCAN_QR_CODE` 콜백으로 WebView에 반환한다. 즉, 임의의 정적 이미지가 아니라 VP 요청, VC 발급 요청, 로그인 요청처럼 실제 플로우를 시작하는 QR을 대상으로 한다.

`checkCredentialStatus`는 VC의 issuer/holder/credentialType으로 XRPL `Credential` ledger entry의 index를 계산해 현재 validated ledger에서 조회한다. 응답의 `Flags`에 accepted bit가 켜져 있고 expiration이 유효하면 active로 판정한다.

예전 expanded JSON용 `verifyVC`와 `canonicalHash 반영` 흐름은 신규 기본 플로우에서 사용하지 않는다. `dc+sd-jwt`는 issuer JWT signature와 disclosure digest가 proof 역할을 하므로 expanded JSON의 `proof`가 없어도 정상이다. 실제 credential 검증은 `실제 Credential 인증 요청`으로 core verifier에 맡긴다.

`submitPresentationToVerifier`는 SD-JWT 흐름에서는 `signMessage`로 생성한 `sdJwtKb`와 holder DID Document를 verifier 검증 요청으로 전송한다. 요청에는 `format: kyvc-sd-jwt-presentation-v1`, `presentation.sdJwtKb`, `did_documents`, `require_status`, `status_mode`를 포함하며, Android 쪽에서 먼저 holder DID/subject/issuer/credentialType과 XRPL status active 여부를 다시 확인한 뒤 POST를 보낸다. WebView는 `SUBMIT_TO_VERIFIER` 콜백으로 verifier 응답을 받는다.

`listCredentials`는 앱에 저장된 VC를 목록으로 보여주고, `refreshAllCredentialStatuses`는 저장된 각 VC의 XRPL status를 다시 조회해 로컬 상태를 갱신한다. `registerVerifierChallenge`는 challenge를 로컬에 저장하고 만료 시간을 기록한다. `submitPresentationToVerifier`는 proof 안의 `challenge`를 사용해 동일 challenge 재제출을 막는다.

브리지 최신 호출 형식은 [WEBVIEW_BRIDGE_SPEC.md](./WEBVIEW_BRIDGE_SPEC.md)를 기준으로 본다. 웹/서버에서 앱 기능을 움직여야 하면 README보다 위 문서의 메서드별 요청/응답/호출 흐름을 우선 사용한다.

## 실서버 테스트
가이드의 실제 issuer/verifier 서버를 붙여 테스트할 때는 앱의 `실서버 issuer / verifier` 카드를 먼저 쓴다.

### 입력값
- `Core Base URL`: `https://<core-testnet-base-url>`
- `KYC Level`: 서버 정책에 맞는 값
- `Jurisdiction`: 서버 정책에 맞는 값
- PEM, issuer seed, issuer account는 실서버 카드에서 입력하지 않는다. issuer 키와 DID 등록은 서버 내부에서 관리한다.
- VC 인증 단계에서 issuer address나 issuer secret을 verifier로 보내지 않는다. verifier는 VC 안의 `issuer` DID와 서버에 등록된 DID Document로 issuer proof를 검증한다.

### 동작
- `실제 SD-JWT 요청`은 `POST /issuer/credentials/kyc` 를 `format: dc+sd-jwt`, `holder_key_id: holder-key-1`와 함께 호출한다.
- `실제 Nonce 요청`은 `POST /verifier/presentations/challenges` 를 `aud`와 함께 호출한다.
- `실제 Credential 인증 요청`은 현재 SD-JWT credential 원문을 `POST /verifier/credentials/verify` 로 보낸다.
- issuer 응답이 오면 앱이 `VC 저장 / 상태 확인` 카드의 VC JSON을 자동으로 채우고 `VC 저장`을 다시 실행한다.
- verifier challenge가 오면 앱이 `Challenge` 입력칸을 채우고 로컬 challenge 저장도 같이 한다.

### 추천 순서
1. `Bridge 확인`
2. `지갑 생성/조회`
3. `Holder DIDSet 등록`
4. `실서버 issuer / verifier` 카드에서 `실제 SD-JWT 요청`
5. `VC 저장 / 상태 확인` 카드에서 `VC 저장 호출`
6. `CredentialAccept 제출`
7. `VC 저장 / 상태 확인` 카드에서 `XRPL 상태 조회` 결과 `active: true`, `accepted: true` 확인
8. `실서버 issuer / verifier` 카드에서 `실제 Credential 인증 요청`
9. `실서버 issuer / verifier` 카드에서 `실제 Nonce 요청`
10. `Key 서명 / VP` 카드에서 `KB-JWT 생성 호출`
11. `Verifier 제출 호출`

### 참고
- 서버가 HTTPS를 안 열고 있으면 `Core Base URL`에 `http://...` 전체 주소를 직접 넣는다.
- 서버가 `issuer_private_key_pem` 누락으로 422를 반환하면 dev-core 배포가 아직 PEM optional 계약으로 갱신되지 않은 상태다.
- 서버가 `issuer private key PEM file could not be read: ./.local-secrets/issuer-key.pem` 또는 `ISSUER_KEY_NOT_CONFIGURED`를 반환하면 Android 입력값 문제가 아니다. `/issuer/credentials/kyc`가 스키마상 PEM optional이어도 현재 core(testnet) 서버가 내부 issuer key 파일을 읽도록 설정되어 있고, 서버에 해당 키가 없어서 발급이 거부된 상태다. Holder 앱은 issuer PEM/secret을 보내지 않는 방향이 맞으므로 core 서버에 issuer key 또는 issuer 운영 설정을 등록해야 한다.
- `ISSUER_REQUEST_TIMEOUT` 또는 `VERIFIER_REQUEST_TIMEOUT`은 앱 검증 로직 실패가 아니라 HTTP 응답 대기 시간이 초과된 상태다. 실제 VC 발급/검증은 core 서버 내부에서 XRPL testnet 조회와 트랜잭션 처리를 기다릴 수 있으므로 네트워크/VPN, core 서버 부하, XRPL testnet 응답 지연을 먼저 확인한다. 앱의 서버 호출 timeout은 기본 10초에서 connect 20초, read 60초, write 30초, call 75초로 늘려두었다.
- PEM 없이 실제 인증만 확인하려면 이미 발급된 VC를 `VC 저장 / 검증` 카드에 넣고 `실제 VC 인증 요청`을 실행한다.
- 실서버 verifier가 `DID Document not found`를 반환하면 issuer DID가 core 서버에 등록되어 있지 않은 상태다. 앱은 VC의 `issuer` DID와 `proof.verificationMethod` DID 양쪽을 기준으로 `/dids/{account}/diddoc.json`를 조회해 verifier 요청에 포함하려고 시도한다.
- VC의 `issuer`가 가리키는 계정과 `proof.verificationMethod`가 가리키는 계정이 다르면 verifier가 어느 DID를 기준으로 proof를 검증하는지 서버 계약과 맞춰야 한다. 서버에도 두 DID Document가 모두 등록되어 있거나, VC 발급 서버가 같은 issuer DID로 VC와 proof를 만들어야 한다.
- `XRPL Credential status is not active`는 해당 VC의 XRPL Credential entry가 아직 holder에게 accepted 상태가 아니라는 뜻이다. issuer 발급 후 같은 VC의 `credentialStatus.issuer`, `credentialStatus.subject`, `credentialStatus.credentialType`으로 `CredentialAccept 제출`을 먼저 실행하고, `XRPL 상태 조회`에서 `active: true`, `accepted: true`가 나온 뒤 verifier 제출을 진행한다.
- `DID ledger entry not found for did:xrpl:1:{holder}`는 holder DIDSet이 아직 XRPL에 없다는 뜻이다. `Holder DIDSet 등록`을 먼저 실행한 뒤 새 nonce로 KB-JWT를 다시 생성해야 한다.
- `DID Document hash mismatch`는 verifier 요청의 `did_documents` 원문과 XRPL DIDSet `Data` hash가 다르다는 뜻이다. 지갑을 새로 만들거나 holder auth key가 바뀐 뒤에는 DIDSet을 다시 등록해야 한다.
- `found: true`, `flags: 0`, `accepted: false`는 조회 실패가 아니라 issuer가 `CredentialCreate`만 완료한 상태다. 이때는 `CredentialAccept 제출`을 누른 뒤 다시 `XRPL 상태 조회`를 실행한다.
- VP 제출 로그에서 `presentation.verifiableCredential[0]`와 요청의 `vcJson`이 서로 다른 VC라면 이전 VP가 남아 있는 상태다. 현재 UI는 VC가 바뀌면 기존 VP를 폐기하고, 제출 전에도 현재 VC와 VP 내부 VC가 다르면 제출을 차단한다. 이 경우 `실제 Challenge 요청` 후 현재 VC로 `VP 생성 호출`을 다시 실행한다.
- issuer secret은 `VC 인증` 요청에 필요한 값이 아니다. 필요하다면 서버의 issuer DID 등록 또는 VC 발급 API 쪽에서만 사용된다.
- 앱 잠금은 인증 성공 후 30분 동안 유지된다. 세션이 만료되면 앱 복귀/재진입 시 다시 인증해야 한다.
- `rIssuerAccountForTestnet`가 들어간 샘플 VC는 저장, 상태 조회, VP 생성, 서버 인증 모두 차단된다. 실제 테스트에는 issuer가 `did:xrpl:1:r...` 형태이고 `credentialStatus.issuer`도 같은 `r...` classic address인 VC를 써야 한다.
- 디버그 카드에서 `Issuer Seed`에는 `s...` seed를, `Issuer Account`에는 `r...` classic address를 넣는다. 앱은 둘을 반대로 넣은 경우 화면에서 자동 교정한다.
- XRPL 응답의 `tecDUPLICATE`는 새 트랜잭션 성공이 아니라 같은 ledger object가 이미 있다는 뜻이다. 앱은 이 경우를 “이미 존재”로 표시하고, 상태 확인은 `XRPL 상태 조회`에서 active 여부로 판단한다.
- `XRPL 발급(디버그)` 카드는 issuer seed를 직접 넣는 개발용 흐름이다.
- 실제 issuer/verifier 연동을 확인할 때는 디버그 카드보다 실서버 카드를 먼저 쓴다.
