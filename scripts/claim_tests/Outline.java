package com.walpro.autobuilder.goal;

import java.nio.file.Files;
import java.nio.file.Path;

/** Prints DerivedTests.outline of each file named: the declarations without bodies, exactly as the loop shows them. */
public class Outline {
    public static void main(String[] args) throws Exception {
        for (String a : args) {
            String o = DerivedTests.outline(Files.readString(Path.of(a)));
            if (o != null) System.out.println("--- " + a + " ---\n" + o);
        }
    }
}
