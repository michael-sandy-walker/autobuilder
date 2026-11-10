# Autobuilder -- functional documentation

What Autobuilder does and how to use it on a project of your own: what to give it, how to start it, how to
follow a run, how it deals with difficulty, and how to read what it produced. How it works inside is in
[technical.md](technical.md).

---

## 1. What Autobuilder is for

You give Autobuilder a **specification** -- what a project should do, in plain prose -- and a place to work. It
then builds or changes the project toward that specification, one cycle at a time: it plans the work into
subgoals, proposes a change for one of them, checks the change (it must compile, keep what it must keep, and
satisfy the subgoal's criteria), merges it, and moves on. It stops when every requirement of the specification
is met and the project passes its own checks -- or when it can show it is not getting further, and says why.

It can work in three ways:

- **build a new project** from a specification;
- **change or extend an existing project** someone else wrote -- including against tests you provide, which then
  define what "done" means;
- **continue** a project it built in an earlier run, or **combine** two runs that grew one project in different
  directions.

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
- Memory to spare beside the model: builds, test runs and debugger traces each start a JVM, and other heavy
  programs on the same machine slow or endanger a run.
- A **target directory** for your project, under `data/<name>/` (see below).

Build Autobuilder with `mvn clean install`; it runs as `java -jar target/autobuilder-*.jar`. Started without a
project, it opens the home screen on port 8080 (`autobuilder.home.port`), where you choose what to work on; the
run itself listens on port 8081 (`server.port`). Started with a project (below), it begins the run at once.

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
other class exactly as it is; do not modify anything under `src/test`." The strongest form of a change request is
**tests you write**: put them in `src/test/java`, protect them (3.3), and end the seed with one sentence opened by
`OBJECTIVE:` -- "OBJECTIVE: every test in RolesSpecTest passes, and the whole suite passes." Autobuilder then works
until your tests pass, and checks that by running them, not by reading the code.

### 3.2 The obligations: `bootstrap/seed.obligations.txt`

On the first run Autobuilder splits your specification into **obligations**, one per line, and writes them here.
Read this file: it is what the run will be held to at the end. You may edit it -- your edit is kept and reported
as yours. Two markers matter:

- `rule:` at the start of a line marks *how* the work is done (e.g. `rule: Do not modify anything under src/test`).
  It guides the work but is never something the end result is checked for.
- `gate: tests` marks a line whose whole claim is that the project's own tests pass (e.g.
  `gate: tests The whole test suite passes.`). It is then answered by actually running the tests, not by a
  model reading your code -- and it makes your test suite an authority for the whole run: a change that makes
  more of your tests fail is rejected, and a change that makes them better is accepted even when the model
  doubts it. Mark such lines yourself if the generated file left them unmarked.

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

### 4.1 From the home screen

Start Autobuilder without naming a project and open `http://localhost:8080` in a browser (the port is
`autobuilder.home.port`). The home screen has three parts: **Now** (the run, if one is up), the **projects** (the
directories in `autobuilder.home.projects-dir`, default `data`), and **+ New project**. "Runs", top right, chooses how
a run you start proceeds: autonomously (`dev-auto`), autonomously with the waiting trimmed (`dev-auto,fast`, what
scripted runs use), or by hand (`dev-local`, cycles only when asked).

**Projects.** Each project shows its state and one button for what that state calls for; the "..." menu holds the
rest.

| State | Button | In "..." |
|---|---|---|
| not started -- a specification, no code | **Start** | Duplicate |
| in progress -- worked on, goal not reached | **Continue** | Extend, Restart, Duplicate |
| finished -- its goal was reached | **Extend...** | Continue, Restart, Duplicate |
| existing code -- code Autobuilder did not write | **Work on it** | Extend, Duplicate |

- **Start** builds it from its specification (`NEW_PROJECT`); **Continue** picks the project up exactly where it was
  left (`CONTINUE_LINEAGE`) -- the code, goals, imports and the loop's own decisions as they were, whether it was
  stopped mid-run, rewound or finished; a run you stopped runs on, a finished project opens finished;
  **Work on it** adopts the code as it is (`ADOPT_PROJECT`) -- nothing is cleared, and it waits for a goal (below).
