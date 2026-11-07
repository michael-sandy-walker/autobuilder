package org.pantry.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The boxes of a household, in the order they were added. */
public class Pantry {

    private final List<Box> boxes = new ArrayList<>();
    private int lastId;

    /** Adds a box and returns it; ids count from 1 and are never reused, even after a removal. */
    public Box add(String contents, String postcode) {
        Box box = new Box(lastId + 1, contents, postcode);
        lastId = box.id();
        boxes.add(box);
        return box;
    }

    public Optional<Box> find(int id) {
        return boxes.stream().filter(b -> b.id() == id).findFirst();
    }

    /** True when a box with that id was there and is now gone. */
    public boolean remove(int id) {
        return boxes.removeIf(b -> b.id() == id);
    }

    /** Every box, in the order added, as a list that cannot be modified. */
    public List<Box> boxes() {
        return List.copyOf(boxes);
    }

    /** The boxes whose contents mention the word, ignoring case, in the order added. */
    public List<Box> search(String word) {
        if (word == null || word.isBlank()) return List.of();
        String w = word.strip().toLowerCase(Locale.ROOT);
        return boxes.stream().filter(b -> b.contents().toLowerCase(Locale.ROOT).contains(w)).toList();
    }
}
