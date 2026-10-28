package com.sqlteacher.domain.ermodel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.11.0 ERM-2: deterministic E-R model validation. Every cap boundary, the cardinality
 * whitelist, identifying/weak pairing in both directions, referential integrity, naming rules,
 * the hierarchy tree constraint, and control-character rejection are pinned here; the validator
 * must aggregate all errors instead of stopping at the first one.
 */
class ErModelValidatorTest {

    // ---- builders -------------------------------------------------------

    private static ErAttribute attr(String name) {
        return new ErAttribute(name, false);
    }

    private static ErEntity entity(String id, String name, ErAttribute... attributes) {
        return new ErEntity(id, name, false, List.of(attributes));
    }

    private static ErEntity weakEntity(String id, String name, ErAttribute... attributes) {
        return new ErEntity(id, name, true, List.of(attributes));
    }

    private static ErRelationshipMember member(String entity, String cardinality) {
        return new ErRelationshipMember(entity, cardinality, false);
    }

    private static ErRelationship rel(String id, String name, boolean identifying,
                                      List<ErRelationshipMember> members, ErAttribute... attributes) {
        return new ErRelationship(id, name, identifying, members, List.of(attributes));
    }

    private static ErHierarchy hierarchy(String id, String name, String superclass, List<String> subclasses) {
        return new ErHierarchy(id, name, superclass, subclasses);
    }

    /** Full-notation sample: composite/multivalued/derived/key attributes, weak entity,
     * identifying relationship, relationship attribute, and an ISA hierarchy. */
    private static ErModel fullNotationModel() {
        return new ErModel(
            "学生选课系统",
            "学生选修课程并记录成绩，开课依赖课程，学生细分为本科生与研究生。",
            List.of(
                entity("student", "学生",
                    new ErAttribute("学号", true),
                    new ErAttribute("住址", false, List.of("省", "市"), false, false),
                    new ErAttribute("电话", false, List.of(), true, false),
                    new ErAttribute("年龄", false, List.of(), false, true)),
                entity("course", "课程",
                    new ErAttribute("课程号", true),
                    attr("课程名")),
                weakEntity("offering", "开课",
                    new ErAttribute("开课号", true)),
                entity("undergraduate", "本科生"),
                entity("postgraduate", "研究生")
            ),
            List.of(
                rel("elective", "选修", false,
                    List.of(member("student", "m"), member("course", "n")),
                    new ErAttribute("成绩", false)),
                rel("arrange", "开设", true,
                    List.of(new ErRelationshipMember("course", "1", true),
                        new ErRelationshipMember("offering", "n", true)))
            ),
            List.of(hierarchy("h_student", "ISA", "student",
                List.of("undergraduate", "postgraduate")))
        );
    }

