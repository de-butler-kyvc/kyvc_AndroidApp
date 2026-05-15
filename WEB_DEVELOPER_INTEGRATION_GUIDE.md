# KYvC Android WebView 연동 가이드 (웹 개발자용)

이 문서는 웹 개발자가 Android WebView 브리지를 사용할 때 필요한 최소 흐름과 예외처리를 정리한 실행 가이드다.  
상세 파라미터는 항상 `WEBVIEW_BRIDGE_SPEC.md`를 기준으로 본다.

## 0) 환경 기준 (Testnet)

- XRPL 네트워크 기준은 `testnet`이다.
- Core API는 testnet ledger를 기준으로 status/검증 결과를 반환해야 한다.
- Devnet 기준 주소/펀딩 상태/성공 로그는 더 이상 판정 기준으로 사용하지 않는다.

## 1) 기본 원칙

- 웹은 `window.Android.<method>(JSON.stringify(payload))`만 호출한다.
- 인증(PIN/패턴/지문), 민감정보 처리, XRPL 서명, 백엔드 API 호출은 네이티브가 담당한다.
- 웹은 `window.onAndroidResult(resultJson)` 콜백으로 결과만 처리한다.
- 민감정보(`seed`, `mnemonic`)는 콘솔/분석SDK/원격 로그에 저장하지 않는다.
- 앱 WebView 기본 진입 URL은 `https://dev-kyvc.khuoo.synology.me/m/`이다.
- 테스트 페이지는 `app/src/main/assets/index.html`이며, 외부 URL 로딩 실패 시 fallback으로 사용된다.
- Android 시스템 뒤로가기 버튼은 WebView 히스토리를 먼저 사용한다. (`canGoBack()==true`면 이전 페이지 이동, 아니면 앱 종료)

## 2) 공통 콜백 처리 템플릿

```js
window.onAndroidResult = (resultJson) => {
  const r = typeof resultJson === "string" ? JSON.parse(resultJson) : resultJson;
  if (!r.ok) {
    // 공통 실패 처리
    showError(r.action, r.error || "요청 실패");
    return;
  }
  // action별 성공 처리
  handleAction(r);
};
```

## 3) 인증/세션 흐름

```text
<!--
앱 WebView 페이지 로드 완료
-> NATIVE_AUTH_SESSION(ok=true, autoLogin=true, sessionUnlocked=true)이 오면 로그인/인증 버튼 화면을 건너뛰고 앱 메인으로 이동
-->

웹 로그인 성공
-> setCurrentWebUser(userId=서버 stable id)
-> walletAccess 확인
-> getAuthStatus
-> requestNativeAuth (wallet-login)
-> (실패 5회 누적) emailVerificationRequired=true
-> 웹 이메일 인증
-> completeEmailVerification
-> requestNativeAuth 재시도
-> 성공 시 30분 세션 시작
```

구현 주의:
- PIN 인증 화면은 웹뷰 페이지가 아니라 네이티브 화면이다.
- 웹은 `requestNativeAuth(method=pin)`만 호출하고, PIN 입력 UI/검증/실패 집계는 앱이 처리한다.
<!-- - 30분 세션 자동 로그인은 인증 버튼 클릭으로 처리하지 않는다. 웹은 앱 진입 직후 수신되는 `NATIVE_AUTH_SESSION`에서 `autoLogin=true`, `sessionUnlocked=true`면 로그인 페이지를 바로 통과시킨다. -->
- 앱 진입 시 네이티브 테스트 잠금 화면은 띄우지 않는다. WebView를 먼저 표시하고, 웹이 필요한 시점에 `requestNativeAuth`를 호출한다.
- PIN/패턴 인증 처리는 네이티브 `UnlockActivity` 경로로 실행된다.
- 기존 앱 진입용 `PIN 로그인`/`지문 로그인` 회색 테스트 화면과 `PIN 재설정 (테스트)` 버튼은 사용하지 않는다.

