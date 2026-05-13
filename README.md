# KYvC Android App

프로젝트 대표 이미지 첨부(배너)

> KYvC Android App은 KYvC 서비스의 모바일 지갑 앱입니다. WebView 기반 사용자 화면과 Android 네이티브 브릿지를 연결해 지갑 생성/복구, PIN/지문 인증, QR 스캔, VC 저장/검증/제출, XRPL 연동을 처리합니다.

## 1. 프로젝트 개요

### 프로젝트 소개

KYvC Android App은 KYvC 모바일 사용자가 로컬 지갑을 생성하거나 복구하고, 발급자/검증자 서비스와 QR 및 WebView 브릿지로 상호작용할 수 있게 하는 Android 애플리케이션입니다.

앱은 웹 화면을 WebView로 표시하되, 보안성이 필요한 기능은 Android 네이티브 레이어에서 처리합니다. 대표적으로 PIN/지문 인증, 카메라 QR 스캔, 로컬 지갑 키 저장, XRPL 트랜잭션 제출, VC 저장 및 제출 보조 기능이 네이티브 브릿지로 제공됩니다.

기존 상세 개발 기록과 브릿지 세부 규격은 다음 문서를 참고합니다.

- 기존 README 백업: [README2.md](./README2.md)
- WebView 브릿지 규격: [WEBVIEW_BRIDGE_SPEC.md](./WEBVIEW_BRIDGE_SPEC.md)
- 웹 개발자 연동 가이드: [WEB_DEVELOPER_INTEGRATION_GUIDE.md](./WEB_DEVELOPER_INTEGRATION_GUIDE.md)
- 지갑 owner 연동 프롬프트: [WEB_WALLET_OWNER_INTEGRATION_PROMPT.md](./WEB_WALLET_OWNER_INTEGRATION_PROMPT.md)

### 프로젝트 목적

- 모바일 환경에서 KYvC 지갑과 VC 기능을 안전하게 제공
- 민감 키와 인증 상태를 웹이 직접 다루지 않도록 Android 네이티브 저장소와 인증 화면으로 분리
- WebView 웹 서비스와 Android 네이티브 기능을 표준 브릿지 계약으로 연결
- XRPL Testnet 기반 DID, CredentialAccept, 잔액/거래 조회, 송금 흐름 지원

### 서비스 도메인

- 디지털 신원 지갑
- Verifiable Credential 발급/보관/검증/제출
- QR 기반 발급자/검증자 연동
- XRPL 기반 DID 및 CredentialAccept 연동
- 모바일 네이티브 인증 및 로컬 지갑 보호

### 핵심 기능 요약

- WebView 기반 KYvC 모바일 웹 진입
- 로컬 지갑 생성, 복구, 삭제
- 로그인 사용자와 로컬 지갑 owner 바인딩
- PIN/패턴/지문 네이티브 인증
- QR 스캔 기반 VC 발급/제출 요청 처리
- VC 저장, 목록, 상태 조회, 검증, 제출
- `dc+sd-jwt` prepare 응답 저장, issuer account 기반 DID 보정, CredentialAccept 연동
- XRPL 계정, DIDSet, CredentialAccept, 잔액/거래 조회, 송금
- 복구 문구 백업, 지갑 복구, 증명서 관련 네이티브 화면

## 2. 전체 서비스 구성

이 저장소는 KYvC 전체 서비스 중 Android 모바일 앱 저장소입니다. 아래 구성은 KYvC 전체 서비스 관점의 역할 구분입니다.

### 사용자 서비스

- 모바일 WebView 사용자 화면 제공
- 사용자의 지갑 생성/복구/조회 흐름 제공
- VC 발급 QR 스캔, VC 저장, 제출 QR 스캔 흐름 제공
- PIN/지문 인증을 통한 민감 기능 보호

### 관리자 서비스

- 이 저장소가 직접 담당하지 않습니다.
- 발급자/검증자/운영 관리자 기능은 별도 관리자 웹/백엔드 서비스에서 담당합니다.

### Core 서비스

