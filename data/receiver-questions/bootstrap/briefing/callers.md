# Where StuckQuestions is used outside the module

## com/walpro/autobuilder/core/build/GrindDetector.java

```java
                Set<String> types = new java.util.LinkedHashSet<>();
                while (c.find()) types.add(c.group(1));
                for (String type : types) {
                    for (String content : candidate.values()) {
                        List<String> declared = content == null ? List.of() : StuckQuestions.constructorsIn(content, type);
                        if (declared.isEmpty()) continue;
                        declared.forEach(d -> out.add("declared-by-change=" + d));
                        break;
                    }
```

## com/walpro/autobuilder/core/stuck/StuckDiagnosis.java

```java
package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.build.StuckQuestions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
    ...

    static Asked declared(String signature, Authorities a) {
        String question = "What does the code declare for the name this failure is about?";
        String authority = "structure: the current tree and the adopted original";
        Optional<StuckQuestions.Answer> now = StuckQuestions.answer(signature, a.currentSources());
        Optional<StuckQuestions.Answer> adopted = StuckQuestions.answer(signature, a.adoptedSources());
        if (now.isEmpty() && adopted.isEmpty()) return new Asked(question, authority, null, false);
        StringBuilder sb = new StringBuilder();
        String meant = meantByImports(signature, now.or(() -> adopted).get(), a);
        if (now.isPresent()) {
    ...
     * java.awt.List) is ambiguous only until one asks which one the code imports. First the failing
     * file itself, as adopted and then as it stands; else the rest of the project, counted. Null when
     * the name is not ambiguous or no import decides it.
     */
    static String meantByImports(String signature, StuckQuestions.Answer ans, Authorities a) {
        if (ans.kind() != StuckQuestions.Kind.SYMBOL || ans.declared().size() < 2) return null;
        List<String> qualified = new ArrayList<>();
        for (String d : ans.declared()) {
            String name = d.replaceAll("\\s*\\(.*$", "");
            if (name.contains(".") && Character.isLowerCase(name.charAt(0))) qualified.add(name);
    ...

    private static List<String> constructorsInTree(String type, Map<String, String> tree) {
        for (String content : tree.values()) {
            if (content == null || !content.contains(type)) continue;
            List<String> c = StuckQuestions.constructorsIn(content, type);
            if (!c.isEmpty()) return c;
        }
        return List.of();
    }
    ...
        Asked asked = a.observer().observe(signature, evidence == null ? List.of() : evidence);
        return asked == null ? new Asked("Which fact would let this move succeed?", "observation", null, false) : asked;
    }

    static String render(StuckQuestions.Answer ans) {
        String list = String.join("; ", ans.declared());
        return switch (ans.kind()) {
            case MEMBERS -> ans.type() + " declares: " + list + "."
                    + (ans.meant() != null ? " It has no " + ans.member() + "(); the member that name means is " + ans.meant() + "."
```

