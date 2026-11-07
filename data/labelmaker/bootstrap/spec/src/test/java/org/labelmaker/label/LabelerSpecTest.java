package org.labelmaker.label;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The operator's specification of labelmaker: what Zone and Labeler must return. */
class LabelerSpecTest {

    @Test
    void zonesAreTheSevenInOrder() {
        assertThat(Zone.values()).containsExactly(
                Zone.NORTH, Zone.EAST, Zone.SOUTH, Zone.WEST, Zone.HARBOR, Zone.VALLEY, Zone.SUMMIT);
    }

    @Test
    void zoneIsTheDigitSumModSeven() {
        assertThat(Zone.of("00000")).isEqualTo(Zone.NORTH);
        assertThat(Zone.of("01067")).isEqualTo(Zone.NORTH);
        assertThat(Zone.of("10115")).isEqualTo(Zone.EAST);
        assertThat(Zone.of("20095")).isEqualTo(Zone.SOUTH);
        assertThat(Zone.of("50667")).isEqualTo(Zone.WEST);
        assertThat(Zone.of("60311")).isEqualTo(Zone.HARBOR);
        assertThat(Zone.of("00005")).isEqualTo(Zone.VALLEY);
        assertThat(Zone.of("00006")).isEqualTo(Zone.SUMMIT);
        assertThat(Zone.of("99999")).isEqualTo(Zone.WEST);
    }

    @Test
    void zoneRefusesAnythingButFiveDigits() {
        for (String bad : new String[] {null, "", "1011", "101155", "1O115", " 0115", "-1011"}) {
            assertThatThrownBy(() -> Zone.of(bad)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void checkCharFollowsTheWeightedRule() {
        assertThat(Labeler.checkChar("NO00001")).isEqualTo('N');
        assertThat(Labeler.checkChar("EA00001")).isEqualTo('C');
        assertThat(Labeler.checkChar("WE12345")).isEqualTo('J');
        assertThat(Labeler.checkChar("SU99999")).isEqualTo('N');
        assertThat(Labeler.checkChar("VA00042")).isEqualTo('N');
        assertThat(Labeler.checkChar("HA00099")).isEqualTo('J');
        assertThat(Labeler.checkChar("SO00042")).isEqualTo('R');
    }

    @Test
    void nextNumbersFromOneAcrossPostcodes() {
        Labeler labeler = new Labeler();
        assertThat(labeler.next("10115")).isEqualTo("EA-00001-C");
        assertThat(labeler.next("20095")).isEqualTo("SO-00002-V");
        assertThat(labeler.next("01067")).isEqualTo("NO-00003-H");
    }

    @Test
    void aRefusedPostcodeUsesUpNoNumber() {
        Labeler labeler = new Labeler();
        assertThat(labeler.next("50667")).isEqualTo("WE-00001-H");
        assertThat(labeler.next("50667")).isEqualTo("WE-00002-R");
        assertThat(labeler.next("50667")).isEqualTo("WE-00003-C");
        assertThatThrownBy(() -> labeler.next("1011")).isInstanceOf(IllegalArgumentException.class);
        assertThat(labeler.next("60311")).isEqualTo("HA-00004-G");
        assertThat(labeler.next("00005")).isEqualTo("VA-00005-V");
        assertThat(labeler.next("00006")).isEqualTo("SU-00006-F");
    }

    @Test
    void eachLabelerCountsOnItsOwn() {
        Labeler first = new Labeler();
        first.next("10115");
        assertThat(new Labeler().next("10115")).isEqualTo("EA-00001-C");
    }

    @Test
    void isValidAcceptsOnlyCorrectCodes() {
        assertThat(Labeler.isValid("NO-00001-N")).isTrue();
        assertThat(Labeler.isValid("EA-12345-D")).isTrue();
        assertThat(Labeler.isValid("HA-00042-K")).isTrue();
        assertThat(Labeler.isValid("NO-00001-A")).isFalse();
        assertThat(Labeler.isValid("XX-00001-N")).isFalse();
        assertThat(Labeler.isValid("NO-0001-N")).isFalse();
        assertThat(Labeler.isValid("NO00001N")).isFalse();
        assertThat(Labeler.isValid("no-00001-n")).isFalse();
        assertThat(Labeler.isValid("")).isFalse();
        assertThat(Labeler.isValid(null)).isFalse();
    }
}
