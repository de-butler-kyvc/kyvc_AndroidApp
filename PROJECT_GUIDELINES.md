KYvC Git 운영 전략:
브랜치는 main, develop, feature/*만 사용한다.
main은 prod 자동배포 기준, develop은 dev 자동배포 기준, feature/*는 기능 개발용이며 배포하지 않는다.
main, develop 브랜치는 직접 커밋/푸쉬할 수 없으며 PR 병합만 허용한다.
작업 흐름은 feature/* → develop → main이다.
커밋 메시지는 type(scope): 작업 내용 형식을 사용한다.
type은 feat, fix, refactor, test, docs, chore를 사용한다.
scope는 back, back-admin, front, front-admin, core, core-admin, infra, -를 사용한다.
scope의 -는 전체 공통, 루트 설정, 저장소 구조처럼 특정 영역 하나로 묶기 애매한 작업에 사용한다.
커밋 예시는 feat(back): 검증 요청 API 추가, docs(-): Git 운영 전략 문서 추가 정도를 기준으로 한다.

Monorepo 구조를 사용하며 /< 디랙토리(ex. Core, core_admin) > 내부에서만 작업하며 다른 디랙토리의 내용은 수정하지 않는다.