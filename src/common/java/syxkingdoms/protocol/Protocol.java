package syxkingdoms.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Protocol {
    public static final int VERSION = 6;
    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final int MAX_TEXT_BYTES = 1024;
    public static final int MAX_CITIES_PER_STATE = 2048;
    public static final int MAX_COMMANDS_PER_UPDATE = 8;
    public static final int MAX_EVENTS_PER_SYNC = 128;
    public static final int MAX_RELATIONS_PER_SYNC = 2048;
    public static final int MAX_ARMIES_PER_SYNC = 256;
    public static final int MAX_DELIVERIES_PER_SYNC = 64;
    public static final int MAX_MARKET_OFFERS_PER_SYNC = 64;
    public static final int MAX_CRESTS_PER_SYNC = 128;

    private Protocol() {
    }

    public static String frame(String type, String... fields) {
        if (type == null || !type.matches("[A-Z_]{2,24}")) {
            throw new IllegalArgumentException("Invalid frame type");
        }
        StringBuilder result = new StringBuilder(type);
        for (String field : fields) {
            if (field == null || field.indexOf('\t') >= 0 || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Invalid frame field");
            }
            result.append('\t').append(field);
        }
        return result.toString();
    }

    public static String[] split(String line, String expectedType, int expectedFields) throws ProtocolException {
        if (line == null) {
            throw new ProtocolException("CONNECTION_CLOSED", "Connection closed before " + expectedType);
        }
        String[] fields = line.split("\\t", -1);
        if (fields.length != expectedFields || !expectedType.equals(fields[0])) {
            throw new ProtocolException("BAD_FRAME", "Expected " + expectedType + " with " + expectedFields + " fields");
        }
        return fields;
    }

    public static String encodeText(String value) {
        if (value == null) {
            value = "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Text exceeds " + MAX_TEXT_BYTES + " UTF-8 bytes");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String decodeText(String encoded, String fieldName) throws ProtocolException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new ProtocolException("TEXT_TOO_LONG", fieldName + " is too long");
            }
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (value.indexOf('\u0000') >= 0) {
                throw new ProtocolException("BAD_TEXT", fieldName + " contains a null character");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("BAD_TEXT", fieldName + " is not valid base64url", e);
        }
    }

    public static int parseInt(String value, String fieldName, int min, int max) throws ProtocolException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new ProtocolException("BAD_NUMBER", fieldName + " is outside its allowed range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ProtocolException("BAD_NUMBER", fieldName + " is not an integer", e);
        }
    }

    public static long parseLong(String value, String fieldName, long min, long max) throws ProtocolException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) {
                throw new ProtocolException("BAD_NUMBER", fieldName + " is outside its allowed range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ProtocolException("BAD_NUMBER", fieldName + " is not an integer", e);
        }
    }

    public static double parseDouble(String value, String fieldName, double min, double max) throws ProtocolException {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
                throw new ProtocolException("BAD_NUMBER", fieldName + " is outside its allowed range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ProtocolException("BAD_NUMBER", fieldName + " is not a number", e);
        }
    }
}
