# WebView Bridge Spec

이 문서는 웹이 Android WebView 브리지를 호출할 때 사용하는 최신 호출 규격 문서다. 브리지 함수가 바뀌면 이 문서를 같이 갱신한다.

## 공통 원칙

- 웹은 브리지 함수만 호출한다.
- 인증, 민감정보 처리, 백엔드 API 호출은 네이티브가 담당한다.
- 웹은 결과만 `window.onAndroidResult(result)` 콜백으로 받는다.
- PIN/패턴 원문은 웹에서 브리지로 보내지 않는다.
- 지문/PIN/패턴 UI는 네이티브가 띄운다.

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

## 인증 브리지

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
  "sessionExpiresAtMs": 1778280000000
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
    "baseUrl": "https://dev-core-kyvc.khuoo.synology.me",
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
  "authenticated": true,
  "failedAttempts": 0,
  "remainingAttempts": 5,
  "failureThreshold": 5,
  "emailVerificationRequired": false,
  "sessionUnlocked": true,
  "sessionRemainingMs": 1800000,
  "sessionExpiresAtMs": 1778280000000,
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

### 3. 이메일 인증 완료 처리

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
  "didDocument": "{...}"
}
```

### 3. 지갑 조회

Method: `getWalletInfo`

Request:

```json
{}
```

Response는 `CREATE_WALLET`과 동일한 형태다.

### 4. Holder DIDSet 등록

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

## Credential / XRPL 브리지

### 1. VC 저장

Method: `saveVC`

지원 입력:

- expanded JSON VC
- `vcJwt`
- `credential`
- `sdJwt`

SD-JWT 예시:

```json
{
  "credential": "<issuer-jwt>~<disclosure-1>~<disclosure-2>"
}
```

### 2. XRPL CredentialAccept 제출

Method: `submitToXRPL`

Request:

```json
{
  "credentialId": "urn:uuid:...",
  "issuerAccount": "rIssuer...",
  "holderAccount": "rHolder...",
  "credentialType": "ABC123..."
}
```

호출 흐름:

```text
실제 SD-JWT 요청
-> saveVC
-> submitToXRPL
-> checkCredentialStatus
-> active: true / accepted: true 확인
```

### 3. XRPL 상태 조회

Method: `checkCredentialStatus`

Request:

```json
{
  "credentialId": "urn:uuid:..."
}
```

Response 주요 필드:

```json
{
  "action": "CHECK_CREDENTIAL_STATUS",
  "ok": true,
  "found": true,
  "active": true,
  "accepted": true
}
```

## Issuer / Verifier 브리지

### 1. 실제 SD-JWT 요청

Method: `requestIssuerCredential`

Request 예시:

```json
{
  "coreBaseUrl": "https://dev-core-kyvc.khuoo.synology.me",
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
  "coreBaseUrl": "https://dev-core-kyvc.khuoo.synology.me",
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
  "coreBaseUrl": "https://dev-core-kyvc.khuoo.synology.me",
  "aud": "https://dev-core-kyvc.khuoo.synology.me",
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
  "domain": "https://dev-core-kyvc.khuoo.synology.me",
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
  }
}
```

### 5. Verifier 제출

Method: `submitPresentationToVerifier`

SD-JWT 요청 예시:

```json
{
  "endpoint": "https://dev-core-kyvc.khuoo.synology.me/verifier/presentations/verify",
  "presentation": {
    "format": "kyvc-sd-jwt-presentation-v1",
    "definitionId": "wallet-direct-kyc-test-v1",
    "aud": "https://dev-core-kyvc.khuoo.synology.me",
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

### QR 스캔

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
- 로그아웃/강제 재잠금 브리지

웹 개발자가 바로 필요할 가능성이 높은 추가 항목은 `실패 사유 코드`와 `lockNow/logout`이다. 이 둘은 아직 없다.

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
    baseUrl: "https://dev-core-kyvc.khuoo.synology.me",
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