필수 분기:
- `walletAccess=owner_mismatch`: 기존 로컬 지갑은 자동 삭제되지 않는다. 웹의 지갑 캐시를 비우고, 사용자가 명시적으로 지갑 삭제를 선택했을 때만 `deleteLocalWalletData` 또는 `logoutAndDeleteLocalWalletData`를 호출한다.
- `walletAccess=binding_required`: 기존 owner 없는 로컬 지갑이므로 사용자 확인 후 `setCurrentWebUser(bindIfUnbound=true)` 재호출
- `emailVerificationRequired=true`: 인증 버튼 비활성화 + 이메일 인증 UI로 이동
- `sessionUnlocked=false`: 보호 기능 호출 전 재인증 유도

## 4) 지갑 생성/조회 흐름

```text
createWallet 또는 restoreWallet
-> LIST_WALLETS로 selector 동기화
-> getWalletInfo로 활성 지갑 표시
```

참고:
- 로그아웃 시 세션 + 활성 계정 선택이 해제된다.
- 로그아웃 직후 `getWalletInfo`는 실패할 수 있으므로, 먼저 재인증 후 호출한다.

## 5) 복구(Seed / Mnemonic) 흐름

```text
restoreWallet(seed 또는 mnemonic, overwrite=false)
-> RESTORE_WALLET 수신
-> reusedExistingAccount 분기
-> holderDidSetRegistrationRequired 분기
```

필수 분기:
1. `ok=false`: `error` 표시 후 종료
2. `reusedExistingAccount=true`: 기존 계정 재사용 안내
3. `reusedExistingAccount=false`: 새 계정 추가 안내
4. `holderDidSetRegistrationRequired=true`: `submitHolderDidSet` 실행(자동/수동)

주의:
- 동일 seed/mnemonic 복구는 기존 계정을 재사용하도록 구현되어 있다.
- DIDSet 제출은 대상 계정이 XRPL 활성 상태여야 한다(미활성 계정이면 실패).
- 전환 이후 활성 상태 확인 기준은 testnet faucet/funding이다.

## 6) 계정 관리 흐름

```text
listWallets
-> switchWallet
-> setAccountName
-> (필요 시) upgradeToMnemonic
-> deriveNextAccount
```

정책:
- `removeWallet`는 현재 비활성화(disabled) 상태다.

필수 분기:
- `deriveNextAccount` 실패 + mnemonic 미준비: 먼저 `upgradeToMnemonic` 유도
- `upgradeToMnemonic` 성공: `mnemonic` 즉시 1회 표시, 저장/전송 금지

## 7) VC / SD-JWT 기본 흐름

```text
QR resolve / offer 조회
-> requestCredentialIssueConfirm
-> result=confirm 수신
-> backend prepare 1회 호출
-> credentialPayload.sdJwt를 saveVC에 전달
-> submitToXRPL (CredentialAccept)
-> backend confirm
-> checkCredentialStatus (active=true, accepted=true 확인)
-> verifyCredentialWithServer
```

판정 기준:
- 로컬 임시 검증보다 `VERIFY_CREDENTIAL_WITH_SERVER` 결과를 우선한다.
- 샘플 placeholder 데이터로 성공 판정을 내리지 않는다.
- `REQUEST_CREDENTIAL_ISSUE_CONFIRM`의 `result=confirm`은 사용자가 확인을 눌렀다는 콜백이다. Android는 이 버튼에서 backend prepare, `saveVC`, `submitToXRPL`, backend confirm을 직접 호출하지 않는다.
- backend prepare는 offer당 1회만 호출한다. 이미 prepare 응답을 받은 상태라면 같은 offerId로 재호출하지 말고 메모리에 보관 중인 `credentialPayload.sdJwt`를 즉시 `saveVC`에 전달한다.
- `saveVC`는 top-level `metadata`와 `credentialPayload.metadata`를 모두 지원한다. `credentialPayload.metadata.issuerAccount`에는 실제 XRPL classic address를 넣고, `issuerDid`는 `did:xrpl:1:{issuerAccount}` 형태로 맞춘다.
- `issuerAccount` 또는 `issuerDid`에 `rIssuer` 같은 placeholder를 넣으면 Android 저장/검증에서 차단된다.

