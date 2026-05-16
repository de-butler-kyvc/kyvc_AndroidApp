# WebView Bridge Spec

이 문서는 웹이 Android WebView 브리지를 호출할 때 사용하는 최신 호출 규격 문서다. 브리지 함수가 바뀌면 이 문서를 같이 갱신한다.

- Spec Version: `1.6.3`
- Last Updated: `2026-05-16`

## Changelog

- `1.6.3` (2026-05-16)
  - 증명서 제출 화면의 발급기관 선택명과 증명서 상세/발급확인 화면의 발급기관명을 backend 공통 DID 기관 조회 API 기준으로 표시
  - 발급기관명 조회 API: `GET /api/common/dids/{issuerDid}/institution`
  - 화면 요청 payload에 `issuerName`이 없거나 신뢰할 수 없는 경우 Android는 `credentialId`로 로컬 credential을 조회해 `issuerDid`를 복원하고 기관명을 조회
  - `KYvC 인증기관`, `법원행정처`, `발급기관 1` 같은 테스트 fallback 이름은 사용하지 않고 실제 API 값 또는 `-`/실제 DID-account fallback만 표시
- `1.6.2` (2026-05-13)
  - PC VP 로그인 submit body에 holder DID Document를 포함하는 기준 명시: `didDocument`, `didDocuments`, `did_documents`
  - VP 생성 전 holder binding 진단 로그 추가: `vp.login.holder.binding`
  - SD-JWT `payload.cnf.kid`는 현재 holder DID Document의 `verificationMethod[].id` 및 `authentication[]`과 정확히 같아야 함을 명시
  - DID Document는 VP 제출 시점마다 변형하지 않고 DIDSet 등록 hash와 같은 기본 문서 구조를 사용하도록 정리
  - 증명서 상세 화면의 삭제 확인 후 로컬 Credential 및 연결 holder document 삭제 동작 명시
  - 발급 확인 화면의 네트워크 수수료가 drops 단위로 전달될 때 `0.000001 XRP` 단위로 정규화되는 기준 추가
- `1.6.1` (2026-05-13)
  - `saveVC`가 top-level `metadata`뿐 아니라 `credentialPayload.metadata`와 `credentialPayload.selectiveDisclosure`도 해석하도록 명시
  - `issuerAccount`가 실제 XRPL classic address면 저장/검증용 `issuerDid`를 `did:xrpl:1:{issuerAccount}`로 보정하는 기준 명시
  - 발급 확인 네이티브 화면 기준 시안을 `발급확인0.png`로 갱신하고 잔액/수수료 표시 필드 추가
  - PC 웹 로그인용 `VP_LOGIN_REQUEST` QR을 Android Native가 인식해 backend resolve/submit으로 제출하는 흐름 추가
- `1.6.0` (2026-05-13)
  - 웹 로그인 사용자와 로컬 지갑 owner 바인딩 브릿지 추가: `setCurrentWebUser`, `getWalletOwnerStatus`, `deleteLocalWalletData`, `logoutAndDeleteLocalWalletData`
  - 지갑/VC/XRPL 계열 브릿지는 현재 웹 사용자와 로컬 지갑 owner가 일치할 때만 지갑 정보를 반환
  - owner 불일치 로그인 시 자동 삭제하지 않고 `walletAccess=owner_mismatch`, `deleteRequired=true`를 반환하도록 규격화
- `1.5.2` (2026-05-12)
  - `getDeviceInfo` 브릿지 추가
  - `scanIssueQrCode`가 PC QR 원문을 `qrData`로 반환하는 계약 명시
  - backend prepare `credentialPayload` 기반 `saveVC` 입력 alias 명시
  - `submitToXRPL` 응답의 `txHash` / `credentialAcceptHash` alias 명시
  - `checkCredentialStatus` 응답의 `credentialEntryFound` / `credentialAccepted` alias 명시
- `1.5.1` (2026-05-11)
  - 앱 WebView 기본 진입 URL을 `https://dev-kyvc.khuoo.synology.me/m/`로 변경
  - 외부 URL 메인 프레임 로딩 실패 시 `app/src/main/assets/index.html` fallback 사용
  - 테스트 페이지 위치를 문서에 명시
- `1.5.0` (2026-05-10)
  - XRPL 네트워크 기준을 Devnet에서 Testnet으로 전환
  - Core Base URL 예시를 testnet 환경 변수 형태로 정리
  - 웹 구현 참고사항에 testnet 전환 체크 포인트 추가
- `1.4.0` (2026-05-10)
  - 보안 정책 명시: 웹 노출 허용은 `seed/mnemonic`까지, `auth key/private key/backup export` 비노출
  - `restoreWallet`의 seed/mnemonic 복구 규칙과 DIDSet 재등록 조건을 최신화
  - 문서 내 호출 대상/규칙 용어 정리
  - 계정관리 브리지 호출 규격 추가(`listWallets/switchWallet/setAccountName/upgradeToMnemonic/deriveNextAccount`)
  - `removeWallet`는 정책상 비활성화(호출 비권장) 상태로 명시

## 문서 유지 규칙

- 새로운 브리지 기능을 추가하거나 기존 요청/응답 형식을 바꾸면 이 문서를 같은 커밋에서 갱신한다.
- 웹 또는 서버가 앱 기능을 외부에서 움직여야 하면 최소한 아래 내용을 같이 기록한다.
  - 메서드명
  - 요청 JSON
  - 응답 JSON
  - 선행 조건
  - 호출 흐름
  - 실패 응답 예시
- 인증, 서명, 백엔드 호출 주체가 웹에서 네이티브로 바뀌는 경우에도 이 문서를 우선 수정한다.

## 공통 원칙

- 웹은 브리지 함수만 호출한다.
- 인증, 민감정보 처리, 백엔드 API 호출은 네이티브가 담당한다.
- 웹은 결과만 `window.onAndroidResult(result)` 콜백으로 받는다.
- 웹 로그인 직후 지갑 관련 브릿지를 호출하기 전에 `setCurrentWebUser`로 현재 로그인 사용자를 Android에 알려야 한다.
- 로컬 지갑은 최초 생성/복구 또는 명시적 기존 지갑 바인딩 시 웹 로그인 사용자에 묶인다.
- 다른 웹 계정으로 로그인한 상태에서 기존 로컬 지갑 owner와 불일치하면 Android는 자동 삭제하지 않고 `walletAccess=owner_mismatch`를 반환한다. 삭제는 사용자가 웹에서 명시적으로 삭제 브릿지를 호출할 때만 실행한다.
- 앱 진입 시 네이티브 테스트 잠금 화면은 띄우지 않는다. 인증은 웹이 `requestNativeAuth`를 호출할 때만 네이티브로 표시한다.
- PIN/패턴 원문은 웹에서 브리지로 보내지 않는다.
- 지문/PIN/패턴 UI는 네이티브가 띄운다.
- `requestNativeAuth(method="pin")` 호출 시 PIN 입력 화면은 WebView가 아닌 네이티브 `UnlockActivity`에서 렌더링된다. UI는 `app/src/main/assets/pinExample.html` 디자인을 기준으로 구현한다.
- `seed/mnemonic` 외 민감 키(auth key/private key)는 웹으로 export하지 않는다.
- XRPL ledger 기준 네트워크는 `testnet`이다.
- 실제 앱 내 WebView는 기본적으로 `https://dev-kyvc.khuoo.synology.me/m/`를 로드한다.
- 브리지 테스트 페이지(로컬)는 `app/src/main/assets/index.html`이며, 네트워크/도메인 문제로 메인 URL이 실패하면 fallback으로 열린다.
- Android 뒤로가기 버튼은 WebView 내부 히스토리를 우선 사용한다. 히스토리가 없을 때만 앱이 종료된다.

## 네트워크 설정 (Testnet)

- `coreBaseUrl`은 운영팀이 제공한 **testnet 연동 Core 주소**를 사용한다.
- 예시 문서값은 `https://<core-testnet-base-url>` 형태로 표기한다.
- 요청 payload의 `network` 필드는 `testnet`을 기본값으로 사용한다.
- 앱/웹 기본 fallback 규칙:
  - `coreBaseUrl`은 **필수 입력**이다. 미입력 시 요청은 실패한다.
  - `aud/domain` 미입력 시 `coreBaseUrl` 값을 그대로 사용한다.
  - `verifierEndpoint`는 `coreBaseUrl + /verifier/presentations/verify`를 기본값으로 맞춘다.

## 외부 연동 기준

- 웹은 브리지 메서드를 통해서만 앱 기능을 호출한다.
- 서버는 앱 내부 기능을 직접 호출하지 않고, 웹이 브리지 요청을 만들거나 네이티브가 직접 백엔드 API를 호출하는 구조를 따른다.
- 외부에서 앱 기능을 움직이려면 호출 규격과 호출 흐름이 먼저 문서화되어 있어야 한다.

## 현재 외부 호출 대상

- 인증 상태 조회 / 네이티브 인증 요청 / 이메일 인증 완료 처리 / 로그아웃
- 웹 로그인 사용자 owner 바인딩 / owner 상태 조회 / owner 일치 시 로컬 지갑 삭제
- 앱 설치 기준 기기 정보 조회
- 지갑 생성 / 조회 / seed 보기 / 비밀구문 보기 / 복구
- XRP 잔액 / 입금 주소 / 거래내역 / XRP 송금
- Holder DIDSet 등록
- VC 저장 / 목록 조회 / 상태 조회 / 상태 일괄 갱신
- XRPL `CredentialAccept` 제출
- issuer debug `CredentialCreate` 제출
- verifier challenge 요청 / 등록
- SD-JWT credential 서버 검증
- KB-JWT 생성 / verifier 제출
- QR 스캔 호출

명시적 비지원:
- auth key/private key 내보내기
- wallet backup blob 내보내기

## 브리지 메서드 상태표

