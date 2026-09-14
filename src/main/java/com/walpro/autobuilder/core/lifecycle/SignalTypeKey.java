package com.walpro.autobuilder.core.lifecycle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * JSON map-key form of a {@link SignalType}: its bare name.
 *
 * <p>Without this, Jackson keys the request ledger by {@code SignalType.toString()} —
 * {@code SignalType[name=LOW_CONFIDENCE]} — and reads it back through the record's single-String
 * constructor, so one round trip turns the key into {@code SIGNALTYPE[NAME=LOW_CONFIDENCE]} and
 * every {@code open.get(BasicSignals.LOW_CONFIDENCE)} misses until the next tick re-keys the map
 * by each value's own kind. Harmless while contexts were only ever written, never read back; a
 * restored context (T4-DQ, 2026-09-14) is read back, and its open requests would be invisible for
 * exactly the first cycle after a rewind — the one cycle the restore exists to make faithful.
 *
 * <p>The deserializer also accepts the legacy wrapped forms, however deeply a file was
 * re-serialized, so a context written before this key form reads back with its requests intact.
 */
public final class SignalTypeKey {

    private SignalTypeKey() {}

    public static final class Serializer extends JsonSerializer<SignalType> {
        @Override
        public void serialize(SignalType value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeFieldName(value.name());
        }
    }

    public static final class Deserializer extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, DeserializationContext ctxt) {
            return SignalType.of(unwrap(key));
        }
    }

    /** {@code SignalType[name=SIGNALTYPE[NAME=X]]} to {@code X}; a bare name is returned as is. */
    static String unwrap(String key) {
        String s = key == null ? "" : key.trim();
        while (true) {
            String upper = s.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("SIGNALTYPE[NAME=") && s.endsWith("]")) {
                s = s.substring("SIGNALTYPE[NAME=".length(), s.length() - 1).trim();
            } else {
                return s;
            }
        }
    }
}
