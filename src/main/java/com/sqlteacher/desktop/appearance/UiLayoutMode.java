package com.sqlteacher.desktop.appearance;

/** Desktop layout breakpoints shared by the application shell and page styles. */
public enum UiLayoutMode {
    COMPACT("layout-compact"),
    MEDIUM("layout-medium"),
    WIDE("layout-wide");

    private static final double COMPACT_MAX_WIDTH = 1039.0;
    private static final double MEDIUM_MAX_WIDTH = 1359.0;

    private final String styleClass;

    UiLayoutMode(String styleClass) {
        this.styleClass = styleClass;
    }

    public String styleClass() {
        return styleClass;
    }

    public static UiLayoutMode forWidth(double width) {
        if (!Double.isFinite(width) || width < 0) {
            throw new IllegalArgumentException("width must be a finite non-negative number");
        }
        if (width <= COMPACT_MAX_WIDTH) return COMPACT;
        if (width <= MEDIUM_MAX_WIDTH) return MEDIUM;
        return WIDE;
    }
}
