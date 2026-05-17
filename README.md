# KYvC Android App

> KYvC Android App은 KYvC 모바일 지갑 앱입니다. WebView 기반 사용자 화면과 Android Native Wallet을 연결해 지갑 생성/복구, 인증, QR 스캔, VC 저장, VP 제출, XRPL 연동을 처리합니다.

## 1. 프로젝트 개요

### 프로젝트 소개

KYvC Android App은 KYvC 서비스의 Holder Wallet 역할을 담당합니다. 사용자는 모바일 앱에서 지갑을 생성하거나 복구하고, PC/모바일 웹에서 생성한 QR을 스캔해 법인 KYC 증명서를 발급받거나 제출할 수 있습니다.

화면 대부분은 WebView로 제공하지만, 보안성과 단말 기능이 필요한 영역은 Native가 담당합니다. 대표적으로 지갑 키 저장, PIN/지문 인증, QR 스캔, XRPL 트랜잭션, SD-JWT/KB-JWT 처리, 로컬 Credential/문서/활동 이력 저장은 Android 레이어에서 처리합니다.

### 프로젝트 목적

- 모바일에서 KYvC Holder Wallet 기능 제공
- 웹이 seed, mnemonic, private key, SD-JWT 원문을 직접 다루지 않도록 Native로 분리
- WebView와 Native 기능을 `window.Android.*` 브릿지 계약으로 연결
- XRPL Testnet 기반 DIDSet, CredentialAccept, 잔액/거래 조회, 수수료 추정 지원
- VC 발급/저장, VP 생성/제출, 원본 문서 첨부 제출 흐름 지원
- 로그인 사용자와 로컬 지갑 owner를 바인딩해 다른 계정의 지갑 노출 방지

### 서비스 도메인

| 도메인 | 설명 |
| --- | --- |
| Holder Wallet | XRPL 계정, DID, holder authentication key, Credential 저장 |
| VC Issuance | Credential Offer QR 스캔, prepare 응답 저장, CredentialAccept 처리 |
| VP Presentation | VP 요청 QR 스캔, SD-JWT disclosure 선택, KB-JWT 생성, 제출 |
| Native Auth | PIN, 지문, 이메일 인증 후 재시도 정책 |
| Local Storage | Credential, 원본 문서, 활동 이력, 지갑 상태 저장 |
| XRPL | Testnet 계정, 잔액, DIDSet, CredentialAccept, 거래 조회 |

### 핵심 기능 요약

- WebView 진입 URL: `https://dev-kyvc.khuoo.synology.me/m/`
- WebView 실패 시 로컬 fallback: `app/src/main/assets/index.html`
- 지갑 생성, 복구, 삭제, 다중 지갑 목록/전환
- 앱 생성 지갑과 웹 로그인 사용자 owner 바인딩
- PIN/지문 네이티브 인증과 30분 인증 세션
- QR 스캔 기반 VC 발급, VP 로그인, 일반 VP 제출
- `dc+sd-jwt` Credential 저장 및 SD-JWT+KB Presentation 생성
- holder DID Document 제출 및 XRPL DIDSet hash 검증 대응
- 원본 문서 base64 수신, 암호화 저장, multipart 첨부 제출 준비
- 증명서 발급/상세/제출 Native Overlay 화면
- 활동 탭용 VC 발급/VP 제출/보안 이벤트 로컬 이력 브릿지
- Pretendard 폰트 기반 Native UI

## 2. 전체 서비스 구성

| 구분 | Android 앱에서의 역할 |
| --- | --- |
| 사용자 서비스 | WebView 화면 표시, 로그인/지갑/증명서 사용자 흐름 연결 |
| Backend | Credential Offer, VP Login resolve/submit, 발급기관명 등 업무 API 제공 |
| Core | DID/VC/VP/SD-JWT/XRPL 검증과 발급 도메인 처리 |
| Android Native | 지갑 키, 인증, QR, 로컬 DB, XRPL 트랜잭션, 브릿지 제공 |
| Infra | dev/prod URL, TLS, reverse proxy, Android 빌드/배포 환경 관리 |

이 문서는 Android 앱 저장소의 개발 기준을 설명합니다.

## 3. Android 저장소 구조