- VC 발급 준비, 검증 요청, 서버 검증, QR 요청 등 핵심 신원 서비스 API를 담당합니다.
- Android 앱은 WebView 또는 브릿지를 통해 Core 계열 API와 연동합니다.

### 인프라

- Android 앱은 원격 WebView URL과 Core API URL을 사용합니다.
- 네트워크 환경 장애 시 로컬 테스트 HTML fallback을 사용할 수 있습니다.

### CI/CD

- Android 빌드와 배포 자동화는 GitHub Actions 또는 별도 배포 파이프라인에서 관리합니다.
- 이 저장소에서는 Gradle 기반 Android 빌드가 기준입니다.

## 3. Monorepo 디렉터리 구조

이 저장소는 Android 앱 단일 레포입니다. 아래 항목은 요청된 KYvC 전체 monorepo 양식에 맞춘 역할 설명이며, 이 저장소에 실제로 모두 존재하는 디렉터리는 아닙니다.

### frontend

- 사용자 웹 프론트엔드 영역입니다.
- Android 앱에서는 WebView로 사용자 웹을 표시합니다.

### frontend_admin

- 일반 관리자 웹 프론트엔드 영역입니다.
- Android 앱 저장소의 직접 책임 범위가 아닙니다.

### frontend_core_admin

- Core 관리자 웹 프론트엔드 영역입니다.
- Android 앱 저장소의 직접 책임 범위가 아닙니다.

### backend

- 사용자 서비스 API 영역입니다.
- Android 앱은 필요 시 WebView 또는 브릿지를 통해 사용자 API와 통신합니다.

### backend_admin

- 관리자 서비스 API 영역입니다.
- Android 앱 저장소의 직접 책임 범위가 아닙니다.

### core

- KYvC VC, DID, 검증, 발급, 제출 관련 핵심 도메인 API 영역입니다.
- Android 앱은 Core API 요청에 필요한 기기 정보, 지갑 공개키, VC 데이터, QR payload를 브릿지로 전달합니다.

### core_admin

- Core 운영/관리 API 영역입니다.
- Android 앱 저장소의 직접 책임 범위가 아닙니다.

### infra

- 배포, 네트워크, 서버, 인증서, 환경 변수, 운영 설정 영역입니다.
- Android 앱에서는 원격 URL, WebView fallback, 네트워크 권한, 앱 빌드 설정과 연결됩니다.

### .github

- GitHub Actions, PR 템플릿, 자동화 설정 영역입니다.
- Android 빌드/검증 자동화가 있다면 이 영역에서 관리합니다.

## 4. 서비스별 책임 분리

### frontend 책임

- 로그인/화면 라우팅/사용자 UI 처리
- Android 브릿지 호출 및 콜백 처리
- 지갑 owner mismatch, 이메일 인증 필요, 인증 실패 상태 UI 처리

### frontend_admin 책임

- 관리자 UI 제공
- Android 앱 직접 책임 범위 아님

### frontend_core_admin 책임

- Core 관리자 UI 제공
- Android 앱 직접 책임 범위 아님

### backend 책임

- 사용자 계정, 로그인, 일반 사용자 서비스 API 제공
- Android 앱의 현재 로그인 사용자 식별값 제공

### backend_admin 책임

- 관리자 계정 및 관리자 API 제공
- Android 앱 직접 책임 범위 아님

### core 책임

- VC 발급/검증/제출 도메인 처리
- QR 요청 생성 및 검증
- DID/VC 관련 서버 검증
- Android 앱과 WebView 브릿지 계약에 맞는 payload 제공

### core_admin 책임

- Core 운영 관리 기능 제공
- Android 앱 직접 책임 범위 아님

### infra 책임

- dev/prod URL, TLS, 서버 접근, 배포 환경 관리
- Android 앱 배포 채널과 연동되는 환경 설정 관리

## 5. 전체 통신 구조

### frontend → backend

- 사용자 로그인, 세션, 계정 정보 조회
- 로그인 성공 후 Android 브릿지 `setCurrentWebUser` 호출에 필요한 stable user id 제공

### frontend_admin → backend_admin

- 관리자 웹과 관리자 API 통신
- Android 앱 직접 책임 범위 아님

