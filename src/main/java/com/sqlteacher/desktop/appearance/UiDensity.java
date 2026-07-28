package com.sqlteacher.desktop.appearance;

public enum UiDensity {
    COMFORTABLE("舒适", "density-comfortable"),
    COMPACT("紧凑", "density-compact");

    private final String displayName;
    private final String styleClass;

    UiDensity(String displayName, String styleClass) {
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
