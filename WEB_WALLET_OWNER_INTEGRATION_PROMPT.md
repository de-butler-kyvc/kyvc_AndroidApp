# Web Developer Prompt: KYvC Android Wallet Owner Binding

KYvC Android WebView 연동에서 로컬 앱 생성 지갑은 로그인 사용자 기준으로 새로 생성하거나 사용자가 직접 복구해야 합니다. 아래 흐름으로 웹 코드를 수정해 주세요.

## 목표

- 웹 로그인 계정 A가 생성/복구한 Android 로컬 지갑은 계정 A에서만 사용합니다.
- 계정 B로 로그인했는데 기기에 계정 A의 로컬 앱 생성 지갑이 있으면 Android는 자동 삭제하지 않고 `walletAccess=owner_mismatch`, `deleteRequired=true`를 반환합니다.
- 지갑 삭제는 사용자가 웹에서 명시적으로 선택했을 때만 `deleteLocalWalletData` 또는 `logoutAndDeleteLocalWalletData` 브릿지로 실행합니다.

## 필수 브릿지 호출 순서

웹 로그인 성공 직후, 지갑 관련 브릿지보다 먼저 호출:

```js
callAndroid("setCurrentWebUser", {
  action: "SET_CURRENT_WEB_USER",
  requestId: crypto.randomUUID(),
  issuedAt: new Date().toISOString(),
  userId: loginUser.id,              // 이메일이 아니라 서버 stable user id
  displayHint: maskEmail(loginUser.email),
  environment: "testnet",
  bindIfUnbound: false
});
```

`SET_CURRENT_WEB_USER` 결과 처리:

```js
function handleSetCurrentWebUser(result) {
  if (result.ok && (result.walletAccess === "allowed" || result.walletAccess === "no_wallet")) {
    callAndroid("getAuthStatus", {});
    return;
  }

  if (result.ok && result.walletAccess === "owner_mismatch") {
    clearWalletUiState();
    showWalletDeleteRequired(
      result.errorHint || "다른 계정의 로컬 지갑이 있습니다. 삭제는 사용자가 선택했을 때만 실행됩니다."
    );
    return;
  }

  if (result.ok && result.walletAccess === "binding_required") {
    // 기존 앱 버전에서 생성된 owner 없는 지갑입니다.
    // 사용자에게 “현재 로그인 계정에 기존 지갑을 연결” 확인을 받은 뒤,
    // 네이티브 인증을 거치고 bindIfUnbound=true로 다시 호출하세요.
    showBindExistingWalletConfirm();
    return;
  }

  showError(result.error || "지갑 사용자 확인에 실패했습니다.");
}
```

기존 owner 없는 지갑을 현재 계정에 1회 연결할 때:

```js
callAndroid("setCurrentWebUser", {
  action: "SET_CURRENT_WEB_USER",
  requestId: crypto.randomUUID(),
  issuedAt: new Date().toISOString(),
  userId: loginUser.id,
  displayHint: maskEmail(loginUser.email),
  environment: "testnet",
  bindIfUnbound: true
});
```

## 지갑 생성/복구 조건

- `setCurrentWebUser`가 `ok=true`이고 `walletAccess`가 `no_wallet` 또는 `allowed`일 때 `createWallet`, `restoreWallet`, `requestWalletRestore` 버튼을 활성화합니다.
- `walletAccess=owner_mismatch`에서는 지갑 생성/복구 버튼을 바로 열지 말고, 먼저 지갑 삭제 또는 로그아웃 선택을 보여줍니다.
- 지갑 생성/복구 성공 시 Android가 현재 웹 사용자에 owner를 저장합니다.
- 웹은 `userId` 원문을 로그/분석 SDK에 남기지 않습니다.

## 지갑 삭제 조건

지갑 삭제는 웹 버튼에서 브릿지로 연결합니다.

```js
callAndroid("deleteLocalWalletData", {
  action: "DELETE_LOCAL_WALLET_DATA",
  requestId: crypto.randomUUID(),
  issuedAt: new Date().toISOString()
});
```

지갑 삭제와 로그아웃을 한 번에 처리할 때:

```js
callAndroid("logoutAndDeleteLocalWalletData", {
  action: "LOGOUT_AND_DELETE_LOCAL_WALLET_DATA",
  requestId: crypto.randomUUID(),
  issuedAt: new Date().toISOString()
});
```

삭제 전에는 기존 네이티브 인증 흐름으로 활성 세션을 확보하세요.

```text
getAuthStatus
-> sessionUnlocked=false면 requestNativeAuth(reason=wallet-login)
-> deleteLocalWalletData 또는 logoutAndDeleteLocalWalletData
```

## 반드시 막아야 할 것

- `SET_CURRENT_WEB_USER` 전 `getWalletInfo`, `listWallets`, `getCredentialSummaries` 호출 금지
- `owner_mismatch` 상태에서 이전 지갑 주소, DID, 증명서 목록, 잔액 표시 금지
- 이메일만 user id로 사용 금지. 서버 stable id를 사용

## 콜백 공통 처리 추가

모든 `onAndroidResult`에서 아래 분기를 최우선으로 처리하세요.

```js
if (result.walletAccess === "owner_mismatch") {
  clearWalletUiState();
  showWalletDeleteRequired(result.errorHint || "다른 계정의 로컬 지갑이 있습니다.");
  return;
}
```
