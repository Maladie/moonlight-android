package com.limelight.console;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Wake-compatible magic packet sender; construction is pure and unit tested. */
final class WakeOnLanSender {
    boolean send(String macAddress) {
        byte[] payload = magicPacket(macAddress);
        if (payload == null) return false;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            Set<InetAddress> destinations = new LinkedHashSet<>();
            destinations.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface network :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) destinations.add(address.getBroadcast());
                }
            }
            for (InetAddress destination : destinations) {
                socket.send(new DatagramPacket(payload, payload.length, destination, 9));
            }
            return true;
        }
        catch (Exception unavailable) {
            return false;
        }
    }

    static byte[] magicPacket(String value) {
        byte[] mac = parseMac(value);
        if (mac == null) return null;
        byte[] payload = new byte[6 + 16 * mac.length];
        for (int index = 0; index < 6; index++) payload[index] = (byte) 0xFF;
        for (int index = 6; index < payload.length; index += mac.length) {
            System.arraycopy(mac, 0, payload, index, mac.length);
        }
        return payload;
    }

    static byte[] parseMac(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split("[:-]");
        if (parts.length != 6) return null;
        byte[] result = new byte[6];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].length() != 2) return null;
                result[index] = (byte) Integer.parseInt(parts[index], 16);
            }
            return result;
        }
        catch (NumberFormatException invalid) {
            return null;
        }
    }
}