    private static List<ErEntity> plainEntities(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> entity("e" + index, "实体" + index))
            .toList();
    }

    private static List<ErRelationship> plainRelationships(int count, String firstId, String secondId) {
        return IntStream.range(0, count)
            .mapToObj(index -> rel("r" + index, "联系" + index, false,
                List.of(member(firstId, "1"), member(secondId, "n"))))
            .toList();
    }

    private static List<ErHierarchy> hierarchies(int count, String superclass) {
        return IntStream.range(0, count)
            .mapToObj(index -> hierarchy("h" + index, "ISA" + index, superclass,
                List.of("sub" + index)))
            .toList();
    }

    // ---- valid model ----------------------------------------------------

    @Test
    void fullNotationModelPassesWithNoErrors() {
        assertTrue(ErModelValidator.validate(fullNotationModel()).isEmpty(),
            ErModelValidator.validate(fullNotationModel()).toString());
    }

    @Test
    void nullModelReportsOneError() {
        assertEquals(1, ErModelValidator.validate(null).size());
    }

    // ---- caps -----------------------------------------------------------

    @Test
    void entityCountAtCapPassesAndBeyondCapFails() {
        List<String> atCap = ErModelValidator.validate(new ErModel("t", "e",
            plainEntities(ErModelValidator.MAX_ENTITIES), List.of(), List.of()));
        assertTrue(atCap.isEmpty(), atCap.toString());

        List<String> beyondCap = ErModelValidator.validate(new ErModel("t", "e",
            plainEntities(ErModelValidator.MAX_ENTITIES + 1), List.of(), List.of()));
        assertTrue(beyondCap.stream().anyMatch(error -> error.contains("实体数量超过上限 16")), beyondCap.toString());
    }

    @Test
    void attributesPerEntityAtCapPassesAndBeyondCapFails() {
        ErAttribute[] twelve = IntStream.range(0, 12).mapToObj(i -> attr("属性" + i)).toArray(ErAttribute[]::new);
        assertTrue(ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲", twelve)), List.of(), List.of())).isEmpty());

        ErAttribute[] thirteen = IntStream.range(0, 13).mapToObj(i -> attr("属性" + i)).toArray(ErAttribute[]::new);
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲", thirteen)), List.of(), List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("属性数量超过上限 12")), errors.toString());
    }

    @Test
    void compositeChildrenAtCapPassesAndBeyondCapFails() {
        List<String> four = List.of("省", "市", "区", "街道");
        assertTrue(ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲", new ErAttribute("住址", false, four, false, false))),
            List.of(), List.of())).isEmpty());

        List<String> five = List.of("省", "市", "区", "街道", "门牌");
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲", new ErAttribute("住址", false, five, false, false))),
            List.of(), List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("复合子属性数量超过上限 4")), errors.toString());
    }

    @Test
    void relationshipCountAtCapPassesAndBeyondCapFails() {
        List<ErEntity> two = List.of(entity("a", "甲"), entity("b", "乙"));
        assertTrue(ErModelValidator.validate(new ErModel("t", "e", two,
            plainRelationships(ErModelValidator.MAX_RELATIONSHIPS, "a", "b"), List.of())).isEmpty());

        List<String> errors = ErModelValidator.validate(new ErModel("t", "e", two,
            plainRelationships(ErModelValidator.MAX_RELATIONSHIPS + 1, "a", "b"), List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("联系数量超过上限 24")), errors.toString());
    }

    @Test
    void relationshipAttributeCountAtCapPassesAndBeyondCapFails() {
        ErAttribute[] six = IntStream.range(0, 6).mapToObj(i -> new ErAttribute("联系属性" + i, false))
            .toArray(ErAttribute[]::new);
        assertTrue(ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲"), entity("b", "乙")),
            List.of(rel("r", "供应", false, List.of(member("a", "1"), member("b", "n")), six)),
            List.of())).isEmpty());

        ErAttribute[] seven = IntStream.range(0, 7).mapToObj(i -> new ErAttribute("联系属性" + i, false))
            .toArray(ErAttribute[]::new);
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲"), entity("b", "乙")),
            List.of(rel("r", "供应", false, List.of(member("a", "1"), member("b", "n")), seven)),
            List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("联系 r 的属性数量超过上限 6")), errors.toString());
    }

    @Test
    void hierarchyCountAtCapPassesAndBeyondCapFails() {
        List<ErEntity> entities = new java.util.ArrayList<>();
        entities.add(entity("base", "基类"));
        for (int index = 0; index < 8; index++) {
            entities.add(entity("sub" + index, "子类" + index));
        }
        assertTrue(ErModelValidator.validate(new ErModel("t", "e", List.copyOf(entities),
            List.of(), hierarchies(ErModelValidator.MAX_HIERARCHIES, "base"))).isEmpty());

        entities.add(entity("sub8", "子类8"));
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e", List.copyOf(entities),
            List.of(), hierarchies(ErModelValidator.MAX_HIERARCHIES + 1, "base")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("层级数量超过上限 8")), errors.toString());
    }

    @Test
    void subclassCountAtCapPassesAndBeyondCapFails() {
        List<String> sixSubclasses = IntStream.range(0, 6).mapToObj(i -> "sub" + i).toList();
        List<ErEntity> withSix = new java.util.ArrayList<>();
        withSix.add(entity("base", "基类"));
        for (int index = 0; index < 6; index++) {
            withSix.add(entity("sub" + index, "子类" + index));
        }
        assertTrue(ErModelValidator.validate(new ErModel("t", "e", List.copyOf(withSix),
            List.of(), List.of(hierarchy("h", "ISA", "base", sixSubclasses)))).isEmpty());

        List<String> sevenSubclasses = IntStream.range(0, 7).mapToObj(i -> "sub" + i).toList();
        List<ErEntity> withSeven = new java.util.ArrayList<>(withSix);
        withSeven.add(entity("sub6", "子类6"));
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e", List.copyOf(withSeven),
            List.of(), List.of(hierarchy("h", "ISA", "base", sevenSubclasses))));
        assertTrue(errors.stream().anyMatch(error -> error.contains("子类数量超过上限 6")), errors.toString());
    }

    // ---- members and cardinality -----------------------------------------

    @Test
    void memberCountMustStayBetweenTwoAndFour() {
        List<ErEntity> four = List.of(entity("a", "甲"), entity("b", "乙"),
            entity("c", "丙"), entity("d", "丁"));

        assertTrue(ErModelValidator.validate(new ErModel("t", "e", four,
            List.of(rel("r2", "二元", false, List.of(member("a", "n"), member("b", "m")))),
            List.of())).isEmpty());
        assertTrue(ErModelValidator.validate(new ErModel("t", "e", four,
            List.of(rel("r4", "四元", false,
                List.of(member("a", "n"), member("b", "1"), member("c", "n"), member("d", "m")))),
            List.of())).isEmpty());

        List<String> oneMember = ErModelValidator.validate(new ErModel("t", "e", four,
            List.of(rel("r1", "一元", false, List.of(member("a", "n")))), List.of()));
        assertTrue(oneMember.stream().anyMatch(error -> error.contains("成员数量必须在 2 到 4 之间")),
            oneMember.toString());

        List<String> fiveMembers = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲"), entity("b", "乙"), entity("c", "丙"),
                entity("d", "丁"), entity("f", "戊")),
            List.of(rel("r5", "五元", false,
                List.of(member("a", "n"), member("b", "n"), member("c", "n"), member("d", "n"),
                    member("f", "n")))),
            List.of()));
        assertTrue(fiveMembers.stream().anyMatch(error -> error.contains("成员数量必须在 2 到 4 之间")),
            fiveMembers.toString());
    }

    @Test
    void cardinalityOutsideTheLowercaseWhitelistIsRejected() {
        List<ErEntity> two = List.of(entity("a", "甲"), entity("b", "乙"));
        for (String bad : new String[] {"N", "M", "many", "one", ""}) {
            List<String> errors = ErModelValidator.validate(new ErModel("t", "e", two,
                List.of(rel("r", "供应", false, List.of(member("a", bad), member("b", "n")))),
                List.of()));
            assertTrue(errors.stream().anyMatch(error -> error.contains("成员基数必须是 1、n、m 之一")),
                bad + " -> " + errors);
        }
    }

    // ---- identifying / weak pairing (both directions) ---------------------

    @Test
    void identifyingRelationshipNeedsExactlyTwoMembersOneWeakAndOwnerCardinalityOne() {
        List<ErEntity> weakAndTwoStrong = List.of(
            weakEntity("w", "家属"), entity("a", "甲"), entity("b", "乙"));

        List<String> threeMembers = ErModelValidator.validate(new ErModel("t", "e", weakAndTwoStrong,
            List.of(rel("r", "拥有", true,
                List.of(member("a", "1"), member("w", "n"), member("b", "n")))),
            List.of()));
        assertTrue(threeMembers.stream().anyMatch(error -> error.contains("必须恰好有 2 个成员")),
            threeMembers.toString());

        List<String> noWeak = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲"), entity("b", "乙")),
            List.of(rel("r", "拥有", true, List.of(member("a", "1"), member("b", "n")))),
            List.of()));
        assertTrue(noWeak.stream().anyMatch(error -> error.contains("必须包含一个弱实体成员")), noWeak.toString());

        List<String> ownerNotOne = ErModelValidator.validate(new ErModel("t", "e",
            List.of(weakEntity("w", "家属"), entity("a", "甲")),
            List.of(rel("r", "拥有", true, List.of(member("a", "n"), member("w", "n")))),
            List.of()));
        assertTrue(ownerNotOne.stream().anyMatch(error -> error.contains("拥有者成员基数必须为 1")),
            ownerNotOne.toString());

        List<String> valid = ErModelValidator.validate(new ErModel("t", "e",
            List.of(weakEntity("w", "家属"), entity("a", "甲")),
            List.of(rel("r", "拥有", true, List.of(member("a", "1"), member("w", "n")))),
            List.of()));
        assertTrue(valid.isEmpty(), valid.toString());
    }

    @Test
    void weakEntityMustParticipateInAnIdentifyingRelationship() {
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(weakEntity("w", "家属"), entity("a", "甲")),
            List.of(rel("r", "拥有", false, List.of(member("a", "1"), member("w", "n")))),
            List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("弱实体 w 未参与任何依赖联系")),
            errors.toString());
    }

    // ---- referential integrity -------------------------------------------

    @Test
    void referencesToUndeclaredEntitiesAreReported() {
        List<ErEntity> two = List.of(entity("a", "甲"), entity("b", "乙"));
        List<ErModel> models = List.of(
            new ErModel("t", "e", two,
                List.of(rel("r", "供应", false, List.of(member("ghost", "n"), member("b", "n")))),
                List.of()),
            new ErModel("t", "e", two, List.of(),
                List.of(hierarchy("h", "ISA", "ghost", List.of("a")))),
            new ErModel("t", "e", two, List.of(),
                List.of(hierarchy("h", "ISA", "a", List.of("ghost"))))
        );
        for (ErModel model : models) {
            List<String> errors = ErModelValidator.validate(model);
            assertTrue(errors.stream().anyMatch(error -> error.contains("引用了未声明的实体")),
                errors.toString());
        }
    }

    // ---- ids and names ----------------------------------------------------

    @Test
    void idsMustMatchTheFormatAndBeGloballyUnique() {
        List<ErEntity> entities = List.of(entity("Student", "甲"));
        List<String> uppercase = ErModelValidator.validate(new ErModel("t", "e", entities, List.of(), List.of()));
        assertTrue(uppercase.stream().anyMatch(error -> error.contains("[a-z0-9_]{1,40}")), uppercase.toString());

        List<String> tooLong = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a".repeat(41), "甲")), List.of(), List.of()));
        assertTrue(tooLong.stream().anyMatch(error -> error.contains("[a-z0-9_]{1,40}")), tooLong.toString());

        List<String> emptyId = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("", "甲")), List.of(), List.of()));
        assertTrue(emptyId.stream().anyMatch(error -> error.contains("id 必须匹配")), emptyId.toString());

        List<String> shared = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("dup", "甲"), entity("b", "乙")),
            List.of(rel("dup", "供应", false, List.of(member("dup", "1"), member("b", "n")))),
            List.of()));
        assertTrue(shared.stream().anyMatch(error -> error.contains("id 重复")), shared.toString());

        List<String> sharedWithHierarchy = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("dup", "甲"), entity("sub", "乙")),
            List.of(),
            List.of(hierarchy("dup", "ISA", "dup", List.of("sub")))));
        assertTrue(sharedWithHierarchy.stream().anyMatch(error -> error.contains("id 重复")),
            sharedWithHierarchy.toString());
    }

    @Test
    void namesMustBeTrimmedNonEmptyBoundedAndUniquePerScope() {
        List<String> blank = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "   ")), List.of(), List.of()));
        assertTrue(blank.stream().anyMatch(error -> error.contains("名称为空")), blank.toString());

        List<String> tooLong = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "名".repeat(21))), List.of(), List.of()));
        assertTrue(tooLong.stream().anyMatch(error -> error.contains("名称超过 20 字符")), tooLong.toString());

        List<String> duplicateEntities = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "学生"), entity("b", "学生")), List.of(), List.of()));
        assertTrue(duplicateEntities.stream().anyMatch(error -> error.contains("实体名称重复")),
            duplicateEntities.toString());

        List<ErEntity> two = List.of(entity("a", "甲"), entity("b", "乙"));
        List<String> duplicateRelationships = ErModelValidator.validate(new ErModel("t", "e", two,
            List.of(
                rel("r1", "供应", false, List.of(member("a", "1"), member("b", "n"))),
                rel("r2", "供应", false, List.of(member("a", "n"), member("b", "1")))),
            List.of()));
        assertTrue(duplicateRelationships.stream().anyMatch(error -> error.contains("联系名称重复")),
            duplicateRelationships.toString());

        List<String> duplicateAttributes = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲", attr("学号"), attr("学号"))), List.of(), List.of()));
        assertTrue(duplicateAttributes.stream().anyMatch(error -> error.contains("属性名称重复")),
            duplicateAttributes.toString());

        // The same attribute name may appear under two different owners.
        List<String> sameAttributeOnTwoEntities = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲", attr("学号")), entity("b", "乙", attr("学号"))),
            List.of(), List.of()));
        assertTrue(sameAttributeOnTwoEntities.isEmpty(), sameAttributeOnTwoEntities.toString());

        List<String> blankRelationshipName = ErModelValidator.validate(new ErModel("t", "e", two,
            List.of(rel("r1", "", false, List.of(member("a", "1"), member("b", "n")))), List.of()));
        assertTrue(blankRelationshipName.stream().anyMatch(error -> error.contains("名称为空")),
            blankRelationshipName.toString());
    }

    // ---- relationship attributes and hierarchies --------------------------

    @Test
    void relationshipAttributesMustNotBeKeys() {
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲"), entity("b", "乙")),
            List.of(rel("r", "供应", false, List.of(member("a", "1"), member("b", "n")),
                new ErAttribute("成绩", true))),
            List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("联系属性不能声明为主码")), errors.toString());
    }

    @Test
    void hierarchiesNeedSubclassesAndFormATree() {
        List<ErEntity> entities = List.of(entity("base", "基类"),
            entity("sub1", "子类一"), entity("sub2", "子类二"));

        List<String> noSubclass = ErModelValidator.validate(new ErModel("t", "e", entities, List.of(),
            List.of(hierarchy("h", "ISA", "base", List.of()))));
        assertTrue(noSubclass.stream().anyMatch(error -> error.contains("至少需要一个子类")), noSubclass.toString());

        List<String> subclassOfTwo = ErModelValidator.validate(new ErModel("t", "e", entities, List.of(),
            List.of(
                hierarchy("h1", "ISA1", "base", List.of("sub1")),
                hierarchy("h2", "ISA2", "base", List.of("sub1", "sub2")))));
        assertTrue(subclassOfTwo.stream().anyMatch(error -> error.contains("只能作为一个层级的子类")),
            subclassOfTwo.toString());

        List<String> duplicatedInsideOneHierarchy = ErModelValidator.validate(new ErModel("t", "e", entities,
            List.of(),
            List.of(hierarchy("h", "ISA", "base", List.of("sub1", "sub1")))));
        assertTrue(duplicatedInsideOneHierarchy.stream().anyMatch(error -> error.contains("重复出现为子类")),
            duplicatedInsideOneHierarchy.toString());
    }

    // ---- control characters and aggregation -------------------------------

    @Test
    void controlCharactersInFreeTextAreRejected() {
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "学\n生", attr("姓\t名"))), List.of(), List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("名称包含控制字符")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("属性名称包含控制字符")), errors.toString());
    }

    @Test
    void allErrorsAreAggregatedNotOnlyTheFirst() {
        List<String> errors = ErModelValidator.validate(new ErModel("t", "e",
            List.of(entity("a", "甲"), entity("b", "乙")),
            List.of(rel("r", "供应", false, List.of(member("ghost", "N"), member("b", "9")))),
            List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("引用了未声明的实体")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("成员基数")), errors.toString());
        assertTrue(errors.size() >= 2, errors.toString());
    }
}
