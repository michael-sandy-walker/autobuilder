---
name: feedback-escalate-unrecoverable-stagnation
description: "User steer 2026-07-02: don't let runs sit in long stagnation; if timeouts recur periodically in an obviously unrecoverable pattern, detect it early and INFORM the user instead of waiting."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 644ecb1e-2601-4fc0-baa0-9f5b02a95e5e
---

During run monitoring, do not tolerate long stagnation windows. If LLM timeouts (or any failure) recur *periodically* in a pattern the system obviously cannot recover from (e.g. run 4's 2-hour reviewer-timeout livelock, 93 timeouts at a fixed ~2-min cadence), that is detectable early — and the user wants to be informed, not to discover it hours later.

**Why:** run 4 (2026-07-02) burned 2+ hours in a self-sustaining timeout loop that was mechanically obvious after the first few periods; my watcher only matched terminal markers and silence, so the repeating-failure pattern went unflagged.

**How to apply:**
- Watcher scripts must include a repeating-failure trigger (e.g. ≥N identical timeout/error lines within a window, or M consecutive closure deferrals with zero merges) alongside terminal markers and silence.
- On trigger: wake, diagnose briefly, and notify the user (PushNotification if they are away) with the pattern and whether the system can recover on its own.
- Framework-side: prefer mechanisms that make the loop itself detect and react to periodic external-dependency failure (the LLM circuit breaker is the model — T1-FD extends it to REST paths + backoff), rather than relying on my monitoring alone.
