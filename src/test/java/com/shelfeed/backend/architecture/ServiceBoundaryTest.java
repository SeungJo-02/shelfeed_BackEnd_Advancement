package com.shelfeed.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MSA 분리를 위한 서비스 경계 규칙.
 *
 * <p>도메인 패키지를 장차 분리될 서비스 단위로 묶어두고, 그 경계를 넘는 JPA 연관관계를 금지한다.
 * 서비스가 물리적으로 분리되면 다른 서비스의 엔티티는 다른 프로세스·다른 DB에 있으므로 객체 참조가
 * 불가능해진다. 미리 ID 참조로 바꿔두어야 분리 시점에 조인이 무너지지 않는다.
 *
 * <p>이 테스트가 실패하며 남기는 위반 목록이 곧 남은 작업 목록이다.
 */
@AnalyzeClasses(
        packages = "com.shelfeed.backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ServiceBoundaryTest {

    /**
     * 도메인 패키지 → 분리 대상 서비스.
     *
     * <p>같은 서비스로 묶인 도메인끼리는 서로 참조해도 된다. 분리 후에도 같은 프로세스에 남기 때문이다.
     */
    private static final Map<String, String> DOMAIN_TO_SERVICE = Map.ofEntries(
            // user-service — 신원과 소셜 그래프
            Map.entry("member", "user"),
            Map.entry("auth", "user"),
            Map.entry("follow", "user"),
            Map.entry("block", "user"),
            Map.entry("genre", "user"),

            // review-service — 컨텐츠 작성과 상호작용
            Map.entry("review", "review"),
            Map.entry("comment", "review"),
            Map.entry("tag", "review"),
            Map.entry("library", "review"),
            Map.entry("report", "review"),

            // catalog-service — 도서 메타데이터·검색
            Map.entry("book", "catalog"),
            Map.entry("search", "catalog"),
            Map.entry("ocr", "catalog"),

            // 이벤트 소비자 — Kafka 도입 시 read model / 팬아웃 담당
            Map.entry("feed", "feed"),
            Map.entry("notification", "notification"),

            Map.entry("admin", "admin"));

    /**
     * 아직 끊지 못한 참조 (Tier 3).
     *
     * <p>Review/Feed는 Kafka 기반 read model로 재작성될 예정이라 그 시점에 함께 정리한다.
     * 목록에 없는 새 위반은 즉시 실패하므로, 경계가 더 나빠지는 것은 막힌다.
     * 하나씩 끊을 때마다 이 목록에서 지우면 된다 — 비면 0단계 완료.
     */
    private static final Set<String> 남은_위반 = Set.of(
            "Review.member",
            "Review.book",
            "Feed.member",
            "Feed.review");

    @ArchTest
    static final ArchRule 엔티티는_다른_서비스의_엔티티를_참조하지_않는다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should(new NoCrossServiceEntityReference())
            .because("서비스 분리 후에는 다른 서비스의 엔티티를 객체로 참조할 수 없다. ID 참조로 바꿀 것");

    /**
     * 예외 목록이 낡지 않게 지킨다. 참조를 끊고도 목록에서 지우지 않으면 여기서 실패한다.
     * (안 그러면 목록이 계속 남아 다시 생긴 위반을 눈감아 준다)
     */
    @ArchTest
    static void 예외_목록에_이미_해결된_항목이_없다(JavaClasses classes) {
        Set<String> 실제_위반 = new HashSet<>();
        for (JavaClass entity : classes) {
            if (!entity.isAnnotatedWith(Entity.class)) continue;

            String ownerService = serviceOf(entity);
            if (ownerService == null) continue;

            for (JavaField field : entity.getFields()) {
                JavaClass fieldType = field.getRawType();
                if (!fieldType.isAnnotatedWith(Entity.class)) continue;

                String targetService = serviceOf(fieldType);
                if (targetService == null || targetService.equals(ownerService)) continue;

                실제_위반.add(entity.getSimpleName() + "." + field.getName());
            }
        }

        Set<String> 이미_해결됨 = new HashSet<>(남은_위반);
        이미_해결됨.removeAll(실제_위반);

        assertThat(이미_해결됨)
                .as("이미 끊은 참조가 예외 목록에 남아 있다 — ServiceBoundaryTest의 남은_위반에서 지울 것")
                .isEmpty();
    }

    private static class NoCrossServiceEntityReference extends ArchCondition<JavaClass> {

        NoCrossServiceEntityReference() {
            super("서비스 경계를 넘는 엔티티 참조를 갖지 않는다");
        }

        @Override
        public void check(JavaClass entity, ConditionEvents events) {
            String ownerService = serviceOf(entity);
            if (ownerService == null) {
                return;
            }

            for (JavaField field : entity.getFields()) {
                JavaClass fieldType = field.getRawType();
                if (!fieldType.isAnnotatedWith(Entity.class)) {
                    continue;
                }

                String targetService = serviceOf(fieldType);
                if (targetService == null || targetService.equals(ownerService)) {
                    continue;
                }

                if (남은_위반.contains(entity.getSimpleName() + "." + field.getName())) {
                    continue;
                }

                events.add(SimpleConditionEvent.violated(
                        field,
                        String.format(
                                "%s.%s 가 %s(%s-service)를 참조한다 — %s-service 경계를 넘음",
                                entity.getSimpleName(),
                                field.getName(),
                                fieldType.getSimpleName(),
                                targetService,
                                ownerService)));
            }
        }
    }

    /** 패키지 경로에서 도메인 이름을 뽑아 소속 서비스를 찾는다. 도메인 패키지 밖이면 null. */
    private static String serviceOf(JavaClass javaClass) {
        String marker = ".domain.";
        String packageName = javaClass.getPackageName();

        int start = packageName.indexOf(marker);
        if (start < 0) {
            return null;
        }

        String remainder = packageName.substring(start + marker.length());
        int end = remainder.indexOf('.');
        String domain = (end < 0) ? remainder : remainder.substring(0, end);

        return DOMAIN_TO_SERVICE.get(domain);
    }
}
