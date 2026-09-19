# 0002. 백엔드 세션 인증과 TanStack Query 사용

- 상태: Accepted
- 날짜: 2026-09-08
- 대체: 없음

## 배경

FRD F01과 현재 Spring Security 구현은 사전 등록 계정의 세션 로그인, 로그아웃, EDITOR/REVIEWER 역할을 제공한다. 사용자는 백엔드 인증 구조를 따르고 Server Component의 페이지 보호와 Client Component의 서버 상태 관리를 결합하기로 결정했다.

## 결정

- 인증의 정본은 백엔드 `JSESSIONID` 세션이다. JWT, NextAuth, 별도 프론트 세션, 브라우저 저장소의 인증 상태는 만들지 않는다.
- 백엔드 access 세션은 Spring Session JDBC로 PostgreSQL에 보존하며 발급 후 30분에 만료된다. 별도 HttpOnly `NPICK_REFRESH` cookie로 최초 로그인부터 최대 8시간까지 재발급하며 이 기한은 연장하지 않는다. refresh는 DB에 해시로 저장한다. 재배포에도 유지되며 기존 8시간 access 버전에서 전환할 때는 재로그인이 필요하다.
- 보호된 각 page에서 `/auth/me`를 `no-store`로 조회한다. Server Component는 server-only `API_INTERNAL_BASE_URL`로 요청하며 수신한 `JSESSIONID`만 전달한다. 세션·쿠키를 Client Component에 전달하지 않는다. layout만으로 접근을 보호하지 않는다.
- 검색은 EDITOR/REVIEWER, `/review`의 모든 화면은 REVIEWER만 허용한다. URL의 로그인 역할은 안내용이다. 최종 데이터 접근 권한은 항상 백엔드가 검증한다.
- 브라우저는 공통 HTTP client로 백엔드에 직접 요청하고 `credentials: include`를 사용한다. 배포에서는 nginx의 동일 오리진 `/api/v1`을 사용하며 Next.js API 프록시는 추가하지 않는다.
- 변경 요청 전에 `/auth/csrf`로 토큰을 준비하고 `XSRF-TOKEN` 쿠키의 값을 `X-XSRF-TOKEN` 헤더로 전송한다. 로그인·로그아웃도 예외가 아니다. 403 뒤에는 다음 수동 요청을 위해 토큰을 다시 준비하며 변경 요청 자체를 자동 재전송하지 않는다.
- TanStack Query v5로 `['auth', 'me']` 서버 상태와 로그인·로그아웃 mutation을 관리한다. 전역 사용자 store에 복제하지 않는다. 이번 구현에서 검색·문의·영상 데모까지 API로 바꾸지는 않는다.
- 일반 API의 `COMM_401`은 공통 client에서 refresh 후 원래 요청을 한 번 재전송한다. 갱신도 `COMM_401`이면 캐시 제거 후 재로그인으로 연결한다. 로그인·로그아웃·CSRF·refresh 자체는 자동 갱신 대상에서 제외한다. 네트워크·서버 오류는 로그아웃으로 바꾸지 않는다. 로그인 자격 증명 오류는 폼 안에서 표시한다. 403은 로그인 상태를 유지한다. 현재 백엔드는 권한 부족과 CSRF 오류에 같은 `COMM_403`을 사용하므로 둘을 단정해서 구분하지 않는다.
- 로그인·로그아웃 후 전체 문서 이동으로 이전 계정의 Router Cache와 메모리 상태를 폐기한다. 로그아웃은 서버 성공을 확인한 뒤 앱 소유 `npick:{memberId}:…` sessionStorage 초안을 삭제한다. 만료 시 초안은 보존하되 다른 계정으로 로그인하면 이전 계정 초안은 제거한다. 비밀번호는 저장소·URL·mutation variables에 보관하지 않는다.
- 여러 화면이 공유하는 보안 계약·API·서버 guard는 `lib/auth`, Query provider와 세션 UI 경계는 `components`에 둔다. 이는 앱 전역 기반 코드이며 기능 간 직접 import를 만들지 않는다.

## 이유

이미 구현된 백엔드 인증을 중복하지 않으면서 첫 진입의 접근 검사와 화면 사용 중 세션 만료 처리를 함께 제공한다. React local state는 입력·모달을 소유하고 TanStack Query는 서버 상태만 소유한다. 이후 query/mutation 적용 범위는 기능별로 확장할 수 있다.

## 영향

- 서버 주소와 브라우저 API 주소가 별도로 필요하다. 브라우저와 백엔드 쿠키는 같은 호스트를 사용해야 하며 운영에서는 HTTPS가 필요하다. 로컬 HTTP는 백엔드의 개발 환경에서만 Secure 쿠키 설정을 조정하거나 HTTPS를 사용한다.
- 클라이언트 접근 검사는 사용자 경험을 위한 보조 장치이며 서버 인가를 대신하지 않는다. 페이지 이동·창 복귀 시 세션을 다시 확인한다.
- SSR에서 access가 만료되고 refresh cookie가 있으면 `/session/renew`로 이동한다. Server Component는 refresh를 사용하거나 쿠키를 쓰지 않으며, 브라우저가 갱신 API와 쿠키 적용 확인을 마친 뒤 안전한 `returnTo`로 복귀한다.
- 근거: `backend/.../SecurityConfig.java`, `AuthController.java`, FRD F01, 설치된 Next.js authentication/cookies 가이드, [TanStack Query SSR 가이드](https://tanstack.com/query/v5/docs/framework/react/guides/advanced-ssr), [Spring Security CSRF 가이드](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
