# Port review: criterion-status

- **Ticket toy case:** `data/criterion-status` (seed: change `SeedStatusSupport`, specification `SeedStatusSupportSpecTest`)
- **Loop run:** `2026-09-26_00-42-19` (`~/.autobuilder/run-archive/2026-09-26_00-42-19/artifact-src.tar.gz`)
- **Port:** commit `27459ebc`
- **State:** `ported-unreviewed` -- the operator decides; see Decision at the end.
- **Reference:** written blind in `/home/anugeshtu/.autobuilder/blind/criterion-status-2026-09-27_19-02-01` from the pristine module, the seed and the spec only. The writer was instructed not to read anything outside that directory; this machine does not enforce it.

The four versions are in `origin/`, `loop/`, `reference/`, `port/` beside this file.

## Measured (no opinion)

| version | suite | spec | lines | +added / -removed vs origin | origin comment lines removed | new methods | new literal tables |
|---|---|---|---|---|---|---|---|
| origin | 303 run, 274 failed | 0/274 | 37 | +0 / -0 | 0 | - | 0 |
| loop | 303 run, 0 failed | 274/274 | 80 | +44 / -1 | 0 | - | 0 |
| reference | 303 run, 0 failed | 274/274 | 67 | +31 / -1 | 0 | isSuccess | 0 |
| port | 303 run, 0 failed | 274/274 | 80 | +44 / -1 | 0 | - | 0 |

## Diffs

### origin -> loop

```diff
--- origin/SeedStatusSupport.java
+++ loop/SeedStatusSupport.java
@@ -1,3 +1,12 @@
 package com.walpro.autobuilder.core.build;
+
+import com.walpro.autobuilder.analysis.structure.Endpoint;
+import com.walpro.autobuilder.analysis.structure.EndpointPaths;
+import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
+
+import java.util.List;
+import java.util.Map;
+import java.util.Optional;
+import java.util.Set;
 
 /**
@@ -33,5 +42,39 @@
     /** The verdict for {@code criterion} under {@code specification}; see the class comment. */
     public static Verdict of(String criterion, String specification) {
-        throw new UnsupportedOperationException("not implemented");
+        if (criterion == null || criterion.isBlank() || specification == null || specification.isBlank()) {
+            return Verdict.NO_OPINION;
+        }
+
+        Optional<BehaviouralCriterionParser.Assertion> parsed = BehaviouralCriterionParser.parse(criterion);
+        if (parsed.isEmpty()) {
+            return Verdict.NO_OPINION;
+        }
+
+        BehaviouralCriterionParser.Assertion assertion = parsed.get();
+        Endpoint endpoint = new Endpoint(assertion.verb(), assertion.path());
+        int assertedStatus = assertion.expectedStatus();
+
+        Set<Endpoint> declared = EndpointPaths.declaredIn(specification);
+        if (!declared.contains(endpoint)) {
+            return Verdict.UNSUPPORTED;
+        }
+
+        Map<Endpoint, List<SpecEndpointBehaviourQuery.StatedBehaviour>> behaviours =
+                new SpecEndpointBehaviourQuery().statedBehaviours(specification);
+        List<SpecEndpointBehaviourQuery.StatedBehaviour> stated = behaviours.getOrDefault(endpoint, List.of());
+
+        if (assertedStatus >= 200 && assertedStatus <= 299) {
+            boolean anySuccessStated = stated.stream().anyMatch(b -> b.status() >= 200 && b.status() <= 299);
+            if (!anySuccessStated) {
+                return Verdict.SUPPORTED;
+            }
+            return stated.stream().anyMatch(b -> b.status() == assertedStatus)
+                    ? Verdict.SUPPORTED
+                    : Verdict.UNSUPPORTED;
+        }
+
+        return stated.stream().anyMatch(b -> b.status() == assertedStatus)
+                ? Verdict.SUPPORTED
+                : Verdict.UNSUPPORTED;
     }
-}
+}```
### origin -> reference

