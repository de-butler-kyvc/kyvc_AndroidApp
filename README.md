# kyvc_AndroidApp

kyvc의 안드로이드 전용 앱 개발용 레포지토리입니다.

## 🛠 기술 스택
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Design System**: Material 3
- **Min SDK**: 24
- **Target SDK**: 36

## 📌 주요 작업 내용
### Wallet 및 Bridge 기능 구현 (2025-05-22)
- **Infrastructure**: Room DB, XRPL4J(6.0.0), JCS(Json Canonicalization), Kotlin Serialization 설정 완료
- **Wallet Core**: XRPL 계정 생성 및 `CredentialAccept` 트랜잭션 제출 기능 기초 구현
- **Storage**: `CredentialEntity`를 통한 VC 로컬 저장소 구축
- **Bridge**: 웹에서 안드로이드 기능을 호출할 수 있는 `Android` 브릿지 객체 등록
- **XRPL Submit**: `submitToXRPL` 브릿지에서 holder seed, issuer account, credential type을 검증한 뒤 XRPL testnet `CredentialAccept`를 제출하고 tx hash를 Room DB에 반영
- **WebView Callback**: VC 저장 및 XRPL 제출 결과를 `window.onAndroidResult`로 반환하도록 테스트 페이지 연동
- **UI**: 지민님이 제공한 고도화된 브릿지 테스트 페이지(`index.html`) 적용
- **Build Fix**: AGP 9.2.0의 내장 Kotlin 지원과 KSP 간의 충돌 해결 (`android.disallowKotlinSourceSets=false`)
- **Runtime Fix**: `xrpl4j-keypairs/crypto-bouncycastle` 3.x와 `xrpl4j-core` 6.x 혼용으로 발생한 `NoClassDefFoundError`를 제거하기 위해 XRPL 런타임 의존성을 6.0.0 core/client 중심으로 정리
- **Wallet State**: holder seed를 Android Keystore AES-GCM 키로 암호화해 SharedPreferences에 저장하고, WebView는 seed 없이 `createWallet/getWalletInfo/submitToXRPL` 브릿지를 호출하도록 변경
- **VP Signing**: `signMessage` 브릿지에서 challenge/domain/VC를 받아 JCS 기반 `DataIntegrityProof` VP를 생성하고 holder DID Document와 함께 WebView 콜백으로 반환
- **QR Bridge**: `scanQRCode` 브릿지가 QR 요청/데이터를 구조화해 `SCAN_QR_CODE` 콜백으로 전달하도록 연결
- **VC Validation**: VC 저장/서명/상태조회 전에 `credentialSubject.id`, `credentialStatus.subject`, `validFrom/validUntil`을 검증하고 holder wallet과 맞지 않으면 차단
- **XRPL Status**: credential ledger entry index를 계산해 `ledger_entry`로 status를 조회하고 active 여부를 `CHECK_CREDENTIAL_STATUS` 콜백으로 반환
- **Verifier Submit**: `submitPresentationToVerifier` 브릿지에서 signed VP, holder DID Document, policy, XRPL status 요구조건을 묶어 `/verifier/presentations/verify` 요청을 POST

## 🚀 향후 작업 계획
- [x] **MCP 연동 환경 구성**: AI 어시스턴트 협업을 위한 `mcp-context.md` 및 환경 설정 완료
- [ ] 베이스 아키텍처 설정 (Clean Architecture + MVVM)
- [x] **WebView Bridge 구현**: Web-to-Native 통신 인터페이스 추가 및 테스트 페이지 연동 완료
- [x] **Blockchain Wallet 연동**: 경량 지갑 기반 구축 (계정 생성, XRPL 통신 기초)
- [x] **CredentialAccept 제출 연동**: WebView 요청 기반 XRPL testnet 제출, holder account 검증, tx hash 저장
- [x] **Holder 지갑 상태 저장**: seed 생성, 암호화 저장, account/publicKey/DID 조회 브릿지 추가
- [x] **VP 생성 기초 구현**: 저장된 holder seed로 secp256k1 JCS proof 생성 및 WebView 콜백 연결
- [x] **QR 브릿지 연결**: QR 요청/결과를 WebView 콜백으로 전달하는 native bridge 추가
- [ ] **VC(Verifiable Credentials) 인증 시스템**: 실데이터 기반 VC 대조 및 인증 로직 고도화
- [x] **Verifier 제출 연동**: `/verifier/presentations/verify` 요청 구성 및 challenge 사용 상태 처리

## WebView Bridge 요청 형식

`createWallet`은 Android 내부에서 secp256k1 holder seed를 만들고 Keystore 보호 AES-GCM 키로 암호화 저장한다. `getWalletInfo`는 seed를 반환하지 않고 holder account, public key, DID만 반환한다.

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

`scanQRCode`는 발급자/검증자가 제시하는 요청 QR을 읽는 용도다. 현재 구현은 QR에 담긴 요청 JSON 또는 텍스트를 읽어 `requestId`, `purpose`, `endpoint`, `expiresInSec`, `qrData`를 추출하고, 스캔 결과를 `SCAN_QR_CODE` 콜백으로 WebView에 반환한다. 즉, 임의의 정적 이미지가 아니라 VP 요청, VC 발급 요청, 로그인 요청처럼 실제 플로우를 시작하는 QR을 대상으로 한다.

`checkCredentialStatus`는 VC의 issuer/holder/credentialType으로 XRPL `Credential` ledger entry의 index를 계산해 현재 validated ledger에서 조회한다. 응답의 `Flags`에 accepted bit가 켜져 있고 expiration이 유효하면 active로 판정한다.

`submitPresentationToVerifier`는 `signMessage`로 생성한 VP와 holder DID Document를 verifier 검증 요청으로 전송한다. 요청에는 `presentation`, `did_documents`, `policy`, `require_status`, `status_mode`를 포함하며, Android 쪽에서 먼저 holder DID/subject/issuer/credentialType과 XRPL status active 여부를 다시 확인한 뒤 POST를 보낸다. WebView는 `SUBMIT_TO_VERIFIER` 콜백으로 verifier 응답을 받는다.