## 8) SD-JWT+KB 제출 흐름

```text
requestVerifierChallenge (nonce/aud)
-> signMessage (sdJwtKb 생성)
-> submitPresentationToVerifier
```

Holder DID Document 기준:
- `signMessage` 성공 응답의 `didDocument`를 backend/Core 제출 body에 그대로 전달한다.
- 프론트에서 DID Document를 재생성하거나 key alias를 추가하지 않는다.
- `didDocuments`/`did_documents` 맵이 필요한 API는 native 응답의 값을 그대로 사용한다.
- `didDocumentHashMatches=false`이면 현재 DID Document와 XRPL DIDSet Data hash가 다른 상태이므로 `submitHolderDidSet`으로 재등록을 유도한다.
- `SD-JWT payload.cnf.kid`, KB-JWT header `kid`, DID Document `verificationMethod[].id`, `authentication[]`는 모두 `did:xrpl:1:{holderAccount}#holder-key-1`이어야 한다.
- `cnf.kid`가 `did#did#holder-key-1`처럼 중복된 VC는 발급 오류이며, 해당 VC 삭제 후 재발급이 필요하다.

PC VP 로그인 QR:
- Android Native가 `VP_LOGIN_REQUEST` QR을 직접 처리해 backend resolve/submit을 호출한다.
- Android submit body에는 `vp`, `didDocument`, `didDocuments`, `did_documents`, `deviceId`가 포함된다.
- PC 로그인 완료 처리는 Android가 하지 않고 PC Frontend polling/complete 흐름이 담당한다.

## 8-1) 증명서 제출 화면 데이터 계약

제출 QR 스캔 후 `requestCredentialSubmit`으로 네이티브 `증명서 제출.png` 화면을 띄운다.

웹/API가 준비되면 아래 데이터를 화면 payload에 포함한다.

```js
window.Android.requestCredentialSubmit(JSON.stringify({
  action: "REQUEST_CREDENTIAL_SUBMIT",
  requestId,
  requesterName: "신한은행",
  issuerOptions: [
    {
      issuerId: "issuer-woori",
      issuerName: "우리은행",
      credentialId: "29",
      selected: true
    }
  ],
  submitDocuments: [
    {
      documentId: "shareholder-list-1",
      documentType: "SHAREHOLDER_LIST",
      title: "주주명부",
      digestSRI: "sha256-...",
      required: true,
      selected: true
    }
  ]
}));
```

처리 기준:
- 발급기관 목록은 API에서 받아온다. 같은 발급기관에는 하나의 credential만 존재해야 한다.
- 같은 발급기관에서 새 회사명/새 VC를 발급하면 기존 credential은 교체 대상으로 본다.
- 발급기관 선택은 제출할 credential 선택과 같은 의미다. Native VP 로그인 제출 흐름에서는 이 화면의 `selectedCredentialId`로 presentation을 생성한다.
- 같은 발급기관 credential 후보가 여러 개면 Android는 최신 1개만 화면에 표시한다. 웹/API에서도 같은 기준으로 중복 발급기관 후보를 내려주지 않는 것이 좋다.
- 제출 문서 원본은 화면 payload에 넣지 않는다. API/브릿지로 Android 로컬 저장소에 저장하고 화면에는 `documentId`, `documentType`, `digestSRI/hash`만 전달한다.
- 문서 row를 누르면 네이티브는 원본이 아니라 hash만 보여준다.
- 제출 확정 콜백에는 `selectedIssuerId`, `selectedCredentialId`, `selectedDocuments[]`가 포함된다.
- 주요 문서 예시는 `주주명부`, `법인인감증명서`, `등기사항전부증명서`, `사업자등록증`, `법인 KYC 증명서`다.

