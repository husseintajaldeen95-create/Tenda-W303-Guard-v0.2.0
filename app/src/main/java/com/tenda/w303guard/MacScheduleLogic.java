package com.tenda.w303guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure scheduling rules for the Tenda MAC policy time-window helper. */
public final class MacScheduleLogic {
    private MacScheduleLogic() { }


    /**
     * Returns policy slot numbers once each, preserving router order.
     * Start time is deliberately NOT part of identity: two different policy
     * numbers remain two policies even when their start/end times are equal.
     */
    public static List<Integer> uniquePolicyNumbers(List<Integer> policyNumbers) {
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        if (policyNumbers != null) {
            for (Integer number : policyNumbers) {
                if (number != null && number >= 1 && number <= 10) unique.add(number);
            }
        }
        return new ArrayList<>(unique);
    }


    public static final class PolicySlot {
        public final int number;
        public final int startMinuteOfDay;

        public PolicySlot(int number, int startMinuteOfDay) {
            this.number = number;
            this.startMinuteOfDay = startMinuteOfDay;
        }
    }

    /**
     * Deduplicates only repeated router slot numbers. Equal start times do not
     * make two different slot numbers the same policy.
     */
    public static List<PolicySlot> uniquePolicySlots(List<PolicySlot> slots) {
        java.util.LinkedHashMap<Integer, PolicySlot> byNumber = new java.util.LinkedHashMap<>();
        if (slots != null) {
            for (PolicySlot slot : slots) {
                if (slot != null && slot.number >= 1 && slot.number <= 10) {
                    if (!byNumber.containsKey(slot.number)) byNumber.put(slot.number, slot);
                }
            }
        }
        return new ArrayList<>(byNumber.values());
    }

    public static boolean isDayAllowed(int day, int startDay, int endDay) {
        if (day < 0 || day > 6 || startDay < 0 || startDay > 6 || endDay < 0 || endDay > 6) return false;
        if (startDay <= endDay) return day >= startDay && day <= endDay;
        return day >= startDay || day <= endDay;
    }

    public static boolean shouldZeroStart(int nowHour, int nowMinute,
                                          int originalStartHour, int originalStartMinute,
                                          int day, int startDay, int endDay) {
        if (nowHour < 0 || nowHour > 23 || nowMinute < 0 || nowMinute > 59) return false;
        if (originalStartHour < 0 || originalStartHour > 23 || originalStartMinute < 0 || originalStartMinute > 59) return false;
        if (originalStartHour == 0 && originalStartMinute == 0) return false;
        if (minutes(nowHour, nowMinute) >= minutes(23, 55)) return false;
        if (!isDayAllowed(day, startDay, endDay)) return false;
        int now = nowHour * 60 + nowMinute;
        int start = originalStartHour * 60 + originalStartMinute;
        return now >= start;
    }

    /** Daily restoration starts at 23:55 according to the router clock. */
    public static boolean isRestoreTime(int nowHour, int nowMinute) {
        if (nowHour < 0 || nowHour > 23 || nowMinute < 0 || nowMinute > 59) return false;
        return minutes(nowHour, nowMinute) >= minutes(23, 55);
    }

    public static boolean shouldRestore(int nowHour, int nowMinute,
                                        boolean modified, boolean modifiedOnAnotherDate) {
        return modified && (isRestoreTime(nowHour, nowMinute) || modifiedOnAnotherDate);
    }

    /**
     * The background task remains active while the user has enabled automation,
     * even when the latest scan finds zero eligible policies. The eligible count
     * is deliberately not part of this lifecycle decision.
     */
    public static boolean shouldKeepAutomationTaskActive(boolean enabled, boolean stopping,
                                                          boolean hasPendingRestore) {
        return enabled || stopping || hasPendingRestore;
    }

    public static int minutes(int hour, int minute) {
        return hour * 60 + minute;
    }
}
