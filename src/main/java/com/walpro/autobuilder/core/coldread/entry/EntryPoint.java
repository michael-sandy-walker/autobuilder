package com.walpro.autobuilder.core.coldread.entry;

/**
 * T4-EK: one way into a project, and the method that implements it.
 *
 * @param kind          how it is exercised
 * @param label         what a person reads: a route ("POST /books"), a program ("program Main"), a method
 *                      ("Labeler.next(String)")
 * @param type          the type declaring the implementation, by its nested name -- the FEATURE it belongs to
 * @param qualifiedType the same type with its package
 * @param method        the implementing method's name
 */
public record EntryPoint(EntryKind kind, String label, String type, String qualifiedType, String method) {

    /** The implementing method's id in a {@code CapabilityCut} inventory, which names a type simply when it can. */
    public String memberId(java.util.Map<String, ?> inventory) {
        String simple = type + "." + method, qualified = qualifiedType + "." + method;
        if (inventory.containsKey(simple)) return simple;
        return inventory.containsKey(qualified) ? qualified : null;
    }
}