```text
kyvc_AndroidApp/
├── app/
│   ├── src/main/java/com/example/kyvc_androidapp/
│   │   ├── MainActivity.kt                 WebView, Native overlay, QR/인증 화면 진입
│   │   ├── bridge/WalletBridge.kt          WebView JavaScript bridge 핵심 구현
│   │   ├── auth/                           PIN/지문 인증 화면과 UnlockActivity
│   │   ├── scanner/                        CameraX/ML Kit QR 스캐너
│   │   ├── data/local/                     Room DB, DAO, Entity, Migration
│   │   ├── data/repository/                Credential/Document/Activity repository
│   │   ├── wallet/core/                    XRPL, 지갑 상태, VP signer
│   │   ├── security/                       앱 잠금 상태, 암호화 문서 저장
│   │   ├── ui/                             Compose theme/main state
│   │   ├── util/                           표시명/문서명 매핑 유틸
│   │   └── di/                             AppContainer
│   ├── src/main/assets/                    로컬 WebView fallback, 이미지/시안 자산
│   └── src/main/res/                       폰트, 아이콘, theme, backup rule
├── gradle/                                 Gradle wrapper 설정
├── README.md                               Android 개발 README
├── .env.example                            팀 공통 URL/환경 예시
└── local.properties.example                Android SDK 로컬 설정 예시
```

## 4. Android 책임 분리

| 영역 | 주요 책임 |
| --- | --- |
| `MainActivity` | WebView 설정, Cookie/WebView lifecycle, Native overlay UI, QR/인증 화면 제어 |
| `WalletBridge` | 웹에서 호출하는 Android bridge method, callback, 백엔드/Core/XRPL 연결 |
| `WalletStateStore` | 지갑 seed/mnemonic/auth key/owner binding 상태 저장 |
| `VpSigner` | SD-JWT+KB Presentation 생성, holder authentication key 서명 |
| `CredentialRepository` | Credential Room 저장/조회/삭제/상태 갱신 |
| `HolderDocumentRepository` | VC 발급 시 받은 원본 문서 메타데이터와 로컬 파일 매핑 |
| `WalletActivityRepository` | 활동 탭에 표시할 VC 발급/VP 제출/보안 이벤트 저장 |
| `SecureDocumentStore` | 원본 문서 bytes 암호화 저장/readback |
| `QrScannerActivity` | CameraX와 ML Kit 기반 QR 스캔 |
| `UnlockActivity` | PIN/패턴 인증 UI |

Android가 직접 처리하지 않는 영역:

- PC 웹 로그인 완료 처리
- Backend/Core 정책 생성
- 서버 DB 저장
- Core 검증 결과 최종 판정
- issuer private key 또는 issuer seed 관리

## 5. 전체 통신 구조

### WebView → Android Bridge

웹은 `window.Android.{method}(JSON.stringify(payload))` 형식으로 Native 기능을 호출합니다. Native 응답은 기본적으로 `window.onAndroidResult(resultJson)`로 전달합니다.

```text
WebView 화면
-> window.Android.* bridge 호출
-> WalletBridge
-> 로컬 지갑/Room/XRPL/Backend API 처리
-> window.onAndroidResult(...)
```

### VC 발급 흐름

```text
PC/웹 Credential Offer QR 생성
-> Android QR 스캔
-> WebView/Native가 Backend prepare 호출 흐름 진행
-> WalletBridge.saveVC
-> dc+sd-jwt 저장
-> documentAttachments base64 decode 및 암호화 저장
-> XRPL CredentialAccept
-> Backend confirm
-> VC_ISSUED 활동 이력 저장
```

confirm 호출 기준:

```text
Credential 저장 성공
+ 원본 문서 저장 성공
+ documentAttachmentManifest 저장 성공
+ 필요 시 readback 확인
-> confirm 호출
```

### VP 로그인 흐름

```text
PC Frontend VP_LOGIN_REQUEST QR 표시
-> Android QR 스캔
-> POST /api/mobile/auth/vp-login-requests/resolve
-> challenge/nonce/aud 등록
-> 증명서 제출 화면에서 발급기관 선택
-> requiredDisclosures 반영해 SD-JWT+KB 생성
-> holder DID Document 포함
-> POST /api/mobile/auth/vp-login-requests/{requestId}/submit
-> PC Frontend polling 후 complete
```

Android는 JWT cookie를 발급하거나 PC 로그인 완료 API를 호출하지 않습니다.

### 일반 VP 제출 흐름

