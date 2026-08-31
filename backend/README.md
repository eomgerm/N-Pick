# N-Pick Backend

Spring Boot 기반 N-Pick API 서버.

## 요구 사항

- **JDK 21** (필수). `java -version` 으로 확인.
- Gradle 은 wrapper(`./gradlew`)를 쓰므로 별도 설치 불필요.

## 실행

```bash
cd backend
./gradlew bootRun            # 기본 프로파일: local
```

Windows PowerShell 에서는 `.\gradlew.bat bootRun`.

기동 확인:

```bash
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

## 빌드 / 테스트

```bash
./gradlew build              # 컴파일 + 테스트 + jar
./gradlew test
```

## 프로파일

| 프로파일 | 용도 | 비고 |
| --- | --- | --- |
| `local` | 로컬 개발 (기본값) | `com.npick` DEBUG 로그, health 상세 노출 |
| `prod` | 배포 | root WARN 로그, health 상세 비노출 |

프로파일 지정:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
java -jar build/libs/npick-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## 환경 변수

민감값은 하드코딩하지 않고 환경 변수로만 주입한다. `.env` 류 파일은 커밋 금지(`.gitignore` 처리됨).

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 포트 충돌 시 오버라이드 |

```bash
SERVER_PORT=8081 ./gradlew bootRun
```

## 패키지 구조

```
com.npick
├── api      # 컨트롤러, 요청/응답 DTO
├── domain   # 도메인별 서비스 · 엔티티 · 리포지토리
└── global   # 공통 설정, 예외 처리, 유틸
```

DB 연동 및 JPA 설정은 이번 범위 밖이며 별도 일감에서 진행한다.