- **Extend...** brings something into the project: another project here, or code in a directory on this machine,
  with what to take from it (its *intent*). Its code, goals and criteria join; a finished project reopens with the
  new work. What it brings is compared with what is there, and a difference no intent settles is a **conflict**,
  which waits for your decision in Now. An import is **not final until you keep it**: it lands, you see what came in
  (new files, files both projects have, rules that differ), decide its conflicts, and then **keep** it -- or
  **cancel** it, which removes all of it again (files, goals, requirements, open conflicts; the record of it
  stays). The same project, unchanged, cannot be brought in a second time while an earlier import of it is still
  in. A project at rest is held throughout, so nothing runs on it before you keep the import; the project whose
  loop is running takes the input between two cycles. How an input lands is described in the technical
  documentation, section 10 ("Bringing a project in").
- **Restart** clears its `output/` and begins a new lineage from its specification; the earlier run is not read.
  The page asks first.
- **Duplicate** makes a new project with a byte-for-byte copy of its `bootstrap/` (specification, obligations,
  protected paths, yardstick) -- no code, no history; the original is left as it is.

Each row also says how the project's last run ended, and when. A project with an import that is not yet kept shows
**import waiting**, and its button is **Review import**: it is opened held, so you can keep or cancel the import. A
project with imports shows how many ("2 imports"), and its "..." menu offers **Manage imports...**, which opens it
held -- nothing runs -- to see what was brought in and keep or cancel what waits. A kept import is part of the
project and cannot be taken out again; to go back to before it, rewind the project. The filter above the list
narrows it to one state.

**+ New project** offers three ways in, each under a new name (it becomes the directory; a name already taken is
refused, nothing is overwritten):

- **From a specification** -- write what to build. Writes `bootstrap/seed.txt`, nothing else, and starts it.
- **From existing code** -- a directory on this machine, or a copy of a project here (its code alone). The code is
  copied into `output/` -- leaving out `.git`, `target`, `build`, `node_modules`, `.idea`, `.gradle` and links; the
  original is only read; at most 20,000 files and 500 MB -- with optional globs of what may not be touched
  (`bootstrap/protected-paths.txt`). It is then adopted and waits for a goal.
- **From several projects** -- a goal, and two or more projects with what to take from each (at most one marked as
  the module that runs). The new, empty project is started held, given the goal, and each project is brought in --
  code, goals and criteria, each as a module of its own; each can take minutes. Conflicts wait for your decision.

**Now** shows the run: what it is doing and since when, its own pages (goals, graph, start state; the run listens on
port 8081), and -- while its loop runs and after it has ended -- the loop at a glance: cycles, merged and rejected
changes, refused closures, about how long a cycle takes, what it works on, its latest events (cycles, merges,
rejections, refused closures, goal changes, pauses), and, when the same closure refusal keeps coming back, that
refusal with how often it came back -- what a stuck run looks like. The events scroll in a box; scrolling to its end
brings older ones from the run's log, back to the run's start. And:

- **Stop** while its loop runs -- it halts after its current cycle (which can take a few minutes) and its results
  stay as they are.
- **Close** once it has ended, or before its loop has begun -- this frees the home screen for the next run. A run
  that has ended stays open, with how it ended, until you close it.
- For adopted code without a goal: what Autobuilder read in the code (its cold read) and a goal box; **Set goal**
  writes it byte for byte to `bootstrap/seed.txt`.
- While projects are being brought in: a progress bar and the step each one is at (placing its code, writing its
  obligations, holding files both projects have, finding the types its intent uses, comparing its types with this
  project's -- the slow step, with a count -- answering its criteria, checking what cannot hold here, placing its
  goals), then each one's outcome (files placed, corrections, conflicts, criteria not yet met).