```text
Verifier QR 스캔
-> VP 요청 조회/resolve
-> 제출할 발급기관/서류 확인
-> requiredDisclosures 반영
-> 문서 첨부 필요 시 attachmentManifest와 attachmentRef 파일 part 준비
-> JSON 또는 multipart API 제출
-> VP_SUBMITTED 활동 이력 저장
```

첨부 제출이 필요한 경우 multipart part 이름은 `attachmentRef`와 같아야 합니다.

## 6. 기술 스택

| 영역 | 기술 |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Web | Android WebView, JavaScriptInterface |
| Local DB | Room |
| QR/Camera | CameraX, ML Kit Barcode Scanning |
| Crypto | BouncyCastle, JCS, Kotlin serialization |
| Wallet | BIP-39 mnemonic, Android Keystore AES-GCM |
| XRPL | xrpl4j core/client |
| Auth | AndroidX Biometric, Native PIN/패턴 |
| Font | Pretendard |
| Min SDK | 24 |
| Compile/Target SDK | 35 |

## 7. 주요 브릿지 요약

### 세션/사용자/인증

| Method | Action | 설명 |
| --- | --- | --- |
| `setCurrentWebUser` | `SET_CURRENT_WEB_USER` | 웹 로그인 사용자와 로컬 지갑 owner 바인딩 |
| `getWalletOwnerStatus` | `GET_WALLET_OWNER_STATUS` | 현재 웹 사용자와 로컬 지갑 owner 일치 여부 |
| `requestNativeAuth` | `REQUEST_NATIVE_AUTH` | PIN/지문 네이티브 인증 요청 |
| `getAuthStatus` | `GET_AUTH_STATUS` | 인증 가능 수단과 세션 상태 조회 |
| `completeEmailVerification` | `COMPLETE_EMAIL_VERIFICATION` | 이메일 인증 완료 후 네이티브 인증 제한 해제 |
| `logout` | `LOGOUT` | 인증 세션/웹 사용자 상태 정리 |

### 지갑/XRPL

| Method | Action | 설명 |
| --- | --- | --- |
| `createWallet` | `CREATE_WALLET` | 신규 holder wallet 생성 |
| `restoreWallet` | `RESTORE_WALLET` | seed 또는 mnemonic 기반 지갑 복구 |
| `getWalletInfo` | `GET_WALLET_INFO` | holder DID/account/public key/device id 조회 |
| `getWalletAssets` | `GET_WALLET_ASSETS` | XRP 잔액, spendable/available 필드 조회 |
| `estimateHolderDidSetFee` | `ESTIMATE_HOLDER_DID_SET_FEE` | XRPL fee RPC 기반 DIDSet 수수료 추정 |
| `submitHolderDidSet` | `SUBMIT_HOLDER_DID_SET` | holder DID Document hash를 XRPL DIDSet에 등록 |
| `getWalletTransactions` | `GET_WALLET_TRANSACTIONS` | XRP 거래 내역 조회 |
| `submitXrpPayment` | `SUBMIT_XRP_PAYMENT` | XRP 송금 |

### Credential/Document

| Method | Action | 설명 |
| --- | --- | --- |
| `saveVC` | `SAVE_VC` | `dc+sd-jwt` Credential 저장, 문서 첨부 저장 준비 |
| `listCredentials` | `LIST_CREDENTIALS` | 저장된 Credential 목록 조회 |
| `getCredentialSummaries` | `GET_CREDENTIAL_SUMMARIES` | 증명서 목록/카드용 요약 조회 |
| `checkCredentialStatus` | `CHECK_CREDENTIAL_STATUS` | XRPL Credential 상태 조회 |
| `refreshAllCredentialStatuses` | `REFRESH_ALL_CREDENTIAL_STATUSES` | 전체 Credential 상태 갱신 |
| `registerDocumentEvidence` | `REGISTER_DOCUMENT_EVIDENCE` | 로컬 문서 증빙 등록 |

### VP/QR/활동

| Method | Action | 설명 |
| --- | --- | --- |
| `scanIssueQrCode` | `SCAN_ISSUE_QR_CODE` | 증명서 발급 QR 스캔 |
| `scanPresentationQrCode` | `SCAN_PRESENTATION_QR_CODE` | 증명서 제출/VP 로그인 QR 스캔 |
| `registerVerifierChallenge` | `REGISTER_VERIFIER_CHALLENGE` | verifier challenge/nonce 로컬 등록 |
| `signMessage` | `SIGN_MESSAGE` | SD-JWT+KB Presentation 생성 |
| `submitPresentationToVerifier` | `SUBMIT_TO_VERIFIER` | 일반 verifier 제출 |
| `getWalletActivityHistory` | `GET_WALLET_ACTIVITY_HISTORY` | 활동 탭 내역 조회 |
| `recordWalletActivity` | `RECORD_WALLET_ACTIVITY` | 보안/경고 등 웹 이벤트를 로컬 활동으로 저장 |
| `markWalletActivitiesRead` | `MARK_WALLET_ACTIVITIES_READ` | 활동 이력 읽음 처리 |

