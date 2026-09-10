package com.sqlteacher.application.exercise;

import java.util.List;
import java.util.Objects;

/**
 * One page of the practice catalog (v3.3 W4.4): bounded item list plus the total count so
 * the UI can page server-side. Filtering happens in Java SQL; rendering stays bounded by
 * pageSize.
 */
public record ExerciseCatalogPage(
    List<ExerciseCatalogItem> items,
    int total,
    int page,
    int pageSize
) {
    public ExerciseCatalogPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("pageSize must be between 1 and 500");
        }
    }
}