| 메서드 | 상태 | 비고 |
| --- | --- | --- |
| `getAuthStatus` | stable | 인증/잠금 상태 단일 조회 기준 |
| `getDeviceInfo` | stable | 웹 기기 등록용 stable device id/app/device 정보 조회 |
| `setCurrentWebUser` | stable | 웹 로그인 사용자와 로컬 지갑 owner 접근권한 확인/바인딩 |
| `getWalletOwnerStatus` | stable | 현재 웹 사용자와 로컬 지갑 owner 상태 조회 |
| `deleteLocalWalletData` | stable | owner 일치 + 인증 세션 상태에서 로컬 지갑 데이터 삭제 |
| `logoutAndDeleteLocalWalletData` | stable | 인증 세션 상태에서 로컬 지갑 데이터 삭제 후 로그아웃 |
| `requestNativeAuth` | stable | PIN/패턴/지문 네이티브 인증 진입 |
| `requestPinReset` | stable | 웹에서 네이티브 PIN 재설정 화면 호출 |
| `completeEmailVerification` | stable | 5회 실패 잠금 해제 플로우 |
| `logout` | stable | 세션 강제 종료 |
| `createWallet` | stable | 지갑 생성 + DID/DIDDoc 생성 |
| `getWalletInfo` | stable | 현재 활성 지갑 정보 조회 |
| `getDeviceInfo` | stable | 앱 설치 단위 deviceId 및 기기 표시 정보 조회 |
| `listWallets` | stable | 계정 목록/활성 계정/파생정보 조회 |
| `switchWallet` | stable | 활성 계정 전환 |
| `setAccountName` | stable | 계정 표시명 변경 |
| `upgradeToMnemonic` | stable | 기존 계정을 mnemonic 백업 가능 상태로 업그레이드 |
| `deriveNextAccount` | stable | mnemonic 기반 다음 계정 파생 |
| `removeWallet` | disabled | 정책상 비활성화(현재 UI에서 호출 차단) |
| `exportWalletSeed` | stable | 민감정보, 세션 필요 |
| `exportWalletMnemonic` | stable | 민감정보, 세션 필요 |
| `restoreWallet` | stable | seed/mnemonic 복구 |
| `requestMnemonicBackup` | stable | 네이티브 복구 문구 백업 화면 호출 |
| `requestWalletRestore` | stable | 네이티브 지갑 복구 화면 호출 |
| `submitHolderDidSet` | stable | DIDSet 해시 등록 |
| `checkHolderDidSet` | stable | 활성 지갑 DIDSet ledger 등록 여부 조회 |
| `getWalletAssets` | stable | XRP/TrustLine 조회 |
| `getWalletDepositInfo` | stable | 입금 주소/QR 조회 |
| `copyWalletAddress` | stable | 주소 클립보드 복사 |
| `getWalletTransactions` | stable | account_tx 최근 내역 |
| `submitXrpPayment` | stable | 송금 전 네이티브 재인증 필요 |
| `saveVC` | stable | VC/SD-JWT 저장 |
| `getCredentialSummaries` | stable | 화면 표시용 증명서 요약 조회 |
| `checkCredentialStatus` | stable | XRPL 상태 조회 |
| `refreshAllCredentialStatuses` | stable | 상태 일괄 동기화 |
| `submitToXRPL` | stable | CredentialAccept |
| `submitCredentialCreate` | experimental | issuer debug 전용 |
| `requestIssuerCredential` | stable | 실서버 발급 요청 |
| `verifyCredentialWithServer` | stable | 실서버 VC 검증 |
| `requestVerifierChallenge` | stable | nonce/aud challenge 발급 |
| `registerVerifierChallenge` | stable | 로컬 challenge guard 등록 |
| `signMessage` | stable | SD-JWT+KB 또는 VP 생성 분기 |
| `submitPresentationToVerifier` | stable | verifier 제출 |
| `scanQRCode` | experimental | 범용 QR 스캔 |
| `scanIssueQrCode` | stable | 증명서 발급 QR 스캔 |
| `scanPresentationQrCode` | stable | 증명서 제출 요청 QR 스캔 |
| `requestCredentialIssueComplete` | stable | 네이티브 발급 완료 화면 |
| `requestCredentialIssueConfirm` | stable | 네이티브 발급 확인 화면 |
| `requestCredentialIssueConfirm1` | legacy alias | `requestCredentialIssueConfirm`과 같은 화면 |
| `requestCredentialIssueConfirm2` | legacy alias | `requestCredentialIssueConfirm`과 같은 화면 |
| `requestCredentialDetail` | stable | 네이티브 증명서 상세 화면 |
| `requestCredentialSubmit` | stable | 네이티브 증명서 제출 화면 |

## 공통 호출 형식

웹에서 호출:

```js
window.Android.<method>(JSON.stringify(payload))
```

Android 콜백:

```js
window.onAndroidResult = function (resultJson) {
  const result = JSON.parse(resultJson);
  console.log(result.action, result.ok, result);
};
```

공통 콜백 필드:

```json
{
  "action": "ACTION_NAME",
  "ok": true,
  "source": "Android"
}
```

## Device Bridge

### 1. 기기 정보 조회

Method: `getDeviceInfo`

Request:

```json
{
  "action": "GET_DEVICE_INFO",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "issuedAt": "2026-05-12T00:00:00Z"
}
```

Response:

```json
{
  "action": "GET_DEVICE_INFO",
  "ok": true,
  "source": "Android",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "deviceId": "android-installation-uuid",
  "deviceName": "Samsung SM-S918N",
  "os": "Android",
  "appVersion": "1.0",
  "publicKey": "holder-auth-public-key-or-empty"
}
```

선행 조건:

- WebView origin은 Android bridge 신뢰 origin이어야 한다.
- `requestId`는 UUID, `issuedAt`은 ISO-8601 UTC timestamp여야 한다.
- 요청 TTL은 30초다.

실패 응답:

```json
{
  "action": "GET_DEVICE_INFO",
  "ok": false,
  "source": "Android",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "error": "request expired"
}
```

deviceId 정책:

- Android OS 고유 ID를 직접 노출하지 않는다.
- 앱 설치 단위 UUID를 `kyvc-device` SharedPreferences의 `device_id`에 저장하고 재사용한다.
- 앱 삭제/재설치 시 deviceId는 바뀔 수 있다.

민감정보 비노출 정책:

- `seed`, `mnemonic`, private key, auth private key는 절대 포함하지 않는다.
- `publicKey`는 활성 지갑의 holder auth public key가 있을 때만 반환하며, 없으면 빈 문자열이다.

## 인증 브리지

### 0. 기기 정보 조회

Method: `getDeviceInfo`

웹의 `/m` 증명서 발급 플로우에서 백엔드 기기 등록 전에 앱 설치 기준 기기 식별자를 조회할 때 사용한다.

Request:

```json
{
  "action": "GET_DEVICE_INFO",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "issuedAt": "2026-05-12T10:00:00Z"
}
```

Response:

```json
{
  "action": "GET_DEVICE_INFO",
  "ok": true,
  "source": "Android",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceId": "stable-device-id",
  "deviceName": "Samsung Galaxy S24",
  "os": "Android 15",
  "appVersion": "1.0",
  "publicKey": "optional-holder-auth-public-key"
}
```

규칙:

- `deviceId`는 앱 설치 기준 UUID이며 앱 재시작 후에도 유지된다.
- 앱 삭제/재설치 또는 앱 데이터 삭제 시 새 `deviceId`가 생성될 수 있다.
- `publicKey`는 활성 지갑이 있을 때 holder authentication public key를 반환한다.
- `requestId`, `action`, `issuedAt`은 공통 요청 검증 대상이며 TTL은 30초다.

실패:

```json
{
  "action": "GET_DEVICE_INFO",
  "ok": false,
  "source": "Android",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "error": "기기 정보를 확인할 수 없습니다."
}
```

### 1. 인증 상태 조회

Method: `getAuthStatus`

Request:

```json
{}
```

Response:

```json
{
  "action": "GET_AUTH_STATUS",
  "ok": true,
  "lockConfigured": true,
  "pinConfigured": true,
  "patternConfigured": false,
  "biometricEnabled": true,
  "availableMethods": ["pin", "biometric"],
  "walletReady": true,
  "failedAttempts": 2,
  "remainingAttempts": 3,
  "failureThreshold": 5,
  "emailVerificationRequired": false,
  "sessionUnlocked": true,
  "sessionRemainingMs": 1423550,
  "sessionExpiresAtMs": 1778280000000,
  "xrpPaymentAuthReady": false,
  "xrpPaymentAuthRemainingMs": 0
}
```

웹이 이 응답으로 바로 판단할 값:

- `failedAttempts`: 현재 누적 실패 횟수
- `remainingAttempts`: 이메일 인증 전까지 남은 시도 횟수
- `failureThreshold`: 현재 정책상 최대 허용 실패 횟수
- `emailVerificationRequired`: `true`면 더 이상 PIN/패턴/지문 인증을 진행하지 말고 이메일 인증 화면으로 넘겨야 한다.
- `sessionUnlocked`: 현재 30분 인증 세션이 살아 있는지
- `sessionRemainingMs`: 세션 만료까지 남은 시간
- `sessionExpiresAtMs`: 세션 만료 시각(epoch ms)
- `xrpPaymentAuthReady`: 최근 송금 재인증이 유효한지
- `xrpPaymentAuthRemainingMs`: 송금 재인증의 남은 유효 시간(ms)

<!-- ### 1-0. 앱 진입 자동 로그인 세션 이벤트

앱은 WebView 페이지 로드가 끝난 직후 30분 인증 세션이 살아 있으면 웹 호출 없이 아래 이벤트를 자동 발행한다.

```json
{
  "action": "NATIVE_AUTH_SESSION",
  "ok": true,
  "authenticated": true,
  "autoLogin": true,
  "sessionReused": true,
  "sessionUnlocked": true,
  "sessionRemainingMs": 1423550,
  "sessionExpiresAtMs": 1778280000000,
  "walletReady": true,
  "walletAccess": "allowed"
}
```

웹은 이 이벤트를 받으면 사용자가 인증 버튼을 누르도록 만들지 말고 로그인/인증 페이지를 바로 통과시킨다. `walletAccess`가 `owner_mismatch`이면 지갑 화면은 숨기고 계정 불일치 안내로 보낸다.
-->

### 1-1. 웹 로그인 사용자 설정 / 지갑 owner 확인

Method: `setCurrentWebUser`

웹 로그인 성공 직후, 지갑 관련 브릿지를 호출하기 전에 반드시 호출한다. `userId`는 이메일이 아니라 서버가 발급하는 변경 불가능한 stable user id를 사용한다.

Request:

```json
{
  "action": "SET_CURRENT_WEB_USER",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "issuedAt": "2026-05-13T10:00:00Z",
  "userId": "server-stable-user-id",
  "displayHint": "k***@example.com",
  "environment": "testnet",
  "bindIfUnbound": false
}
```

성공, 지갑 없음:

```json
{
  "action": "SET_CURRENT_WEB_USER",
  "ok": true,
  "walletAccess": "no_wallet",
  "walletReady": false,
  "ownerBound": false
}
```

성공, owner 일치:

```json
{
  "action": "SET_CURRENT_WEB_USER",
  "ok": true,
  "walletAccess": "allowed",
  "walletReady": true,
  "ownerBound": true,
  "ownerDisplayHint": "k***@example.com",
  "ownerEnvironment": "testnet"
}
```

기존 owner 없는 지갑:

```json
{
  "action": "SET_CURRENT_WEB_USER",
  "ok": true,
  "walletAccess": "binding_required",
  "walletReady": true,
  "ownerBound": false,
  "requiresNativeAuth": true,
  "errorHint": "기존 로컬 지갑을 현재 로그인 계정에 연결하려면 사용자 확인 후 bindIfUnbound=true로 다시 호출하세요."
}
```

