# kyvc_AndroidApp

kyvc의 안드로이드 전용 앱 개발용 레포지토리입니다.

## 🛠 기술 스택
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Design System**: Material 3
- **Min SDK**: 24
- **Target SDK**: 36

## 📌 주요 작업 내용
### WebView 로컬 테스트 환경 구축 (2025-05-22)
- `app/src/main/assets/index.html` 생성 (테스트용 웹 페이지)
- `MainActivity.kt` 로드 URL을 로컬 에셋(`file:///android_asset/index.html`)으로 변경
- Web-to-Native 통신 실험을 위한 JS 인터페이스 기초 준비

## 🚀 향후 작업 계획
- [x] **MCP 연동 환경 구성**: AI 어시스턴트 협업을 위한 `mcp-context.md` 및 환경 설정 완료
- [ ] 베이스 아키텍처 설정 (Clean Architecture + MVVM)
- [x] **WebView 기능 구현**: 기초 WebView 환경 구축 및 테스트 웹 로드 완료
- [ ] **WebView Bridge 구현**: Web-to-Native 통신 인터페이스 추가
- [ ] **VC(Verifiable Credentials) 인증 시스템**: 로컬 저장소 기반 VC 대조 및 인증 로직 구현
- [ ] **Blockchain Wallet 연동**: 경량 지갑 구현 (VC 저장, XRPL 통신, Key 서명 등)
