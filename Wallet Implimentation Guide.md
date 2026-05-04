# Android Holder Wallet 연동 가이드

이 문서는 KYvC Android holder wallet이 `core` issuer/verifier와 연동하기 위한 현재 기준 계약을 정리한다.

신규 기준은 **JWT 기반 secured representation**이다.

- VC: compact `application/vc+jwt`
- VP: compact `application/vp+jwt`
- VP 내부 VC: `EnvelopedVerifiableCredential`
- 서명 알고리즘: JOSE `ES256K`, secp256k1 + SHA-256
- DID: `did:xrpl:1:{xrplAccount}`
- expanded JSON + `proof.proofValue` 형식은 compatibility mode로만 유지한다.

## 1. Wallet 책임

Android 앱은 다음 값을 생성하거나 보관한다.

| 데이터 | 용도 | 보관 방식 |
| --- | --- | --- |
| XRPL holder seed/account | `CredentialAccept` 서명 | Keystore/StrongBox 래핑 키로 암호화 |
| holder DID | holder 식별 | 앱 DB |
| holder 인증 키 | `vp+jwt` 서명 | hardware-backed 우선, 불가 시 암호화 저장 |
| holder DID Document | verifier가 VP 서명 검증 | 앱 DB 및 verifier 제출 |
| VC JWT | issuer가 발급한 `vc+jwt` 원문 | 앱 DB |
| credentialType | XRPL Credential 식별 | VC JWT payload에서 파싱 |
| issuer/holder account | status 조회, accept | VC JWT payload에서 파싱 |
| tx hash | accept 제출 추적 | 앱 DB |

현재 Android 구현은 XRPL holder seed와 VP 서명용 holder authentication key를 분리한다.

- XRPL holder seed: `CredentialAccept` 트랜잭션 서명 전용
- holder authentication key: `vp+jwt` 및 compatibility VP proof 서명 전용

두 민감 값은 모두 Android Keystore AES-GCM 래핑 키로 암호화해 저장한다. 기존 설치에 authentication key가 없으면 앱이 wallet state 조회 시 별도 secp256k1 authentication key를 자동 생성해 저장한다.

## 2. 전체 플로우

```text
1. 앱이 XRPL holder account를 생성하거나 복구한다.
2. 앱이 holder DID와 DID Document를 만든다.
3. issuer backend가 holder_account, holder_did로 core에 VC 발급을 요청한다.
4. core가 VC를 vc+jwt로 발급하고 XRPL CredentialCreate를 제출한다.
5. 앱이 vc+jwt를 수신하고 payload를 검증한 뒤 저장한다.
6. 앱이 holder account로 CredentialAccept를 제출한다.
7. 앱이 XRPL status active/accepted를 확인한다.
8. verifier challenge를 발급받는다.
9. 앱이 vc+jwt를 EnvelopedVerifiableCredential로 감싼 vp+jwt를 생성한다.
10. 앱이 vp+jwt와 holder DID Document를 verifier에 제출한다.
```

XRPL status 규칙:

- `CredentialCreate` 직후에는 active가 아니다.
- holder가 `CredentialAccept`를 제출해야 active가 된다.
- `CredentialDelete`, expiration, accepted bit 미설정 상태는 inactive다.

## 3. DID Document

holder DID는 다음 형식이다.

```text
did:xrpl:1:{holderAccount}
```

DID Document:

```json
{
  "@context": [
    "https://www.w3.org/ns/did/v1",
    "https://w3id.org/security/jwk/v1"
  ],
  "id": "did:xrpl:1:rHolder...",
  "verificationMethod": [
    {
      "id": "did:xrpl:1:rHolder...#holder-key-1",
      "type": "JsonWebKey",
      "controller": "did:xrpl:1:rHolder...",
      "publicKeyJwk": {
        "kty": "EC",
        "crv": "secp256k1",
        "x": "...base64url-no-padding...",
        "y": "...base64url-no-padding..."
      }
    }
  ],
  "authentication": [
    "did:xrpl:1:rHolder...#holder-key-1"
  ]
}
```

규칙:

- `crv`는 `secp256k1`
- `x`, `y`는 32-byte big-endian 좌표의 base64url no-padding
- VP JWT header의 `kid`는 `authentication`에 포함된 method여야 한다.

## 4. VC 발급 수신

Android 앱은 issuer seed/private key/PEM을 다루지 않는다. 발급 요청은 issuer backend 또는 운영 API가 core로 보낸다.

```http
POST /issuer/credentials/kyc
Content-Type: application/json
```

요청 예시:

```json
{
  "holder_account": "rHolder...",
  "holder_did": "did:xrpl:1:rHolder...",
  "claims": {
    "kycLevel": "BASIC",
    "jurisdiction": "KR"
  },
  "valid_from": "2026-05-02T00:00:00Z",
  "valid_until": "2026-06-02T00:00:00Z"
}
```

응답 예시:

```json
{
  "credential": "eyJhbGciOiJFUzI1Nksi...",
  "credential_type": "56435F5354415455535F56313A...",
  "vc_core_hash": "...",
  "credential_create_transaction": {},
  "ledger_entry": {},
  "status_mode": "xrpl"
}
```

앱은 `credential` JWT 원문을 저장하고, payload를 decode해서 다음 값을 추출한다.

- VC `id`
- issuer DID/account
- holder DID/account
- `credentialStatus.credentialType`
- `credentialStatus.vcCoreHash`
- `validFrom`, `validUntil`

## 5. VC JWT 검증

VC JWT는 compact JWS다.

```text
BASE64URL(header).BASE64URL(payload).BASE64URL(signature)
```

