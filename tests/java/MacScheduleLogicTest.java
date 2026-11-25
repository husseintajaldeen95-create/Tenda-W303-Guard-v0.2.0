package com.tenda.w303guard;

/** Regression tests for per-policy start-time zeroing and 23:55 restoration. */
public final class MacScheduleLogicTest {
    private static int passed;

    public static void main(String[] args) {
        zerosEachPolicyOnlyAfterItsOwnStart();
        doesNotZeroBeforeStartOrAtRestoreTime();
        skipsAlwaysOnPolicies();
        respectsWeekdayRangesIncludingWraparound();
        restoresOnlyModifiedPoliciesAt2355OrNextDate();
        countsSameTimePoliciesAsSeparateSlots();
        countsSameTimePolicySlotsByNumberNotByTime();
        countsAllTenPolicySlotsIndependently();
        keepsAutomationActiveWhenNoPolicyIsEligible();
        System.out.println("MAC schedule logic tests: " + passed + " passed.");
    }

    private static void zerosEachPolicyOnlyAfterItsOwnStart() {
        check(MacScheduleLogic.shouldZeroStart(14, 0, 14, 0, 1, 0, 6), "14:00 policy zeroes at 14:00");
        check(!MacScheduleLogic.shouldZeroStart(15, 59, 16, 0, 1, 0, 6), "16:00 policy remains unchanged at 15:59");
        check(MacScheduleLogic.shouldZeroStart(16, 0, 16, 0, 1, 0, 6), "16:00 policy zeroes at 16:00");
        check(MacScheduleLogic.shouldZeroStart(19, 10, 14, 0, 1, 0, 6), "missed start is handled later the same day");
    }

    private static void doesNotZeroBeforeStartOrAtRestoreTime() {
        check(!MacScheduleLogic.shouldZeroStart(13, 59, 14, 0, 1, 0, 6), "does not zero before the policy start");
        check(MacScheduleLogic.shouldZeroStart(23, 0, 23, 0, 1, 0, 6), "23:00 policy can zero at its own start before daily restore");
        check(MacScheduleLogic.shouldZeroStart(23, 54, 23, 30, 1, 0, 6), "late policy can still zero before 23:55");
        check(!MacScheduleLogic.shouldZeroStart(23, 54, 23, 55, 1, 0, 6), "does not zero before a 23:55 policy start");
        check(!MacScheduleLogic.shouldZeroStart(23, 55, 23, 30, 1, 0, 6), "does not zero at or after the 23:55 restore time");
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

    private static void restoresOnlyModifiedPoliciesAt2355OrNextDate() {
        check(MacScheduleLogic.isRestoreTime(23, 55), "daily restore starts at exactly 23:55");
        check(MacScheduleLogic.isRestoreTime(23, 56), "daily restore remains active after 23:55");
        check(!MacScheduleLogic.isRestoreTime(23, 54), "do not enter daily restore before 23:55");
        check(!MacScheduleLogic.isRestoreTime(23, 0), "23:00 is no longer the daily restore time");
        check(!MacScheduleLogic.isRestoreTime(22, 59), "do not enter daily restore before 23:55");
        check(MacScheduleLogic.shouldRestore(23, 55, true, false), "restore modified policy at 23:55");
        check(!MacScheduleLogic.shouldRestore(23, 55, false, false), "do not restore policy not modified by app");
        check(MacScheduleLogic.shouldRestore(8, 0, true, true), "restore stale modification after date change");
        check(!MacScheduleLogic.shouldRestore(23, 54, true, false), "do not restore before 23:55 on same date");
    }


    private static void countsSameTimePoliciesAsSeparateSlots() {
        java.util.List<Integer> slots = java.util.Arrays.asList(1, 2, 3, 4, 5, 6, 6);
        java.util.List<Integer> unique = MacScheduleLogic.uniquePolicyNumbers(slots);
        check(unique.size() == 6, "six distinct policy numbers remain six even when their times match");
        check(unique.get(0) == 1 && unique.get(5) == 6, "policy slot order is preserved");
    }


    private static void countsSameTimePolicySlotsByNumberNotByTime() {
        java.util.List<MacScheduleLogic.PolicySlot> slots = java.util.Arrays.asList(
                new MacScheduleLogic.PolicySlot(1, 16 * 60),
                new MacScheduleLogic.PolicySlot(2, 16 * 60),
                new MacScheduleLogic.PolicySlot(3, 16 * 60),
                new MacScheduleLogic.PolicySlot(4, 18 * 60),
                new MacScheduleLogic.PolicySlot(5, 18 * 60),
                new MacScheduleLogic.PolicySlot(6, 20 * 60),
                new MacScheduleLogic.PolicySlot(6, 21 * 60));
        java.util.List<MacScheduleLogic.PolicySlot> unique = MacScheduleLogic.uniquePolicySlots(slots);
        check(unique.size() == 6, "same-time policies are counted separately; duplicate slot number is not counted twice");
        check(unique.get(0).number == 1 && unique.get(1).number == 2 && unique.get(2).number == 3,
                "three policies at 16:00 remain three separate policies");
        check(unique.get(3).number == 4 && unique.get(4).number == 5,
                "two policies at 18:00 remain two separate policies");
    }


    private static void countsAllTenPolicySlotsIndependently() {
        java.util.List<MacScheduleLogic.PolicySlot> slots = new java.util.ArrayList<>();
        for (int number = 1; number <= 10; number++) {
            slots.add(new MacScheduleLogic.PolicySlot(number, 16 * 60));
        }
        java.util.List<MacScheduleLogic.PolicySlot> unique = MacScheduleLogic.uniquePolicySlots(slots);
        check(unique.size() == 10, "all ten policy slots remain ten even when every start time is identical");
        check(unique.get(9).number == 10, "the tenth policy slot is retained independently");
    }

    private static void keepsAutomationActiveWhenNoPolicyIsEligible() {
        check(MacScheduleLogic.shouldKeepAutomationTaskActive(true, false, false),
                "user-enabled automation remains active when the current scan has zero eligible policies");
        check(MacScheduleLogic.shouldKeepAutomationTaskActive(false, true, false),
                "the service remains active while an explicit stop/restore phase is pending");
        check(MacScheduleLogic.shouldKeepAutomationTaskActive(false, false, true),
                "the service remains active while a policy still needs restoration");
        check(!MacScheduleLogic.shouldKeepAutomationTaskActive(false, false, false),
                "the MAC task can stop when disabled and no restoration is pending");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError("FAILED: " + message);
        passed++;
        System.out.println("PASS: " + message);
    }
}
