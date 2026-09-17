package com.npick.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * 모듈 경계와 계층 방향을 코드로 못 박는 문지기 (S15P21A501-196).
 *
 * <p>정본 {@code backend/docs/ddd-package-architecture.md}. 기존 위반이 있어 지금은 red 인 규칙은
 * {@link FreezingArchRule} 로 현재 상태를 베이스라인해 신규 위반만 실패시킨다. 기존 위반의 실제 해소는
 * S15P21A501-201(clip↔pipeline 순환·tag_evidence 소유권).
 */
@AnalyzeClasses(packages = "com.npick", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {

    /** domain 계층은 프레임워크를 모른다 (§도메인 순수성). */
    @ArchTest
    static final ArchRule domain_is_framework_free =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..", "jakarta.persistence..", "org.hibernate..")
                    .as("domain 계층은 Spring·JPA·Hibernate 에 의존하지 않는다");

    /** UseCase 는 인터페이스다 (§6). */
    @ArchTest
    static final ArchRule usecases_are_interfaces =
            classes()
                    .that()
                    .haveSimpleNameEndingWith("UseCase")
                    .should()
                    .beInterfaces()
                    .as("UseCase 는 인터페이스여야 한다");

    /** 구현 어댑터는 infrastructure 에 있다 (§구현 방향 역전). */
    @ArchTest
    static final ArchRule adapters_reside_in_infrastructure =
            classes()
                    .that()
                    .haveSimpleNameEndingWith("Adapter")
                    .should()
                    .resideInAPackage("..infrastructure..")
                    .as("*Adapter 는 infrastructure 계층에 있어야 한다");

    /** 인터페이스·enum·record 가 아닌 구체 클래스. */
    private static final DescribedPredicate<JavaClass> a_concrete_class =
            new DescribedPredicate<>("구체 클래스") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !javaClass.isInterface() && !javaClass.isEnum() && !javaClass.isRecord();
                }
            };

    /**
     * Controller 는 application 계층의 구체 클래스(구체 *Service·Query 구현 등)에 의존하지 않는다 — UseCase 인터페이스와
     * Command·Result record 만 사용한다 (§19). 네이밍이 아니라 타입 종류로 검사하므로 이름이 *Service 가 아닌 구체 클래스
     * 주입도 막는다. 인터페이스 추출 전 컨트롤러 8곳이 구체 Service 에 직접 의존했다.
     */
    @ArchTest
    static final ArchRule controllers_depend_only_on_application_abstractions =
            noClasses()
                    .that()
                    .haveSimpleNameEndingWith("Controller")
                    .should()
                    .dependOnClassesThat(resideInAPackage("..application..").and(a_concrete_class))
                    .as("Controller 는 application 구체 클래스가 아니라 UseCase 인터페이스·record 에 의존해야 한다 (§19)");

    /**
     * 모듈 간 순환 의존 금지 (§14). 현재 clip↔pipeline 순환 존재 → freeze 로 베이스라인, 신규 순환만 실패.
     * 실제 해소는 S15P21A501-201.
     */
    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            FreezingArchRule.freeze(
                    slices()
                            .matching("com.npick.(*)..")
                            .should()
                            .beFreeOfCycles()
                            .as("no cyclic dependency between com.npick modules"));

    /**
     * 다른 모듈의 infrastructure(Entity 등)를 직접 참조하지 않는다 — infrastructure 는 모듈 사적이다 (§14/§17).
     * 현재 tag→clip.infrastructure 참조 존재 → freeze 로 베이스라인. 실제 해소는 S15P21A501-201.
     */
    @ArchTest
    static final ArchRule tag_does_not_touch_clip_infrastructure =
            FreezingArchRule.freeze(
                    noClasses()
                            .that()
                            .resideInAPackage("com.npick.tag..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage("com.npick.clip.infrastructure..")
                            .as("tag must not depend on clip infrastructure (module-private)"));
}
