package com.sqlteacher.desktop.appearance;

/** Semantic icon names backed by the centrally versioned Ikonli Material Design 2 pack. */
public enum UiIcon {
    HOME("mdi2h-home-outline"),
    CODE("mdi2c-code-tags"),
    PRACTICE("mdi2n-notebook-edit-outline"),
    LIBRARY("mdi2b-bookshelf"),
    CHART("mdi2c-chart-box-outline"),
    BOOK("mdi2b-book-open-page-variant-outline"),
    SPARK("mdi2a-auto-fix"),
    TABLE("mdi2t-table-large"),
    CLOUD("mdi2c-cloud-outline"),
    SETTINGS("mdi2c-cog-outline"),
    USER("mdi2a-account-outline"),
    REFRESH("mdi2r-refresh"),
    DATABASE("mdi2d-database-outline"),
    COLUMN("mdi2v-view-column-outline"),
    KEY("mdi2k-key-outline"),
    WARNING("mdi2a-alert-outline");

    private final String literal;

    UiIcon(String literal) {
        this.literal = literal;
    }

    public String literal() {
        return literal;
    }
}
