package com.sqlteacher.domain.ermodel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * Deterministic Chen-notation E-R model validation (v3.11.0 plan §4). The input is a leniently
 * built {@link ErModel} (AI output is untrusted, so parsing never fails here); the result is the
 * aggregated list of ALL validation errors, never a first-error abort, so the list can feed the
 * one-shot repair retry and the user-facing failure summary. Pure static function: no I/O, no
 * AI, no mutation of the model.
 */
public final class ErModelValidator {

    public static final int MAX_ENTITIES = 16;
    public static final int MAX_ATTRIBUTES_PER_OWNER = 12;
    public static final int MAX_COMPOSITE_CHILDREN = 4;
    public static final int MAX_RELATIONSHIPS = 24;
    public static final int MIN_MEMBERS = 2;
    public static final int MAX_MEMBERS = 4;
    public static final int MAX_RELATIONSHIP_ATTRIBUTES = 6;
    public static final int MAX_HIERARCHIES = 8;
    public static final int MAX_SUBCLASSES = 6;
    public static final int MAX_ID_LENGTH = 40;
    public static final int MAX_NAME_LENGTH = 20;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Set<String> CARDINALITY_WHITELIST = Set.of("1", "n", "m");

    private ErModelValidator() {
    }

    /** Validates the whole model and returns every violation; an empty list means the model is valid. */
    public static List<String> validate(ErModel model) {
        if (model == null) {
            return List.of("E-R 模型为空");
        }
        List<String> errors = new ArrayList<>();

        // Declared entity ids and the weak subset drive referential and identifying-shape checks.
        Set<String> entityIds = new HashSet<>();
        Set<String> weakEntityIds = new HashSet<>();
        for (ErEntity entity : model.entities()) {
            entityIds.add(entity.id());
            if (entity.weak()) {
                weakEntityIds.add(entity.id());
            }
        }

        Set<String> declaredIds = new HashSet<>();
        Set<String> entityNames = new HashSet<>();
        if (model.entities().size() > MAX_ENTITIES) {
            errors.add("实体数量超过上限 " + MAX_ENTITIES);
        }
        for (ErEntity entity : model.entities()) {
            validateId("实体", entity.id(), declaredIds, errors);
            declaredIds.add(entity.id());
            validateName("实体 " + safeLabel(entity.id()), entity.name(), errors);
            String nameKey = entity.name() == null ? "" : entity.name().strip();
            if (!entityNames.add(nameKey)) {
                errors.add("实体名称重复：" + safeLabel(nameKey));
            }
            if (entity.attributes().size() > MAX_ATTRIBUTES_PER_OWNER) {
                errors.add("实体 " + safeLabel(entity.id()) + " 的属性数量超过上限 " + MAX_ATTRIBUTES_PER_OWNER);
            }
            validateAttributes("实体 " + safeLabel(entity.id()), entity.attributes(), errors);
        }

        Set<String> relationshipNames = new HashSet<>();
        if (model.relationships().size() > MAX_RELATIONSHIPS) {
            errors.add("联系数量超过上限 " + MAX_RELATIONSHIPS);
        }
        for (ErRelationship relationship : model.relationships()) {
            String owner = "联系 " + safeLabel(relationship.id());
            validateId("联系", relationship.id(), declaredIds, errors);
            declaredIds.add(relationship.id());
            validateName(owner, relationship.name(), errors);
            String nameKey = relationship.name() == null ? "" : relationship.name().strip();
            if (!relationshipNames.add(nameKey)) {
                errors.add("联系名称重复：" + safeLabel(nameKey));
            }
            validateRelationship(owner, relationship, entityIds, weakEntityIds, errors);
        }

        validateHierarchies(model, entityIds, declaredIds, errors);

        for (String weakId : weakEntityIds) {
            if (!participatesInIdentifying(model, weakId)) {
                errors.add("弱实体 " + safeLabel(weakId) + " 未参与任何依赖联系");
            }
        }
        return List.copyOf(errors);
    }