## 8-2) 실제 테스트 권장 순서 (Testnet E2E)

```text
1. getAuthStatus / requestNativeAuth
2. getWalletInfo (holder 계정 확인)
3. requestIssuerCredential (dc+sd-jwt)
4. saveVC
5. submitToXRPL (CredentialAccept)
6. checkCredentialStatus (active=true, accepted=true)
7. verifyCredentialWithServer (credential 단독 검증)
8. requestVerifierChallenge (nonce/aud)
9. signMessage (sdJwtKb 생성)
10. submitPresentationToVerifier
```

실패 시 우선 확인:
- `coreBaseUrl` 미입력/오입력
- holder/issuer 계정 testnet funded 여부
- nonce 재사용 여부

필수 분기:
- nonce 재사용 금지(실패 정상)
- `aud`/`nonce` 불일치 시 실패 정상
- `holderDidSetRegistrationRequired` 미처리 상태면 holder binding 실패 가능
- `vct is not accepted by verifier policy`는 DIDSet 재등록으로 해결되지 않는다. 발급 VC의 `vct`와 verifier `acceptedVct` 정책을 맞춰야 한다.
- `KB-JWT verificationMethod not found in holder DID Document`는 DIDSet hash 불일치, 잘못 발급된 `cnf.kid`, 또는 현재 지갑 auth key와 VC holder binding 불일치 중 하나를 우선 확인한다.

## 9) 송금 흐름

```text
getAuthStatus
-> requestNativeAuth(reason=xrp-payment)
-> submitXrpPayment
-> getWalletAssets / getWalletTransactions 갱신
```

필수 분기:
- 재인증 미완료 시 송금 차단
- 실패 시 `error`/`engineResultMessage` 우선 표시

## 10) 웹 구현 체크리스트

- [ ] `onAndroidResult`에서 `action + ok + error` 공통 분기 구현
- [ ] 인증 5회 실패(`emailVerificationRequired`) 분기 구현
- [ ] 복구 응답의 `reusedExistingAccount`, `holderDidSetRegistrationRequired` 분기 구현
- [ ] `LIST_WALLETS` 수신 시 selector/UI 동기화
- [ ] 민감정보 마스킹/비로그 정책 적용
- [ ] `removeWallet` 호출 제거(현재 비활성화 정책)
- [ ] 실패 응답 원문(`error`) 사용자/운영 로그에 남김(민감정보 제외)
- [ ] DIDSet hash mismatch 시 `submitHolderDidSet` 재등록 UX 제공
- [ ] VC 발급 후 `cnf.kid`와 `vct`가 정책에 맞지 않는 경우 재발급 안내

## 11) 빠른 디버깅 포인트

- 버튼 클릭은 되는데 호출이 안 되면: 웹 로그에서 `웹 → Android 호출` 라인 확인
- 복구 후 verifier 실패:
  - `holderDidSetRegistrationRequired` 처리 여부 확인
  - DIDSet 등록 성공 여부 확인
- VP 로그인 제출 실패:
  - Android Logcat `vp.login.credential.selected`, `vp.login.credential.payload`, `vp.login.holder.binding`, `vp.login.submit.request` 확인
  - Core verify 실패 시 backend가 DID Document를 Core request에 실제 전달했는지 확인
  - `cnf.kid`, `vct`, DIDSet hash mismatch를 분리해서 판단
- DIDSet 실패 `actNotFound`:
  - 해당 holder account가 XRPL testnet에서 활성(펀딩)됐는지 확인

## 12) 관련 문서

- 브리지 상세 규격: `WEBVIEW_BRIDGE_SPEC.md`
- 프로젝트 전반 상태: `README.md`
- SD-JWT 지갑 개발 참고: `Wallet Implimentation Guide.md`
