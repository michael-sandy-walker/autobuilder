---
name: TTS — in-JVM neural synthesis preferred
description: For autobuilder narration, user wants a single-application offline neural TTS; rejected MaryTTS as outdated and inconvenient
type: feedback
originSessionId: 91428756-093e-4520-b302-87d23904c4ce
---
For autobuilder's graph.html narration, the user wants **offline neural TTS that runs inside the Spring Boot application** (no standalone server process, no separate binary the user has to launch). Size is not a concern.

**Why:** User originally said MaryTTS + effects was acceptable but changed course after seeing the reality — 5.2.1 is 10 years old, transitive deps (Jampack, JTok 1.9.3) aren't on Maven Central, and the practical install path was "run marytts-server.sh on the side," which they called inconvenient. The new bar is one process, modern voices.

**How to apply:** Default to a neural TTS that can be embedded in-JVM — either via JNI bindings to an ONNX-based engine (e.g. sherpa-onnx), or by bundling a native binary + voice model as classpath resources and extracting at startup. Avoid solutions that require the user to manage a separate server process or install anything outside `mvn`. Re-propose effects (reverb/delay/low-pass via Web Audio API) on top if the chosen voice is dry.
