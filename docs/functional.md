# Autobuilder -- functional documentation

What Autobuilder does and how to use it on a project of your own: what to give it, how to start it, how to
follow a run, and how to read what it produced. How it works inside is in [technical.md](technical.md).

---

## 1. What Autobuilder is for

You give Autobuilder a **specification** -- what a project should do, in plain prose -- and a place to work. It
then builds or changes the project toward that specification, one cycle at a time: it plans the work into
subgoals, proposes a change for one of them, checks the change (it must compile, keep what it must keep, and
satisfy the subgoal's criteria), merges it, and moves on. It stops when every requirement of the specification
is met and the project passes its own checks -- or when it can show it is not getting further.

It is meant to run unattended against a **local** language model (an OpenAI-compatible endpoint such as Ollama).
Everything it decides and every change it makes is kept: in a structured event log, in the run's goal state, and
in a Neo4j graph across runs.

It works on **Java projects built with Maven** today; several checks (compiling, running the tests, booting the
artifact, tracing a failing test) rely on that.

---

## 2. What you need

- Java 21 and Maven, on the machine that runs Autobuilder.
- Neo4j 5 (`docker run -d --name neo4j -p7474:7474 -p7687:7687 -e NEO4J_AUTH=neo4j/your_password neo4j:5`), with
  its credentials in `spring.data.neo4j.*`.
- A local model behind an OpenAI-compatible API: `llm.api.url`, `llm.api.model` (and `llm.api.key` if the
  endpoint wants one). Set `llm.api.context-window-tokens` to the window the model is actually served with.
- A **target directory** for your project, under `data/<name>/` (see below).

Build Autobuilder with `mvn clean install`; it runs as `java -jar target/autobuilder-*.jar` and listens on port
8081 (`server.port`).

---

## 3. Preparing your project: the target directory

Everything Autobuilder knows about your project lives in one directory, e.g. `data/my-service/`. You prepare
`bootstrap/`; Autobuilder creates the rest.

```
data/my-service/
  bootstrap/
    seed.txt                 your specification (required)
    seed.obligations.txt     the specification as checkable obligations (generated if absent; editable)
    protected-paths.txt      what Autobuilder may not touch (optional)
  output/                    your project's code -- Autobuilder works only here
```

Point Autobuilder at it with `file.base-path=data/my-service` (or the `FILE_BASE_PATH` environment variable).

### 3.1 The specification: `bootstrap/seed.txt`

Write what the project must do, the way you would brief a developer: the behaviour, the interfaces, the rules,
the constraints. Be concrete where it matters -- a status code, a file format, an exit status, a field that is
required -- because what the specification states is what Autobuilder will check, and what it does not state it
cannot hold the result to.

A short example (from the `firestarter` toy case):

```
Evolve the Java 21 Spring Boot application into a library book-lending REST API with three resource types.

Books: POST /books (create; title required, max 120 chars; isbn required, must match \d{13}), GET /books (list all),
 GET /books/{id} (single; 404 if absent).
...
All data stored in in-memory collections (no database). Invalid input returns HTTP 400 with JSON body {"message":
"<reason>"}. Business-rule violations use the codes above with the same JSON shape.
```

If you want an **existing** project changed (rather than a new one built), say so in the seed and say what must
stay as it is -- for example "Change only `ParameterizedCaseInput` and add the new class beside it; keep every
other class exactly as it is; do not modify anything under `src/test`." See section 4.

### 3.2 The obligations: `bootstrap/seed.obligations.txt`

On the first run Autobuilder splits your specification into **obligations**, one per line, and writes them here.
Read this file: it is what the run will be held to at the end. You may edit it -- your edit is kept and reported
as yours. Two markers matter:

- `rule:` at the start of a line marks *how* the work is done (e.g. `rule: Do not modify anything under src/test`).
  It guides the work but is never something the end result is checked for.
- `gate: tests` marks a line whose whole claim is that the project's own tests pass (e.g.
  `gate: tests The whole test suite passes.`). It is then answered by actually running the tests, not by a
  model reading your code. Mark such lines yourself if the generated file left them unmarked.

### 3.3 What may not be touched: `bootstrap/protected-paths.txt`

One rule per line, relative to `output/`, `#` for comments:

```
# the tests are mine: the specification and the existing behaviour
src/test/**
# this whole package stays as it is
src/main/java/com/example/legacy/**
# this file keeps every declaration it has, except answer(), which the change extends
members: src/main/java/com/example/Router.java except answer
```

A plain glob forbids writing those files at all. A `members:` line lets the file be changed but keeps every
declaration it had as it was -- new declarations are added beside them -- except the names you list after
`except`.

---

## 4. Starting a run

Choose how the run begins with `autobuilder.start.mode`. Autobuilder checks your choice against what is in
`output/` and refuses, without touching anything, when it does not fit.