    private static void validateRelationship(String owner, ErRelationship relationship,
                                             Set<String> entityIds, Set<String> weakEntityIds,
                                             List<String> errors) {
        int memberCount = relationship.members().size();
        if (memberCount < MIN_MEMBERS || memberCount > MAX_MEMBERS) {
            errors.add(owner + " 的成员数量必须在 " + MIN_MEMBERS + " 到 " + MAX_MEMBERS + " 之间");
        }
        for (ErRelationshipMember member : relationship.members()) {
            if (!entityIds.contains(member.entity())) {
                errors.add(owner + " 的成员引用了未声明的实体：" + safeLabel(member.entity()));
            }
            String cardinality = member.cardinality() == null ? "" : member.cardinality().strip();
            if (!CARDINALITY_WHITELIST.contains(cardinality)) {
                errors.add(owner + " 的成员基数必须是 1、n、m 之一：" + safeLabel(cardinality));
            }
        }
        if (relationship.attributes().size() > MAX_RELATIONSHIP_ATTRIBUTES) {
            errors.add(owner + " 的属性数量超过上限 " + MAX_RELATIONSHIP_ATTRIBUTES);
        }
        for (ErAttribute attribute : relationship.attributes()) {
            if (attribute.key()) {
                errors.add(owner + " 的联系属性不能声明为主码：" + safeLabel(attribute.name()));
            }
        }
        validateAttributes(owner, relationship.attributes(), errors);

        if (relationship.identifying()) {
            validateIdentifying(owner, relationship, weakEntityIds, errors);
        }
    }

    /**
     * Plan §4: an identifying relationship must have exactly two members, one of them a weak
     * entity, and the other member (the owner) must carry cardinality "1".
     */
    private static void validateIdentifying(String owner, ErRelationship relationship,
                                            Set<String> weakEntityIds, List<String> errors) {
        if (relationship.members().size() != 2) {
            errors.add(owner + " 是依赖联系，必须恰好有 2 个成员");
            return;
        }
        boolean hasWeakMember = relationship.members().stream()
            .anyMatch(member -> weakEntityIds.contains(member.entity()));
        if (!hasWeakMember) {
            errors.add(owner + " 是依赖联系，必须包含一个弱实体成员");
        }
        for (ErRelationshipMember member : relationship.members()) {
            if (weakEntityIds.contains(member.entity())) {
                continue;
            }
            String cardinality = member.cardinality() == null ? "" : member.cardinality().strip();
            if (!"1".equals(cardinality)) {
                errors.add(owner + " 的拥有者成员基数必须为 1");
                break;
            }
        }
    }

