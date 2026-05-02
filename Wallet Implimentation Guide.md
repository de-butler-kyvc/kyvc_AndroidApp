# Android Holder Wallet 구현 가이드

이 문서는 `core`의 issuer/verifier 흐름을 기준으로 Android holder wallet을 구현할 때 필요한 계약과 순서를 정리한다. 현재 `core`는 holder API를 제공하지 않는다. Holder wallet은 모바일 앱에서 XRPL 계정과 holder 인증 키를 보관하고, issuer가 만든 VC를 저장한 뒤 XRPL `CredentialAccept` 트랜잭션을 직접 제출한다.

참고 코드:

- `holder-test/test_holder_flow.py`: holder 역할을 수행하는 end-to-end 예제
- `app/issuer/api_models.py`: VC 발급 요청/응답 모델
- `app/verifier/api_models.py`: VC/VP 검증 요청/응답 모델
- `app/credentials/vc.py`, `app/credentials/vp.py`: VC/VP 서명 입력과 proof 규칙
- `app/xrpl/ledger.py`: XRPL `CredentialCreate`, `CredentialAccept`, `CredentialDelete`, status 조회 규칙

## 1. Holder Wallet의 책임

Android 앱은 다음 데이터를 생성하거나 보관해야 한다.

| 데이터 | 용도 | 보관 위치 권장 |
| --- | --- | --- |
| XRPL holder seed/account | `CredentialAccept` 서명 및 holder 계정 식별 | Keystore/StrongBox 래핑 키로 암호화한 저장소 |
| holder DID | `did:xrpl:1:{holderAccount}` 형식 | 앱 DB |
| holder 인증 키 | VP `authentication` proof 서명 | secp256k1 지원 여부에 따라 직접 하드웨어 키 또는 암호화 저장 |
| holder DID Document | verifier가 VP proof를 검증할 때 사용 | 앱 DB, verifier 제출용 JSON |
| VC 원문 JSON | issuer가 발급한 KYC VC | 앱 DB |
| credentialType | XRPL Credential ledger entry 식별 | VC의 `credentialStatus.credentialType` |
| issuer/holder account | status 조회, accept, revoke 확인 | VC에서 파싱 후 앱 DB |
| tx hash | accept 제출 추적 | 앱 DB |

`core`의 DID 형식은 고정이다.

```text
did:xrpl:1:{xrplAccount}
```

예를 들어 holder account가 `rHolder...`이면 holder DID는 `did:xrpl:1:rHolder...`이다.

## 2. 전체 플로우

```text
1. 앱이 XRPL holder account와 holder 인증 키를 생성한다.
2. 앱이 holder DID Document를 만든다.
3. 앱이 issuer에게 holder_account, holder_did를 전달한다.
4. issuer가 POST /issuer/credentials/kyc로 VC를 발급하고 XRPL CredentialCreate를 제출한다.
5. 앱이 VC를 수신하고 로컬 검증 후 저장한다.
6. 앱이 XRPL CredentialAccept를 holder 계정으로 서명/제출한다.
7. 앱 또는 verifier가 credential status를 조회해 active 상태를 확인한다.
8. verifier 로그인/제출 시 앱이 challenge를 받아 VP를 만들고 제출한다.
```

중요한 상태 규칙:

- issuer가 `CredentialCreate`를 제출한 직후 VC는 아직 active가 아니다.
- holder가 `CredentialAccept`를 제출해야 active가 된다.
- issuer가 `CredentialDelete`를 제출하면 inactive가 된다.
- verifier는 기본적으로 XRPL ledger entry를 authoritative status source로 사용한다.

## 3. Holder DID Document

