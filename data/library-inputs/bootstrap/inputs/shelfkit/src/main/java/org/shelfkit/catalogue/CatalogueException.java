package org.shelfkit.catalogue;

/** A value the catalogue will not hold; the message says why. */
public class CatalogueException extends RuntimeException {

    public CatalogueException(String message) {
        super(message);
    }
}
