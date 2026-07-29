package com.sqlteacher.application.risk;

/**
 * Stores the local SQL execution safety mode selected by the user.
 *
 * <p>Unrestricted mode only bypasses SQLTeacher's application-level execution gates. Database
 * permissions, JDBC driver limitations and operating-system protections still apply. AI output
 * remains a draft and is never executed automatically.</p>
 */
public interface SqlSafetyModeService {
    boolean isUnrestrictedModeEnabled();

    void setUnrestrictedModeEnabled(boolean enabled);

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
