package org.pantry.store;

/** A storage box: its number in the pantry, what it holds, and the postcode of the place it is kept. */
public record Box(int id, String contents, String postcode) {

    public Box {
        if (id < 1) throw new IllegalArgumentException("a box id counts from 1, got " + id);
        if (contents == null || contents.isBlank()) throw new IllegalArgumentException("a box must say what it holds");
        if (postcode == null || !postcode.matches("[0-9]{5}")) {
            throw new IllegalArgumentException("a postcode is five digits, got " + postcode);
        }
        contents = contents.strip();
    }
}