```diff
--- origin/SeedStatusSupport.java
+++ reference/SeedStatusSupport.java
@@ -1,3 +1,14 @@
 package com.walpro.autobuilder.core.build;
+
+import com.walpro.autobuilder.analysis.structure.Endpoint;
+import com.walpro.autobuilder.analysis.structure.EndpointPaths;
+import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
+import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery.StatedBehaviour;
+import com.walpro.autobuilder.core.build.BehaviouralCriterionParser.Assertion;
+
+import java.util.List;
+import java.util.Optional;
+import java.util.Set;
+import java.util.stream.Collectors;
 
 /**
@@ -33,5 +44,24 @@
     /** The verdict for {@code criterion} under {@code specification}; see the class comment. */
     public static Verdict of(String criterion, String specification) {
-        throw new UnsupportedOperationException("not implemented");
+        if (specification == null || specification.isBlank()) return Verdict.NO_OPINION;
+        Optional<Assertion> assertion = BehaviouralCriterionParser.parse(criterion);
+        if (assertion.isEmpty()) return Verdict.NO_OPINION;
+
+        Endpoint endpoint = new Endpoint(assertion.get().verb(), assertion.get().path());
+        if (!EndpointPaths.declaredIn(specification).contains(endpoint)) return Verdict.UNSUPPORTED;
+
+        int asserted = assertion.get().expectedStatus();
+        Set<Integer> stated = new SpecEndpointBehaviourQuery().statedBehaviours(specification)
+                .getOrDefault(endpoint, List.of()).stream()
+                .map(StatedBehaviour::status)
+                .collect(Collectors.toSet());
+        if (stated.contains(asserted)) return Verdict.SUPPORTED;
+        boolean statesASuccess = stated.stream().anyMatch(SeedStatusSupport::isSuccess);
+        return isSuccess(asserted) && !statesASuccess ? Verdict.SUPPORTED : Verdict.UNSUPPORTED;
+    }
+
+    /** The success range the rule distinguishes: 200-299. */
+    private static boolean isSuccess(int status) {
+        return status >= 200 && status <= 299;
     }
 }
```
### origin -> port

```diff
--- origin/SeedStatusSupport.java
+++ port/SeedStatusSupport.java
@@ -1,3 +1,12 @@
 package com.walpro.autobuilder.core.build;
+
+import com.walpro.autobuilder.analysis.structure.Endpoint;
+import com.walpro.autobuilder.analysis.structure.EndpointPaths;
+import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
+
+import java.util.List;
+import java.util.Map;
+import java.util.Optional;
+import java.util.Set;
 
 /**
@@ -33,5 +42,39 @@
     /** The verdict for {@code criterion} under {@code specification}; see the class comment. */
     public static Verdict of(String criterion, String specification) {
-        throw new UnsupportedOperationException("not implemented");
+        if (criterion == null || criterion.isBlank() || specification == null || specification.isBlank()) {
+            return Verdict.NO_OPINION;
+        }
+
+        Optional<BehaviouralCriterionParser.Assertion> parsed = BehaviouralCriterionParser.parse(criterion);
+        if (parsed.isEmpty()) {
+            return Verdict.NO_OPINION;
+        }
+
+        BehaviouralCriterionParser.Assertion assertion = parsed.get();
+        Endpoint endpoint = new Endpoint(assertion.verb(), assertion.path());
+        int assertedStatus = assertion.expectedStatus();
+
+        Set<Endpoint> declared = EndpointPaths.declaredIn(specification);
+        if (!declared.contains(endpoint)) {
+            return Verdict.UNSUPPORTED;
+        }
+
+        Map<Endpoint, List<SpecEndpointBehaviourQuery.StatedBehaviour>> behaviours =
+                new SpecEndpointBehaviourQuery().statedBehaviours(specification);
+        List<SpecEndpointBehaviourQuery.StatedBehaviour> stated = behaviours.getOrDefault(endpoint, List.of());
+
+        if (assertedStatus >= 200 && assertedStatus <= 299) {
+            boolean anySuccessStated = stated.stream().anyMatch(b -> b.status() >= 200 && b.status() <= 299);
+            if (!anySuccessStated) {
+                return Verdict.SUPPORTED;
+            }
+            return stated.stream().anyMatch(b -> b.status() == assertedStatus)
+                    ? Verdict.SUPPORTED
+                    : Verdict.UNSUPPORTED;
+        }
+
+        return stated.stream().anyMatch(b -> b.status() == assertedStatus)
+                ? Verdict.SUPPORTED
+                : Verdict.UNSUPPORTED;
     }
 }
```
### loop -> port (what the port review changed in the loop's code)

```diff
--- loop/SeedStatusSupport.java
+++ port/SeedStatusSupport.java
@@ -78,3 +78,3 @@
                 : Verdict.UNSUPPORTED;
     }
-}+}
```

## Assessment: better / worse

In `ASSESSMENT.md` beside this file, written by an independent comparer (a fresh agent) that had no hand in the loop's code, the reference or the port. An opinion, kept apart from the measurements above.

## Decision (the operator)

- [ ] approve the port as it is
- [ ] approve with changes: ...
- [ ] decline (revert or do not port)
