---
name: feedback-read-the-reply-before-reporting-state
description: "Read the answer of every state-changing call (stop, return, start, decide) before telling the user what state things are in; a refused call in a background command went unread and the user found a \"running\" project."
metadata:
  node_type: memory
  type: feedback
  originSessionId: d74d867e-f52a-49f6-a267-65717906fe22
  modified: 2026-11-16T19:49:21.662Z
---

When a command changes state (home `/return`, `/stop`, `/start`, a decision, a kill), read its reply -- and confirm
the resulting state with a read (`/home/state`) -- before reporting "it's idle / stopped / back home". Do not bundle
a state change into a background command whose output nobody reads before the next message.

**Why:** 2026-11-16 (session 39), checking T5-BY I started `gradient-t1gq2` in ASK with no profile, then put
`/home/return` in front of a backgrounded `mvn test` and told the user the home app was idle. The return had been
REFUSED ("still running; it can be left once it has ended" -- ASK did not hold a project with a finished tree, and a
no-scheduler run must be stopped first, T1-GO). The user: "Why does it say gradient-t1gq2 is running ... but still
with the old graph and nothing happening?"

**How to apply:** state change in the foreground, reply printed, state read back; only then the report. Same family
as [[feedback-monitor-every-terminal]] (silence is not success) and the pgrep self-match traps in
[[project-run-lifecycle-runbook]].
