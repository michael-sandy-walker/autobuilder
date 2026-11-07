package org.labelmaker.label;

public enum Zone {
    NORTH, EAST, SOUTH, WEST, HARBOR, VALLEY, SUMMIT;

    public static Zone of(String postcode) {
        if (postcode == null || postcode.length() != 5) {
            throw new IllegalArgumentException("postcode must be exactly five digits");
        }
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            char c = postcode.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("postcode must contain only digits");
            }
            sum += c - '0';
        }
        return values()[sum % 7];
    }
}