- Open conflicts, with both sides. For each: **Keep this project's**, **Take what came in**, or **Decide
  differently...** (**Merge them...** for a file both projects have) -- your own resolution in your words, which
  becomes a requirement of the project and work for the loop. **Ask for options** has the model lay out two to four
  ways to resolve it, each with what it changes, one of them marked **recommended** with the reason; **Use this**
  copies one into your resolution to send or edit. The model only advises; you decide.
- With two or more conflicts, a bar above them acts on the selected ones (all are selected at first; the
  checkboxes and "all" / "none" change that): **Keep this project's**, **Take what came in**, **Suggest** -- the
  model's recommended resolution put into each one's box, marked as suggested, for you to review and edit -- and
  **Decide all as shown**, which decides each selected conflict as its box says. A line says how far a batch is. An import is kept only once its conflicts are decided, so open conflicts are
  shown whenever the project's run is up.
- Until it is kept, an import is a proposal: the loop holds (a running project stops working until you keep or cancel
  it), and the import's text and requirements do not count yet. Kept, it is simply part of the project -- one
  specification, one list of requirements, nothing marking which part came from where; cancelled, it leaves nothing.
- When every conflict is decided: **Keep the imports and start <project>** (a new project), **Keep the import and
  continue <project>** (one that was at rest), or **Keep the import** (one whose loop was running) -- or **Cancel
  the import** at any point before. Whenever the project's run is up, Now lists its **imports** -- kept, or still
  waiting from an earlier session -- with what each brought; a waiting one has **Keep it** and **Cancel the import**.
  Once nothing waits, **Continue <project>** starts a held project.

One run at a time: while a run is up, the buttons that would start another are disabled.

Everything the page does is a REST call on the home port, so it can also be done with curl:

```bash
curl http://localhost:8080/home/state                    # IDLE, STARTING, RUNNING or FAILED, and on what
curl http://localhost:8080/home/projects                 # the projects, and the modes each can start in
curl -X POST -H 'Content-Type: application/json' \
     -d '{"name": "my-service", "seed": "What to build ..."}' \
     http://localhost:8080/home/projects                 # a new project from a specification
curl -X POST -H 'Content-Type: application/json' \
     -d '{"name": "my-service-2", "from": "my-service"}' \
     http://localhost:8080/home/projects                 # a duplicate: a copy of my-service's bootstrap/
curl -X POST -H 'Content-Type: application/json' \
     -d '{"project": "my-service", "mode": "NEW_PROJECT", "profiles": "dev-auto"}' \
     http://localhost:8080/home/start                    # start a run (202), or 409 while one is going
curl -X POST -H 'Content-Type: application/json' \
     -d '{"name": "their-service", "source": "/home/me/code/their-service", "protectedPaths": "src/main/**"}' \
     http://localhost:8080/home/import                   # copy a codebase in, then start it with mode ADOPT_PROJECT
                                                         # ({"name": ..., "from": "<project here>"} copies that one's code)
curl http://localhost:8080/home/run/cold-read            # what the adopted run read in the code
curl -X POST -H 'Content-Type: text/plain' --data-binary @goal.txt \
     http://localhost:8080/home/run/goal                 # its goal, byte for byte
curl -X POST -H 'Content-Type: application/json' -d '{"name": "combined", "goal": "...", "profiles": "dev-auto",
     "inputs": [{"project": "a", "intent": "...", "runs": true}, {"project": "b", "intent": "..."}]}' \
     http://localhost:8080/home/integrate                # bring projects together (follow with GET /home/integrate)
curl http://localhost:8080/home/run/conflicts            # what waits for a decision
curl http://localhost:8080/home/run/input-progress      # the step the input under way is at (204 when none)
curl http://localhost:8080/home/run/conflicts/<id>/options  # the model's suggested resolutions, each with its effect
curl http://localhost:8080/home/run/live                 # the running loop at a glance
curl http://localhost:8080/home/run/imports              # every import still in, kept or pending
curl http://localhost:8080/home/run/pending              # imports not yet kept
curl -X POST http://localhost:8080/home/run/imports/<input-id>/keep      # keep one ...
curl -X POST http://localhost:8080/home/run/imports/<input-id>/cancel    # ... or cancel a pending one
curl -X POST -H 'Content-Type: application/json' -d '{"decision": "input"}' \
     http://localhost:8080/home/run/conflicts/<id>       # or "receiving", or {"decision": "own", "resolution": "..."}
curl -X POST -H 'Content-Type: application/json' -d '{"project": "base", "profiles": "dev-auto",
     "input": {"project": "lib", "intent": "..."}}' \
     http://localhost:8080/home/extend                   # bring lib into base ("path": "/abs/dir" for code elsewhere)
curl -X POST http://localhost:8080/home/integrate/release  # let the loop work once nothing is open
curl -X POST http://localhost:8080/home/stop             # stop the running loop after its current cycle
curl -X POST http://localhost:8080/home/return           # close a run that has ended or not begun (409 while it runs)
curl http://localhost:8080/home/run/services             # what was said about the services the project needs
curl -X POST -H 'Content-Type: application/json' -d '{"answer": "yes", "services": [...]}' \
     http://localhost:8080/home/run/services             # or "no", or "withdraw" (the services as proposed, edited)
```

