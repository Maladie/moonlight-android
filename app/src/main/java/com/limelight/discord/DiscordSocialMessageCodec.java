package com.limelight.discord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Pure RFC 4648 codec for the versioned DM JNI records; safe in local JVM unit tests. */
final class DiscordSocialMessageCodec {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private DiscordSocialMessageCodec() { }

    static String encodeUtf8(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(((bytes.length + 2) / 3) * 4);
        for (int index = 0; index < bytes.length; index += 3) {
            int first = bytes[index] & 0xff;
            boolean secondPresent = index + 1 < bytes.length;
            boolean thirdPresent = index + 2 < bytes.length;
            int second = secondPresent ? bytes[index + 1] & 0xff : 0;
            int third = thirdPresent ? bytes[index + 2] & 0xff : 0;
            result.append(ALPHABET[first >>> 2]);
            result.append(ALPHABET[((first & 3) << 4) | (second >>> 4)]);
            result.append(secondPresent ? ALPHABET[((second & 15) << 2) | (third >>> 6)] : '=');
            result.append(thirdPresent ? ALPHABET[third & 63] : '=');
        }
        return result.toString();
    }

    static String decodeUtf8(String value) {
        if (value == null || (value.length() & 3) != 0) throw new IllegalArgumentException();
        ByteArrayOutputStream result = new ByteArrayOutputStream((value.length() / 4) * 3);
        for (int index = 0; index < value.length(); index += 4) {
            int a = decode(value.charAt(index));
            int b = decode(value.charAt(index + 1));
            char c = value.charAt(index + 2);
            char d = value.charAt(index + 3);
            boolean cPadding = c == '=';
            boolean dPadding = d == '=';
            if (a < 0 || b < 0 || (cPadding && !dPadding) || ((cPadding || dPadding)
                    && index + 4 != value.length())) throw new IllegalArgumentException();
            int cValue = cPadding ? 0 : decode(c);
            int dValue = dPadding ? 0 : decode(d);
            if (cValue < 0 || dValue < 0 || (cPadding && (b & 15) != 0)
                    || (dPadding && !cPadding && (cValue & 3) != 0)) throw new IllegalArgumentException();
            result.write((a << 2) | (b >>> 4));
            if (!cPadding) result.write(((b & 15) << 4) | (cValue >>> 2));
            if (!dPadding) result.write(((cValue & 3) << 6) | dValue);
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int decode(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A';
        if (value >= 'a' && value <= 'z') return value - 'a' + 26;
        if (value >= '0' && value <= '9') return value - '0' + 52;
        if (value == '+') return 62;
        if (value == '/') return 63;
        return -1;
    }
}
