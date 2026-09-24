package com.walpro.autobuilder.protocol.evolution;

/**
 * T4-CQ: tells the evolution agent where the application's routes are actually answered, when
 * its own configuration serves them under a prefix the code never mentions.
 *
 * <p>Found on the first adopted project. Its routes are declared as {@code /basic/add} and served
 * at {@code /calculator/basic/add}; the loop wrote tests that sent the prefixed path through an
 * in-process test client, which bypasses the server that applies the prefix, and all four failed
 * with 404 -- 145 refused closures until the deadline. The cold read had stated the rule in so
 * many words, and nothing carried it to the agent writing the tests.
 *
 * <p>Two sentences and no more: the fact (declared here, served there) and the one consequence
 * that decides a test's path. It fires only where a prefix is configured, so every other target's
 * prompt is unchanged.
 */
public final class ServedPrefixPromptBlock {

    private ServedPrefixPromptBlock() {}

    /** The block for {@code prefix}, or null when the tree declares none. */
    public static String render(String prefix) {
        if (prefix == null || prefix.isBlank()) return null;
        return "--- HOW THIS APPLICATION IS SERVED (read from its own configuration) ---\n"
                + "Routes are declared in the code WITHOUT the prefix " + prefix + " and answered over HTTP "
                + "UNDER it: a route declared as /x answers at " + prefix + "/x.\n"
                + "A request that goes through a running server needs the prefix; a test that calls the "
                + "application in-process, without starting a server, must use the declared path WITHOUT it.\n"
                + "--- END HOW THIS APPLICATION IS SERVED ---";
    }

    /** {@code prompt} with the block appended, or unchanged when there is none. */
    public static String appendTo(String prompt, String prefix) {
        String block = render(prefix);
        if (block == null || prompt == null) return prompt;
        return prompt + "\n\n" + block + "\n";
    }
}