owner 불일치:

```json
{
  "action": "SET_CURRENT_WEB_USER",
  "ok": true,
  "walletAccess": "owner_mismatch",
  "walletReady": false,
  "ownerBound": true,
  "deleteRequired": true,
  "ownerDisplayHint": "a***@example.com",
  "errorHint": "다른 계정의 로컬 지갑이 있습니다. 지갑 삭제는 웹에서 deleteLocalWalletData 브릿지를 명시적으로 호출할 때만 실행됩니다."
}
```

규칙:

- `userId`는 서버의 stable id를 사용한다. 이메일/전화번호처럼 바뀔 수 있는 값만으로 바인딩하지 않는다.
- Android는 `environment + userId`를 SHA-256 해시로 저장하며 원문 user id를 저장하지 않는다.
- 지갑이 없는 상태에서 `createWallet` 또는 `restoreWallet`이 성공하면 현재 웹 사용자에 owner가 저장된다.
- owner 없는 기존 로컬 지갑은 자동 바인딩하지 않는다. 웹에서 사용자 확인 후 `bindIfUnbound=true`로 재호출한다.
- `owner_mismatch`면 웹은 기존 지갑 값을 표시하지 말고, 사용자가 지갑 삭제를 명시적으로 선택한 경우에만 `deleteLocalWalletData` 또는 `logoutAndDeleteLocalWalletData`를 호출한다.

### 1-2. 지갑 owner 상태 조회

Method: `getWalletOwnerStatus`

Request:

```json
{
  "action": "GET_WALLET_OWNER_STATUS",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "issuedAt": "2026-05-13T10:00:00Z"
}
```

Response 주요 필드:

```json
{
  "action": "GET_WALLET_OWNER_STATUS",
  "ok": true,
  "walletAccess": "allowed",
  "walletCount": 1,
  "walletReady": true,
  "ownerBound": true,
  "currentWebUserSet": true
}
```

`walletAccess` 값:

- `no_wallet`: 로컬 지갑 없음
- `allowed`: 현재 웹 사용자와 지갑 owner 일치
- `binding_required`: 기존 로컬 지갑에 owner 없음
- `web_user_required`: `setCurrentWebUser` 미호출
- `owner_mismatch`: 현재 웹 사용자와 owner 불일치

### 1-3. 로컬 지갑 데이터 삭제

Method: `deleteLocalWalletData`

현재 웹 사용자와 로컬 지갑 owner가 일치하고, 활성 네이티브 인증 세션이 있을 때 전체 로컬 지갑 데이터를 삭제한다. 다른 owner 지갑 삭제를 명시적으로 허용해야 하는 운영 화면에서는 `allowOwnerMismatch=true`를 함께 보낼 수 있다.

Request:

```json
{
  "action": "DELETE_LOCAL_WALLET_DATA",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "issuedAt": "2026-05-13T10:00:00Z"
}
```

Response:

```json
{
  "action": "DELETE_LOCAL_WALLET_DATA",
  "ok": true,
  "deleted": true,
  "walletReady": false
}
```

### 1-4. 로컬 지갑 삭제 후 로그아웃

Method: `logoutAndDeleteLocalWalletData`

웹에서 “지갑 삭제 + 로그아웃” 버튼에 연결할 때 사용한다. 활성 네이티브 인증 세션이 필요하다.

Request:

```json
{
  "action": "LOGOUT_AND_DELETE_LOCAL_WALLET_DATA",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "issuedAt": "2026-05-13T10:00:00Z"
}
```

Response:

```json
{
  "action": "LOGOUT_AND_DELETE_LOCAL_WALLET_DATA",
  "ok": true,
  "deleted": true,
  "walletReady": false,
  "sessionUnlocked": false,
  "walletDisconnected": true
}
```


### 2. 네이티브 인증 요청

Method: `requestNativeAuth`

Request:

```json
{
  "action": "REQUEST_NATIVE_AUTH",
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "issuedAt": "2026-05-08T10:15:30Z",
  "method": "biometric",
  "reason": "wallet-login",
  "backendRequest": {
    "baseUrl": "https://<core-testnet-base-url>",
    "endpoint": "/auth/login",
    "body": {
      "loginType": "wallet",
      "holderDid": "did:xrpl:1:rHolder..."
    }
  }
}
```

필수 규칙:

- `requestId`: UUID
- `issuedAt`: ISO-8601 UTC
- 요청 TTL: 30초
- `method`: `pin` | `pattern` | `biometric`
- 웹은 PIN/패턴 값을 보내지 않는다.

Response:

```json
{
  "action": "REQUEST_NATIVE_AUTH",
  "ok": true,
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "method": "biometric",
  "reason": "wallet-login",
  "authenticated": true,
  "failedAttempts": 0,
  "remainingAttempts": 5,
  "failureThreshold": 5,
  "emailVerificationRequired": false,
  "sessionUnlocked": true,
  "sessionRemainingMs": 1800000,
  "sessionExpiresAtMs": 1778280000000,
  "xrpPaymentAuthReady": false,
  "xrpPaymentAuthRemainingMs": 0,
  "backendResponse": {
    "ok": true
  }
}
```

실패 응답 예시:

```json
{
  "action": "REQUEST_NATIVE_AUTH",
  "ok": false,
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "method": "pin",
  "reason": "wallet-login",
  "authenticated": false,
  "failedAttempts": 5,
  "remainingAttempts": 0,
  "failureThreshold": 5,
  "emailVerificationRequired": true,
  "sessionUnlocked": false,
  "sessionRemainingMs": 0,
  "error": "인증 5회 실패로 이메일 인증이 필요합니다."
}
```

### 3. PIN 재설정 요청

웹이 PIN 값을 직접 받거나 전달하지 않고, Android 네이티브 PIN 재설정 화면을 띄울 때 사용한다.

Method: `requestPinReset`

Request:

```json
{
  "action": "REQUEST_PIN_RESET",
  "requestId": "f76c7a7e-9e8d-4636-b9e1-6b5964240f10",
  "issuedAt": "2026-05-12T00:10:30Z",
  "reason": "user-request"
}
```

필수 규칙:

- `requestId`: UUID
- `issuedAt`: ISO-8601 UTC
- 요청 TTL: 30초
- 웹은 새 PIN 값을 보내지 않는다.
- Android가 네이티브 PIN 재설정 화면을 띄우고, 4자리 입력 완료 시 앱 내부 암호화 저장소에 저장한다.
- 인증 실패 5회로 `emailVerificationRequired=true` 상태면 재설정 요청은 거부된다. 이 경우 웹에서 이메일 인증 완료 후 `completeEmailVerification`을 먼저 호출한다.

성공 응답:

```json
{
  "action": "REQUEST_PIN_RESET",
  "ok": true,
  "requestId": "f76c7a7e-9e8d-4636-b9e1-6b5964240f10",
  "reason": "user-request",
  "reset": true,
  "failedAttempts": 0,
  "remainingAttempts": 5,
  "failureThreshold": 5,
  "emailVerificationRequired": false,
  "sessionUnlocked": true,
  "sessionRemainingMs": 1800000
}
```

취소/실패 응답:

```json
{
  "action": "REQUEST_PIN_RESET",
  "ok": false,
  "requestId": "f76c7a7e-9e8d-4636-b9e1-6b5964240f10",
  "reset": false,
  "failedAttempts": 0,
  "remainingAttempts": 5,
  "failureThreshold": 5,
  "emailVerificationRequired": false,
  "error": "사용자가 PIN 재설정을 취소했습니다."
}
```

호출 흐름:

```text
웹의 PIN 재설정 버튼 클릭
-> requestPinReset 호출
-> Android가 requestId/action/issuedAt/origin 검증
-> Android가 네이티브 PIN 재설정 화면 표시
-> 사용자가 새 PIN 4자리 입력
-> Android가 PIN을 저장하고 실패 횟수 초기화 + 30분 세션 시작
-> REQUEST_PIN_RESET 결과를 onAndroidResult로 반환
```

### 4. 이메일 인증 완료 처리

웹에서 이메일 인증을 자체적으로 끝낸 뒤, Android 쪽 실패 횟수를 초기화할 때 사용한다.

Method: `completeEmailVerification`

Request:

```json
{
  "action": "COMPLETE_EMAIL_VERIFICATION",
  "requestId": "74b1837c-7252-43c2-8652-5c685e86461a",
  "issuedAt": "2026-05-08T10:20:30Z"
}
```

Response:

```json
{
  "action": "COMPLETE_EMAIL_VERIFICATION",
  "ok": true,
  "requestId": "74b1837c-7252-43c2-8652-5c685e86461a",
  "failedAttempts": 0,
  "remainingAttempts": 5,
  "failureThreshold": 5,
  "emailVerificationRequired": false,
  "sessionUnlocked": false,
  "sessionRemainingMs": 0
}
```

### 5. 로그아웃

Method: `logout`

Request:

```json
{
  "action": "LOGOUT",
  "requestId": "74b1837c-7252-43c2-8652-5c685e86461a",
  "issuedAt": "2026-05-08T10:25:30Z"
}
```

Response:

```json
{
  "action": "LOGOUT",
  "ok": true,
  "requestId": "74b1837c-7252-43c2-8652-5c685e86461a",
  "sessionUnlocked": false,
  "sessionRemainingMs": 0
}
```

### 인증 호출 흐름

```text
웹 로그인 버튼 클릭
-> requestNativeAuth 호출
-> Android가 requestId/action/issuedAt 검증
-> Android가 네이티브 PIN/패턴/지문 인증 화면 실행
-> 인증 성공 시 Android가 backendRequest를 직접 호출
-> Android가 결과를 onAndroidResult로 웹에 반환
```

이메일 인증 포함 흐름:

```text
웹 로그인 버튼 클릭
-> requestNativeAuth 호출
-> 인증 실패 누적
-> REQUEST_NATIVE_AUTH 응답의 emailVerificationRequired=true 확인
-> 웹이 자체 이메일 인증 화면/백엔드 호출 수행
-> 이메일 인증 성공 후 completeEmailVerification 호출
-> Android 실패 횟수 초기화
-> 이후 requestNativeAuth 재시도
```

## 지갑 / DID 브리지

### 1. 브리지 연결 확인

Method: `checkBridge`

Response: `Connected`

### 2. 지갑 생성

Method: `createWallet`

Request:

```json
{
  "overwrite": false
}
```

Response 주요 필드:

```json
{
  "action": "CREATE_WALLET",
  "ok": true,
  "account": "r...",
  "publicKey": "...",
  "authPublicKey": "...",
  "did": "did:xrpl:1:r...",
  "mnemonic": "apple banana ...",
  "didDocument": "{...}"
}
```

### 3. 지갑 조회

Method: `getWalletInfo`

