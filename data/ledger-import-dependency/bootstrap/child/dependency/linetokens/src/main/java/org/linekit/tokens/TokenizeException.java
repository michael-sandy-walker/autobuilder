package org.linekit.tokens;

public class TokenizeException extends IllegalArgumentException {

    private final int position;

    public TokenizeException(String message, int position) {
        super(message);
        this.position = position;
    }

    public int position() {
        return position;
    }
}