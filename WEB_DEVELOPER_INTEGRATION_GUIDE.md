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
앱 진입
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
- 앱 진입 잠금 화면에서는 `PIN 로그인` / `지문 로그인` 버튼으로 인증 방식을 선택한다.
- 선택 후 인증 처리는 네이티브 `UnlockActivity` 경로로 실행된다.
- 테스트용으로 앱 진입 잠금 화면에 `PIN 재설정 (테스트)` 버튼이 있다. (개발/테스트 목적)

필수 분기:
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
requestIssuerCredential
-> saveVC
-> submitToXRPL (CredentialAccept)
-> checkCredentialStatus (active=true, accepted=true 확인)
-> verifyCredentialWithServer
```

판정 기준:
- 로컬 임시 검증보다 `VERIFY_CREDENTIAL_WITH_SERVER` 결과를 우선한다.
- 샘플 placeholder 데이터로 성공 판정을 내리지 않는다.

## 8) SD-JWT+KB 제출 흐름

```text
requestVerifierChallenge (nonce/aud)
-> signMessage (sdJwtKb 생성)
-> submitPresentationToVerifier
```

## 8-1) 실제 테스트 권장 순서 (Testnet E2E)

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

## 11) 빠른 디버깅 포인트

- 버튼 클릭은 되는데 호출이 안 되면: 웹 로그에서 `웹 → Android 호출` 라인 확인
- 복구 후 verifier 실패:
  - `holderDidSetRegistrationRequired` 처리 여부 확인
  - DIDSet 등록 성공 여부 확인
- DIDSet 실패 `actNotFound`:
  - 해당 holder account가 XRPL testnet에서 활성(펀딩)됐는지 확인

## 12) 관련 문서

- 브리지 상세 규격: `WEBVIEW_BRIDGE_SPEC.md`
- 프로젝트 전반 상태: `README.md`
- SD-JWT 지갑 개발 참고: `Wallet Implimentation Guide.md`
