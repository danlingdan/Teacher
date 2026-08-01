package com.sqlteacher.application.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeNotificationPolicyTest {

    @Test void allowsOnlyWhitelistedPhrasesAndTargets() {
        assertTrue(NativeNotificationPolicy.isAllowed("更新可用", "updates"));
        assertTrue(NativeNotificationPolicy.isAllowed("Backup complete", "tasks"));
        assertFalse(NativeNotificationPolicy.isAllowed("学生张三提交了作业", "tasks"));
        assertFalse(NativeNotificationPolicy.isAllowed("update ready", "tasks"));
        assertFalse(NativeNotificationPolicy.isAllowed("更新可用", "some-other-page"));
    }

    @Test void rejectsSensitiveContentInBodies() {
        assertFalse(NativeNotificationPolicy.isAllowed("select * from students", "tasks"));
        assertFalse(NativeNotificationPolicy.isAllowed("反馈正文：无法登录", "support"));
        assertFalse(NativeNotificationPolicy.isAllowed("提示词：帮我写SQL", "tasks"));
    }
}
