package com.sqlteacher.application.risk;

/**
 * Stores the local SQL execution safety mode selected by the user.
 *
 * <p>The persisted flag now represents developer mode. Legacy method names remain for adapter
 * compatibility. Developer mode reduces routine confirmation without bypassing forbidden SQL,
 * read-only connections, or destructive-operation confirmation. AI output remains a draft.</p>
 */
public interface SqlSafetyModeService {
    boolean isUnrestrictedModeEnabled();

    void setUnrestrictedModeEnabled(boolean enabled);

    default boolean isDeveloperModeEnabled() {
        return isUnrestrictedModeEnabled();
    }

    default void setDeveloperModeEnabled(boolean enabled) {
        setUnrestrictedModeEnabled(enabled);
    }

    static SqlSafetyModeService standardMode() {
        return new SqlSafetyModeService() {
            @Override
            public boolean isUnrestrictedModeEnabled() {
                return false;
            }

            @Override
            public void setUnrestrictedModeEnabled(boolean enabled) {
                if (enabled) {
                    throw new UnsupportedOperationException("This safety mode is read-only");
                }
            }
        };
    }
}
