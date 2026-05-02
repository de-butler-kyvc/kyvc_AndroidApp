# kyvc_AndroidApp

kyvc의 안드로이드 전용 앱 개발용 레포지토리입니다.

## 🛠 기술 스택
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Design System**: Material 3
- **Min SDK**: 24
- **Target SDK**: 36

## 📌 주요 작업 내용
### 프로젝트 초기화 (2025-05-22)
- Android 프로젝트 기본 구조 생성
- Jetpack Compose 및 기본 종속성 설정
- 프로젝트 가이드라인(`PROJECT_GUIDELINES.md`) 및 프롬프트(`codex_prompt.txt`) 설정

## 🚀 향후 작업 계획
- [x] **MCP 연동 환경 구성**: AI 어시스턴트 협업을 위한 `mcp-context.md` 및 환경 설정 완료
- [ ] 베이스 아키텍처 설정 (Clean Architecture + MVVM)
- [ ] **WebView 기능 구현**: 기존 웹 서비스 연동을 위한 WebView 브라우저 환경 구축 (Bridge 기능 포함)
- [ ] **VC(Verifiable Credentials) 인증 시스템**: 로컬 저장소 기반 VC 대조 및 인증 로직 구현
- [ ] **Blockchain Wallet 연동**: 경량 지갑 구현 (VC 저장, XRPL 통신, Key 서명 등)
