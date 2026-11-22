package com.tenda.w303guard;

/** Regression tests for per-policy start-time zeroing and 23:00 restoration. */
public final class MacScheduleLogicTest {
    private static int passed;

    public static void main(String[] args) {
        zerosEachPolicyOnlyAfterItsOwnStart();
        doesNotZeroBeforeStartOrAtRestoreHour();
        skipsAlwaysOnPolicies();
        respectsWeekdayRangesIncludingWraparound();
        restoresOnlyModifiedPoliciesAt23OrNextDate();
        System.out.println("MAC schedule logic tests: " + passed + " passed.");
    }

    private static void zerosEachPolicyOnlyAfterItsOwnStart() {
        check(MacScheduleLogic.shouldZeroStart(14, 0, 14, 0, 1, 0, 6), "14:00 policy zeroes at 14:00");
        check(!MacScheduleLogic.shouldZeroStart(15, 59, 16, 0, 1, 0, 6), "16:00 policy remains unchanged at 15:59");
        check(MacScheduleLogic.shouldZeroStart(16, 0, 16, 0, 1, 0, 6), "16:00 policy zeroes at 16:00");
        check(MacScheduleLogic.shouldZeroStart(19, 10, 14, 0, 1, 0, 6), "missed start is handled later the same day");
    }

    private static void doesNotZeroBeforeStartOrAtRestoreHour() {
        check(!MacScheduleLogic.shouldZeroStart(13, 59, 14, 0, 1, 0, 6), "does not zero before the policy start");
        check(!MacScheduleLogic.shouldZeroStart(23, 0, 14, 0, 1, 0, 6), "does not zero at or after 23:00");
    }

    private static void skipsAlwaysOnPolicies() {
        check(!MacScheduleLogic.shouldZeroStart(17, 0, 0, 0, 1, 0, 6), "start 00:00 needs no temporary zeroing");
    }

    private static void respectsWeekdayRangesIncludingWraparound() {
        check(MacScheduleLogic.isDayAllowed(2, 1, 4), "weekday inside normal range");
        check(!MacScheduleLogic.isDayAllowed(5, 1, 4), "weekday outside normal range");
        check(MacScheduleLogic.isDayAllowed(6, 5, 1), "Saturday inside wrapped range");
        check(MacScheduleLogic.isDayAllowed(0, 5, 1), "Sunday inside wrapped range");
        check(!MacScheduleLogic.isDayAllowed(3, 5, 1), "Wednesday outside wrapped range");
    }

    private static void restoresOnlyModifiedPoliciesAt23OrNextDate() {
        check(MacScheduleLogic.shouldRestore(23, true, false), "restore modified policy at 23:00");
        check(!MacScheduleLogic.shouldRestore(22, false, false), "do not restore policy not modified by app");
        check(MacScheduleLogic.shouldRestore(8, true, true), "restore stale modification after date change");
        check(!MacScheduleLogic.shouldRestore(22, true, false), "do not restore before 23:00 on same date");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError("FAILED: " + message);
        passed++;
        System.out.println("PASS: " + message);
    }
}
