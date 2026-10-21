package org.linekit.tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public final class LineTokenizer {

    private LineTokenizer() {
    }

    public static List<Token> tokenize(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }

        List<Token> tokens = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int i = 0;
        int n = line.length();

        while (i < n) {
            // Skip leading spaces
            while (i < n && line.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }

            // Find '=' in the pair (must be before any ';' that ends the pair)
            int eqIdx = -1;
            int pairEnd = i;
            while (pairEnd < n && line.charAt(pairEnd) != ';') {
                if (line.charAt(pairEnd) == '=') {
                    eqIdx = pairEnd;
                    break;
                }
                pairEnd++;
            }
            if (pairEnd < n && line.charAt(pairEnd) == ';') {
                // pairEnd is the ';' position
            } else {
                pairEnd = n;
            }

            if (eqIdx == -1) {
                throw new TokenizeException("missing '='", i);
            }

            // Extract name (trim spaces)
            String namePart = line.substring(i, eqIdx).trim();
            if (namePart.isEmpty() || !isValidName(namePart)) {
                int nameStart = i;
                while (nameStart < eqIdx && line.charAt(nameStart) == ' ') {
                    nameStart++;
                }
                throw new TokenizeException("invalid name", nameStart);
            }

            if (!seen.add(namePart)) {
                int nameStart = i;
                while (nameStart < eqIdx && line.charAt(nameStart) == ' ') {
                    nameStart++;
                }
                throw new TokenizeException("duplicate name", nameStart);
            }

            // Value starts after '='
            int valStart = eqIdx + 1;
            String value;

            // Check if value starts with a quote (after skipping spaces)
            int valTrimStart = valStart;
            while (valTrimStart < n && line.charAt(valTrimStart) == ' ') {
                valTrimStart++;
            }

            if (valTrimStart < n && line.charAt(valTrimStart) == '"') {
                // Quoted value: scan from after opening quote until unescaped '"'
                int quoteStartInLine = valTrimStart;
                int j = valTrimStart + 1;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;

                while (j < n) {
                    char c = line.charAt(j);
                    if (c == '\\' && j + 1 < n) {
                        char next = line.charAt(j + 1);
                        if (next == '"' || next == '\\') {
                            sb.append(next);
                            j += 2;
                            continue;
                        }
                    }
                    if (c == '"') {
                        closed = true;
                        j++;
                        break;
                    }
                    sb.append(c);
                    j++;
                }

                if (!closed) {
                    throw new TokenizeException("unterminated quote", quoteStartInLine);
                }

                // Check remaining chars after closing quote: only spaces allowed before ';' or end
                while (j < n && line.charAt(j) != ';') {
                    if (line.charAt(j) != ' ') {
                        throw new TokenizeException("after closing quote", j);
                    }
                    j++;
                }

                value = sb.toString();
                // j is now at ';' or n
                i = (j < n) ? j + 1 : n;
            } else {
                // Bare value: from valStart to next ';' or end, trim spaces
                int bareEnd = valStart;
                while (bareEnd < n && line.charAt(bareEnd) != ';') {
                    bareEnd++;
                }
                value = line.substring(valStart, bareEnd).trim();
                i = (bareEnd < n) ? bareEnd + 1 : n;
            }

            tokens.add(new Token(namePart, value));
        }

        return List.copyOf(tokens);
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }
}