수신 직후 확인할 항목:

- header `alg == "ES256K"`
- header `typ == "vc+jwt"`
- header `cty == "vc"`
- header `iss == payload.issuer`
- `payload.credentialSubject.id == holderDid`
- `payload.credentialStatus.subject == holderAccount`
- `payload.credentialStatus.issuer == account_from_did(payload.issuer)`
- `payload.credentialStatus.credentialType == issueResponse.credential_type`
- `validFrom <= now <= validUntil`
- issuer DID Document를 구할 수 있으면 `kid` 공개키로 JWS signature 검증

주의:

- `credentialType`은 직접 재계산하지 말고 issuer 응답/VC payload 값을 그대로 쓴다.
- 실서버 VC JWT payload를 앱에서 임의 수정하면 issuer signature가 깨진다.

## 6. ES256K JWS 주의사항

JOSE `ES256K` signature는 64-byte raw signature다.

```text
R(32 bytes) || S(32 bytes)
```

Android/Java ECDSA API는 보통 DER signature를 반환한다. JWT signature segment에 넣기 전 DER를 raw 64-byte로 변환해야 한다.

signing input:

```text
ascii(BASE64URL(header) + "." + BASE64URL(payload))
```

base64url은 padding `=`을 제거한다.

## 7. CredentialAccept 제출

Holder가 VC를 수락하려면 Android 앱이 holder XRPL wallet으로 `CredentialAccept`를 제출한다.

```json
{
  "TransactionType": "CredentialAccept",
  "Account": "rHolder...",
  "Issuer": "rIssuer...",
  "CredentialType": "56435F5354415455535F56313A..."
}
```

입력 매핑:

| XRPL 필드 | 값 |
| --- | --- |
| `Account` | holder XRPL account |
| `Issuer` | VC JWT payload `issuer` DID에서 account 추출 |
| `CredentialType` | VC JWT payload `credentialStatus.credentialType` |

제출 후 `/credential-status/credentials/{issuerAccount}/{holderAccount}/{credentialType}` 기준으로 active를 확인한다.

## 8. VP JWT 생성

Verifier 제출 전 challenge를 발급받는다.

```http
POST /verifier/presentations/challenges
Content-Type: application/json
```

응답의 `challenge`, `domain`, `expires_at`를 사용한다.

VP payload의 `verifiableCredential`에는 raw VC payload가 아니라 Enveloped VC를 넣는다.

```json
{
  "@context": ["https://www.w3.org/ns/credentials/v2"],
  "type": ["VerifiablePresentation"],
  "holder": "did:xrpl:1:rHolder...",
  "verifiableCredential": [
    {
      "@context": "https://www.w3.org/ns/credentials/v2",
      "id": "data:application/vc+jwt,eyJhbGciOiJFUzI1Nksi...",
      "type": "EnvelopedVerifiableCredential"
    }
  ]
}
```

VP JWT header:

```json
{
  "alg": "ES256K",
  "typ": "vp+jwt",
  "cty": "vp",
  "kid": "did:xrpl:1:rHolder...#holder-key-1",
  "challenge": "...",
  "domain": "kyvc.local"
}
```

`challenge`와 `domain`은 protected header에 들어가야 한다.

## 9. VP 제출

```http
POST /verifier/presentations/verify
Content-Type: application/json
```

요청:

```json
{
  "presentation": "eyJhbGciOiJFUzI1Nksi...",
  "did_documents": {
    "did:xrpl:1:rHolder...": {}
  },
  "policy": {
    "trustedIssuers": ["did:xrpl:1:rIssuer..."],
    "acceptedKycLevels": ["BASIC"],
    "acceptedJurisdictions": ["KR"]
  },
  "status_mode": "xrpl",
  "require_status": true
}
```

성공 기준:

- `ok: true`
- `errors: []`
- embedded VC 검증 성공
- XRPL credential accepted
- challenge가 발급/미사용/미만료 상태

## 10. 현재 Android 구현 상태

완료:

- holder wallet 생성/조회
- seed Keystore 암호화 저장
- DID Document 생성
- expanded JSON compatibility flow
- devnet `CredentialCreate` / `CredentialAccept`
- 실서버 VC 인증 및 VP 제출 성공
- `vc+jwt` issuer 응답 수신/파싱/저장 1차 반영
- `vp+jwt` 생성 1차 반영
- XRPL account key와 VP holder authentication key 분리
- VC JWT header 검증
- issuer DID Document 조회 가능 시 VC JWT ES256K signature 검증
- verifier 제출 시 `vp+jwt` 우선 제출

남은 작업:

- Room schema의 `vcJson` 명칭을 `credential`/`vcJwt` 중심으로 정리
- JWT test vector 추가

## 11. 개발 검증 순서

1. holder wallet 생성/조회
2. holder DID Document 확인
3. issuer에서 `vc+jwt` 발급
4. Android에서 VC JWT decode 및 저장
5. `CredentialAccept` 제출
6. XRPL status `active: true`, `accepted: true`
7. verifier challenge 수신
8. `vp+jwt` 생성
9. `/verifier/presentations/verify` 성공 확인
10. 같은 VP 재제출 시 challenge reuse 실패 확인

## 12. 운영 주의사항

- seed, private key, PEM은 WebView/로그/문서에 저장하지 않는다.
- Android Keystore가 secp256k1 signing을 직접 지원하지 않는 기기가 많을 수 있다.
- `CredentialAccept` tx 제출 성공과 ledger active 상태는 별도로 관리한다.
- challenge는 1회용이다.
- 신규 구현은 `proof.jws` expanded JSON을 기본 경로로 사용하지 않는다.
