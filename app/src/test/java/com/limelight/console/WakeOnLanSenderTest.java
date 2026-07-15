package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WakeOnLanSenderTest {
    @Test public void magicPacketHasHeaderAndSixteenMacCopies() {
        byte[] packet = WakeOnLanSender.magicPacket("00:11:22:33:44:55");

        assertEquals(102, packet.length);
        for (int index = 0; index < 6; index++) assertEquals((byte) 0xFF, packet[index]);
        byte[] expectedMac = new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55};
        for (int copy = 0; copy < 16; copy++) {
            byte[] actual = new byte[6];
            System.arraycopy(packet, 6 + copy * 6, actual, 0, 6);
            assertArrayEquals(expectedMac, actual);
        }
    }

    @Test public void parserAcceptsWakeSeparatorsAndRejectsMalformedValues() {
        assertArrayEquals(WakeOnLanSender.parseMac("AA:BB:CC:DD:EE:FF"),
                WakeOnLanSender.parseMac("AA-BB-CC-DD-EE-FF"));
        assertNull(WakeOnLanSender.magicPacket(null));
        assertNull(WakeOnLanSender.magicPacket("AA:BB:CC"));
        assertNull(WakeOnLanSender.magicPacket("GG:BB:CC:DD:EE:FF"));
    }
}
