package com.sqlteacher.desktop.appearance;

public enum UiTheme {
    SYSTEM("跟随系统"),
    DARK("深色"),
    LIGHT("浅色");

    private final String displayName;

    UiTheme(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
