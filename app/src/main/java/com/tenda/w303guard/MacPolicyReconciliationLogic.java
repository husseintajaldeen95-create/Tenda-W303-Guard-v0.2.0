package com.tenda.w303guard;

/** Pure rules for reconciling saved MAC automation flags with a fresh router read. */
public final class MacPolicyReconciliationLogic {
    private MacPolicyReconciliationLogic() { }

    public enum Decision {
        NONE,
        UNVERIFIED,
        CONFLICT,
        ZERO_CONFIRMED,
        RETRY_ZERO,
        RESTORE_CONFIRMED,
        RETRY_RESTORE
    }

    /**
     * Compare the saved operation state with the actual start time read from the
     * numbered router slot. A saved "modified" flag is never accepted as proof
     * that the router currently contains 00:00.
     */
    public static Decision evaluate(boolean readSuccessful, boolean identityMatches,
                                    boolean modified, boolean pending, String pendingOperation,
                                    int originalHour, int originalMinute,
                                    int currentHour, int currentMinute,
                                    String modifiedDate, String currentDate,
                                    boolean dailyRestoreDue) {
        if (!readSuccessful) return Decision.UNVERIFIED;
        if (!identityMatches || !validTime(originalHour, originalMinute)
                || !validTime(currentHour, currentMinute)) return Decision.CONFLICT;
        if (!modified && !pending) return Decision.NONE;

        boolean currentIsZero = currentHour == 0 && currentMinute == 0;
        boolean currentIsOriginal = currentHour == originalHour && currentMinute == originalMinute;
        String operation = pendingOperation == null ? "" : pendingOperation;

        if (pending) {
            if ("zero".equals(operation)) {
                if (currentIsZero) return Decision.ZERO_CONFIRMED;
                if (currentIsOriginal) return dailyRestoreDue
                        ? Decision.RESTORE_CONFIRMED : Decision.RETRY_ZERO;
                return Decision.CONFLICT;
            }
            if ("restore".equals(operation)) {
                if (currentIsOriginal) return Decision.RESTORE_CONFIRMED;
                if (currentIsZero) return Decision.RETRY_RESTORE;
                return Decision.CONFLICT;
            }
            return Decision.CONFLICT;
        }

        if (modified) {
            if (currentIsZero) return Decision.ZERO_CONFIRMED;
            if (currentIsOriginal) {
                boolean sameOccurrenceDate = modifiedDate != null && !modifiedDate.isEmpty()
                        && modifiedDate.equals(currentDate);
                return sameOccurrenceDate && !dailyRestoreDue
                        ? Decision.RETRY_ZERO : Decision.RESTORE_CONFIRMED;
            }
            return Decision.CONFLICT;
        }
        return Decision.NONE;
    }

    private static boolean validTime(int hour, int minute) {
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }
}