Request:

```json
{}
```

규칙:

- 활성 인증 세션이 있어야 한다. (세션 만료 시 또는 로그아웃 후 호출하면 에러 반환)

Response는 `CREATE_WALLET`과 동일한 형태다.

### 3-1. 지갑 목록 조회

Method: `listWallets`

Request:

```json
{}
```

Response 주요 필드:

```json
{
  "action": "LIST_WALLETS",
  "ok": true,
  "activeAccount": "r...",
  "wallets": [
    {
      "account": "r...",
      "did": "did:xrpl:1:r...",
      "name": "Account 0",
      "derivationIndex": 0,
      "mnemonicHash": "a1b2c3d4",
      "hasMnemonic": true,
      "isActive": true
    }
  ]
}
```

### 3-2. 활성 지갑 전환

Method: `switchWallet`

Request:

```json
{
  "account": "r..."
}
```

Response:
- 성공 시 `SWITCH_WALLET` + 활성 지갑 정보 반환
- 실패 시 `error` 반환

### 3-3. 계정 이름 변경

Method: `setAccountName`

Request:

```json
{
  "action": "SET_ACCOUNT_NAME",
  "requestId": "uuid",
  "issuedAt": "2026-05-10T00:00:00Z",
  "name": "업무용 계정"
}
```

규칙:
- `name` 필수
- `account` 생략 시 현재 활성 계정 대상

### 3-4. 비밀문구 백업 생성(기존 계정 업그레이드)

Method: `upgradeToMnemonic`

Request:

```json
{
  "action": "UPGRADE_TO_MNEMONIC",
  "requestId": "uuid",
  "issuedAt": "2026-05-10T00:00:00Z"
}
```

Response 주요 필드:

```json
{
  "action": "UPGRADE_TO_MNEMONIC",
  "ok": true,
  "account": "r...",
  "mnemonic": "apple banana ...",
  "did": "did:xrpl:1:r..."
}
```

### 3-5. 계정 추가(파생)

Method: `deriveNextAccount`

Request:

```json
{
  "action": "DERIVE_NEXT_ACCOUNT",
  "requestId": "uuid",
  "issuedAt": "2026-05-10T00:00:00Z",
  "name": "Account 2"
}
```

규칙:
- 현재 활성 계정이 `hasMnemonic=true` 상태여야 한다.
- mnemonic 없는 계정이면 먼저 `upgradeToMnemonic` 실행 후 파생한다.

Response:
- 성공 시 `DERIVE_NEXT_ACCOUNT` + 새 활성 지갑 정보 반환
- 실패 시 `error` 반환

### 3-6. 계정 삭제

Method: `removeWallet`

현재 정책:
- 계정 삭제는 **비활성화(disabled)** 상태다.
- 웹 UI에서는 호출을 차단한다.
- 운영 정책이 확정되기 전까지 삭제 플로우를 사용하지 않는다.

### 3-7. 참고사항(웹 예외처리 권장)

복구/계정관리 웹 구현 시 아래 분기를 기본으로 처리한다.

1) `RESTORE_WALLET`
- `ok=false`: `error` 표시 후 종료
- `ok=true` && `reusedExistingAccount=true`: 기존 계정 재사용 안내
- `ok=true` && `reusedExistingAccount=false`: 새 계정 추가 안내
- `holderDidSetRegistrationRequired=true`: `submitHolderDidSet` 실행(자동 또는 수동 유도)

2) `DERIVE_NEXT_ACCOUNT`
- `ok=false` && mnemonic 미준비 계정: 먼저 `upgradeToMnemonic` 유도
- 성공 시 `listWallets` 재조회 후 selector/UI 동기화

3) `UPGRADE_TO_MNEMONIC`
- `ok=true`: 응답의 `mnemonic`을 즉시 1회 표시하고 로그/원격전송 금지
- `ok=false`: 실패 사유(`error`) 그대로 사용자 안내

4) `LOGOUT`
- 성공 시 로컬 UI 캐시(활성 계정, VC/SD-JWT 임시값, selector)를 즉시 초기화
- 로그아웃 직후 `getWalletInfo` 자동 호출 금지(인증 세션 없으면 실패)

5) 공통
- 민감정보(`seed`, `mnemonic`)는 콘솔/분석SDK/원격 로그에 저장하지 않는다.
- 브리지 결과는 항상 `action + ok + error` 기준으로 분기하고, 성공 가정 코드를 두지 않는다.
- testnet 전환 이후에는 계정 활성 여부를 testnet faucet/funding 기준으로 확인한다.
- `actNotFound`는 holder/issuer 계정이 testnet에서 미활성일 가능성이 가장 높다.
- 신규 지갑 생성 직후 XRPL ledger에는 아직 계정이 없을 수 있다. 이 상태는 오류가 아니라 `accountActivated=false`, `depositRequired=true` 상태로 취급하고, 웹은 입금 주소/QR을 보여줘야 한다.

### 4. 자산 조회

Method: `getWalletAssets`

Request:

```json
{}
```

Response 주요 필드:

```json
{
  "action": "GET_WALLET_ASSETS",
  "ok": true,
  "account": "r...",
  "accountActivated": true,
  "depositRequired": false,
  "xrpBalanceDrops": "12345678",
  "xrpBalanceXrp": "12.345678",
  "ownerCount": 2,
  "sequence": 17,
  "trustLineCount": 1,
  "lines": [
    {
      "currency": "USD",
      "issuer": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
      "balance": "10",
      "limit": "1000",
      "limitPeer": "0"
    }
  ],
  "checkedAtUtc": "2026-05-09T12:00:00Z"
}
```

미활성 신규 계정 응답 예시:

```json
{
  "action": "GET_WALLET_ASSETS",
  "ok": true,
  "account": "r...",
  "accountActivated": false,
  "depositRequired": true,
  "trustLineCount": 0,
  "lines": [],
  "checkedAtUtc": "2026-05-09T12:00:00Z",
  "errorCode": "XRPL_ACCOUNT_NOT_ACTIVATED",
  "errorTitle": "XRPL 계정 활성화 필요",
  "errorHint": "이 주소로 XRP를 입금한 뒤 자산 조회를 다시 실행하세요.",
  "error": "XRPL account is not activated. Deposit XRP to this address first."
}
```

웹 처리 기준:

- `ok=true && depositRequired=true`: 오류 화면으로 보내지 말고 입금 주소/QR을 표시한다.
- `CredentialAccept`, `DIDSet`, 송금은 ledger sequence가 필요하므로 입금 전에는 실행할 수 없다.
- 입금 후 `getWalletAssets`를 다시 호출해 `accountActivated=true`가 되는지 확인한다.

호출 흐름:

```text
웹이 자산 조회 버튼 클릭
-> getWalletAssets 호출
-> Android가 현재 holder account 기준 account_info / account_lines 조회
-> 활성 계정이면 XRP 잔액 + trust line 목록 반환
-> 미활성 계정이면 ok=true + depositRequired=true 반환
```

### 5. 입금 정보 조회

Method: `getWalletDepositInfo`

Request:

```json
{}
```

Response 주요 필드:

```json
{
  "action": "GET_WALLET_DEPOSIT_INFO",
  "ok": true,
  "account": "r...",
  "did": "did:xrpl:1:r...",
  "receiveAddress": "r...",
  "qrPayload": "r..."
}
```

호출 흐름:

```text
웹이 입금 정보 조회 버튼 클릭
-> getWalletDepositInfo 호출
-> Android가 현재 holder account를 입금 주소로 반환
-> 웹이 주소 표시 / QR 미리보기 렌더링
```

### 6. 입금 주소 복사

Method: `copyWalletAddress`

Request:

```json
{}
```

Response 주요 필드:

```json
{
  "action": "COPY_WALLET_ADDRESS",
  "ok": true,
  "account": "r...",
  "copied": true
}
```

### 7. XRP 송금

Method: `submitXrpPayment`

Request:

```json
{
  "destinationAddress": "rDestination...",
  "amountXrp": "1.25",
  "amountDrops": "1250000"
}
```

규칙:

- 활성 인증 세션이 있어야 한다.
- 송금 직전 `requestNativeAuth(reason="xrp-payment")`가 성공해야 한다.
- `xrp-payment` 재인증은 60초 동안 유효하고, 실제 송금 제출 시 1회 소비된다.
- 이메일 인증 필요 상태에서는 거부된다.
- `amountXrp` 또는 `amountDrops` 중 하나는 필수다.
- 둘 다 오면 `amountDrops`를 우선 사용한다.
- `amountXrp`는 소수점 이하 최대 6자리까지 지원한다.

Response 주요 필드:

```json
{
  "action": "SUBMIT_XRP_PAYMENT",
  "ok": true,
  "sourceAccount": "rSource...",
  "destinationAddress": "rDestination...",
  "requestedAmountXrp": "1.25",
  "requestedAmountDrops": "1250000",
  "amountXrp": "1.25",
  "amountDrops": "1000000",
  "txHash": "ABC123...",
  "engineResult": "tesSUCCESS"
}
```

호출 흐름:

```text
웹이 수신 주소 / drops 금액 입력
-> getAuthStatus 호출
-> availableMethods 확인
-> requestNativeAuth(action=REQUEST_NATIVE_AUTH, reason=xrp-payment) 호출
-> Android 네이티브 인증 성공
-> submitXrpPayment 호출
-> Android가 세션 상태 / xrp-payment 재인증 / 주소 / 금액 검증
-> XRPL Payment 제출
-> tx hash / engine result 반환
-> 웹이 자산 / 거래내역 자동 새로고침
```

재인증 없이 바로 송금을 호출하면:

```json
{
  "action": "SUBMIT_XRP_PAYMENT",
  "ok": false,
  "error": "송금 전 재인증이 필요합니다."
}
```

### 7-1. 거래내역 조회

Method: `getWalletTransactions`

Request:

```json
{
  "limit": 10
}
```

규칙:

- 현재 holder account 기준 최근 거래를 조회한다.
- `limit`는 1~50 사이로 정규화된다.
- 1차 구현은 최근 내역과 Payment 중심 방향(`incoming` / `outgoing` / `other`)만 내려준다.

Response 주요 필드:

```json
{
  "action": "GET_WALLET_TRANSACTIONS",
  "ok": true,
  "account": "rHolder...",
  "count": 2,
  "transactions": [
    {
      "hash": "ABC123...",
      "transactionType": "Payment",
      "direction": "outgoing",
      "amountDrops": "1000000",
      "amountXrp": "1",
      "feeDrops": "12",
      "feeXrp": "0.000012",
      "validated": true,
      "result": "tesSUCCESS",
      "dateUtc": "2026-05-09T10:30:00Z"
    }
  ]
}
```

호출 흐름:

```text
웹이 조회 건수 입력
-> getWalletTransactions 호출
-> Android가 holder account 기준 account_tx 조회
-> 최근 거래 요약을 onAndroidResult로 반환
```

