package org.quillfixture.ink;

/** A fixture class of an invented library, read by DependencyApiTest through a jar. */
public class InkNib {
    public int width() { return 1; }
    public InkNib widen(int by) { return this; }
    public boolean isInkNib() { return true; }
    public DryInkNib asDryInkNib() { return null; }
    static void hidden() { }
}
