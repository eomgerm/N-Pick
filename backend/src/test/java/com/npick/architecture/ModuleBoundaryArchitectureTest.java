package com.npick.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 모듈 경계와 계층 방향을 코드로 못 박는 문지기 (S15P21A501-196).
 *
 * <p>정본 {@code backend/docs/ddd-package-architecture.md}. 기존 위반이 있어 지금은 red 인 규칙은 {@link FreezingArchRule} 로 현재 상태를
 * 베이스라인해 신규 위반만 실패시킨다. 위반이 0 이 된 규칙은 freeze 를 벗겨 하드 규칙으로 올린다 — 모듈 순환과 module-private 두 건을 S15P21A501-201 에서 올렸다.
 */
@AnalyzeClasses(packages = "com.npick", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {

    private static final String ROOT = "com.npick.";

    /** {@code com.npick.<module>.…} 패키지에서 {@code <module>} 최상위 세그먼트. 루트 직속·외부는 null. */
    private static String moduleOf(String packageName) {
        if (packageName == null || !packageName.startsWith(ROOT)) {
            return null;
        }
        String rest = packageName.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** domain 계층은 프레임워크를 모른다 (§도메인 순수성). */
    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .as("domain 계층은 Spring·JPA·Hibernate 에 의존하지 않는다");

    /** UseCase 는 인터페이스다 (§6). */
    @ArchTest
    static final ArchRule usecases_are_interfaces = classes()
            .that()
            .haveSimpleNameEndingWith("UseCase")
            .should()
            .beInterfaces()
            .as("UseCase 는 인터페이스여야 한다");

    /** 구현 어댑터는 infrastructure 에 있다 (§구현 방향 역전). */
    @ArchTest
    static final ArchRule adapters_reside_in_infrastructure = classes()
            .that()
            .haveSimpleNameEndingWith("Adapter")
            .should()
            .resideInAPackage("..infrastructure..")
            .as("*Adapter 는 infrastructure 계층에 있어야 한다");

    /**
     * 계층 방향: application·domain 은 infrastructure 구현에 의존하지 않는다 (§구현 방향 역전, §도메인 순수성). {@code *Adapter} 위치 검사만으로는 잡지 못하던
     * 방향 위반을 막는다 — 예: application Service 가 infrastructure 의 Mapper 를 직접 주입하는 경우. 현재 위반(search.application →
     * search.infrastructure Mapper 등) 존재 → freeze 로 베이스라인, 신규 방향 위반만 실패.
     */
    @ArchTest
    static final ArchRule application_and_domain_do_not_depend_on_infrastructure = FreezingArchRule.freeze(noClasses()
            .that()
            .resideInAnyPackage("..application..", "..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .as("application·domain 은 infrastructure 구현에 의존하지 않는다 (계층 방향)"));

    /** 인터페이스·enum·record 가 아닌 구체 클래스. */
    private static final DescribedPredicate<JavaClass> a_concrete_class = new DescribedPredicate<>("구체 클래스") {
        @Override
        public boolean test(JavaClass javaClass) {
            return !javaClass.isInterface() && !javaClass.isEnum() && !javaClass.isRecord();
        }
    };

    /**
     * Controller 는 application 계층의 구체 클래스(구체 *Service·Query 구현 등)에 의존하지 않는다 — UseCase 인터페이스와 Command·Result record 만
     * 사용한다 (§19). 네이밍이 아니라 타입 종류로 검사하므로 이름이 *Service 가 아닌 구체 클래스 주입도 막는다.
     */
    @ArchTest
    static final ArchRule controllers_do_not_depend_on_application_concretes = noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat(resideInAPackage("..application..").and(a_concrete_class))
            .as("Controller 는 application 구체 클래스가 아니라 UseCase 인터페이스·record 에 의존해야 한다 (§19)");

    /** UseCase 인터페이스. */
    private static final DescribedPredicate<JavaClass> a_usecase_interface = new DescribedPredicate<>("UseCase 인터페이스") {
        @Override
        public boolean test(JavaClass javaClass) {
            return javaClass.isInterface() && javaClass.getSimpleName().endsWith("UseCase");
        }
    };

    /**
     * Controller 가 <b>주입</b>하는 application 의존(필드=생성자 주입)은 UseCase 인터페이스여야 한다 (§19). 구체 클래스
     * 금지({@link #controllers_do_not_depend_on_application_concretes})만으로는 QueryPort·Repository 같은 UseCase 가 아닌
     * application 인터페이스 주입을 못 잡으므로, 주입 지점을 타입으로 좁혀 "Controller → UseCase" 계약을 강제한다. 반환·매개변수로 오가는 Command·Result 값 타입은
     * 필드가 아니라 자연히 제외된다.
     */
    @ArchTest
    static final ArchRule controller_injected_application_deps_are_usecases = fields().that()
            .areDeclaredInClassesThat()
            .haveSimpleNameEndingWith("Controller")
            .and()
            .haveRawType(resideInAPackage("..application.."))
            .should()
            .haveRawType(a_usecase_interface)
            .as("Controller 가 주입하는 application 의존은 UseCase 인터페이스여야 한다 (§19)");

    /** 모듈 간 순환 의존 금지 (§14). clip↔pipeline 순환을 S15P21A501-201 에서 끊어 하드 규칙으로 올렸다 — 이제 순환이 하나라도 생기면 실패한다. */
    @ArchTest
    static final ArchRule modules_are_free_of_cycles = slices().matching("com.npick.(*)..")
            .should()
            .beFreeOfCycles()
            .as("no cyclic dependency between com.npick modules");

    /** 다른 모듈의 infrastructure(Entity 등)에 의존하지 않는다 — infrastructure 는 모듈 사적이다. common 은 공용이라 예외. */
    private static final ArchCondition<JavaClass> not_depend_on_other_module_infrastructure =
            new ArchCondition<>("다른 모듈의 infrastructure 에 의존하지 않아야") {
                @Override
                public void check(JavaClass origin, ConditionEvents events) {
                    String ownerModule = moduleOf(origin.getPackageName());
                    if (ownerModule == null) {
                        return;
                    }
                    for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                        String targetPackage = dependency.getTargetClass().getPackageName();
                        String targetModule = moduleOf(targetPackage);
                        if (targetModule == null || targetModule.equals(ownerModule) || "common".equals(targetModule)) {
                            continue;
                        }
                        if (targetPackage.contains(".infrastructure")) {
                            events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                        }
                    }
                }
            };

    /**
     * 어떤 모듈도 다른 모듈의 infrastructure 를 직접 참조하지 않는다 — infrastructure 는 모듈 사적이다 (§14/§17, §19 체크리스트). 최상위 모듈 소유자를 비교하는 일반
     * 규칙이라 tag→clip 한 쌍만이 아니라 모든 모듈 쌍을 덮는다. common(공용 계약)은 예외. tag→clip Entity 참조를 S15P21A501-201 에서 끊어 하드 규칙으로 올렸다.
     */
    @ArchTest
    static final ArchRule modules_do_not_touch_other_module_infrastructure = classes()
            .that()
            .resideInAPackage("com.npick..")
            .should(not_depend_on_other_module_infrastructure)
            .as("모듈은 다른 모듈의 infrastructure 에 의존하지 않는다 (module-private)");
}