### 8. seed 내보내기

Method: `exportWalletSeed`

Request:

```json
{
  "action": "EXPORT_WALLET_SEED",
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "issuedAt": "2026-05-09T12:00:00Z"
}
```

규칙:

- 활성 인증 세션이 있어야 한다.
- 이메일 인증 필요 상태에서는 거부된다.
- 응답의 `seed`는 민감정보이므로 웹 로그, 분석 SDK, 원격 저장소에 남기면 안 된다.

Response 주요 필드:

```json
{
  "action": "EXPORT_WALLET_SEED",
  "ok": true,
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "account": "r...",
  "did": "did:xrpl:1:r...",
  "seed": "sEd...."
}
```

### 9. 비밀구문 내보내기

Method: `exportWalletMnemonic`

Request:

```json
{
  "action": "EXPORT_WALLET_MNEMONIC",
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "issuedAt": "2026-05-09T12:00:00Z"
}
```

규칙:

- 활성 인증 세션이 있어야 한다.
- 이메일 인증 필요 상태에서는 거부된다.
- 응답의 `mnemonic`은 민감정보이므로 웹 로그, 분석 SDK, 원격 저장소에 남기면 안 된다.

Response 주요 필드:

```json
{
  "action": "EXPORT_WALLET_MNEMONIC",
  "ok": true,
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "account": "r...",
  "did": "did:xrpl:1:r...",
  "mnemonic": "apple banana ..."
}
```

### 10. 네이티브 복구 문구 백업 화면

웹이 복구 문구를 직접 표시하지 않고 Android 네이티브 화면에서 보여줄 때 사용한다. UI는 `app/src/main/assets/복구문구 백업.png` 시안을 기준으로 구현한다.

Method: `requestMnemonicBackup`

Request:

```json
{
  "action": "REQUEST_MNEMONIC_BACKUP",
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "issuedAt": "2026-05-12T10:00:00Z"
}
```

규칙:

- 활성 인증 세션이 있어야 한다.
- 이메일 인증 필요 상태에서는 거부된다.
- Android가 복구 문구를 네이티브 화면에 표시하고, 웹 콜백에는 mnemonic 원문을 반환하지 않는다.

Response:

```json
{
  "action": "REQUEST_MNEMONIC_BACKUP",
  "ok": true,
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "confirmed": true
}
```

### 11. 네이티브 지갑 복구 화면

웹이 복구 문구 입력값을 직접 받지 않고 Android 네이티브 화면에서 입력받을 때 사용한다. UI는 `app/src/main/assets/지갑복구.png` 시안을 기준으로 구현한다.

Method: `requestWalletRestore`

Request:

```json
{
  "action": "REQUEST_WALLET_RESTORE",
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "issuedAt": "2026-05-12T10:00:00Z",
  "overwrite": true,
  "autoRegisterDidSet": false
}
```

규칙:

- Android가 12/24단어 입력 화면을 띄운다.
- 웹은 mnemonic 원문을 받거나 전달하지 않는다.
- 복구 성공 시 holder auth key가 새로 생성될 수 있으므로 `holderDidSetRegistrationRequired`를 확인한다.

Response 주요 필드:

```json
{
  "action": "REQUEST_WALLET_RESTORE",
  "ok": true,
  "requestId": "3d6109ea-4a06-4c34-a9f8-5f9be4e3a8f5",
  "restored": true,
  "holderDidSetRegistrationRequired": true,
  "account": "r...",
  "did": "did:xrpl:1:r...",
  "didDocument": "{...}"
}
```

### 12. 지갑 복구

Method: `restoreWallet`

Request:

```json
{
  "seed": "sEd....",
  "mnemonic": "apple banana ...",
  "overwrite": true,
  "autoRegisterDidSet": true
}
```

규칙:

- `seed` 또는 `mnemonic` 중 하나는 필수다. (둘 다 오면 `mnemonic` 우선)
- `seed`는 XRPL base58 seed여야 한다.
- `mnemonic`은 12개 단어의 BIP-39 구문이어야 한다.
- 기존 지갑이 있으면 `overwrite: true`가 필요하다.
- `autoRegisterDidSet`는 웹 테스트 UI 편의용 옵션이다. `true`면 복구 성공 콜백 직후 `submitHolderDidSet`를 이어서 호출한다.
- 복구 시 holder auth key는 새로 생성되므로 `holderDidSetRegistrationRequired` 값을 보고 DIDSet 재등록 필요 여부를 판단한다.

Response 주요 필드:

```json
{
  "action": "RESTORE_WALLET",
  "ok": true,
  "restored": true,
  "mnemonic": "apple banana ...",
  "restoredWithExistingAuthKey": true,
  "holderDidSetRegistrationRequired": false,
  "account": "r...",
  "publicKey": "...",
  "authPublicKey": "...",
  "did": "did:xrpl:1:r...",
  "didDocument": "{...}"
}
```

호출 흐름:

```text
웹이 seed 또는 mnemonic 입력 UI 제공
-> restoreWallet 호출
-> Android가 입력값 유효성 검증 및 암호화 저장
-> 새 holder auth key 생성
-> account / DID / DID Document 재구성
-> holderDidSetRegistrationRequired 값으로 DIDSet 재등록 필요 여부 판단
-> 재등록 필요 && autoRegisterDidSet=true면 submitHolderDidSet 자동 호출
-> 재등록 필요 && autoRegisterDidSet=false면 웹이 submitHolderDidSet 수동 호출
```

### 11. Holder DIDSet 등록

Method: `submitHolderDidSet`

Request:

```json
{
  "didDocumentUri": "https://holder.example/did.json"
}
```

호출 흐름:

```text
getWalletInfo
-> didDocument 확보
-> submitHolderDidSet
-> XRPL DIDSet에 URI + canonical DID Document hash 등록
```

등록 성공 또는 상태 조회 응답 주요 필드:

```json
{
  "didSetRegistered": true,
  "holderDidSetRegistered": true,
  "holderDidSetRegistrationRequired": false,
  "didRegistrationRequired": false,
  "didRegistrationLabel": "did 등록됨",
  "didLedgerEntryFound": true,
  "didDocumentHashMatches": true,
  "expectedDidDocumentDataHash": "1220...",
  "ledgerDidDocumentDataHash": "1220..."
}
```

웹 표시 기준:

- `didSetRegistered === false` 또는 `didRegistrationRequired === true`면 지갑/증명서 제출 준비 화면에 `did 등록하기` 문구와 등록 버튼을 표시한다.
- `didSetRegistered === true`면 `did 등록됨` 또는 등록 버튼 숨김으로 처리한다.
- `didDocumentHashMatches === false`면 기존 DIDSet은 있지만 현재 holder auth key의 DID Document와 ledger hash가 다르다는 뜻이다. 이 경우에도 `did 등록하기`로 재등록을 유도한다.
- `getWalletInfo`, `listWallets`, `switchWallet`, `createWallet`, `deriveNextAccount`, `setAccountName`, `upgradeToMnemonic` 응답은 활성 지갑 기준 DIDSet 상태 필드를 포함한다. 웹은 지갑 전환 직후 이 응답의 `didRegistrationLabel`로 잔액 아래 링크 문구를 즉시 갱신한다.
- DID는 계정별 값이다. 웹은 `account`/`holderAccount`와 `did`/`holderDid`를 같은 응답에서 받은 값으로 함께 갱신해야 하며, 이전 계정의 DID를 캐시해서 재사용하면 안 된다.
- `didAccountBindingValid === false`가 오면 앱 저장소의 account-DID 바인딩이 깨진 상태로 보고 지갑 재조회/복구 점검을 유도한다.

계정 바인딩 필드:

```json
{
  "account": "rHolder...",
  "holderAccount": "rHolder...",
  "did": "did:xrpl:1:rHolder...",
  "holderDid": "did:xrpl:1:rHolder...",
  "didBoundAccount": "rHolder...",
  "didAccountBindingValid": true
}
```

### 12. Holder DIDSet 상태 조회

Method: `checkHolderDidSet`

Request:

```json
{}
```

호출 흐름:

```text
checkHolderDidSet
-> 활성 지갑 DID Document 생성
-> canonical DID Document hash 계산
-> XRPL account_objects(type=did) 조회
-> ledger Data hash와 현재 DID Document hash 비교
-> did 등록하기 / did 등록됨 표시값 반환
```

## Credential / XRPL 브리지

### 1. VC 저장

Method: `saveVC`

지원 입력:

- expanded JSON VC
- `vcJwt`
- `credentialJwt`
- `credential`
- `sdJwt`
- `vcJson`
- `credentialPayload`
- `metadata`

backend prepare 응답의 `credentialPayload` wrapper를 그대로 넘길 수 있다. Android는 다음 alias를 순서대로 해석한다.

- credential 원문: `sdJwt`, `credentialJwt`, `vcJwt`, `vcJson`, `credentialPayload`, `credential`
- metadata: top-level `metadata` 우선, 없으면 `credentialPayload.metadata`
- selective disclosure: top-level `selectiveDisclosure` 우선, 없으면 `credentialPayload.selectiveDisclosure`
- credentialId: request `credentialId`, `metadata.credentialId`, credential `credentialId/id/jti`
- issuer: `metadata.issuerDid`, `metadata.issuerAccount`, credential status
- holder: `metadata.holderDid`, `metadata.holderXrplAddress`, active wallet
- type/hash/date: `metadata.credentialType`, `metadata.vcHash`, `metadata.issuedAt`, `metadata.expiresAt`

issuer 정규화 기준:

- `metadata.issuerAccount` 또는 `credentialPayload.metadata.issuerAccount`가 실제 XRPL classic address면 Android는 저장/검증용 `issuerDid`를 `did:xrpl:1:{issuerAccount}`로 보정한다.
- 이 보정은 SD-JWT issuer payload나 legacy `issuerDid`에 `did:xrpl:1:rIssuer` 같은 placeholder가 남아 있어도 backend prepare metadata의 실제 issuer account를 우선하기 위한 것이다.
- `credentialStatus.issuer`, `issuerAccount`, `issuerDid`의 account 부분은 최종적으로 같은 실제 XRPL classic address여야 한다.
- `issuerAccount` 예시에는 `rIssuer` 같은 placeholder를 쓰지 않는다.

SD-JWT 예시:

```json
{
  "credential": "<issuer-jwt>~<disclosure-1>~<disclosure-2>"
}
```

backend prepare credentialPayload 예시:

