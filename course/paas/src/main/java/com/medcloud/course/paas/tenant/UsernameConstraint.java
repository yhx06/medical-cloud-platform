package com.medcloud.course.paas.tenant;

import java.sql.SQLException;
import java.util.regex.Pattern;

/** Recognizes only the supported MySQL username duplicate-key diagnostic. */
final class UsernameConstraint {
    private static final Pattern DUPLICATE = Pattern.compile(
            "(?is)^Duplicate entry .* for key ['`](?:[^'`]*\\.)?uk_course_username['`]\\s*$");
    private UsernameConstraint() { }

    static boolean violatedBy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                SQLException sql = (SQLException) cause;
                // MySQL 5.6/8 identify the key at the end of error 1062. A marker in
                // submitted data or another integrity diagnostic is not a key identity.
                if (sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())
                        && sql.getMessage() != null && DUPLICATE.matcher(sql.getMessage()).matches()) return true;
            }
        }
        return false;
    }
}
