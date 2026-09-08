# 🏗️ 실용적 DDD 기반 Spring Boot 패키지 아키텍처 가이드

> [!NOTE]
> **원본:** Notion — [실용적 DDD 기반 Spring Boot 패키지 아키텍처 가이드](https://app.notion.com/p/39d77095faf281079737eb831165685f)
> 최종 수정 2026-09-01 · 이 파일은 Notion 원본의 사본이며 현재 원본과 동일하다. 내용 변경은 Notion 원본을 먼저 고치고 이 파일에 반영한다.

> 단일 Spring Boot 모듈에서 도메인 경계를 유지하면서도 과도한 추상화를 피하기 위한 실용적 DDD 패키지 설계 가이드다. API 서버가 비즈니스 로직과 데이터 저장을 담당하고, 외부 시스템은 Port와 Adapter로 연동하는 상황을 전제로 한다.

---

## 용어

- **도메인 모듈**: 최상위 패키지 단위. Bounded Context 또는 업무 도메인을 말하며 업무 언어로 명명한다.
- **domain 계층**: 도메인 모듈 안의 `domain/` 패키지. 순수 도메인 모델과 Domain Repository 인터페이스가 있다.

# 1. 설계 목표

- 단일 Spring Boot 모듈을 **모듈러 모놀리스**로 운영한다.
- 최상위 패키지를 기술 계층이 아니라 도메인 단위로 나눈다.
- 순수 도메인 모델과 JPA Entity를 분리한다.
- 비즈니스 불변식이나 상태 전이를 수반하는 변경은 도메인 모델을 통과하게 한다.
- Projection·목록·검색·집계 조회에는 application이 소유하는 조회 전용 QueryPort를 허용한다.
- Controller는 구체 Application Service가 아닌 UseCase 인터페이스에 의존한다.
- 외부 시스템 세부 구현이 application과 domain에 노출되지 않게 한다.
- 공통 코드는 최소화하고 비즈니스 코드를 common에 몰아넣지 않는다.

> 💡 이 구조는 엄격한 헥사고날 아키텍처보다는 가볍고, 일반적인 controller-service-repository 수평 구조보다는 도메인 경계가 강한 실용적 절충안이다.

# 2. 최상위 패키지

```
src/main/java/com.example
├── Application.java
│
├── common
│   ├── config
│   ├── security
│   ├── response
│   ├── error
│   └── infrastructure
│
├── domainA
├── domainB
├── domainC
└── domainD
```

- `common`: 여러 도메인 모듈이 공유하는 기술 설정과 공통 계약
- `domainA` 등: 실제 Bounded Context 또는 업무 도메인
- common은 하나의 비즈니스 도메인이 아니다.
- 외부 API Client, 도메인 DTO, Repository를 common에 모으지 않는다.

# 3. 도메인 모듈 내부 기본 구조

```
domainA/
├── presentation
│   ├── controller
│   ├── request
│   └── response
│
├── application
│   ├── command
│   ├── query
│   ├── port
│   └── error
│
├── domain
│   ├── model
│   ├── repository
│   ├── policy
│   └── error
│
└── infrastructure
    ├── persistence
    │   ├── entity
    │   ├── mapper
    │   ├── repository
    │   └── query
    └── externalSystem
        ├── client
        │   ├── request
        │   └── response
        ├── config
        └── adapter
```

## 계층별 책임

### presentation

- REST Controller
- HTTP 요청 검증
- API Request를 Command 또는 Query로 변환
- application Result를 API Response로 변환
- HTTP에만 필요한 표현과 설정 관리

### application

- 유스케이스 흐름 조정
- 관계형 영속 작업의 원자성이 필요할 때 트랜잭션 경계 소유
- 도메인 모델과 Domain Repository, QueryPort, 외부 Port 호출
- 상태 변경 Command와 읽기 Query 구분
- 외부 의존성 실패와 유스케이스 조정 실패를 표현하는 application ErrorCode 소유
- 비즈니스 규칙 자체보다는 실행 순서와 협력 관계 관리

### domain

- 순수 Java 도메인 모델
- Aggregate와 Value Object
- 비즈니스 불변식과 상태 전이
- Aggregate를 저장·복원하는 Domain Repository 인터페이스
- 여러 도메인 객체에 걸친 순수 Policy
- 비즈니스 규칙 실패를 표현하는 domain ErrorCode
- Spring, JPA, Feign, HTTP에 의존하지 않음

### infrastructure

- JPA Entity와 Spring Data JPA
- Domain Repository 구현 Adapter
- QueryPort 구현 Adapter와 쿼리 기술상 필요할 때만 사용하는 조회 전용 Row
- JPQL·QueryDSL 등 조회 구현
- Feign Client와 외부 시스템 Adapter
- 공급사 예외를 application ErrorCode 기반 내부 예외로 변환
- 프레임워크와 외부 기술에 대한 실제 구현

# 4. 의존성 방향

```
presentation → application → domain
                    ↑            ↑
                    └ infrastructure ┘
```

세부 규칙은 다음과 같다.

- domain은 application, presentation, infrastructure를 알지 못한다.
- domain과 application이 common에 의존할 수 있는 범위는 Spring·HTTP에 독립적인 ErrorCode와 BusinessException 계약뿐이다.
- application은 domain에 의존한다.
- presentation은 application의 UseCase 인터페이스에 의존한다.
- presentation은 domain을 알지 못한다. Command·Query·Result만 주고받으며, domain의 enum·VO가 필요하면 presentation 전용 타입으로 다시 정의하고 변환은 Response 매핑 지점에서 한다.
- infrastructure는 domain 또는 application이 정의한 인터페이스를 구현한다.
- application과 domain은 infrastructure 구현체를 직접 참조하지 않는다.
- 다른 도메인 모듈의 JPA Entity나 infrastructure Repository에 직접 접근하지 않는다.

# 5. Command와 Query

Command와 Query는 별도 서버나 별도 DB를 사용하는 CQRS를 의미하지 않는다. 하나의 애플리케이션 안에서 요청의 목적을 구분하는 규칙이다.

## Command

데이터를 생성·수정·삭제하거나 비즈니스 상태를 변경한다.

```
API Request
→ Command
→ UseCase
→ Application Service
→ Domain Model behavior
→ Domain Repository
→ Persistence Adapter
```

- 비즈니스 불변식이나 상태 전이를 수반하는 변경은 반드시 Aggregate behavior를 통과한다.
- 캐시 항목, 토큰 저장, 파일 전송 상태 같은 순수 기술 상태를 위해 Domain Model을 억지로 만들지 않는다.
- 관계형 영속 작업의 원자성이 필요할 때 Application Service의 public UseCase 구현 메서드에 `@Transactional`을 둔다.
- Redis, 파일, 외부 API만 사용하는 Command에는 트랜잭션을 기계적으로 붙이지 않는다.
- 도메인 객체의 메서드가 불변식과 상태 전이를 보호한다.
- 반환값이 없으면 Result를 억지로 만들지 않는다.

## Query

상태를 변경하지 않고 필요한 데이터를 읽는다.

```
Aggregate 자체 또는 도메인 판단이 필요한 조회
→ Domain Repository
→ Domain Model
→ Result

Projection·목록·검색·집계·리포트
→ QueryPort
→ Infrastructure Query Adapter와 필요한 경우 Row
→ Result
```

- Query 경로는 SQL 난이도가 아니라 반환 목적을 기준으로 선택한다.
- Aggregate 자체나 도메인 행동이 필요하면 Domain Repository를 사용한다.
- Projection, 페이징, 검색, 집계, 리포트, 교차 Aggregate 읽기는 QueryPort를 사용한다.
- QueryPort는 application이 소유하고 Infrastructure가 구현한다.
- Query라고 해서 QueryDSL을 반드시 사용하지 않는다.
- 구현이 단순하면 Spring Data JPA 메서드나 JPQL을 사용할 수 있고, 동적 조건·복잡한 조인·집계에는 QueryDSL을 선택할 수 있다.
- 여러 조회를 하나의 일관된 스냅샷으로 읽어야 하거나 지연 로딩이 필요한 Aggregate 조회에만 `@Transactional(readOnly = true)`를 붙인다. 단건 Projection 조회에는 붙이지 않는다.

> 📌 핵심 원칙은 "비즈니스 상태 변경은 도메인을 통과한다"이다. 읽기는 필요한 결과의 성격에 따라 Domain Repository와 QueryPort 중 하나를 선택한다.

# 6. UseCase와 Application Service 구성

UseCase는 application 계층이 외부에 제공하는 좁은 인터페이스이며, 하나의 public application operation만 제공한다.

```java
public interface CreateResourceUseCase {

    CreateResourceResult create(CreateResourceCommand command);
}
```

Application Service는 UseCase의 실제 구현체다. UseCase와 Application Service를 반드시 1:1로 만들 필요는 없다.

```java
@Service
@RequiredArgsConstructor
class ResourceLifecycleService
        implements CreateResourceUseCase,
                   StartResourceUseCase,
                   CloseResourceUseCase {

    @Override
    @Transactional
    public CreateResourceResult create(
        CreateResourceCommand command
    ) {
        // 유스케이스 구현
    }

    @Override
    @Transactional
    public void start(StartResourceCommand command) {
        // 유스케이스 구현
    }

    @Override
    @Transactional
    public void close(CloseResourceCommand command) {
        // 유스케이스 구현
    }
}
```

관계형 영속 변경이 없는 유스케이스에는 트랜잭션을 붙이지 않는다.

```java
@Service
@RequiredArgsConstructor
class ResourceNotificationService
        implements NotifyResourceUseCase {

    @Override
    public void notify(NotifyResourceCommand command) {
        // 외부 API만 호출하므로 트랜잭션 없음
    }
}
```

## 유스케이스별 하위 패키지

```
application/
├── command
│   ├── ResourceLifecycleService.java
│   ├── create
│   │   ├── CreateResourceUseCase.java
│   │   ├── CreateResourceCommand.java
│   │   └── CreateResourceResult.java
│   ├── start
│   │   ├── StartResourceUseCase.java
│   │   └── StartResourceCommand.java
│   └── close
│       ├── CloseResourceUseCase.java
│       └── CloseResourceCommand.java
│
└── query
    ├── ResourceQueryService.java
    ├── detail
    │   ├── GetResourceUseCase.java
    │   ├── GetResourceQuery.java
    │   └── GetResourceResult.java
    └── list
        ├── GetResourceListUseCase.java
        ├── GetResourceListQuery.java
        └── ResourceSummaryResult.java
```

- 인터페이스·입력·결과는 해당 유스케이스 패키지에 함께 둔다.
- 하나의 UseCase 인터페이스는 하나의 public operation만 표현한다.
- 같은 Aggregate, 트랜잭션 성격, 핵심 의존성을 공유하는 UseCase만 하나의 Application Service가 구현한다.
- 위 기준 중 하나가 달라지면 Application Service 분리를 검토한다.
- 하나의 거대한 Application Service에 모든 기능을 모으지 않는다.
- 모든 UseCase마다 Application Service를 기계적으로 하나씩 만들지 않는다.
- `Impl`보다 `ResourceLifecycleService`처럼 책임을 드러내는 이름을 사용한다.

# 7. Domain Service 대신 Policy

이 가이드에서 Service는 UseCase를 구현하는 Application Service를 의미한다. 여러 Domain Model에 걸친 순수 비즈니스 규칙은 Policy라고 부르며, 기본 구조에서는 `domain.service`를 만들지 않는다.

단일 객체가 처리할 수 있는 규칙은 해당 도메인 모델에 둔다.

```java
resource.start();
resource.close();
resource.changeOwner(ownerId);
```

여러 도메인 객체에 걸친 순수 규칙이 필요할 때만 Policy를 사용한다.

```java
public final class ResourceAdmissionPolicy {

    public boolean canJoin(
        Resource resource,
        Participant participant
    ) {
        return resource.isOpen()
            && !resource.isFull()
            && !participant.isBlocked();
    }
}
```

Policy는 순수 Java로 유지하며 Spring의 `@Service`를 붙이지 않는다.

Policy는 상태를 가지지 않는다. Application Service가 필드로 직접 생성해 보유한다.

# 8. 도메인 모델과 JPA Entity 분리

```
domain/model/Resource.java
infrastructure/persistence/entity/ResourceJpaEntity.java
infrastructure/persistence/mapper/ResourcePersistenceMapper.java
```

저장 흐름은 다음과 같다.

```
Application Service
→ Domain Model
→ Domain Repository
→ Repository Adapter
→ Persistence Mapper
→ JPA Entity
→ Spring Data JPA Repository
```

- 도메인 모델에 `@Entity`, `@Table`, `@Column`을 붙이지 않는다.
- JPA 연관관계와 지연 로딩은 infrastructure 내부 문제로 제한한다.
- Mapper가 도메인 모델과 JPA Entity를 명시적으로 변환한다.
- 공통 생성일·수정일은 `common.infrastructure.persistence.BaseJpaEntity`에 둘 수 있다.

# 9. QueryPort와 Result

DDD의 Repository는 Aggregate Root를 저장·복원하는 Domain 계약이다. Projection 조회 계약에는 Repository라는 이름을 사용하지 않는다.

application의 출력 객체는 UI를 암시하는 View보다 중립적인 Result를 사용한다.

```
GetResourceResult
ResourceSummaryResult
ResourceDetailResult
DashboardResult
```

Projection 조회에서는 application이 필요한 조회 형태를 QueryPort로 정의하고, infrastructure가 이를 구현한다.

```
GetResourceSummaryQueryPort
→ JPQL 또는 QueryDSL
→ ResourceSummaryRow
→ ResourceSummaryQueryAdapter
→ GetResourceResult
```

- QueryPort 인터페이스는 해당 Query UseCase와 가까운 application.query 패키지에 둔다.
- QueryPort 인터페이스의 시그니처에 QueryDSL 타입, JPA 타입, infrastructure Row를 노출하지 않는다.
- QueryPort 구현 Adapter와 쿼리 기술상 필요한 Row는 infrastructure에 둔다.
- application Result에 QueryDSL의 `@QueryProjection`을 붙이지 않는다.
- JPQL의 `select new`를 사용한다면 infrastructure Row를 대상으로 한다.
- 단순 SQL인지 복잡한 SQL인지가 아니라 Aggregate가 필요한지 Projection이 필요한지로 Domain Repository와 QueryPort를 구분한다.

# 10. Port와 Adapter

Port는 application이 외부 시스템에 요구하는 기능의 계약이다. Adapter는 해당 Port를 특정 기술로 구현한다.

```
application/port/ExternalResourcePort.java
infrastructure/externalSystem/ExternalResourceAdapter.java
infrastructure/externalSystem/client/ExternalFeignClient.java
```

```java
public interface ExternalResourcePort {

    ExternalResource create(Long resourceId);
}
```

```java
@Component
@RequiredArgsConstructor
class ExternalResourceAdapter
        implements ExternalResourcePort {

    private final ExternalFeignClient client;

    @Override
    public ExternalResource create(Long resourceId) {
        ExternalCreateRequest request =
            ExternalCreateRequest.from(resourceId);

        ExternalCreateResponse response =
            client.create(request);

        return new ExternalResource(
            response.id(),
            response.name()
        );
    }
}
```

Adapter의 책임은 다음과 같다.

- application 입력을 외부 API Request로 변환
- Feign Client 호출
- 외부 Response를 application 객체로 변환
- 공급사 예외를 application이 소유한 ErrorCode 기반 BusinessException으로 변환
- 외부 의존성의 일반 실패, 일시적 사용 불가, 시간 초과를 application 의미로 구분할 수 있게 함
- 외부 시스템 DTO, Client 예외, 상세 계약이 내부 계층으로 유출되지 않게 차단

# 11. Common 패키지

```
common/
├── config
│   ├── JpaConfig.java
│   ├── QueryDslConfig.java
│   ├── OpenApiConfig.java
│   └── FeignConfig.java
├── security
│   ├── config
│   └── resolver
├── response
│   └── BaseResponse.java
├── error
│   ├── ErrorCode.java
│   ├── ErrorType.java
│   ├── BusinessException.java
│   └── handler
│       ├── ErrorTypeHttpStatusMapper.java
│       └── GlobalExceptionHandler.java
└── infrastructure
    └── persistence
        └── BaseJpaEntity.java
```

common에 둘 수 있는 코드:

- 여러 도메인 모듈이 실제로 공유하는 Spring Security 설정과 현재 사용자 계약. 단, 인증 유스케이스 자신은 인증 도메인 모듈이 소유한다.
- OpenAPI·Swagger 설정
- QueryDSL 공통 설정
- Feign 공통 로깅, 타임아웃, 오류 디코더
- Spring과 HTTP에 독립적인 ErrorCode와 BusinessException 계약
- 전역 예외 처리와 공통 API 응답 Envelope
- JPA Auditing 기반 Base Entity

common에 두지 않는 코드:

- 토큰 발급·검증, JWT 상세 구현, Refresh Session, 로그인 규칙 등 인증 도메인 모듈이 소유할 코드
- 특정 외부 시스템의 Feign Client
- 특정 도메인 모듈의 Request와 Response
- Domain Repository 또는 QueryPort
- 비즈니스 Service
- 단지 두 곳에서 사용된다는 이유로 이동한 도메인 모델

# 12. 공통 API 응답

성공과 실패 응답을 하나의 `BaseResponse<T>`로 통일한다.

```java
public record BaseResponse<T>(
    boolean success,
    String code,
    String message,
    T data
) {

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(
            true,
            "SUCCESS",
            "요청에 성공했습니다.",
            data
        );
    }

    public static BaseResponse<Void> success() {
        return new BaseResponse<>(
            true,
            "SUCCESS",
            "요청에 성공했습니다.",
            null
        );
    }

    public static BaseResponse<Void> failure(
        ErrorCode errorCode
    ) {
        return new BaseResponse<>(
            false,
            errorCode.code(),
            errorCode.message(),
            null
        );
    }
}
```

파일 다운로드, 스트리밍, Webhook 응답 등 Envelope가 부적절한 API는 예외적으로 사용하지 않을 수 있다.

# 13. 예외 소유권과 전역 에러 경계

Domain과 application은 Spring·HTTP에 독립적인 공통 ErrorCode와 BusinessException 계약만 공유한다. 내부 오류 계약에는 HttpStatus를 직접 넣지 않는다.

```java
public interface ErrorCode {

    String code();

    String message();

    ErrorType type();
}
```

```java
public enum ErrorType {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    DEPENDENCY_FAILURE,
    DEPENDENCY_UNAVAILABLE,
    DEPENDENCY_TIMEOUT,
    INTERNAL
}
```

```java
public final class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

소유권은 실패의 의미를 기준으로 나눈다.

- domain ErrorCode: 비즈니스 불변식 위반과 잘못된 상태 전이
- application ErrorCode: 유스케이스 조정 실패와 외부 API·Redis·파일 저장소 등 의존성 실패
- infrastructure: 공급사 예외를 application ErrorCode 기반 BusinessException으로 변환하며 자체 예외를 안쪽으로 유출하지 않음
- common: ErrorCode, ErrorType, BusinessException처럼 프레임워크에 독립적인 최소 계약만 소유

처리 경계는 다음과 같다.

- `domain.error` 와 `application.error` 에는 예외 클래스가 아니라 해당 계층이 소유한 ErrorCode 구현만 둔다.
- 도메인별 또는 오류별 RuntimeException 클래스를 반복해서 만들지 않고 공통 BusinessException을 사용한다.
- MVC 경계의 단일 GlobalExceptionHandler가 BusinessException을 처리한다.
- Bean Validation 실패는 GlobalExceptionHandler가 공통 `INVALID_REQUEST` ErrorCode로 변환한다. 필드별 상세가 필요하면 `BaseResponse.data`에 담으며, domain·application 계층에 Validation 예외를 전파하지 않는다.
- ErrorTypeHttpStatusMapper가 바깥쪽 경계에서 ErrorType을 HttpStatus로 변환한다.
- 실패 응답은 공통 BaseResponse 형식을 사용한다.
- Spring Security 필터 체인의 인증·인가 실패는 MVC Handler에 도달하지 않으므로 AuthenticationEntryPoint와 AccessDeniedHandler가 처리한다.
- 보안 전용 Handler도 공통 ErrorCode와 응답 Writer를 사용해 동일한 실패 형식을 유지한다.
- Domain과 application은 GlobalExceptionHandler, HttpStatus, ResponseEntity를 알지 못한다.

# 14. 도메인 모듈 간 호출

다른 도메인 모듈의 infrastructure나 JPA Entity에 직접 접근하지 않는다.

```
잘못된 접근
domainB/application
→ domainA/infrastructure/persistence/entity

권장 접근
domainB/application
→ domainA/application/query/GetSummaryUseCase
```

- 즉시 결과가 필요한 동기 호출은 상대 도메인 모듈이 의도적으로 공개한 application UseCase를 직접 사용한다.
- 다른 도메인 모듈의 Repository 구현체, JPA Entity, Infrastructure에 접근하지 않는다.
- 다른 도메인 모듈의 Domain Model을 직접 수정하지 않는다.
- application 간 직접 의존성이 순환하면 호출 흐름을 재설계하고, 구체적 필요가 있을 때 호출자 소유 Port나 이벤트를 검토한다.
- 여러 도메인 모듈에 변경 사실을 알릴 필요가 있으면 이벤트를 검토한다.
- 처음부터 모든 도메인 모듈 호출에 Port나 이벤트를 만들지 않는다.
- 비동기 처리나 결합도 완화가 실제로 필요할 때 이벤트를 도입한다.

# 15. 테스트 구조

```
src/test/java/com.example
├── architecture
│   └── PackageDependencyTest.java
├── domainA
│   ├── domain
│   ├── application
│   ├── presentation
│   └── infrastructure
└── ...
```

- domain: Spring 없이 순수 단위 테스트
- application: Repository와 Port를 Mock 또는 Fake로 대체
- presentation: `@WebMvcTest`
- persistence: `@DataJpaTest`
- 패키지 의존성: 필요하면 ArchUnit으로 검증
- 외부 Adapter: Mock Server 또는 WireMock 기반 계약 테스트 검토

# 16. 생성하지 않아도 되는 패키지

디렉터리 구조를 맞추기 위해 빈 패키지를 억지로 만들지 않는다.

- 도메인 Policy가 없다면 `domain.policy` 생략
- Projection 조회가 없다면 QueryPort와 `infrastructure.persistence.query` 생략
- 외부 연동이 없다면 `application.port`와 외부 Adapter 생략
- application 계층이 소유할 조정·외부 의존성 오류가 없다면 `application.error` 생략
- Command에 입력이 없다면 Command 객체 생략
- 반환값이 없다면 Result 객체 생략
- 단순 조회에 QueryDSL을 강제하지 않음

# 17. 피해야 할 구조

- 최상위 `controller`, `service`, `repository` 중심의 수평 패키지
- 모든 코드를 모으는 거대한 `common` 또는 `util`
- 도메인 모델에 JPA와 HTTP 책임 혼합
- 다른 도메인 모듈의 Repository와 Entity 직접 참조
- 모든 UseCase를 한 Service에 몰아넣는 God Service
- 모든 UseCase마다 기계적으로 Service를 하나씩 만드는 과도한 보일러플레이트
- 단순 조회까지 무조건 QueryDSL 사용
- 복잡한 집계 조회를 위해 여러 Aggregate 전체를 메모리에 로딩
- Projection 조회 계약을 Domain Repository로 부르거나 domain.repository에 배치
- application Result가 QueryDSL 어노테이션에 의존
- 특정 외부 서비스 DTO나 Client 예외가 application이나 domain으로 유출
- 토큰 발급·검증, JWT 구현, Refresh Session 같은 인증 도메인 모듈 책임을 common으로 이동

# 18. 나중에 결정해도 되는 항목

다음 항목은 실제 요구사항이 생겼을 때 결정해도 패키지 뼈대에 큰 영향을 주지 않는다.

- 실제 Bounded Context와 Aggregate 경계
- 사용자 역할과 권한 모델
- 외부 시스템의 세부 API 계약
- 비동기 이벤트와 Outbox를 도입할 구체적인 기준
- Outbox와 메시지 브로커
- 캐시와 Redis
- QueryDSL을 적용할 실제 조회
- 멀티모듈 또는 마이크로서비스 전환

# 19. 최종 체크리스트

- [ ] 최상위 패키지가 도메인 기준으로 나뉘어 있는가?
- [ ] domain이 Spring, JPA, Feign, HTTP를 모르는가?
- [ ] Controller가 UseCase 인터페이스에 의존하는가?
- [ ] 비즈니스 상태 변경이 Aggregate behavior를 통과하는가?
- [ ] 순수 기술 상태를 위해 불필요한 Domain Model을 만들지 않았는가?
- [ ] Aggregate 조회에는 Domain Repository, Projection 조회에는 QueryPort를 사용했는가?
- [ ] JPA Entity와 도메인 모델의 변환 경계가 명확한가?
- [ ] 외부 시스템이 Port와 Adapter 뒤에 숨겨져 있는가?
- [ ] 다른 도메인 모듈의 infrastructure를 직접 참조하지 않는가?
- [ ] common에 비즈니스 코드가 쌓이고 있지 않은가?
- [ ] UseCase가 하나의 public operation만 표현하는가?
- [ ] Application Service가 같은 Aggregate, 트랜잭션 성격, 핵심 의존성을 공유하는 UseCase만 묶는가?
- [ ] Domain과 application 오류 계약에 Spring·HTTP 타입이 없는가?
- [ ] 인증 도메인 모듈 책임이 common으로 이동하지 않았는가?