| You want to | Mode | What happens |
|---|---|---|
| build a new project from the specification | `NEW_PROJECT` | `output/` is cleared and the goal is taken from `seed.txt`. Refused if `output/` holds code Autobuilder did not write. |
| change or extend an existing project | `ADOPT_PROJECT` | Put your project in `output/`. Nothing is cleared. Autobuilder reads it and **waits for a goal** -- send it (below). |
| continue a project Autobuilder built before | `CONTINUE_LINEAGE` | Restores the earlier artifact and goal state and goes on. |
| decide later | `ASK` | Nothing happens until you choose (`start.html` in the browser, or `POST /autobuilder/start`). |

Then choose how it runs, with a Spring profile:

- `dev-auto` -- the autonomous loop: it cycles until it finishes or stops.
- `dev-local` -- one cycle per request (`POST /autobuilder/cycle`), to watch it step by step.
- add `fast` to either to remove waiting between cycles.

Example -- change an existing project:

```bash
java -jar target/autobuilder-*.jar --spring.profiles.active=dev-auto \
     --file.base-path=data/my-service --autobuilder.start.mode=ADOPT_PROJECT

# once it has started and adopted the project:
curl http://localhost:8081/autobuilder/cold-read                 # what it read in your project
curl -X POST -H 'Content-Type: text/plain' --data-binary @data/my-service/bootstrap/seed.txt \
     http://localhost:8081/autobuilder/goal                       # the goal, byte for byte
```

Post the seed file unchanged: the obligations file is keyed to its exact text.

`scripts/preflight.sh` does all of this for a target under `data/` -- builds Autobuilder, starts it with a time
budget, and archives and scores the run when it ends (`PREFLIGHT_TARGET=<name> scripts/preflight.sh live 60`).
`scripts/post_adopted_goal.sh <name> <goal-file>` posts the goal of an adopted project once it is ready.

---

## 5. Following a run

- **In the browser:** `goals.html` shows the goal tree as it changes -- subgoals, their criteria, which are met;
  `graph.html` and `graph-explorer.html` show the history in the graph. The run can be narrated aloud.
- **Status:** `GET /autobuilder/status` -- the current cycle and goal.
- **The event log:** `logs/behaviour.ndjson` in the target directory, one structured event per decision (also
  streamed live on `GET /events/behaviour`): which agent ran, what was proposed, what was rejected and why, what
  was merged, what the stuck layer asked and answered.
- **Steering by hand:** `POST /autobuilder/pause` and `/resume`; `POST /autobuilder/rewind` to return to an
  earlier checkpoint; the goal explorer endpoints (`/autobuilder/goals/...`) to inspect or edit subgoals and
  criteria, inject a subgoal, or give one up.

---

## 6. Reading the result

### How a run ends

| Ending | Meaning |
|---|---|
| `GOAL_FULFILLED` | every obligation was confirmed and the completion checks passed (the project assembles, its tests pass, and it starts and answers the way the specification says) |
| `GOAL_EXHAUSTED` | the work items ran out without fulfilling the goal |
| `honest-halt:...` | the loop stopped itself because it was not converging, and says on what (e.g. `root-convergence-stall`) |
| `deadline` / `operator-stop` | the time budget ran out, or the run was paused from outside |

A run's verdict is the loop's own. To know whether the project really does what you specified, check it
independently: Autobuilder's toy cases each carry a **yardstick** -- a script that tests the artifact against the
specification without trusting the loop -- and you can write one for your project the same way (one check per
requirement, the last line `SMOKE: <passed>/<total>`). `preflight.sh` runs it on every run.

### What to look at

- `output/` -- your project as it is now.
- `meta/GoalStack.json` -- the goal tree: every subgoal, its criteria, and how each ended.
- `meta/stuck-ledger.json` -- what kept failing, which questions were asked about it, and what answered them.
- `logs/behaviour.ndjson` -- the whole run, decision by decision.

When the loop gets stuck on something, the stuck ledger usually shows the exact fact it was missing, and its
answer -- for example the declarations a type really has, the classes a dependency package really declares, or
a failing test's calls with the values they returned. That is also where to look before you change the
specification.

---

## 7. Worked examples: the toy cases

The targets under `data/` are toy cases -- small, versioned projects Autobuilder is measured on. They are also
the best examples of how to prepare a target:

| Toy case | Kind | Shows |
|---|---|---|
| `firestarter` | new project | a REST API built from a prose specification |
| `clitarget` | new project | a command-line tool, checked by running it |
| `receiver-questions`, `edit-hunks`, `parameterized-rows` | change an existing project | a seed quoting a real ticket, `members:` rules, a specification test the change must satisfy |
| `participation-totals`, `participation-roles` | extend an existing library | operator-written specification tests, protected tests |

Each has `bootstrap/` with its seed, obligations, protected paths, and a `yardstick` line naming its scoring
script.

---

## 8. What it cannot do (yet)

- Projects other than Java with Maven: the checks that compile, test, boot and trace rely on Maven.
- Requirements the specification does not state: Autobuilder only holds the result to what was written. If it
  matters, write it.
- Judging whether a result is *better* than required: it checks the requirements, not taste or design quality.
- Hosted models are not part of its design: it is built for a local model, and its timeouts and output bounds
  adapt to one.
- Large changes across many classes of an existing project remain hard for a local model; expect the stuck
  layer to show what it could not resolve rather than a finished change.