**A project that needs a service to run** (a database, a message broker) cannot be started on its own. When
reverse engineering finds that the project's configuration names an address on this machine where nothing
answers, it starts the project once; if that fails, it stops and asks before any model work. The proposal comes
from the project's own compose file when it has one, taken as written; otherwise the model writes it from the
project's own lines (its configuration, README and build file) and what it printed when it failed -- the page
shows those lines beside it. You can edit the image, the port inside the container and its environment, and tick
a setup script the project ships (unticked by default). The port is always the one the project is configured
with; when something else already uses it, nothing is started and the reason is said.

Nothing is started without your yes. The answer is kept in the project (`meta/services.json`) and used by every
later start of it -- reverse engineering's and the loop's own start-up checks -- so it is asked once. A fresh
container (`autobuilder-<project>-<service>`, needs Docker) is created before each start of the project and
removed after it, so no start sees data another left; the first start pulls the image, which can take minutes
(`autobuilder.services.ready-timeout-ms`). "withdraw" forgets the answer.

### 4.2 From the command line

Naming a project at startup (`--file.base-path=data/my-service` or `FILE_BASE_PATH=data/my-service`) skips the
choice: the run starts at once, the same way "Start" starts it, and the home screen still shows it on port 8080.
This is how scripted runs start.

Choose how the run begins with `autobuilder.start.mode`. Autobuilder checks your choice against what is in
`output/` and refuses, without touching anything, when it does not fit.

| You want to | Mode | What happens |
|---|---|---|
| build a new project from the specification | `NEW_PROJECT` | `output/` is cleared and the goal is taken from `seed.txt`. Refused if `output/` holds code Autobuilder did not write. |
| change or extend an existing project | `ADOPT_PROJECT` | Put your project in `output/`. Nothing is cleared. Autobuilder reads it and **waits for a goal** -- send it (below). |
| continue a project Autobuilder built before | `CONTINUE_LINEAGE` | Restores the best state the earlier run preserved, with its goal state, and goes on; the new run reads the earlier run's own end report first. |
| decide later | `ASK` | Nothing happens until you choose (`start.html` in the browser, or `POST /autobuilder/start`). |

`GET /autobuilder/targets` lists the targets under `data/` and which modes each supports.

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

Post the seed file unchanged: the obligations file is keyed to its exact text. On adoption Autobuilder records the
digest of every file, so "nothing else was changed" can later be proven, not guessed.

`scripts/preflight.sh` does all of this for a target under `data/` -- builds Autobuilder, starts it with a time
budget, and archives and scores the run when it ends (`PREFLIGHT_TARGET=<name> scripts/preflight.sh live 60`).
`scripts/post_adopted_goal.sh <name> <goal-file>` posts the goal of an adopted project once it is ready, and
`scripts/early_stop.sh` started beside a run pauses it when its score stops moving.

---

## 5. Following a run

