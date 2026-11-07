package org.labelmaker.label;

public class Labeler {

    private static final String ALPHABET = "ABCDEFGHJKLMNPRTVWX";
    private static final int[] WEIGHTS = {7, 3, 9, 7, 3, 9, 7};

    private int runningNumber = 0;

    public Labeler() {
    }

    public static char checkChar(String body) {
        if (body == null || body.length() != 7) {
            throw new IllegalArgumentException("body must be exactly 7 characters");
        }
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            char c = body.charAt(i);
            int value;
            if (c >= '0' && c <= '9') {
                value = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                value = 10 + (c - 'A');
            } else {
                throw new IllegalArgumentException("body must contain only uppercase letters and digits");
            }
            sum += value * WEIGHTS[i];
        }
        return ALPHABET.charAt(sum % 19);
    }

    public String next(String postcode) {
        Zone zone = Zone.of(postcode);
        runningNumber++;
        String zonePrefix = zone.name().substring(0, 2);
        String digits = String.format("%05d", runningNumber);
        String body = zonePrefix + digits;
        char check = checkChar(body);
        return zonePrefix + "-" + digits + "-" + check;
    }

    public static boolean isValid(String code) {
        if (code == null || code.length() != 10) {
            return false;
        }
        if (code.charAt(2) != '-' || code.charAt(8) != '-') {
            return false;
        }
        String zonePrefix = code.substring(0, 2);
        String digits = code.substring(3, 8);
        char check = code.charAt(9);

        Zone zone = null;
        for (Zone z : Zone.values()) {
            if (z.name().startsWith(zonePrefix)) {
                zone = z;
                break;
            }
        }
        if (zone == null) {
            return false;
        }

        for (int i = 0; i < 5; i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        String body = zonePrefix + digits;
        return checkChar(body) == check;
    }
}