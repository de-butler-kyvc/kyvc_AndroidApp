# Android Holder Wallet SD-JWT 연동 가이드

이 문서는 KYvC Android holder wallet이 Core issuer/verifier와 연동할 때 따르는 최신 계약이다. 신규 legal entity KYC wallet은 `dc+sd-jwt` credential과 SD-JWT+KB presentation을 기본으로 구현한다. 기존 `vc+jwt`/`vp+jwt` 흐름은 compatibility mode로만 유지한다.

## 1. 핵심 포맷

- Credential: `dc+sd-jwt`
- Presentation: SD-JWT+KB
- Credential serialization: `<issuer-jwt>~<disclosure-1>~<disclosure-2>~...`
- Presentation serialization: `<issuer-jwt>~<selected-disclosure-1>~...~<kb-jwt>`
- Issuer signature: JOSE compact JWS, `ES256K`
- Holder binding: KB-JWT, holder DID `authentication` key
- Status: XRPL Credential, `credentialStatus.credentialType`
- DID: `did:xrpl:1:{xrplAccount}`

SD-JWT presentation에서는 holder-signed `vp+jwt`를 만들지 않는다. verifier challenge는 `challenge/domain` 대신 `nonce/aud`를 사용한다. KB-JWT의 `sd_hash`가 issuer JWT와 선택 disclosure set 전체를 holder 서명에 묶는다.

## 2. Wallet 보관 값

| 데이터 | 용도 | 권장 보관 |
| --- | --- | --- |
| XRPL holder seed/account | `CredentialAccept` 서명 | Keystore/StrongBox wrapping key로 암호화 |
| holder DID | `did:xrpl:1:{holderAccount}` | 앱 DB |
| holder auth key | KB-JWT 서명 | hardware-backed 우선, 불가 시 암호화 저장 |
| holder DID Document | verifier가 KB-JWT 검증 | 앱 DB 및 verifier 제출 |
| SD-JWT credential 원문 | issuer JWT + 전체 disclosure | 앱 DB 암호화 저장 |
| credentialType | XRPL accept/status lookup | issuer JWT payload `credentialStatus.credentialType` |
| issuer/holder account | status lookup, accept, revoke 확인 | issuer JWT payload에서 파싱 |
| accepted tx hash | holder accept 추적 | 앱 DB |

SD-JWT credential 원문에는 전체 disclosure가 들어 있으므로 전체 KYC 원문과 같은 민감도로 취급한다. verifier에는 선택된 disclosure만 제출한다.

## 3. 전체 플로우

```text
1. 앱이 XRPL holder account를 생성하거나 복구한다.
2. 앱이 holder authentication secp256k1 key pair를 생성한다.
3. 앱이 holder DID와 DID Document를 만든다.
4. issuer backend가 Core /issuer/credentials/kyc에 holder_account, holder_did, holder_key_id를 전달한다.
5. Core가 dc+sd-jwt credential을 발급하고 XRPL CredentialCreate를 제출한다.
6. 앱이 SD-JWT credential을 수신해 issuer JWT와 disclosures를 저장한다.
7. 앱이 XRPL CredentialAccept를 holder 계정으로 제출한다.
8. verifier 제출 시 앱이 nonce/aud challenge를 받는다.
9. 앱이 제출할 disclosure를 선택한다.
10. 앱이 selected SD-JWT string의 sd_hash를 계산하고 KB-JWT를 holder auth key로 서명한다.
11. 앱이 SD-JWT+KB presentation과 holder DID Document를 verifier에 제출한다.
```

XRPL status는 active/revoked lookup key다. tamper-proofing은 issuer signature, disclosure digest, KB-JWT signature, `sd_hash`, `nonce`, `aud`가 담당한다.

## 4. DID와 Holder Key

holder DID:

```text
did:xrpl:1:{holderAccount}
```

DID Document의 `verificationMethod`는 secp256k1 JWK를 사용한다. `x`, `y`는 32-byte big-endian 좌표를 base64url no-padding으로 인코딩한다. issuer JWT payload의 `cnf.kid`는 holder authentication method URL이어야 하고, KB-JWT header `kid`는 DID Document `authentication`에 포함되어야 한다.

## 5. SD-JWT 발급 요청/응답

Android 앱은 issuer seed/private key/PEM을 다루지 않는다.

```http
POST /issuer/credentials/kyc
Content-Type: application/json
```

요청 핵심 필드:

```json
{
  "format": "dc+sd-jwt",
  "holder_account": "rHolder...",
  "holder_did": "did:xrpl:1:rHolder...",
  "holder_key_id": "holder-key-1",
  "claims": {},
  "valid_from": "2026-05-05T00:00:00Z",
  "valid_until": "2027-05-05T00:00:00Z"
}
```

응답 핵심 필드:

```json
{
  "format": "dc+sd-jwt",
  "credential": "<issuer-jwt>~<disclosure-1>~<disclosure-2>",
  "credentialId": "urn:uuid:...",
  "credential_type": "75525E...",
  "status": {
    "type": "XRPLCredentialStatus",
    "credentialType": "75525E..."
  },
  "selectiveDisclosure": {
    "disclosablePaths": []
  }
}
```

앱은 `credential` 원문, `credentialId`, `credential_type`, issuer JWT header/payload decode 결과, disclosure 목록, accepted tx hash/timestamp를 저장한다.

## 6. SD-JWT 파싱/검증

credential 문자열은 `~`로 split한다.

```text
parts[0] = issuer-signed JWT
parts[1..n] = disclosures
```

