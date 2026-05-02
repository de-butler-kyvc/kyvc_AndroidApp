# MCP Context Configuration for kyvc_AndroidApp

이 파일은 AI 어시스턴트가 프로젝트의 문맥을 파악하기 위한 가이드라인입니다.

## 📂 프로젝트 구조 및 접근 권한
- **Core Module**: `/app` (Android App)
- **External Context**: AI는 프로젝트 루트의 `PROJECT_GUIDELINES.md`와 `codex_prompt.txt`를 최우선으로 참조한다.
- **Data Access**: VC(Verifiable Credentials) 관련 로컬 저장소 로직 및 Blockchain SDK(XRPL) 연동 코드를 중점적으로 분석한다.

## 🛠 AI 작업 규칙
1. **Bridge 구현**: Web-to-Native 통신을 위한 JavaScript Interface 구현 시 안전성을 최우선으로 한다.
2. **Lightweight Wallet**: 외부 SDK 사용을 지양하고, 필요한 기능(서명, 통신) 위주로 직접 구현하는 방향을 지원한다.
3. **Documentation**: 모든 주요 변경 사항은 `README.md`에 실시간으로 반영한다.

## 🔗 외부 파일 연동 (External Files)
- 프로젝트 외부의 가이드라인이나 환경 변수가 필요한 경우, 사용자의 승인을 거쳐 `read_file` 또는 `read_url`을 통해 확보한다.
