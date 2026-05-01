KYvC Git 운영 전략:

커밋 메시지는 type(scope): 작업 내용 형식을 사용한다.
type은 feat, fix, refactor, test, docs, chore를 사용한다.
scope는 back, back-admin, front, front-admin, core, core-admin, infra, -를 사용한다.
scope의 -는 전체 공통, 루트 설정, 저장소 구조처럼 특정 영역 하나로 묶기 애매한 작업에 사용한다.
커밋 예시는 feat(back): 검증 요청 API 추가, docs(-): Git 운영 전략 문서 추가 정도를 기준으로 한다.

Monorepo 구조를 사용하며 /< 디랙토리(ex. Core, core_admin) > 내부에서만 작업하며 다른 디랙토리의 내용은 수정하지 않는다.