    private static boolean participatesInIdentifying(ErModel model, String weakEntityId) {
        for (ErRelationship relationship : model.relationships()) {
            if (!relationship.identifying()) {
                continue;
            }
            for (ErRelationshipMember member : relationship.members()) {
                if (weakEntityId.equals(member.entity())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateHierarchies(ErModel model, Set<String> entityIds,
                                            Set<String> declaredIds, List<String> errors) {
        if (model.hierarchies().size() > MAX_HIERARCHIES) {
            errors.add("层级数量超过上限 " + MAX_HIERARCHIES);
        }
        Map<String, Set<String>> subclassOwners = new LinkedHashMap<>();
        for (ErHierarchy hierarchy : model.hierarchies()) {
            String owner = "层级 " + safeLabel(hierarchy.id());
            validateId("层级", hierarchy.id(), declaredIds, errors);
            declaredIds.add(hierarchy.id());
            validateName(owner, hierarchy.name(), errors);
            if (!entityIds.contains(hierarchy.superclass())) {
                errors.add(owner + " 的超类引用了未声明的实体：" + safeLabel(hierarchy.superclass()));
            }
            if (hierarchy.subclasses().isEmpty()) {
                errors.add(owner + " 至少需要一个子类");
            }
            if (hierarchy.subclasses().size() > MAX_SUBCLASSES) {
                errors.add(owner + " 的子类数量超过上限 " + MAX_SUBCLASSES);
            }
            for (String subclass : hierarchy.subclasses()) {
                if (!entityIds.contains(subclass)) {
                    errors.add(owner + " 的子类引用了未声明的实体：" + safeLabel(subclass));
                }
                Set<String> owners = subclassOwners.computeIfAbsent(subclass, key -> new HashSet<>());
                if (!owners.add(hierarchy.id())) {
                    errors.add("实体 " + safeLabel(subclass) + " 在层级 " + safeLabel(hierarchy.id())
                        + " 中重复出现为子类");
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : subclassOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                errors.add("实体 " + safeLabel(entry.getKey()) + " 只能作为一个层级的子类");
            }
        }
    }

    private static void validateAttributes(String owner, List<ErAttribute> attributes, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (ErAttribute attribute : attributes) {
            if (containsControlCharacter(attribute.name())) {
                errors.add(owner + " 的属性名称包含控制字符：" + safeLabel(attribute.name()));
            }
            String nameKey = attribute.name() == null ? "" : attribute.name().strip();
            if (nameKey.isEmpty()) {
                errors.add(owner + " 的属性名称为空");
            } else if (nameKey.length() > MAX_NAME_LENGTH) {
                errors.add(owner + " 的属性名称超过 " + MAX_NAME_LENGTH + " 字符：" + safeLabel(nameKey));
            }
            if (!names.add(nameKey)) {
                errors.add(owner + " 的属性名称重复：" + safeLabel(nameKey));
            }
            if (attribute.composite().size() > MAX_COMPOSITE_CHILDREN) {
                errors.add(owner + " 的属性 " + safeLabel(nameKey) + " 的复合子属性数量超过上限 "
                    + MAX_COMPOSITE_CHILDREN);
            }
            for (String child : attribute.composite()) {
                if (containsControlCharacter(child)) {
                    errors.add(owner + " 的属性 " + safeLabel(nameKey) + " 的复合子属性包含控制字符");
                }
                String childKey = child == null ? "" : child.strip();
                if (childKey.isEmpty()) {
                    errors.add(owner + " 的属性 " + safeLabel(nameKey) + " 的复合子属性名称为空");
                } else if (childKey.length() > MAX_NAME_LENGTH) {
                    errors.add(owner + " 的属性 " + safeLabel(nameKey) + " 的复合子属性名称超过 "
                        + MAX_NAME_LENGTH + " 字符");
                }
            }
        }
    }

    private static void validateId(String kind, String id, Set<String> declaredIds, List<String> errors) {
        if (containsControlCharacter(id)) {
            errors.add(kind + " id 包含控制字符：" + safeLabel(id));
        }
        String value = id == null ? "" : id.strip();
        if (!ID_PATTERN.matcher(value).matches()) {
            errors.add(kind + " id 必须匹配 [a-z0-9_]{1,40}：" + safeLabel(value));
        }
        if (!declaredIds.add(value)) {
            errors.add(kind + " id 重复：" + safeLabel(value));
        }
    }

    private static void validateName(String owner, String name, List<String> errors) {
        if (containsControlCharacter(name)) {
            errors.add(owner + " 的名称包含控制字符：" + safeLabel(name));
        }
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) {
            errors.add(owner + " 的名称为空");
        } else if (value.length() > MAX_NAME_LENGTH) {
            errors.add(owner + " 的名称超过 " + MAX_NAME_LENGTH + " 字符：" + safeLabel(value));
        }
    }

    private static boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        return value.chars().anyMatch(Character::isISOControl);
    }

    /** Bounds untrusted text inside error messages so a hostile model string cannot flood the UI. */
    private static String safeLabel(String value) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.length() > 20) {
            cleaned = cleaned.substring(0, 20) + "…";
        }
        return cleaned.isEmpty() ? "<空>" : cleaned;
    }
}