### Native 화면

| Method | 설명 |
| --- | --- |
| `requestCredentialIssueComplete` | 발급 완료 Native 화면 |
| `requestCredentialIssueConfirm` | 발급 확인 Native 화면 |
| `requestCredentialDetail` | 증명서 상세 Native 화면 |
| `requestCredentialSubmit` | 증명서 제출 Native 화면 |
| `requestMnemonicBackup` | 복구 문구 백업 Native 화면 |
| `requestWalletRestore` | 지갑 복구 Native 화면 |
| `requestPinReset` | PIN 재설정 Native 화면 |

브릿지 요청/응답은 민감정보 노출을 막기 위해 로그에 원문을 남기지 않습니다. `sdJwt`, `sdJwtKb`, `credentialJwt`, `contentBase64`, seed, mnemonic, private key, PDF bytes는 로그 출력 금지 대상입니다.

## 8. 로컬 저장소와 보안 정책

### Room DB

| 테이블 | Entity | 설명 |
| --- | --- | --- |
| `credentials` | `CredentialEntity` | SD-JWT/VC 메타데이터와 XRPL accept 상태 |
| `holder_documents` | `HolderDocumentEntity` | 원본 문서 메타데이터, attachmentRef, encrypted path |
| `wallet_activities` | `WalletActivityEntity` | 활동 탭용 VC 발급/VP 제출/보안 이벤트 |

현재 DB version은 `4`입니다. Entity 변경 시 `AppDatabase` migration을 반드시 추가하고 기존 설치 앱에서 Room schema 검증이 통과해야 합니다.

### 보안 저장 기준

- XRPL seed와 mnemonic은 Android Keystore 기반 암호화 저장소에서 관리합니다.
- holder authentication private key는 WebView로 반환하지 않습니다.
- SD-JWT credential 원문은 전체 KYC disclosure를 포함하므로 민감정보로 취급합니다.
- 원본 문서는 `SecureDocumentStore`로 암호화 저장하고, VP 첨부 제출 시에만 readback합니다.
- 앱 삭제 시 로컬 지갑과 로컬 DB는 백업/복원되지 않아야 합니다.
- `AndroidManifest.xml`은 `android:allowBackup="false"`로 설정되어 있습니다.

### Owner Binding

앱 생성 지갑은 최초 생성/복구 시 웹 로그인 사용자에 바인딩됩니다. 다른 웹 계정으로 로그인하면 기존 지갑을 자동 삭제하지 않고, 웹이 명시적으로 `deleteLocalWalletData` 또는 `logoutAndDeleteLocalWalletData`를 호출해야 합니다.

## 9. 실행과 빌드

### 사전 준비

- Android Studio 또는 Android SDK
- JDK 11
- `local.properties`에 SDK 경로 설정

예시:

```properties
sdk.dir=/Users/{user}/Library/Android/sdk
```

### 주요 명령

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

실기기 설치 예시:

```bash
./gradlew :app:installDebug
```

### Logcat 필터

주요 tag:

```text
WalletBridge
```

자주 보는 키워드:

```text
vp.login
vc.issue
VC_DOCUMENT_SAVE
GET_WALLET_ACTIVITY_HISTORY
ESTIMATE_HOLDER_DID_SET_FEE
```

## 10. 테스트 기준

### VC 발급 테스트

1. 웹 로그인 후 `setCurrentWebUser` 호출 여부 확인
2. 지갑 생성 또는 복구
3. DIDSet 미등록이면 `submitHolderDidSet`
4. PC에서 Credential Offer QR 생성
5. Android에서 발급 QR 스캔
6. 발급 확인 화면에서 실제 세부 정보와 잔액/수수료 표시 확인
7. `saveVC` 성공
8. `documentAttachments`가 있으면 decode/store/readback 확인
9. XRPL CredentialAccept 성공
10. Backend confirm 후 `walletSaved=true`
11. `VC_ISSUED` 활동 이력 저장 확인

