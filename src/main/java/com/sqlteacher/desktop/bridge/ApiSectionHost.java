package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * v3.4.0 REF-8: what a {@link LocalAppApiSection} may reach on the facade. The facade stays the
 * only owner of the lazily initialized Spring core; sections resolve beans per call so no
 * section outlives or pins the core beyond what the facade already does.
 */
interface ApiSectionHost {

    ObjectMapper mapper();

    /** Lazily creates (once) and returns the shared Spring core; same locking as before the split. */
    AnnotationConfigApplicationContext context();

    /** Whether the Spring core has been created; must never trigger initialization. */
    boolean coreInitialized();
}
