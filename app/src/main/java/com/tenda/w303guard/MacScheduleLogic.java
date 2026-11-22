package com.tenda.w303guard;

/** Pure scheduling rules for the Tenda MAC policy time-window helper. */
public final class MacScheduleLogic {
    private MacScheduleLogic() { }

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
        if (nowHour >= 23) return false;
        if (!isDayAllowed(day, startDay, endDay)) return false;
        int now = nowHour * 60 + nowMinute;
        int start = originalStartHour * 60 + originalStartMinute;
        return now >= start;
    }

    public static boolean shouldRestore(int nowHour, boolean modified, boolean modifiedOnAnotherDate) {
        return modified && (nowHour >= 23 || modifiedOnAnotherDate);
    }

    public static int minutes(int hour, int minute) {
        return hour * 60 + minute;
    }
}
