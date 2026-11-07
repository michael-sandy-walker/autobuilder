package org.pantry.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WITHHELD (T4-EJ): the label capability adapted from labelmaker. The loop never sees this file; the yardstick lays
 * it over a copy of the tree it scores. Expected codes are labelmaker's own (its LabelerSpecTest and rule).
 */
class PantryLabelSpecTest {

    private static Pantry sixBoxes() {
        Pantry pantry = new Pantry();
        pantry.add("jars", "10115");
        pantry.add("tins", "20095");
        pantry.add("flour", "01067");
        pantry.add("rope", "60311");
        pantry.add("candles", "00005");
        pantry.add("maps", "00006");
        return pantry;
    }

    @Test
    void aBoxLabelIsLabelmakersCodeWithTheBoxIdAsRunningNumber() {
        Pantry pantry = sixBoxes();
        assertThat(pantry.labelOf(1)).contains("EA-00001-C");
        assertThat(pantry.labelOf(2)).contains("SO-00002-V");
        assertThat(pantry.labelOf(3)).contains("NO-00003-H");
        assertThat(pantry.labelOf(4)).contains("HA-00004-G");
        assertThat(pantry.labelOf(5)).contains("VA-00005-V");
        assertThat(pantry.labelOf(6)).contains("SU-00006-F");
    }

    @Test
    void noBoxNoLabel() {
        Pantry pantry = sixBoxes();
        assertThat(pantry.labelOf(9)).isEmpty();
        assertThat(pantry.remove(2)).isTrue();
        assertThat(pantry.labelOf(2)).isEmpty();
    }

    @Test
    void findByLabelFindsOnlyTheExactLabel() {
        Pantry pantry = sixBoxes();
        assertThat(pantry.findByLabel("SO-00002-V")).map(Box::contents).contains("tins");
        assertThat(pantry.findByLabel("SU-00006-F")).map(Box::id).contains(6);
        assertThat(pantry.findByLabel("SO-00002-A")).isEmpty();
        assertThat(pantry.findByLabel("EA-00009-C")).isEmpty();
        assertThat(pantry.findByLabel(null)).isEmpty();
    }

    @Test
    void aLaterBoxContinuesTheNumbering() {
        Pantry pantry = sixBoxes();
        pantry.remove(6);
        assertThat(pantry.add("nails", "50667").id()).isEqualTo(7);
        assertThat(pantry.labelOf(7)).contains("WE-00007-M");
    }
}
