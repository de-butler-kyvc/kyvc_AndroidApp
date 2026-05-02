# kyvc_AndroidApp

kyvc의 안드로이드 전용 앱 개발용 레포지토리입니다.

## 🛠 기술 스택
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Design System**: Material 3
- **Min SDK**: 24
- **Target SDK**: 36

## 📌 주요 작업 내용
### Wallet 및 Bridge 기능 구현 (2025-05-22)
- **Infrastructure**: Room DB, XRPL4J(6.0.0), JCS(Json Canonicalization), Kotlin Serialization 설정 완료
- **Wallet Core**: XRPL 계정 생성 및 `CredentialAccept` 트랜잭션 제출 기능 기초 구현
- **Storage**: `CredentialEntity`를 통한 VC 로컬 저장소 구축
- **Bridge**: 웹에서 안드로이드 기능을 호출할 수 있는 `Android` 브릿지 객체 등록
- **UI**: 지민님이 제공한 고도화된 브릿지 테스트 페이지(`index.html`) 적용
- **Build Fix**: AGP 9.2.0의 내장 Kotlin 지원과 KSP 간의 충돌 해결 (`android.disallowKotlinSourceSets=false`)

## 🚀 향후 작업 계획
- [x] **MCP 연동 환경 구성**: AI 어시스턴트 협업을 위한 `mcp-context.md` 및 환경 설정 완료
- [ ] 베이스 아키텍처 설정 (Clean Architecture + MVVM)
- [x] **WebView Bridge 구현**: Web-to-Native 통신 인터페이스 추가 및 테스트 페이지 연동 완료
- [x] **Blockchain Wallet 연동**: 경량 지갑 기반 구축 (계정 생성, XRPL 통신 기초)
- [ ] **VC(Verifiable Credentials) 인증 시스템**: 실데이터 기반 VC 대조 및 인증 로직 고도화
- [ ] **VP(Verifiable Presentation) 생성**: JCS 기반 서명 알고리즘 완성