### frontend_core_admin → core_admin

- Core 관리자 웹과 Core 관리자 API 통신
- Android 앱 직접 책임 범위 아님

### backend → core

- 사용자 서비스가 VC 발급/검증/제출 등 Core 도메인 기능을 위임할 때 사용
- Android 앱은 QR payload 또는 WebView 브릿지를 통해 해당 흐름에 참여

### backend_admin → core 직접 호출 금지

- 관리자 백엔드가 Core를 직접 호출하는 정책은 전체 서비스 아키텍처 규칙에 따릅니다.
- Android 앱은 이 호출 경로를 직접 만들지 않습니다.

## 6. 기술 스택 요약

### Frontend

- Android WebView
- 로컬 테스트 HTML: `app/src/main/assets/index.html`
- JavaScript Android Bridge: `window.Android.*`

### Backend

- 사용자 계정/로그인 API
- Android 앱은 WebView 또는 브릿지 backendRequest를 통해 일부 API와 연동

### Core

- VC 발급/검증/제출 API
- QR 기반 발급/제출 요청
- XRPL DID 및 CredentialAccept 연동

### Infra

- Android Gradle 빌드
- 원격 WebView URL
- 로컬 asset fallback
- Android 권한: 카메라, 인터넷, 생체 인증 등

### CI/CD

- Gradle 기반 빌드
- 기본 검증 명령:

```bash
./gradlew :app:compileDebugKotlin
```

## 7. README 문서 구조

### Front README

- 사용자 웹 화면과 Android WebView 브릿지 호출 방법 설명
- 이 저장소에서는 `WEB_DEVELOPER_INTEGRATION_GUIDE.md`가 해당 역할을 보조합니다.

### Backend README

- 사용자 로그인/계정/API 규격 설명
- Android 앱에서는 로그인 사용자 stable id를 지갑 owner 바인딩에 사용합니다.

### Infra README

- dev/prod URL, 배포 환경, 앱 설정, 네트워크 접근 정책 설명

### GitHub Actions README

- Android 빌드, 테스트, 릴리즈 자동화 설명
- 현재 저장소의 Git 운영 규칙은 [PROJECT_GUIDELINES.md](./PROJECT_GUIDELINES.md)를 따릅니다.

## 8. 배포 구조

### feature/* 기준

- 기능 단위 개발 브랜치
- Android 앱 기능 변경, 브릿지 추가, 화면 수정은 feature 브랜치에서 작업 후 PR

### develop 기준

- 개발 환경 통합 브랜치
- dev WebView/Core URL 기준 검증 대상

### main 기준

- 안정화된 기준 브랜치
- 운영 배포 또는 릴리즈 기준으로 사용

### dev 배포

- 개발 서버 URL과 Testnet 기준 검증
- WebView 원격 URL 장애 시 로컬 테스트 HTML fallback 확인

### prod 배포

- 운영 URL, 운영 서명키, 운영 배포 채널 기준 검증
- 민감 키 노출 금지와 인증/owner mismatch 정책을 반드시 확인

## 9. Git 운영 전략

### 브랜치 전략

- `feature/*`: 기능 개발
- `develop`: 개발 통합
- `main`: 안정화 및 배포 기준

### PR 흐름

- 기능 브랜치에서 작업
- 빌드 검증 후 PR 생성
- 리뷰와 충돌 해결 후 기준 브랜치에 병합

### 커밋 메시지 규칙

커밋 메시지는 [PROJECT_GUIDELINES.md](./PROJECT_GUIDELINES.md)를 따릅니다.

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
feat(core): 지갑 소유자 보호 및 네이티브 인증 개선
docs(-): Android 앱 README 구조 정리
```

### 직접 push 금지 기준

- 공동 작업 브랜치와 배포 브랜치에는 직접 push를 피하고 PR 기반 병합을 우선합니다.
- 긴급 수정이나 사용자가 명시적으로 요청한 경우에만 현재 브랜치에 직접 커밋/푸쉬합니다.
- 브릿지 요청/응답 형식이 바뀌면 코드와 문서를 같은 커밋에서 갱신합니다.