### VP 로그인 테스트

1. PC에서 VP 로그인 QR 생성
2. Android에서 제출 QR 스캔
3. `VP login resolve` 요청 확인
4. requiredDisclosures가 최종 disclosure 목록에 포함되는지 확인
5. holder binding 로그에서 `walletDid`, `sdJwtSub`, `cnf.kid`, `kbHeaderKid` 일치 확인
6. `VP login submit` 요청 확인
7. Backend/Core status가 `VALID`, PC polling `canComplete=true` 확인

### 원본 문서 첨부 VP 제출 테스트

1. VC 발급 시 `documentAttachmentManifest` 저장 확인
2. 제출 요청의 `documentRules` 또는 `requiredDisclosures`에 `documentEvidence[]` 포함 여부 확인
3. multipart API 사용 여부 확인
4. `presentation` part 포함
5. `attachmentManifest` part 포함
6. 파일 part name이 `attachmentRef`와 같은지 확인
7. Core 결과에서 원본 문서 hash match 확인

## 11. 디버깅 기준

### VP 로그인 실패

| 증상 | 우선 확인 |
| --- | --- |
| `VP_LOGIN_REQUEST_EXPIRED` | QR 재생성, 서버 만료 시간 |
| `required disclosure missing` | resolve 응답의 `requiredDisclosures`가 VP 생성까지 전달되는지 |
| `KB-JWT header.kid mismatch` | SD-JWT `cnf.kid`, KB-JWT header `kid`, DID Document key id |
| `DID Document hash mismatch` | 현재 DID Document로 DIDSet 재등록 필요 |
| `vct is not accepted` | VC `vct`와 verifier policy 일치 여부 |
| `VP_LOGIN_CREDENTIAL_INVALID` | credentialId, walletSaved, XRPL accepted, holder account 매칭 |

### VC 발급 실패

| 증상 | 우선 확인 |
| --- | --- |
| `활성 인증 세션이 필요합니다` | 웹이 보호 브릿지 호출 전 `requestNativeAuth`를 수행했는지 |
| `issuerAccount ... rIssuer` | Backend/Core가 실제 XRPL classic address를 내려주는지 |
| `SD-JWT credential must include issuer JWT and at least one disclosure` | Web/Backend가 `sdJwt` 원문을 전달하는지 |
| `walletSaved=false` | Android confirm 호출 여부와 saveVC/문서 저장 실패 여부 |

### 잔액/수수료 표시

- `getWalletAssets`는 현재 잔액과 available/spendable 필드를 제공합니다.
- `estimateHolderDidSetFee`는 XRPL `fee` RPC 결과를 drops 기준으로 반환합니다.
- DID 등록 후 사용 가능 잔액은 현재 잔액에서 DID object reserve 증가분과 네트워크 수수료만 차감해야 합니다.
- reserve 또는 available 값을 중복 차감하지 않습니다.

## 12. 문서 갱신 규칙

- 브릿지 메서드가 추가되면 README의 브릿지 표와 테스트 기준을 함께 갱신합니다.
- Room Entity가 바뀌면 DB version, migration, 테스트 기준을 함께 갱신합니다.
- WebView/Backend/Core 계약이 바뀌면 Android가 실제로 받는 필드와 저장/제출 기준을 명확히 기록합니다.
- 민감정보 로그 정책이 바뀌면 금지 로그 목록을 먼저 갱신합니다.
- 화면 시안 기준이 바뀌면 Native 화면 섹션에 반영합니다.

## 13. Git 운영 전략

브랜치는 전체 KYvC 운영 기준과 맞춥니다.

| 브랜치 | 용도 |
| --- | --- |
| `feature/*` | 기능 개발 |
| `develop` | 개발 환경 통합 |
| `main` | 안정화 및 배포 기준 |

커밋 메시지는 다음 형식을 사용합니다.

```text
type(scope): 작업 내용
```

사용 가능한 type:

- `feat`
- `fix`
- `refactor`
- `test`
- `docs`
- `chore`

예시:

```text
docs(-): Android README 구조 정리
feat(native): add wallet activity bridge
fix(native): correct DIDSet fee display
```

요청 없는 branch 생성, commit, push, merge, PR 생성은 하지 않습니다. 공동 작업 브랜치에서는 pull 후 충돌 여부를 확인하고, 사용자가 명시한 경우에만 커밋/푸쉬합니다.
