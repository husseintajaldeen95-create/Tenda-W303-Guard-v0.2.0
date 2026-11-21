package com.tenda.w303guard;

import java.util.Arrays;
import java.util.Collections;

/** Lightweight JVM regression tests; run with tests/run_ip_reservation_tests.sh. */
public final class IpReservationValidatorTest {
    private static int passed;

    public static void main(String[] args) {
        acceptsLastOctetAndSameMacLease();
        rejectsIpUsedByAnotherDhcpDevice();
        rejectsIpReservedForAnotherMac();
        rejectsSecondReservationForSameMac();
        identifiesExactExistingReservation();
        rejectsRouterIpAndForeignSubnet();
        System.out.println("IP reservation validation tests: " + passed + " passed.");
    }

    private static void acceptsLastOctetAndSameMacLease() {
        IpReservationValidator.Result r = IpReservationValidator.validate("104", "4C:24:CE:65:F4:3A", "192.168.0.1",
                Arrays.asList(new IpReservationValidator.Entry("192.168.0.104", "4C:24:CE:65:F4:3A")),
                Collections.<IpReservationValidator.Entry>emptyList());
        check(r.allowed && "192.168.0.104".equals(r.ip), "same device may reserve its current lease");
    }

    private static void rejectsIpUsedByAnotherDhcpDevice() {
        IpReservationValidator.Result r = IpReservationValidator.validate("104", "4C:24:CE:65:F4:3A", "192.168.0.1",
                Arrays.asList(new IpReservationValidator.Entry("192.168.0.104", "E0:80:6B:21:41:06")),
                Collections.<IpReservationValidator.Entry>emptyList());
        check(!r.allowed && r.message.contains("مستخدم حالياً"), "reject DHCP IP used by another MAC");
    }

    private static void rejectsIpReservedForAnotherMac() {
        IpReservationValidator.Result r = IpReservationValidator.validate("104", "4C:24:CE:65:F4:3A", "192.168.0.1",
                Collections.<IpReservationValidator.Entry>emptyList(),
                Arrays.asList(new IpReservationValidator.Entry("192.168.0.104", "E0:80:6B:21:41:06")));
        check(!r.allowed && r.message.contains("محجوز مسبقاً"), "reject duplicate static IP");
    }

    private static void rejectsSecondReservationForSameMac() {
        IpReservationValidator.Result r = IpReservationValidator.validate("110", "4C:24:CE:65:F4:3A", "192.168.0.1",
                Collections.<IpReservationValidator.Entry>emptyList(),
                Arrays.asList(new IpReservationValidator.Entry("192.168.0.104", "4C:24:CE:65:F4:3A")));
        check(!r.allowed && r.message.contains("حجز ثابت آخر"), "reject multiple reservations for same MAC");
    }

    private static void identifiesExactExistingReservation() {
        IpReservationValidator.Result r = IpReservationValidator.validate("192.168.0.104", "4C:24:CE:65:F4:3A", "192.168.0.1",
                Collections.<IpReservationValidator.Entry>emptyList(),
                Arrays.asList(new IpReservationValidator.Entry("192.168.0.104", "4C:24:CE:65:F4:3A")));
        check(!r.allowed && r.alreadyReserved, "do not create an exact duplicate reservation");
    }

    private static void rejectsRouterIpAndForeignSubnet() {
        IpReservationValidator.Result router = IpReservationValidator.validate("1", "4C:24:CE:65:F4:3A", "192.168.0.1", null, null);
        IpReservationValidator.Result foreign = IpReservationValidator.validate("192.168.1.104", "4C:24:CE:65:F4:3A", "192.168.0.1", null, null);
        check(!router.allowed && !foreign.allowed, "reject router IP and foreign subnet");
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("PASS: " + name);
    }
}