- **In the browser:** `goals.html` shows the goal tree as it changes -- subgoals, their criteria, which are met --
  and can narrate the run aloud; `graph.html` and `graph-explorer.html` show the history in the graph.
- **Status:** `GET /autobuilder/status` -- the current cycle and goal; `GET /autobuilder/usage` -- model usage.
- **The event log:** `logs/behaviour.ndjson` in the target directory, one structured event per decision (also
  streamed live on `GET /events/behaviour`): which agent ran, what was proposed, what was rejected and why, what
  was merged, each comparison of a change with the project's own tests (`test_delta`), what the stuck layer asked
  and answered, each question the evolution agent asked about the run and what answered it
  (`log_question_asked`), and why the run stopped.
- **Steering by hand:**
  - `POST /autobuilder/pause` and `/resume`;
  - `POST /autobuilder/rewind` to return to an earlier checkpoint (`GET /autobuilder/checkpoints` lists them);
  - the goal explorer (`/autobuilder/goals/...`) to inspect or edit subgoals and criteria, mark a criterion met,
    inject a subgoal, pin one, give one up or reactivate it;
  - `POST /autobuilder/coverage-review` to have the plan checked against the specification;
  - `PUT /autobuilder/signals/{name}` to poke the loop: ask a role to run, open a role's window, or set a switch.

---

## 6. How Autobuilder deals with difficulty

What it does on its own when work does not go smoothly -- each of these shows up in the event log.

- **A change does not compile.** Before asking the model again, it tries known repairs itself: a missing import or
  dependency, a method called on the wrong type, `getX()` on a record that declares `x()`, a dependency version
  that will not resolve, missing test-framework dependencies. Versions and coordinates come from your project's
  own build and local repository first. The model is then shown the exact compiler errors, and what the types
  involved really declare.
- **A change is rejected and tried again.** Before the retry, the model may ask one question about the run itself
  -- which errors these files have already had, how an earlier attempt ended, what a file or test contains now. A
  second model finds the answer by searching the run's logs and your project's files step by step, and the answer
  is added to the retry. Turn it off with `autobuilder.log-consultation.enabled=false`.
- **A change needs something that does not exist yet.** A change rejected only because it uses a member or class
  that a later change could supply is kept, and re-tried automatically once a merge supplies it.
- **A change must touch several classes before anything compiles.** Each step that leaves fewer of your test files
  failing to compile is kept, so such a change is built one class at a time instead of all at once or not at all.
- **The model judges a good change wrongly.** Measurements outrank it: a change after which the check that raised
  the work item passes, or after which your test suite is better, is accepted -- and a change that makes your
  suite worse, removes an annotation a type relied on, or manufactures a dependency instead of injecting it, is
  rejected whatever the model said.
- **The same failure keeps coming back.** The stuck layer asks a question about it and answers it from the code:
  what a type really declares, where a class really lives, what the failing line constructs, the declaring file
  whole -- and, for failing tests, **what a failing test actually executed**: the test is run again under a
  debugger, and the model is shown each call into your code with the values it received and returned. If no question helps, it asks you for a diagnosis. Earlier runs on the same
  specification hand over what ended each failure there.
- **The plan drifts.** Work items the specification does not ask for cannot block the goal; requirements no subgoal
  ever targeted are added back; duplicate subgoals are merged; earlier abandoned work on the same topic is shown
  before new work is planned.
- **The run stops making progress.** Before stopping, it may **rewind** to an earlier checkpoint and take a
  different path, or explore more than one path from the same point and keep the better one. When it does stop,
  it keeps the best state it ever reached (not merely the last one) and writes an end report a continuing run will
  read.

---

## 7. Reading the result

### How a run ends

| Ending | Meaning |
|---|---|
| `GOAL_FULFILLED` | every obligation was confirmed and the completion checks passed (the project assembles, its tests pass, and it starts and answers the way the specification says) |
| `GOAL_EXHAUSTED` | the work items ran out without fulfilling the goal |
| `honest-halt:<reason>` | the loop stopped itself because it was not converging, and says on what -- e.g. `root-convergence-stall` (no subgoal completed for a stretch while no work landed), `exhaustion-runaway` (subgoals kept being given up), `build-blocked-deadlock`, `empty-backlog-injection-limit`, `llm-circuit-breaker` (the model endpoint kept failing) |
| `deadline` / `operator-stop` | the time budget ran out, or the run was paused from outside |