```json
{
  "action": "SAVE_VC",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "issuedAt": "2026-05-12T00:00:00Z",
  "credentialId": "200",
  "credentialPayload": {
    "format": "dc+sd-jwt",
    "sdJwt": "<compact-sd-jwt>",
    "credentialJwt": "<legacy-alias-same-value>",
    "credential": null,
    "selectiveDisclosure": {
      "disclosablePaths": [
        "legalEntity.name",
        "legalEntity.businessRegistrationNumber"
      ]
    },
    "metadata": {
      "credentialId": 200,
      "credentialType": "KYC_CREDENTIAL",
      "issuerDid": "did:xrpl:1:rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
      "issuerAccount": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
      "holderDid": "did:xrpl:1:rf7J73nExampleHolderAddress",
      "holderXrplAddress": "rf7J73nExampleHolderAddress",
      "vcHash": null,
      "xrplTxHash": "...",
      "credentialStatusId": null,
      "issuedAt": null,
      "expiresAt": null,
      "format": "dc+sd-jwt"
    }
  }
}
```

Response:

```json
{
  "action": "SAVE_VC",
  "ok": true,
  "source": "Android",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "credentialId": "200",
  "issuerDid": "did:xrpl:1:rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
  "issuerAccount": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
  "holderDid": "did:xrpl:1:rf7J73nExampleHolderAddress",
  "holderAccount": "rf7J73nExampleHolderAddress",
  "credentialType": "KYC_CREDENTIAL",
  "saved": true,
  "format": "dc+sd-jwt",
  "hasSdJwt": true,
  "hasCredentialJwt": false
}
```

저장 전 holder DID/account가 active wallet과 다르면 실패한다. SD-JWT/VC 원문과 disclosure 원문은 Logcat에 출력하지 않는다.

### 2. 증명서 요약 조회

저장된 증명서의 화면 표시용 메타데이터만 반환한다. raw VC/SD-JWT 원문은 포함하지 않는다.
응답은 현재 활성 지갑의 `holderAccount`와 일치하는 증명서만 포함한다. 한 기기에 여러 지갑/계정이 있어도 다른 지갑에서 발급받은 증명서는 섞어서 반환하지 않는다.

Method: `getCredentialSummaries`

Request:

```json
{}
```

Response 주요 필드:

```json
{
  "action": "GET_CREDENTIAL_SUMMARIES",
  "ok": true,
  "activeAccount": "rHolder...",
  "filteredByHolderAccount": true,
  "count": 1,
  "credentials": [
    {
      "credentialId": "urn:uuid:...",
      "status": "active",
      "statusLabel": "활성",
      "issuedAt": "2026-05-12T00:00:00Z",
      "expiresAt": "2026-06-12T00:00:00Z",
      "issuerDid": "did:xrpl:1:rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
      "issuerAccount": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
      "holderDid": "did:xrpl:1:rf7J73nExampleHolderAddress",
      "holderAccount": "rf7J73nExampleHolderAddress",
      "credentialType": "ABC123...",
      "credentialKind": "https://kyvc.example/vct/legal-entity-kyc-v1",
      "format": "dc+sd-jwt",
      "accepted": true
    }
  ]
}
```

상태값:

- `active`: CredentialAccept 완료, 만료/비활성 아님
- `issued`: 발급 저장됨, 아직 CredentialAccept 전
- `inactive`: revoke 또는 XRPL 상태 동기화에서 inactive 확인
- `expired`: 만료일 지남
- `notYetValid`: 유효 시작일 전

웹 처리 기준:

- 증명서 카드/목록 화면은 이 브릿지를 우선 사용한다.
- 상세 검증이나 원문 제출이 필요할 때만 `listCredentials` 또는 저장된 credential payload를 사용한다.
- 지갑 전환 후에는 `getCredentialSummaries`를 다시 호출한다. 웹이 이전 지갑의 목록을 캐시하고 있으면 반드시 비운 뒤 새 응답으로 다시 그린다.
- `LIST_CREDENTIALS`, `GET_CREDENTIAL_SUMMARIES`, `REFRESH_CREDENTIAL_STATUSES`는 모두 활성 지갑 기준으로 필터링된 결과를 반환한다.

### 3. XRPL CredentialAccept 제출

Method: `submitToXRPL`

Request:

```json
{
  "credentialId": "urn:uuid:...",
  "issuerAccount": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
  "holderAccount": "rf7J73nExampleHolderAddress",
  "credentialType": "ABC123..."
}
```

입력 alias:

- `issuerAccount`, `issuerAddress`, `issuer`, `issuer_account`
- `holderAccount`, `holderXrplAddress`, `holder`, `subject`, `subjectAccount`, `holder_account`
- `credentialType`, `credentialTypeHex`, `credential_type`

Response:

```json
{
  "action": "SUBMIT_TO_XRPL",
  "ok": true,
  "source": "Android",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "credentialId": "200",
  "issuerAccount": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
  "holderAccount": "rf7J73nExampleHolderAddress",
  "credentialType": "KYC_CREDENTIAL",
  "txHash": "ABCDEF...",
  "credentialAcceptHash": "ABCDEF...",
  "engineResult": "tesSUCCESS",
  "acceptedAt": "2026-05-12T16:01:00Z"
}
```

`txHash`와 `credentialAcceptHash`는 같은 XRPL CredentialAccept transaction hash다.

호출 흐름:

```text
실제 SD-JWT 요청
-> saveVC
-> submitToXRPL
-> checkCredentialStatus
-> active: true / accepted: true 확인
```

### 4. XRPL 상태 조회

Method: `checkCredentialStatus`

Request:

```json
{
  "credentialId": "urn:uuid:...",
  "issuerAccount": "rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
  "holderAccount": "rf7J73nExampleHolderAddress",
  "credentialType": "KYC_CREDENTIAL"
}
```

`credentialId`만 전달하면 저장된 VC에서 issuer/holder/type을 복원한다. 저장된 VC가 없으면 `issuerAccount`, `holderAccount`, `credentialType`을 직접 전달해야 한다.

Response 주요 필드:

```json
{
  "action": "CHECK_CREDENTIAL_STATUS",
  "ok": true,
  "credentialId": "200",
  "found": true,
  "credentialEntryFound": true,
  "active": true,
  "accepted": true,
  "credentialAccepted": true,
  "txHash": "ABCDEF..."
}
```

호환 alias:

- `found` = `credentialEntryFound`
- `accepted` = `credentialAccepted`
- `active` = ledger entry가 존재하고 accepted 상태이며 만료/비활성 상태가 아닌 경우
- `txHash`는 로컬에 저장된 `credentialAcceptHash`가 있을 때 포함된다.

## Issuer / Verifier 브리지

### 1. 실제 SD-JWT 요청

Method: `requestIssuerCredential`

Request 예시:

```json
{
  "coreBaseUrl": "https://<core-testnet-base-url>",
  "format": "dc+sd-jwt",
  "jurisdiction": "KR",
  "assuranceLevel": "STANDARD"
}
```

호출 흐름:

```text
getWalletInfo
-> requestIssuerCredential
-> saveVC
```

### 2. 실제 Credential 인증

Method: `verifyCredentialWithServer`

Request 예시:

```json
{
  "coreBaseUrl": "https://<core-testnet-base-url>",
  "vcJson": "{...}",
  "require_status": true,
  "status_mode": "xrpl"
}
```

### 3. 실제 Nonce 요청

Method: `requestVerifierChallenge`

Request 예시:

```json
{
  "coreBaseUrl": "https://<core-testnet-base-url>",
  "aud": "https://<core-testnet-base-url>",
  "presentationDefinition": {
    "id": "wallet-direct-kyc-test-v1",
    "acceptedFormat": "dc+sd-jwt",
    "acceptedVct": ["https://kyvc.example/vct/legal-entity-kyc-v1"],
    "acceptedJurisdictions": ["KR"],
    "minimumAssuranceLevel": "STANDARD",
    "requiredDisclosures": [
      "legalEntity.type",
      "representative.name",
      "representative.birthDate",
      "representative.nationality",
      "beneficialOwners[].name",
      "beneficialOwners[].birthDate",
      "beneficialOwners[].nationality"
    ],
    "documentRules": []
  }
}
```

### 4. KB-JWT 생성

Method: `signMessage`

SD-JWT 요청 예시:

```json
{
  "challenge": "nonce-value",
  "domain": "https://<core-testnet-base-url>",
  "credentialId": "urn:uuid:...",
  "credential": "<issuer-jwt>~<disclosure-1>~<disclosure-2>",
  "selectedDisclosures": [
    "<disclosure-1>",
    "<disclosure-2>"
  ]
}
```

Response 주요 필드:

```json
{
  "action": "SIGN_MESSAGE",
  "ok": true,
  "format": "kyvc-sd-jwt-presentation-v1",
  "sdJwtKb": "<issuer-jwt>~<selected-disclosures>~<kb-jwt>",
  "didDocument": {
    "id": "did:xrpl:1:rHolder..."
  },
  "didDocuments": {
    "did:xrpl:1:rHolder...": {
      "id": "did:xrpl:1:rHolder..."
    }
  },
  "did_documents": {
    "did:xrpl:1:rHolder...": {
      "id": "did:xrpl:1:rHolder..."
    }
  }
}
```

Holder binding 규칙:

- KB-JWT header `kid`는 `did:xrpl:1:{holderAccount}#holder-key-1` 형식이다.
- SD-JWT issuer payload `cnf.kid`도 동일한 key id여야 한다.
- DID Document의 `id`는 holder DID와 같아야 한다.
- DID Document의 `verificationMethod[].id`와 `authentication[]`에는 같은 key id가 포함되어야 한다.
- `cnf.kid`가 `did#did#holder-key-1`처럼 DID prefix를 중복 포함하면 Android는 제출 전에 차단한다. 이 경우 발급 쪽 `cnf.kid` 생성 로직 수정 후 VC 재발급이 필요하다.
- DID Document는 DIDSet 등록 hash와 같은 기본 문서 구조를 사용한다. VP 제출 시점에 alias key를 임의 추가하면 ledger DIDSet Data hash와 달라져 Core 검증에 실패할 수 있다.

### 5. Verifier 제출

Method: `submitPresentationToVerifier`

SD-JWT 요청 예시:

```json
{
  "endpoint": "https://<core-testnet-base-url>/verifier/presentations/verify",
  "presentation": {
    "format": "kyvc-sd-jwt-presentation-v1",
    "definitionId": "wallet-direct-kyc-test-v1",
    "aud": "https://<core-testnet-base-url>",
    "nonce": "nonce-value",
    "sdJwtKb": "<issuer-jwt>~<selected-disclosures>~<kb-jwt>"
  },
  "didDocument": {
    "@context": [
      "https://www.w3.org/ns/did/v1",
      "https://w3id.org/security/jwk/v1"
    ],
    "id": "did:xrpl:1:rHolder..."
  },
  "credentialId": "urn:uuid:...",
  "require_status": true,
  "status_mode": "xrpl"
}
```

호출 흐름:

```text
requestIssuerCredential
-> saveVC
-> submitToXRPL
-> checkCredentialStatus(active=true)
-> verifyCredentialWithServer
-> requestVerifierChallenge
-> signMessage
-> submitPresentationToVerifier
```