issuer JWT header 확인:

- `alg == "ES256K"`
- `typ == "dc+sd-jwt"`
- `iss == payload.iss`
- `kid`가 issuer DID에 속함

issuer JWT payload 확인:

- `sub == holderDid`
- `cnf.kid == "$holderDid#holder-key-1"`
- `credentialStatus.type == "XRPLCredentialStatus"`
- `credentialStatus.credentialType` 존재
- `iat <= now <= exp`

Disclosure digest:

```text
digest = BASE64URL_NO_PADDING(SHA-256(ASCII(disclosure)))
```

앱은 malformed disclosure와 중복 digest를 실패 처리한다. 향후 고도화에서는 issuer payload `_sd`가 실제로 reference하지 않는 disclosure도 제출하지 않도록 전체 경로 검증을 추가한다.

## 7. CredentialAccept

Holder가 credential을 수락하려면 holder XRPL wallet으로 `CredentialAccept`를 제출한다.

```json
{
  "TransactionType": "CredentialAccept",
  "Account": "rHolder...",
  "Issuer": "rIssuer...",
  "CredentialType": "75525E..."
}
```

입력 매핑:

| XRPL 필드 | 값 |
| --- | --- |
| `Account` | holder XRPL account |
| `Issuer` | issuer JWT payload `iss`에서 account 추출 |
| `CredentialType` | issuer JWT payload `credentialStatus.credentialType` |

active 조건은 ledger entry 존재, accepted flag `0x00010000`, expiration 유효 여부다.

## 8. Challenge와 KB-JWT

Verifier challenge 요청:

```http
POST /verifier/presentations/challenges
Content-Type: application/json
```

```json
{
  "aud": "https://verifier.example"
}
```

응답의 `nonce`는 1회용이고, `aud`는 KB-JWT payload에 그대로 넣는다.

선택한 disclosure로 selected SD-JWT string을 만든다.

```text
selectedSdJwt = issuerJwt + "~" + selectedDisclosure1 + "~" + selectedDisclosure2
sd_hash = BASE64URL_NO_PADDING(SHA-256(ASCII(selectedSdJwt)))
```

KB-JWT header:

```json
{
  "alg": "ES256K",
  "typ": "kb+jwt",
  "kid": "did:xrpl:1:rHolder...#holder-key-1"
}
```

KB-JWT payload:

```json
{
  "iat": 1777939200,
  "aud": "https://verifier.example",
  "nonce": "...",
  "sd_hash": "..."
}
```

최종 presentation:

```text
sdJwtKb = selectedSdJwt + "~" + kbJwt
```

## 9. Presentation 제출

```http
POST /verifier/presentations/verify
Content-Type: application/json
```

```json
{
  "format": "kyvc-sd-jwt-presentation-v1",
  "presentation": {
    "format": "kyvc-sd-jwt-presentation-v1",
    "definitionId": "kr-stock-company-kyc-v1",
    "aud": "https://verifier.example",
    "nonce": "...",
    "sdJwtKb": "<issuer-jwt>~<selected-disclosures>~<kb-jwt>",
    "attachmentManifest": []
  },
  "did_documents": {
    "did:xrpl:1:rHolder...": {}
  },
  "status_mode": "xrpl",
  "require_status": true
}
```

성공 응답은 `credentialVerified`, `holderBindingVerified`, `statusVerified`, `policyVerified`, `verified`가 모두 true인 상태다.

## 10. 원본 문서 Attachment

원본 PDF/image는 SD-JWT에 넣지 않는다. verifier가 원본 제출을 요구하거나 허용할 때만 multipart attachment로 별도 제출한다. 현재 Android 구현은 JSON 제출을 우선 지원하며 multipart attachment는 후속 작업이다.

## 11. 자주 보는 실패

| 오류 | 원인 |
| --- | --- |
| `SD-JWT issuer signature verification failed` | issuer JWT 변조 또는 DID Document/key 불일치 |
| `disclosure digest is not referenced by issuer payload` | 선택 disclosure가 해당 credential에 속하지 않음 |
| `duplicate disclosure` | 같은 disclosure 중복 제출 |
| `XRPL Credential status is not active` | CredentialAccept 미제출, revoke, expire, ledger 조회 실패 |
| `KB-JWT signature verification failed` | holder auth key 또는 ES256K serialization 문제 |
| `KB-JWT nonce was not issued by verifier` | verifier challenge 없이 제출 |
| `KB-JWT nonce was already used` | nonce 재사용 |
| `KB-JWT aud mismatch` | challenge aud와 KB-JWT aud 불일치 |
| `KB-JWT sd_hash mismatch` | KB-JWT 생성 후 disclosure set 변경 |
| `required disclosure missing` | policy 요구 disclosure 미제출 |

## 12. Android 구현 상태

- SD-JWT credential 원문 수신/저장 지원
- issuer JWT payload를 XRPL status/accept용 내부 payload로 정규화
- disclosure malformed/duplicate 1차 검증
- nonce/aud challenge 수신 및 로컬 사용 상태 관리
- selected SD-JWT 전체 disclosure 기본 선택
- KB-JWT ES256K 서명 및 `sd_hash` 생성
- SD-JWT+KB verifier 제출 payload 분기
- 기존 `vc+jwt`/`vp+jwt` 호환 흐름 유지

남은 작업은 verifier `presentationDefinition.requiredDisclosures` 기반 선택 UI, disclosure reference 전체 검증, multipart attachment 제출, SD-JWT 테스트 벡터 추가다.
