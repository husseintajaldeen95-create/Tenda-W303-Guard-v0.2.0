package com.tenda.w303guard;

import java.util.Collection;

/**
 * Read-only validation for DHCP static reservations on the Tenda W303R.
 * This class never sends configuration changes to the router.
 */
public final class IpReservationValidator {
    public static final class Entry {
        public final String ip;
        public final String mac;

        public Entry(String ip, String mac) {
            this.ip = normalizeIpv4(ip);
            this.mac = MacAddress.normalize(mac);
        }
    }

    public static final class Result {
        public final boolean allowed;
        public final boolean alreadyReserved;
        public final String ip;
        public final String mac;
        public final String message;

        private Result(boolean allowed, boolean alreadyReserved, String ip, String mac, String message) {
            this.allowed = allowed;
            this.alreadyReserved = alreadyReserved;
            this.ip = ip;
            this.mac = mac;
            this.message = message;
        }
    }

    private IpReservationValidator() { }

    public static Result validate(String rawIp, String rawMac, String routerIp,
                                  Collection<Entry> dhcpLeases,
                                  Collection<Entry> staticReservations) {
        String normalizedMac = MacAddress.normalize(rawMac);
        if (normalizedMac.isEmpty()) {
            return fail("عنوان MAC غير صحيح. أدخل ستة أجزاء مثل AA:BB:CC:DD:EE:FF.");
        }

        String normalizedIp = normalizeIpInput(rawIp, routerIp);
        if (normalizedIp.isEmpty()) {
            return fail("عنوان IP غير صحيح. أدخل آخر جزء مثل 104 أو العنوان كاملاً ضمن شبكة الراوتر.");
        }
        if (normalizedIp.equals(normalizeIpv4(routerIp))) {
            return fail("لا يمكن حجز عنوان IP الخاص بالراوتر نفسه.");
        }

        if (dhcpLeases != null) {
            for (Entry lease : dhcpLeases) {
                if (lease == null || lease.ip.isEmpty() || lease.mac.isEmpty()) continue;
                if (normalizedIp.equals(lease.ip) && !normalizedMac.equals(lease.mac)) {
                    return fail("عنوان IP مستخدم حالياً في DHCP بواسطة جهاز آخر: " + lease.mac + ". اختر عنواناً آخر.");
                }
            }
        }

        boolean exactMatch = false;
        if (staticReservations != null) {
            for (Entry reservation : staticReservations) {
                if (reservation == null || reservation.ip.isEmpty() || reservation.mac.isEmpty()) continue;
                if (normalizedIp.equals(reservation.ip) && !normalizedMac.equals(reservation.mac)) {
                    return fail("عنوان IP محجوز مسبقاً لجهاز آخر: " + reservation.mac + ".");
                }
                if (normalizedMac.equals(reservation.mac) && !normalizedIp.equals(reservation.ip)) {
                    return fail("عنوان MAC هذا لديه حجز ثابت آخر: " + reservation.ip + ". احذف الحجز القديم من الراوتر أولاً ثم أعد الفحص.");
                }
                if (normalizedIp.equals(reservation.ip) && normalizedMac.equals(reservation.mac)) {
                    exactMatch = true;
                }
            }
        }

        if (exactMatch) {
            return new Result(false, true, normalizedIp, normalizedMac,
                    "هذا الحجز موجود بالفعل في جدول Static Assignment؛ لن ننشئ نسخة مكررة.");
        }

        return new Result(true, false, normalizedIp, normalizedMac,
                "لا يوجد تعارض ظاهر في آخر قراءة DHCP أو Static Assignment. راجع الجدول مرة أخيرة قبل الحجز.");
    }

    /** Accepts either the final octet (e.g. 104) or a full address in the router's /24 subnet. */
    public static String normalizeIpInput(String rawIp, String routerIp) {
        if (rawIp == null || routerIp == null) return "";
        String value = rawIp.trim();
        String router = normalizeIpv4(routerIp);
        if (router.isEmpty()) return "";
        String[] routerParts = router.split("\\.");
        if (value.matches("\\d{1,3}")) value = routerParts[0] + "." + routerParts[1] + "." + routerParts[2] + "." + value;
        String ip = normalizeIpv4(value);
        if (ip.isEmpty()) return "";
        String[] parts = ip.split("\\.");
        if (!parts[0].equals(routerParts[0]) || !parts[1].equals(routerParts[1]) || !parts[2].equals(routerParts[2])) return "";
        int last = Integer.parseInt(parts[3]);
        if (last < 1 || last > 254) return "";
        return ip;
    }

    public static String normalizeIpv4(String value) {
        if (value == null || !value.trim().matches("\\d{1,3}(\\.\\d{1,3}){3}")) return "";
        String[] parts = value.trim().split("\\.");
        if (parts.length != 4) return "";
        StringBuilder normalized = new StringBuilder();
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].length() > 3) return "";
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) return "";
                if (i > 0) normalized.append('.');
                normalized.append(octet);
            }
        } catch (NumberFormatException e) {
            return "";
        }
        return normalized.toString();
    }

    private static Result fail(String message) {
        return new Result(false, false, "", "", message);
    }
}
