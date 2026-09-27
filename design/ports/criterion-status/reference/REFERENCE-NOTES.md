# Reference notes: SeedStatusSupport.of

## What changed
Only `src/main/java/com/walpro/autobuilder/core/build/SeedStatusSupport.java`: implemented `of`, added
one private `isSuccess` helper for the 200-299 range. Class comment, enum and constructor untouched.

- Criterion read by `BehaviouralCriterionParser.parse` (it already returns empty for null/blank and for
  anything that is not one endpoint with one status) -> `NO_OPINION`; null/blank specification -> `NO_OPINION`.
- The parsed verb and normalized path form an `Endpoint`; if `EndpointPaths.declaredIn(spec)` does not
  contain it -> `UNSUPPORTED`.
- Stated statuses come from `new SpecEndpointBehaviourQuery().statedBehaviours(spec)` for that endpoint
  (absent key = none stated). Asserted status stated -> `SUPPORTED`; otherwise `SUPPORTED` only when it is
  a success status and no success status is stated; else `UNSUPPORTED`. This single ordering covers both
  the in-range and out-of-range branches of the rule.

## Ambiguities noticed
- "States for that endpoint" is taken to be whatever `statedBehaviours` returns, which includes
  cross-cutting (document-wide) statuses it binds to endpoints whose clause states an input constraint.
  The seed's wording does not say whether those count; delegating to the existing reader was the instruction.
- `SpecEndpointBehaviourQuery` is an instance class (a bean in the parent app); it is stateless, so a
  fresh instance per call is used rather than adding a static field or changing its shape.
- Endpoint identity is exact on verb + normalized path (query string dropped by both readers); a
  wildcard verb `*` is never produced by these readers, so it is not special-cased.