VP 검증에는 holder DID Document가 필요하다. `core`의 `holder_diddoc()`과 같은 구조를 Android에서 생성한다.

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
        "x": "...base64url...",
        "y": "...base64url..."
      }
    }
  ],
  "authentication": [
    "did:xrpl:1:rHolder...#holder-key-1"
  ]
}
```

주의:

- `crv`는 `secp256k1`이어야 한다.
- `x`, `y`는 32-byte big-endian 좌표를 base64url no-padding으로 인코딩한다.
- VP proof의 `verificationMethod`는 `authentication` 배열에 들어 있어야 한다.

## 4. VC 발급 연동

발급 요청은 issuer 또는 issuer backend가 `core`로 보낸다. Android 앱이 직접 issuer seed나 issuer private key를 다루면 안 된다.

```http
POST /issuer/credentials/kyc
Content-Type: application/json
```

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

실제 issuer 요청에는 `issuer_seed` 또는 서버 환경변수 `XRPL_ISSUER_SEED`, 그리고 `issuer_private_key_pem`이 필요하다. 이 값들은 Android 앱으로 전달하지 않는다.

발급 응답에서 앱이 저장해야 하는 핵심 필드는 다음과 같다.

```json
{
  "credential": {},
  "credential_type": "56435F5354415455535F56313A...",
  "vc_core_hash": "...",
  "credential_create_transaction": {},
  "ledger_entry": {},
  "status_mode": "xrpl"
}
```

VC 내부에도 같은 값이 들어 있다.

```json
{
  "credentialStatus": {
    "type": "XRPLCredentialStatus",
    "issuer": "rIssuer...",
    "subject": "rHolder...",
    "credentialType": "56435F5354415455535F56313A...",
    "vcCoreHash": "..."
  }
}
```

앱은 VC 수신 직후 최소한 다음을 확인한다.

- `credentialSubject.id == holderDid`
- `credentialStatus.subject == holderAccount`
- `credentialStatus.issuer == account_from_did(credential.issuer)`
- `credentialStatus.credentialType == credential_type`
- `validFrom <= now <= validUntil`
- 가능한 경우 issuer DID Document로 VC signature를 검증

## 5. CredentialAccept 제출

Holder가 VC를 수락하려면 Android 앱이 holder XRPL wallet으로 `CredentialAccept`를 서명하고 제출해야 한다. `core`의 Python 구현은 다음 필드만 사용한다.

```json
{
  "TransactionType": "CredentialAccept",
  "Account": "rHolder...",
  "Issuer": "rIssuer...",
  "CredentialType": "56435F5354415455535F56313A..."
}
```

입력값 매핑:

| XRPL 필드 | 값 |
| --- | --- |
| `Account` | holder XRPL account |
| `Issuer` | VC의 `issuer` DID에서 account 추출 |
| `CredentialType` | VC의 `credentialStatus.credentialType` |

제출 전 검증:

- holder seed에서 복원한 account가 VC의 `credentialStatus.subject`와 같아야 한다.
- `CredentialType`은 VC에서 받은 hex 문자열을 그대로 사용한다.
- issuer account는 `did:xrpl:1:{account}`에서 `{account}` 부분만 사용한다.

제출 후에는 tx hash를 저장하고 status를 다시 조회한다.

```http
GET /credential-status/credentials/{issuerAccount}/{holderAccount}/{credentialType}
```

응답은 다음 형태다.

```json
{
  "issuer_account": "rIssuer...",
  "holder_account": "rHolder...",
  "credential_type": "56435F5354415455535F56313A...",
  "found": true,
  "active": true,
  "entry": {},
  "checked_at": "2026-05-02T00:00:00Z"
}
```

active 판정은 `core` 기준으로 다음 조건을 모두 만족해야 한다.

- ledger entry가 존재한다.
- `Flags`에 accepted bit `0x00010000`이 설정되어 있다.
- `Expiration`이 있으면 현재 시간보다 미래다.

## 6. VP 생성과 제출

Verifier에게 holder가 VC를 제시할 때는 challenge 기반 VP를 만든다.

### 6.1 Challenge 발급

```http
POST /verifier/presentations/challenges
Content-Type: application/json
```

```json
{
  "domain": "example.com"
}
```

응답:

```json
{
  "challenge": "...",
  "domain": "example.com",
  "expires_at": "2026-05-02T00:05:00Z"
}
```

### 6.2 VP JSON

Android 앱은 다음 구조의 VP를 만든다.

```json
{
  "@context": ["https://www.w3.org/ns/credentials/v2"],
  "type": ["VerifiablePresentation"],
  "holder": "did:xrpl:1:rHolder...",
  "verifiableCredential": [
    {}
  ],
  "proof": {
    "type": "DataIntegrityProof",
    "cryptosuite": "ecdsa-secp256k1-jcs-poc-2026",
    "created": "2026-05-02T00:00:00Z",
    "verificationMethod": "did:xrpl:1:rHolder...#holder-key-1",
    "proofPurpose": "authentication",
    "challenge": "...",
    "domain": "example.com",
    "proofValue": "..."
  }
}
```

`proofValue` 생성 규칙은 `app/credentials/vp.py`와 같다.

```text
signing_input =
  "POC-DATA-INTEGRITY-v1" bytes
  + canonical_json(vp_without_proof)
  + "." bytes
  + canonical_json(proof_without_proofValue)
```

서명 규칙:

- 알고리즘: ECDSA over secp256k1 with SHA-256
- signature encoding: DER bytes
- 문자열 encoding: base64url no-padding
- canonical JSON: key 정렬, 공백 없음, UTF-8, `ensure_ascii=false`와 동등한 출력

Kotlin 의사 코드:

```kotlin
val vpWithoutProof = mapOf(
    "@context" to listOf("https://www.w3.org/ns/credentials/v2"),
    "type" to listOf("VerifiablePresentation"),
    "holder" to holderDid,
    "verifiableCredential" to listOf(vcJsonObject)
)

val proofWithoutValue = mapOf(
    "type" to "DataIntegrityProof",
    "cryptosuite" to "ecdsa-secp256k1-jcs-poc-2026",
    "created" to nowIsoUtc(),
    "verificationMethod" to "$holderDid#holder-key-1",
    "proofPurpose" to "authentication",
    "challenge" to challenge,
    "domain" to domain
)

val input = concat(
    "POC-DATA-INTEGRITY-v1".toByteArray(),
    canonicalJson(vpWithoutProof),
    ".".toByteArray(),
    canonicalJson(proofWithoutValue)
)

