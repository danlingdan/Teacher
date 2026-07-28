package com.sqlteacher.desktop.appearance;

public enum UiFontChoice {
    MODERN("现代中文", "font-modern"),
    SYSTEM("系统默认", "font-system"),
    CLASSIC("经典清晰", "font-classic");

    private final String displayName;
    private final String styleClass;

    UiFontChoice(String displayName, String styleClass) {
        this.displayName = displayName;
        this.styleClass = styleClass;
    }

    public String styleClass() {
        return styleClass;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
