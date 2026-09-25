package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalTypeOfTest {

    @Test
    void testOfReturnsSignalTypeWithName() {
        SignalType st = SignalType.of("mySignal");
        // Observed: SignalType.of("mySignal") returns name "MYSIGNAL" (uppercased)
        assertEquals("MYSIGNAL", st.name());
    }
}