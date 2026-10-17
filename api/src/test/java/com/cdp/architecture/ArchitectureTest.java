package com.cdp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * 換路保障的強制規則（docs/spec/04-node-contract.md §9 規則 1~6）＋ RabbitMQ 紅線 2（INIT-01「RabbitMQ」）＋ 時間只能來自
 * Clock（README「時間」）。make check 必跑；只有 architect 可改本檔（DESIGN-01「檔案分工」）。
 */
@AnalyzeClasses(packages = "com.cdp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String NODE_PACKAGE = "com.cdp.engine.node..";
    private static final String[] PURE_TYPES = {
        "com.cdp.engine.node.NodeContext",
        "com.cdp.engine.node.NodeResult",
        "com.cdp.engine.model.EnrollmentState"
    };

    /** 規則 1：節點無狀態。 */
    @ArchTest
    static final ArchRule nodesHaveNoFields =
            classes()
                    .that()
                    .implement("com.cdp.engine.node.JourneyNode")
                    .should(haveNoFields())
                    .because("節點必須是純函式、可共用單一實例（04 §9 規則 1）");

    /** 規則 2：節點不依賴 Repository／JDBC／Service。 */
    @ArchTest
    static final ArchRule nodesDoNotDependOnPersistence =
            noClasses()
                    .that()
                    .implement("com.cdp.engine.node.JourneyNode")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..repository..",
                            "..persistence..",
                            "..jdbc..",
                            "javax.sql..",
                            "..service..",
                            "org.springframework.jdbc..",
                            "org.springframework.amqp..")
                    .orShould()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("Store")
                    .orShould()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("Repository")
                    .because("節點收不到任何 Repository、Service、DataSource（README 裁決 4）");

    /** 規則 3：NodeContext、NodeResult、EnrollmentState 只含純資料型別。 */
    @ArchTest
    static final ArchRule pureDataFields =
            fields().that()
                    .areDeclaredInClassesThat()
                    .haveFullyQualifiedName(PURE_TYPES[0])
                    .or()
                    .areDeclaredInClassesThat()
                    .haveFullyQualifiedName(PURE_TYPES[1])
                    .or()
                    .areDeclaredInClassesThat()
                    .haveFullyQualifiedName(PURE_TYPES[2])
                    .should(haveDataOnlyType())
                    .because("限 record／enum／java.time／集合／基本型別（04 §9 規則 3）");

    /** 規則 4：JourneyRuntime 簽章不出現 persistence 型別。 */
    @ArchTest
    static final ArchRule runtimeSignatureIsPersistenceFree =
            methods()
                    .that()
                    .areDeclaredIn(com.cdp.engine.JourneyRuntime.class)
                    .should(notUsePersistenceTypes())
                    .because("裁決 2 的落地檢查點（04 §7）");

    /** 02 §10：契約層不出現 next_wake_at。 */
    @ArchTest
    static final ArchRule noNextWakeAtInContract =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAnyPackage("com.cdp.engine.model..", NODE_PACKAGE)
                    .should()
                    .haveNameMatching("(?i).*next_?wake.*")
                    .because("next_wake_at 只存在實作層（02 §1 鐵則）");

    /** 規則 5（擴大到全系統）：時間只能來自注入的 Clock。 */
    @ArchTest
    static final ArchRule noSystemTime =
            noClasses()
                    .should()
                    .callMethod(Instant.class, "now")
                    .orShould()
                    .callMethod(LocalDate.class, "now")
                    .orShould()
                    .callMethod(LocalDateTime.class, "now")
                    .orShould()
                    .callMethod(OffsetDateTime.class, "now")
                    .orShould()
                    .callMethod(ZonedDateTime.class, "now")
                    .orShould()
                    .callMethod(System.class, "currentTimeMillis")
                    .because("禁止直接取時間，一律注入 java.time.Clock（README「時間」）");

    /** 規則 6：節點不認識通道。 */
    @ArchTest
    static final ArchRule nodesDoNotKnowChannels =
            noClasses()
                    .that()
                    .resideInAPackage(NODE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..channel..", "..delivery..")
                    .because("節點只回傳 Effect（04 §9 規則 6）");

    /** 紅線 2：入列經 outbox，只有 relay 可碰 RabbitTemplate。 */
    @ArchTest
    static final ArchRule onlyRelayPublishes =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.cdp.delivery.relay..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.amqp.rabbit.core.RabbitTemplate")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.amqp.rabbit.core.RabbitOperations")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.amqp.core.AmqpTemplate")
                    .because("背景任務一律先寫 outbox，由 relay 推送（INIT-01 紅線 2）");

    private static ArchCondition<JavaClass> haveNoFields() {
        return new ArchCondition<>("have no fields") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaField field : item.getFields()) {
                    events.add(SimpleConditionEvent.violated(item, field.getFullName() + " 是欄位"));
                }
            }
        };
    }

    private static ArchCondition<JavaField> haveDataOnlyType() {
        return new ArchCondition<>("have a data-only type") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (!isDataOnly(field.getRawType())) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    field,
                                    field.getFullName() + " 的型別 " + field.getRawType().getName() + " 不是純資料"));
                }
            }
        };
    }

    /** record、enum、java.time、集合、基本型別與其包裝、String，或 permitted 全為 record 的 sealed interface。 */
    static boolean isDataOnly(JavaClass type) {
        if (type.isPrimitive() || type.isEnum() || type.isRecord()) {
            return true;
        }
        String name = type.getName();
        if (name.startsWith("java.time.") || name.startsWith("java.lang.")) {
            return true;
        }
        if (type.isAssignableTo(Collection.class)
                || type.isAssignableTo(Map.class)
                || type.isAssignableTo(Optional.class)) {
            return true;
        }
        Class<?> reflected = type.reflect();
        return reflected.isInterface()
                && reflected.isSealed()
                && Stream.of(reflected.getPermittedSubclasses()).allMatch(Class::isRecord);
    }

    private static ArchCondition<JavaMethod> notUsePersistenceTypes() {
        return new ArchCondition<>("not use persistence types") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Stream.concat(
                                Stream.of(method.getRawReturnType()),
                                method.getRawParameterTypes().stream())
                        .filter(t -> t.getPackageName().contains(".persistence"))
                        .forEach(
                                t ->
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        method, method.getFullName() + " 使用 " + t.getName())));
            }
        };
    }
}