val signatureDer = secp256k1SignSha256Der(holderAuthPrivateKey, input)
val proofValue = base64UrlNoPadding(signatureDer)
```

### 6.3 VP 검증 요청

Verifier API에는 VP와 holder DID Document를 함께 전달한다. Issuer DID Document는 issuer API에서 저장되어 있으면 별도로 넘기지 않아도 된다.

```http
POST /verifier/presentations/verify
Content-Type: application/json
```

```json
{
  "presentation": {
    "holder": "did:xrpl:1:rHolder..."
  },
  "did_documents": {
    "did:xrpl:1:rHolder...": {
      "@context": [
        "https://www.w3.org/ns/did/v1",
        "https://w3id.org/security/jwk/v1"
      ],
      "id": "did:xrpl:1:rHolder...",
      "verificationMethod": [],
      "authentication": []
    }
  },
  "policy": {
    "trustedIssuers": ["did:xrpl:1:rIssuer..."],
    "acceptedKycLevels": ["BASIC", "ADVANCED"],
    "acceptedJurisdictions": ["KR"]
  },
  "require_status": true,
  "status_mode": "xrpl"
}
```

성공 응답:

```json
{
  "ok": true,
  "errors": [],
  "details": {
    "challengeFound": true,
    "challengeUsed": false,
    "vc_0": {
      "ok": true
    }
  }
}
```

자주 나는 실패:

| 오류 | 원인 |
| --- | --- |
| `VP challenge was not issued by verifier` | verifier에서 받은 challenge가 아니거나 만료/저장 실패 |
| `VP domain mismatch` | proof의 domain과 challenge 발급 domain 불일치 |
| `VP verificationMethod is not authorized for authentication` | DID Document의 `authentication`에 key id가 없음 |
| `embedded VC 0 failed verification` | VC signature, status, policy 중 하나가 실패 |
| `XRPL Credential status is not active` | holder가 아직 `CredentialAccept`를 제출하지 않았거나 revoke/expire됨 |

## 7. Android 저장소 모델 예시

최소 Room entity는 다음 정도면 충분하다.

```kotlin
data class HolderCredentialEntity(
    val credentialId: String,
    val vcJson: String,
    val issuerDid: String,
    val issuerAccount: String,
    val holderDid: String,
    val holderAccount: String,
    val credentialType: String,
    val vcCoreHash: String,
    val validFrom: String,
    val validUntil: String,
    val acceptedAt: String?,
    val credentialAcceptHash: String?,
    val revokedOrInactiveAt: String?
)
```

계정/키 저장은 VC DB와 분리한다.

```kotlin
data class HolderWalletState(
    val xrplAccount: String,
    val encryptedSeedRef: String,
    val holderDid: String,
    val authKeyAlias: String,
    val holderDidDocumentJson: String,
    val xrplJsonRpcUrl: String
)
```

## 8. 개발 검증 순서

Core 서버를 로컬에서 실행한다.

```bash
cd core
docker compose -f docker-compose.local.yml up -d mysql
uvicorn app.main:app --reload --port 8090
```

먼저 Python holder runner로 네트워크와 core 설정이 맞는지 확인한다.

```bash
cd core
PYTHONPATH=. .venv/bin/python holder-test/test_holder_flow.py
```

정상이라면 출력에서 다음 값들이 기대된다.

- `verify_before_accept_ok: false`
- `active_before_accept: false`
- `verify_after_accept_ok: true`
- `active_after_accept: true`
- `verify_after_delete_ok: false`
- `active_after_delete: false`

Android 앱 구현 후에는 같은 순서로 검증한다.

1. holder account 생성
2. issuer로 VC 발급
3. 앱 DB에 VC 저장
4. 앱에서 `CredentialAccept` 제출
5. `/credential-status/credentials/...` active 확인
6. challenge 발급
7. 앱에서 VP 생성
8. `/verifier/presentations/verify` 성공 확인

## 9. 운영 체크리스트

- issuer seed/private key는 Android 앱에 넣지 않는다.
- holder XRPL seed와 holder 인증 키는 백업/복구 정책을 먼저 정한 뒤 배포한다.
- Android Keystore가 secp256k1 서명을 직접 지원하지 않는 기기에서는 하드웨어 보호 키로 seed/private key를 암호화하고, 실제 secp256k1 연산은 검증된 crypto provider에서 수행한다.
- mainnet RPC 사용은 `ALLOW_MAINNET=1`과 명시적 사용자 확인 없이는 막는다.
- VP challenge는 재사용하지 않는다. `core` verifier는 성공한 challenge를 used 처리한다.
- VC 원문 JSON은 서명 대상이므로 저장 후 임의 정규화/필드 재정렬 결과를 원본 대신 쓰지 않는다.
- Android에서 canonical JSON을 직접 구현할 경우 Python `canonical_json()` 결과와 test vector를 만들어 byte-for-byte 비교한다.
- 만료된 VC는 UI에서 제출 불가 상태로 보여준다.
- `CredentialAccept` tx가 pending/failure일 수 있으므로 tx hash와 ledger status를 분리해서 관리한다.