## QR 브리지

### 1. 범용 QR 스캔

Method: `scanQRCode`

Request 예시:

```json
{
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d"
}
```

Response:

```json
{
  "action": "SCAN_QR_CODE",
  "ok": true,
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "qrData": "{...}",
  "actionType": "VC_ISSUE"
}
```

### 2. 증명서 발급 QR 스캔

발급자 화면의 credential offer / VC 발급 QR을 읽을 때 사용한다. 네이티브는 `app/src/main/assets/발급용큐알.png` 시안을 기준으로 QR 화면을 띄운다.

Method: `scanIssueQrCode`

Request 예시:

```json
{
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "issuedAt": "2026-05-12T10:00:00Z"
}
```

Response:

```json
{
  "action": "SCAN_ISSUE_QR_CODE",
  "ok": true,
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "mode": "scanned",
  "actionType": "VC_ISSUE",
  "qrData": "{\"type\":\"CREDENTIAL_OFFER\",\"offerId\":100,\"qrToken\":\"...\",\"expiresAt\":\"...\"}"
}
```

취소/실패 Response:

```json
{
  "action": "SCAN_ISSUE_QR_CODE",
  "ok": false,
  "source": "Android",
  "requestId": "50fd0de3-6705-478f-9535-d48cbbcd090d",
  "mode": "issue",
  "actionType": "VC_ISSUE",
  "error": "QR scan cancelled"
}
```

호출 흐름:

```text
웹의 증명서 발급 QR 버튼 클릭
-> scanIssueQrCode 호출
-> Android가 네이티브 QR 스캐너 표시
-> 사용자가 발급 QR을 흰색 정사각형 프레임 안에 맞춤
-> Android가 QR 원문을 재조립하지 않고 qrData로 SCAN_ISSUE_QR_CODE 반환
-> 웹이 반환값으로 issuer 발급 플로우 진행
```

### 3. 증명서 제출 QR 스캔

검증자 화면의 presentation request / 제출 요청 QR을 읽을 때 사용한다. 네이티브는 `app/src/main/assets/제출용큐알.png` 시안을 기준으로 QR 화면을 띄운다.

Method: `scanPresentationQrCode`

Request 예시:

```json
{
  "requestId": "4d14d98a-dbb1-4078-b3df-8cfa131f3377",
  "issuedAt": "2026-05-12T10:00:00Z"
}
```

Response:

```json
{
  "action": "SCAN_PRESENTATION_QR_CODE",
  "ok": true,
  "requestId": "4d14d98a-dbb1-4078-b3df-8cfa131f3377",
  "mode": "scanned",
  "actionType": "VP_REQUEST",
  "qrData": "{...}",
  "challenge": "...",
  "domain": "https://...",
  "endpoint": "https://..."
}
```

호출 흐름:

```text
웹의 증명서 제출 QR 버튼 클릭
-> scanPresentationQrCode 호출
-> Android가 네이티브 QR 스캐너 표시
-> 사용자가 제출 요청 QR을 흰색 정사각형 프레임 안에 맞춤
-> Android가 QR payload를 파싱하고 challenge/nonce가 있으면 로컬 guard에 등록
-> Android가 SCAN_PRESENTATION_QR_CODE 반환
-> 웹이 반환값으로 challenge/signMessage/submitPresentationToVerifier 흐름 진행
```

### 4. PC VP 로그인 QR

PC 로그인 QR payload:

```json
{
  "type": "VP_LOGIN_REQUEST",
  "requestId": "vp-login-request-id",
  "qrToken": "raw-qr-token"
}
```

Android 처리:

```text
QR 스캔
-> type=VP_LOGIN_REQUEST 확인
-> POST /api/mobile/auth/vp-login-requests/resolve
-> resolve.data.nonce / aud / expiresAt로 기존 verifier challenge 저장소 등록
-> 사용자가 저장된 법인 KYC SD-JWT Credential 선택
-> 기존 SD-JWT+KB presentationObject 생성 규칙으로 VP 생성
-> POST /api/mobile/auth/vp-login-requests/{requestId}/submit
-> 완료 화면 표시
```

Android submit body:

```json
{
  "qrToken": "raw-qr-token",
  "credentialId": 200,
  "vp": {
    "format": "kyvc-sd-jwt-presentation-v1",
    "definitionId": "kyvc-corporate-web-vp-login-v1",
    "aud": "kyvc-corporate-web-login",
    "nonce": "core-issued-nonce",
    "sdJwtKb": "ISSUER_JWT~DISCLOSURE~KB_JWT",
    "attachmentManifest": []
  },
  "didDocument": {
    "id": "did:xrpl:1:rHolder..."
  },
  "didDocuments": {
    "did:xrpl:1:rHolder...": {
      "id": "did:xrpl:1:rHolder..."
    }
  },
  "did_documents": {
    "did:xrpl:1:rHolder...": {
      "id": "did:xrpl:1:rHolder..."
    }
  },
  "deviceId": "android-device-id"
}
```

주의:

- `WalletBridge.signMessage()` 내부 SD-JWT/KB-JWT 생성 로직과 presentation object 구조는 변경하지 않는다.
- Android는 JWT Cookie, access token, refresh token, PC complete API를 처리하지 않는다.
- `qrToken`, `vp`, `sdJwtKb`, SD-JWT 원문, disclosure 원문, JWT 원문은 로그에 남기지 않는다.
- 디버깅 로그는 `requestId`, `credentialId`, holder DID, holder binding key id, API 성공/실패 여부, error code 수준만 허용한다.
- holder binding 진단은 `WalletBridge` 로그의 `vp.login.holder.binding` 이벤트에서 확인한다.

공통 주의:

- 기존 `scanQRCode`는 계속 동작하지만, 운영 웹에서는 용도별 브릿지(`scanIssueQrCode`, `scanPresentationQrCode`)를 우선 사용한다.
- QR 화면은 WebView 페이지 이동이 아니라 WebView 위에 표시되는 네이티브 오버레이다.
- `scanIssueQrCode`의 `qrData`는 QR에 들어 있던 raw string 그대로다. JSON parse/검증은 WebView 또는 backend가 수행한다.
- Native는 PC Credential Offer QR 값을 필드별로 재조립하지 않는다. QR 값이 JSON 문자열이어도 URL이어도 그대로 반환한다.
- 스캔한 원문 `qrData`에는 민감한 challenge나 endpoint가 들어갈 수 있으므로 원격 로그에 저장하지 않는다.

## 네이티브 증명서 화면 브리지

아래 화면은 WebView 페이지 이동이 아니라 Android 네이티브 오버레이로 표시한다. 웹은 화면 진입 요청과 결과 콜백만 처리한다.

지원 화면:

- `requestCredentialIssueComplete`: `발급완료.png`
- `requestCredentialIssueConfirm`: 발급 확인 화면. 현재 기준 시안은 `발급확인0.png`이며, 발급 상세 정보와 XRP 잔액/네트워크 수수료/등록 후 사용 가능 잔액을 표시한다.
- `requestCredentialDetail`: `증명서 상세.png`
- `requestCredentialSubmit`: `증명서 제출.png`. 제출 QR 스캔 후 실제 제출 직전에 표시되는 화면이며, 발급기관 선택과 제출 문서 hash 확인/체크를 담당한다.

공통 Request 예시:

```json
{
  "action": "REQUEST_CREDENTIAL_DETAIL",
  "requestId": "0c8f6d1a-3648-4cb6-a1e1-515ce60f5dd5",
  "issuedAt": "2026-05-12T10:00:00Z",
  "issuerName": "법원행정처",
  "requesterName": "신한은행",
  "credentialTitle": "법인등록증명서",
  "submitCredentialTitle": "법인 KYC 증명서",
  "holderName": "주식회사 케이와이브이씨",
  "registrationNumber": "110111-1234567",
  "companyType": "주식회사",
  "establishedAt": "2020년 3월 15일",
  "representativeName": "홍길동",
  "beneficialOwnerName": "이현수",
  "agentName": "김계와",
  "address": "서울특별시 강남구 테헤란로 123",
  "did": "DID:kyvc:corp:240315",
  "issuerDid": "did:xrpl:1:rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
  "holderDid": "did:xrpl:1:rf7J73nExampleHolderAddress",
  "issuedAtFull": "2026.05.07 14:32",
  "expiresAt": "2027.05.06",
  "transactionHash": "0x7f3a92e1c4d28b1a...",
  "currentBalanceXrp": "0 XRP",
  "networkFeeXrp": "0.000012 XRP",
  "usableBalanceXrp": "0 XRP",
  "balanceWarning": "* 잔액이 부족합니다",
  "issuerOptions": [
    {
      "issuerId": "issuer-woori",
      "issuerName": "우리은행",
      "credentialId": "29",
      "selected": true
    }
  ],
  "submitDocuments": [
    {
      "documentId": "shareholder-list-1",
      "documentType": "SHAREHOLDER_LIST",
      "title": "주주명부",
      "digestSRI": "sha256-...",
      "required": true,
      "selected": true
    }
  ]
}
```

필수 규칙:

