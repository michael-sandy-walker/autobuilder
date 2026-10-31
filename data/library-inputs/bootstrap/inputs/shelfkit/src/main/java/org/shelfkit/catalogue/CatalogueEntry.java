package org.shelfkit.catalogue;

/** One title in the catalogue. */
public record CatalogueEntry(Isbn isbn, String title) {

    public static final int MAX_TITLE = 200;

    public CatalogueEntry {
        if (isbn == null) {
            throw new CatalogueException("an entry needs an ISBN");
        }
        if (title == null || title.isBlank()) {
            throw new CatalogueException("an entry needs a title");
        }
        if (title.length() > MAX_TITLE) {
            throw new CatalogueException("a title is at most " + MAX_TITLE + " characters");
        }
    }
}
