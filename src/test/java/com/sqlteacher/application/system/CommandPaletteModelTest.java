package com.sqlteacher.application.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandPaletteModelTest {

    @Test void matchesChineseAndEnglishAliases() {
        CommandPaletteModel model = new CommandPaletteModel();
        model.register("updates", "更新与支持", "updates", false, "settings#general");
        model.register("practice", "SQL 练习", "sql practice", false, "practice");
        model.register("logout", "退出登录", "logout", true, "login");

        List<CommandPaletteModel.Command> chinese = model.search("更新", 10);
        assertEquals(1, chinese.size());
        assertEquals("updates", chinese.getFirst().id());

        List<CommandPaletteModel.Command> english = model.search("sql practice", 10);
        assertEquals(1, english.size());
        assertEquals("practice", english.getFirst().id());
    }

    @Test void emptyQueryListsAllCommandsBoundedByLimit() {
        CommandPaletteModel model = new CommandPaletteModel();
        for (int index = 0; index < 10; index++) model.register("c" + index, "命令 " + index, "", false, "page");
        assertEquals(5, model.search("", 5).size());
        assertEquals(10, model.search("", 100).size());
    }

    @Test void destructiveCommandsAreClassifiedButNeverHidden() {
        CommandPaletteModel model = new CommandPaletteModel();
        model.register("reset", "重置本地设置", "reset settings", true, "settings#general");
        assertTrue(model.search("reset", 10).getFirst().destructive());
        assertEquals(1, model.search("重置", 10).size());
    }
}