A run's verdict is the loop's own. To know whether the project really does what you specified, check it
independently: Autobuilder's toy cases each carry a **yardstick** -- a script that tests the artifact against the
specification without trusting the loop -- and you can write one for your project the same way (one check per
requirement, the last line `SMOKE: <passed>/<total>`). `preflight.sh` runs it on every run, on the final tree and
on the preserved one. When two runs score the same total, compare *which* checks fail: the checks build on each
other, and the same number can mean different things.

### What to look at

- `output/` -- your project as it is now; `meta/preserved-artifact/` -- the best state the run reached.
- `meta/GoalStack.json` -- the goal tree: every subgoal, its criteria, and how each ended.
- `meta/stuck-ledger.json` -- what kept failing, which questions were asked about it, and what answered them.
- the run end report -- why the run stopped, in the loop's own words.
- `logs/behaviour.ndjson` -- the whole run, decision by decision.

When the loop gets stuck on something, the stuck ledger usually shows the exact fact it was missing, and its
answer -- for example the declarations a type really has, the classes a dependency package really declares, or
a failing test's calls with the values they returned. That is also where to look before you change the
specification.

---

## 8. Worked examples: the toy cases

The targets under `data/` are toy cases -- small, versioned projects Autobuilder is measured on (each records its
version and the reason for every change in `bootstrap/CHANGELOG.md`). They are also the best examples of how to
prepare a target:

| Toy case | Kind | Shows |
|---|---|---|
| `firestarter` | new project | a REST API built from a prose specification, checked by calling it |
| `clitarget` | new project | a command-line tool, checked by running it |
| `rung1` | adopted project | a small REST service read from its README and routes, then extended |
| `structdiff`, `participation`, `participation-b` | adopted library | an existing library whose behaviour the loop characterises with tests |
| `participation-roles`, `participation-totals` | extend an existing library | operator-written specification tests, protected tests |
| `receiver-questions`, `edit-hunks`, `parameterized-rows`, `criterion-status`, `stuck-questions` | change an existing project | a seed quoting a real ticket, `members:` rules, a specification test the change must satisfy |
| `ladder-rows`, `ladder-grind` | the ticket ladder | a past change of Autobuilder itself, cut out as it was before, with that change's own tests as the specification |
| `flightsim` | new project, no yardstick | a large open-ended specification (a 3D flight simulator), for observing behaviour rather than scoring it |

Each has `bootstrap/` with its seed, obligations, protected paths, and a `yardstick` line naming its scoring
script.

### The ticket ladder

The ladder measures Autobuilder on real changes of growing size: each rung is a commit from Autobuilder's own
history, banded by how many classes it changed. `scripts/ladder_case.py <commit> <name>` turns a commit into a toy
case -- the classes it changed and everything they reach, as they were before it, the commit's own tests as the
specification, and its commit message as the ticket. A case is validated before it is run: its starting tree must
build with its existing tests passing, must fail the specification, and must pass it with the original commit's
classes in place.

---

## 9. What it cannot do (yet)

- Projects other than Java with Maven: the checks that compile, test, boot and trace rely on Maven.
- Requirements the specification does not state: Autobuilder only holds the result to what was written. If it
  matters, write it.
- Judging whether a result is *better* than required: it checks the requirements, not taste or design quality.
- Hosted models are not part of its design: it is built for a local model, and its timeouts and output bounds
  adapt to one.
- Changes across several classes of an existing project are within reach -- such a change is now kept step by step
  -- but they remain the hardest work for a local model: on the ladder's second band-3 case the best runs reach
  most of the specification and stall on the last few tests. Expect the stuck layer to show what it could not
  resolve rather than a finished change.
