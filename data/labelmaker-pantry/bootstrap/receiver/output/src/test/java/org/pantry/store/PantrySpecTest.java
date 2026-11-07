package org.pantry.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The operator's specification of pantry. */
class PantrySpecTest {

    @Test
    void aBoxNeedsContentsAndAFiveDigitPostcode() {
        assertThatThrownBy(() -> new Box(1, " ", "10115")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Box(1, "jars", "1011")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Box(0, "jars", "10115")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Box(1, "  jars ", "10115").contents()).isEqualTo("jars");
    }

    @Test
    void idsCountFromOneAndAreNotReused() {
        Pantry pantry = new Pantry();
        assertThat(pantry.add("jars", "10115").id()).isEqualTo(1);
        assertThat(pantry.add("tins", "20095").id()).isEqualTo(2);
        assertThat(pantry.remove(2)).isTrue();
        assertThat(pantry.remove(2)).isFalse();
        assertThat(pantry.add("flour", "20095").id()).isEqualTo(3);
    }

    @Test
    void findAndSearch() {
        Pantry pantry = new Pantry();
        pantry.add("Winter jackets", "10115");
        pantry.add("jam jars", "50667");
        assertThat(pantry.find(2)).map(Box::postcode).contains("50667");
        assertThat(pantry.find(9)).isEmpty();
        assertThat(pantry.search("JA")).extracting(Box::id).containsExactly(1, 2);
        assertThat(pantry.search(" ")).isEmpty();
    }

    @Test
    void boxesAreInOrderAndUnmodifiable() {
        Pantry pantry = new Pantry();
        pantry.add("jars", "10115");
        pantry.add("tins", "10115");
        assertThat(pantry.boxes()).extracting(Box::contents).containsExactly("jars", "tins");
        assertThatThrownBy(() -> pantry.boxes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