- `action`, `requestId`, `issuedAt`은 공통 브리지 검증 대상이다.
- 발급 확인과 증명서 제출 화면의 확인/거부 버튼은 스크롤과 독립된 하단 고정 영역에 표시한다.
- 발급 확인 화면의 `confirm` 결과는 사용자가 확인 버튼을 눌렀다는 신호만 의미한다. Android는 이 버튼에서 backend prepare, `saveVC`, `submitToXRPL`, backend confirm을 직접 호출하지 않는다.
- 발급 확인 화면의 현재 잔액/등록 후 사용 가능 잔액은 Android가 활성 지갑의 XRPL account balance를 조회해 표시한다. 웹 payload의 `currentBalanceXrp`, `balanceXrp`, `usableBalanceXrp`는 조회 실패 시 fallback으로만 사용한다.
- 발급 확인 화면의 `networkFeeXrp`/`feeXrp`가 `"0.000012 XRP"`처럼 XRP 단위면 그대로 사용하고, `"12"` 또는 `"12 drops"`처럼 drops 단위로 들어오면 `0.000012 XRP`로 정규화해 표시/계산한다.
- 발급 확인 화면의 발급 상세 정보는 실제 offer/credential 응답값을 사용해야 한다. Android는 `corporateName/companyName/legalEntity.name`, `businessNumber/businessRegistrationNumber/legalEntity.registrationNumber`, `legalEntity.type`, `representative.name`, `beneficialOwners[].name`, `agent.name/authorizedAgent.name/delegate.name` alias를 함께 해석하며, 값이 없으면 테스트 값 대신 `-`로 표시한다.
- 발급 확인/상세/제출 화면의 발급기관명은 `issuerDid` 기준 `GET /api/common/dids/{issuerDid}/institution` 응답의 `institutionName`을 사용한다. payload에 `issuerDid`가 없고 `credentialId`가 있으면 Android는 로컬 credential에서 `issuerDid`를 복원해 같은 API를 호출한다.
- 발급기관명 API 조회 실패 시 Android는 테스트 기관명을 표시하지 않는다. 실제 API 값이 없으면 `-` 또는 로컬 credential의 실제 `issuerDid`/`issuerAccount`만 fallback으로 표시한다.
- 증명서 상세 화면 카드의 큰 제목은 `법인 kyc 증명서`로 고정 표시한다. 카드 좌측 상단에는 `issuerDid`, 좌측 하단에는 `holderDid`, 우측 하단에는 발급일/만료일을 2줄로 표시한다.
- 증명서 상세 화면은 `credentialId`로 로컬 저장된 credential을 찾을 수 있으면 저장된 `sdJwt`, `vcJwt`, `vcJson`, `selectiveDisclosureJson`의 claim key/value를 우선 펼쳐 표시한다. 저장 원문을 찾지 못하면 화면 요청 payload의 요약 필드를 fallback으로 표시한다.
- 저장 credential claim은 KYvC legal entity KYC claim 포맷 기준으로 정렬/라벨링한다. 공통 메타 claim(`iss`, `sub`, `vct`, `jti`, `iat`, `exp`, `cnf.kid`, `credentialStatus.*`) 뒤에 `kyc.*`, `legalEntity.*`, `representative.*`, `beneficialOwners[]`, `delegate.*`, `delegation.*`, `establishmentPurpose.*`, `extra.aiAssessmentRef.*`, `documentEvidence[]` 순서로 표시한다.
- `legalEntity.type` 값은 코드 그대로가 아니라 `STOCK_COMPANY=주식회사`, `LIMITED_COMPANY=유한회사`, `LIMITED_PARTNERSHIP=유한합자회사 계열`, `GENERAL_PARTNERSHIP=합명회사 계열`, `INCORPORATED_ASSOCIATION=사단법인`, `FOUNDATION=재단법인`, `COOPERATIVE=협동조합`, `UNIQUE_NUMBER_ORGANIZATION=고유번호 단체`, `FOREIGN_COMPANY=외국회사`로 표시한다.
- 저장된 credential에서 추출된 claim은 모두 표시한다. 한국어 라벨 매핑이 있는 claim은 한국어 라벨로 표시하고, 매핑이 없는 claim은 raw key를 그대로 표시한다.
- 증명서 상세 화면의 `증명서 삭제` 버튼은 네이티브 확인 다이얼로그를 한 번 더 표시한 뒤, 확정 시 Android 로컬 `credentials` row와 같은 `credentialId`의 `holder_documents` row를 삭제하고 `result=delete`를 반환한다.
- 증명서 제출 화면은 `issuerOptions` 또는 `issuers` 배열을 받아 발급기관 선택 UI로 표시한다. 발급기관 선택은 제출할 credential 선택과 같은 의미이며, 확정 응답의 `selectedCredentialId`가 VP submit에 사용된다.
- 같은 발급기관의 credential은 하나만 존재해야 하며, 새 회사명/새 VC가 발급되면 같은 발급기관의 기존 credential은 교체되어야 한다. Native VP 로그인 제출 흐름에서는 같은 발급기관 후보가 여러 개면 `acceptedAt`, `validFrom`, `credentialId` 기준 최신 1개만 화면에 표시한다.
- 증명서 제출 화면은 `submitDocuments`, `requiredDocuments`, `documents`, `attachmentDocuments` 중 하나의 배열을 받아 제출 문서 목록으로 표시한다. 문서를 누르면 원본 내용은 보여주지 않고 hash/digest 값만 표시한다.
- 제출 문서 예시는 `주주명부`, `법인인감증명서`, `등기사항전부증명서`, `사업자등록증`, `법인 KYC 증명서`이다.
- 제출 문서 원본은 API 또는 별도 브릿지로 수신한 뒤 Android 로컬 저장소에 저장해야 한다. 화면 payload에는 원본 bytes/base64를 넣지 않고 `documentId`, `documentType`, `digestSRI/hash`, `mediaType`, `byteSize` 같은 메타만 넣는다.
- VC 저장 브릿지는 prepare 응답에서 분리된 `documentAttachments`와 `documentAttachmentManifest`를 받을 수 있다. `credentialPayload`는 기존 VC 저장에 사용하고, `documentAttachments[].contentBase64`는 Android 내부 암호화 저장소에 저장한다.
- Android는 문서 저장 시 `documentId`, `documentType`, `attachmentRef`, `requirementId`, `digestSRI`, `mediaType`, `byteSize`, `fileName`을 로컬 credential과 연결해 보관한다. 원본 bytes/base64는 로그와 화면 payload에 남기지 않는다.
- VP multipart 제출 시 `attachmentManifest` part에는 prepare 때 받은 manifest를 그대로 보내고, 파일 part name은 반드시 manifest의 `attachmentRef` 값과 같아야 한다.
- 기존 테스트 페이지 호환을 위해 `requestCredentialIssueConfirm1`, `requestCredentialIssueConfirm2`는 남겨두지만, 둘 다 동일한 발급 확인 화면을 연다. 신규 웹은 `requestCredentialIssueConfirm`을 사용한다.
- 화면은 PNG 시안을 기준으로 네이티브 Compose에서 재현한다.
- 민감한 raw SD-JWT, disclosure 원문, seed는 이 화면 요청 payload에 넣지 않는다.

공통 Response 예시:

```json
{
  "action": "REQUEST_CREDENTIAL_DETAIL",
  "ok": true,
  "requestId": "0c8f6d1a-3648-4cb6-a1e1-515ce60f5dd5",
  "screen": "CredentialDetail",
  "result": "showQr",
  "confirmed": true
}
```

증명서 제출 화면에서 제출 확정 시 Response 추가 필드:

```json
{
  "action": "REQUEST_CREDENTIAL_SUBMIT",
  "ok": true,
  "screen": "CredentialSubmit",
  "result": "submit",
  "selectedIssuerId": "issuer-woori",
  "selectedIssuerName": "우리은행",
  "selectedCredentialId": "29",
  "selectedDocuments": [
    {
      "documentId": "shareholder-list-1",
      "documentType": "SHAREHOLDER_LIST",
      "title": "주주명부",
      "digest": "sha256-..."
    }
  ]
}
```

결과값:

- 발급 완료: `viewCredential`, `home`
- 발급 확인: `confirm`, `reject`
- 증명서 상세: `showQr`, `delete`
- 증명서 제출: `submit`, `reject`
- `confirm`, `reject`, `submit`, `showQr`, `delete`, `viewCredential`, `home`은 사용자가 정상적으로 선택한 결과이므로 `ok=true`로 반환한다.
- 뒤로가기/닫기: `ok=false`, `result=cancel`

웹 처리 기준:

- 발급 확인/제출 화면에서 사용자가 거부를 누른 경우도 브릿지 호출 자체는 성공이다. 웹은 `ok`가 아니라 `result === "reject"`로 거부 흐름을 처리한다.
- `ok=false`는 네이티브 화면을 닫았거나 브릿지 검증에 실패한 경우로만 본다.
- VP 로그인 QR native 제출 흐름에서는 별도 credential picker를 띄우지 않는다. `REQUEST_CREDENTIAL_SUBMIT` 화면의 발급기관 드롭다운에서 선택된 `selectedCredentialId`로 presentation을 생성하고 backend submit을 호출한다.
- QR 스캔 화면은 프레임 내부만 카메라 preview를 표시하고, 프레임 외부는 남색 불투명 배경으로 유지한다.
- 원본 첨부가 필요한 VP 제출은 JSON API가 아니라 multipart API를 사용한다. Android multipart body는 `presentation`, `attachmentManifest`, `doc-101` 같은 attachmentRef 파일 part로 구성한다.

## 웹에서 꼭 처리해야 하는 상태값

웹은 아래 값을 기준으로 UI를 제어해야 한다.

- `emailVerificationRequired`
  - `true`면 PIN/패턴/지문 버튼을 비활성화하고 이메일 인증 화면으로 이동
- `remainingAttempts`
  - 로그인 화면에 남은 시도 횟수 표시
- `availableMethods`
  - `pin`, `pattern`, `biometric` 중 어떤 버튼을 보여줄지 결정
- `walletReady`
  - 인증 후 지갑 관련 화면으로 이동 가능한지 판단
- `sessionUnlocked`, `sessionRemainingMs`
  - 30분 인증 세션 유지 여부와 남은 시간 표시

## 현재 웹에 추가로 전달할 필요가 있는 것

현재 구현된 값:

- 인증 가능 방식 목록
- 누적 실패 횟수
- 남은 시도 횟수
- 이메일 인증 필요 여부
- 세션 잠금 여부와 남은 시간
- 인증 성공/실패 결과
- 네이티브가 직접 호출한 백엔드 응답

아직 구현되지 않은 값:

- 마지막 실패 시각
- 실패 사유 코드 분류 (`PIN_MISMATCH`, `PATTERN_MISMATCH`, `BIOMETRIC_FAILED`, `REQUEST_EXPIRED` 등)
- 잠금 해제 후 세션 만료 시각
- 강제 재잠금(`lockNow`) 브리지

웹 개발자가 바로 필요할 가능성이 높은 추가 항목은 `실패 사유 코드`와 `lockNow`다.

## 웹에서 브리지 기능을 사용하는 방법

기본 패턴:

```js
function callAndroid(method, payload) {
  window.Android[method](JSON.stringify(payload));
}

window.onAndroidResult = function (resultJson) {
  const result = JSON.parse(resultJson);

  switch (result.action) {
    case "GET_AUTH_STATUS":
      renderAuthState(result);
      break;
    case "REQUEST_NATIVE_AUTH":
      handleNativeAuthResult(result);
      break;
    case "COMPLETE_EMAIL_VERIFICATION":
      handleEmailVerificationReset(result);
      break;
  }
};
```

인증 상태 조회:

```js
callAndroid("getAuthStatus", {});
```

지문 인증 시작:

```js
callAndroid("requestNativeAuth", {
  action: "REQUEST_NATIVE_AUTH",
  requestId: crypto.randomUUID(),
  issuedAt: new Date().toISOString(),
  method: "biometric",
  reason: "wallet-login",
  backendRequest: {
    baseUrl: "https://<core-testnet-base-url>",
    endpoint: "/auth/login",
    body: {
      loginType: "wallet",
      holderDid: currentHolderDid
    }
  }
});
```

이메일 인증 성공 후 리셋:

```js
callAndroid("completeEmailVerification", {
  action: "COMPLETE_EMAIL_VERIFICATION",
  requestId: crypto.randomUUID(),
  issuedAt: new Date().toISOString()
});
```
