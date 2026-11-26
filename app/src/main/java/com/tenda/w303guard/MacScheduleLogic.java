package com.tenda.w303guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

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

    /**
     * Determines whether a policy should be zeroed now, including a missed start
     * from the previous eligible weekday when the automation was not running.
     *
     * lastZeroDate is retained after restoration so yesterday's already-processed
     * occurrence is not mistaken for a missed event after midnight. trackingStartDate
     * prevents a newly-added policy from being retroactively applied to a day before
     * the app first captured that policy.
     */
    public static boolean shouldZeroStartWithCatchUp(int nowHour, int nowMinute,
                                                     int originalStartHour, int originalStartMinute,
                                                     int day, int startDay, int endDay,
                                                     String currentDate, String lastZeroDate,
                                                     String trackingStartDate) {
        if (nowHour < 0 || nowHour > 23 || nowMinute < 0 || nowMinute > 59) return false;
        if (originalStartHour < 0 || originalStartHour > 23
                || originalStartMinute < 0 || originalStartMinute > 59) return false;
        if (originalStartHour == 0 && originalStartMinute == 0) return false;
        if (minutes(nowHour, nowMinute) >= minutes(23, 55)) return false;
        if (day < 0 || day > 6 || !isDayAllowed(day, startDay, endDay)) return false;
        if (!isValidDate(currentDate)) return false;

        String dueOccurrenceDate;
        int now = minutes(nowHour, nowMinute);
        int start = minutes(originalStartHour, originalStartMinute);
        if (now >= start) {
            dueOccurrenceDate = currentDate;
        } else {
            int previousDay = (day + 6) % 7;
            if (!isDayAllowed(previousDay, startDay, endDay)) return false;
            dueOccurrenceDate = previousDate(currentDate);
            if (dueOccurrenceDate == null) return false;
        }

        if (isValidDate(lastZeroDate)) {
            return dueOccurrenceDate.compareTo(lastZeroDate) > 0;
        }
        // On first activation, only catch up occurrences on/after the date this
        // policy was first tracked. This avoids applying a newly-added policy to
        // a previous day before the user enabled or added it.
        if (isValidDate(trackingStartDate)) {
            return dueOccurrenceDate.compareTo(trackingStartDate) >= 0;
        }
        return true;
    }

    /** Returns whether this individual policy needs restoration now. */
    public static boolean shouldRestorePolicy(boolean modified, boolean pending,
                                              String pendingOperation, String modifiedDate,
                                              String currentDate, boolean dailyRestoreDue) {
        if (!modified && !pending) return false;
        boolean stale = modifiedDate != null && !modifiedDate.isEmpty()
                && currentDate != null && !currentDate.isEmpty()
                && !modifiedDate.equals(currentDate);
        boolean explicitRestorePending = pending && "restore".equals(pendingOperation);
        return dailyRestoreDue || stale || explicitRestorePending;
    }

    /** Includes same-day pending zero writes so an unconfirmed write is retried. */
    public static boolean shouldAttemptZeroPolicy(boolean modified, boolean pending,
                                                  String pendingOperation) {
        return (!modified && !pending) || (pending && "zero".equals(pendingOperation));
    }

    private static boolean isValidDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        try {
            Date parsed = format.parse(value);
            return parsed != null && format.format(parsed).equals(value);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String previousDate(String value) {
        if (!isValidDate(value)) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        try {
            Date parsed = format.parse(value);
            if (parsed == null) return null;
            Calendar calendar = Calendar.getInstance(Locale.US);
            calendar.setTime(parsed);
            calendar.add(Calendar.DAY_OF_MONTH, -1);
            return format.format(calendar.getTime());
        } catch (Exception ignored) {
            return null;
        }
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
