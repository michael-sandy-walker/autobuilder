#!/usr/bin/env python3
"""T4-DZ offline slice: can a command, run in a READ-ONLY copy of the tree, answer the stuck questions the
structural router leaves open?

Usage:
  scripts/terminal_agent_replay.py oracle            # hand-written commands: the command set's ceiling
  scripts/terminal_agent_replay.py model [reps=3]    # the local model proposes the commands
  options: --questions DIR (default src/test/resources/t4dz)  --ledger FILE (Q&A kept for later sessions)

The questions, their ground truth and the bar are frozen in src/test/resources/t4dz/README.txt.

The agent never touches the live tree: each question gets a fresh copy (tree/ + candidate/ +
COMPILER_OUTPUT.txt) made read-only, commands run with that copy as cwd, without a shell, from an
allow-list, each inside a time budget; paths may not leave the copy. javac may run, but writes only to a
scratch dir outside the copy. Every question and answer is appended to a ledger (JSON lines) so later
sessions can put the same questions to another model and compare.
"""
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

ALLOWED = {"grep", "sed", "cat", "head", "tail", "find", "ls", "wc", "javap", "jar", "javac"}
TIME_BUDGET_S = 10
MAX_COMMANDS = 3
OUTPUT_LINES = 80
LLM_URL = os.environ.get("T4DZ_LLM_URL", "http://localhost:11434/v1/chat/completions")
LLM_MODEL = os.environ.get("T4DZ_LLM_MODEL", "qwen3.8")

PKG = "src/main/java/com/walpro/autobuilder/core/lifecycle/"

# The ceiling: one allow-listed command per question, written by hand from the question alone.
ORACLE = {
    "q1-indexOf-on-map": [["grep", "-n", "active", PKG + "ParticipationWindows.java"]],
    "q2-list-to-int": [["grep", "-rn", "record TickResult", "src"]],
    "q3-inference-bounds": [["grep", "-rn", "record TickResult", "src"]],
    "q4-list-ambiguous-class": [["grep", "-n", "^import", PKG + "ParticipationWindows.java"]],
    "q5-list-ambiguous-variable": [["grep", "-n", "^import", PKG + "ParticipationWindows.java"]],
    "q6-list-ambiguous-record": [["grep", "-n", "^import", PKG + "ChannelRequest.java"]],
}


def tree_hash(root):
    h = hashlib.sha256()
    for d, _, files in sorted(os.walk(root)):
        for f in sorted(files):
            p = os.path.join(d, f)
            h.update(os.path.relpath(p, root).encode())
            with open(p, "rb") as fh:
                h.update(fh.read())
    return h.hexdigest()


def read_only_copy(qdir):
    work = tempfile.mkdtemp(prefix="t4dz-")
    copy = os.path.join(work, "copy")
    shutil.copytree(os.path.join(qdir, "tree"), copy)
    if os.path.isdir(os.path.join(qdir, "candidate")):
        shutil.copytree(os.path.join(qdir, "candidate"), os.path.join(copy, "candidate"))
    shutil.copy(os.path.join(qdir, "COMPILER_OUTPUT.txt"), copy)
    for d, dirs, files in os.walk(copy):
        for f in files:
            os.chmod(os.path.join(d, f), stat.S_IRUSR | stat.S_IRGRP)
        for x in dirs:
            os.chmod(os.path.join(d, x), stat.S_IRUSR | stat.S_IXUSR)
    os.chmod(copy, stat.S_IRUSR | stat.S_IXUSR)
    scratch = os.path.join(work, "scratch")
    os.mkdir(scratch)
    return work, copy, scratch


def release(work):
    for d, dirs, files in os.walk(work):
        os.chmod(d, stat.S_IRWXU)
    shutil.rmtree(work, ignore_errors=True)


def refuse_reason(argv, copy):
    if not argv or not isinstance(argv, list) or not all(isinstance(a, str) for a in argv):
        return "not an argument list"
    if argv[0] not in ALLOWED:
        return "not allowed: " + str(argv[0])
    for a in argv[1:]:
        if a.startswith("/") or ".." in a.split("/"):
            return "path leaves the copy: " + a
    if argv[0] == "sed" and not (len(argv) >= 4 and argv[1] == "-n" and re.fullmatch(r"[0-9]+(,[0-9$]+)?p", argv[2])):
        return "sed may only print lines: sed -n <from>,<to>p <file>"
    if argv[0] == "find" and any(a in ("-delete", "-exec", "-execdir", "-fprint", "-fprintf", "-ok") for a in argv):
        return "find may only list"
    if argv[0] == "jar" and len(argv) > 1 and not re.match(r"^-?t", argv[1]):
        return "jar may only list (tf)"
    return None


def run(argv, copy, scratch):
    if argv[0] == "javac":
        argv = ["javac", "-d", scratch, "-proc:none"] + [a for a in argv[1:] if a not in ("-d",)]
    try:
        p = subprocess.run(argv, cwd=copy, capture_output=True, text=True, timeout=TIME_BUDGET_S)
        out = (p.stdout + p.stderr).splitlines()
    except subprocess.TimeoutExpired:
        return "(timed out after %d s)" % TIME_BUDGET_S, True
    except FileNotFoundError as e:
        return "(could not run: %s)" % e, False
    return "\n".join(out[:OUTPUT_LINES]), False


def listing(copy):
    out = []
    for d, _, files in os.walk(copy):
        for f in sorted(files):
            out.append(os.path.relpath(os.path.join(d, f), copy))
    return "\n".join(sorted(out))


SYSTEM = ("You answer questions about a Java project by choosing shell commands to run in a READ-ONLY copy of it."
          " You cannot change any file. Reply with JSON only.")


def propose(question, compiler, files):
    user = f"""The build keeps failing the same way, and reading the project's declarations did not settle why.

Failure: {question['signature']}

The compiler's own output (paths are as the build saw them; in the copy they are relative):
{compiler}

The copy holds these files (candidate/ is the change that failed; the rest is the project as it stands):
{files}

Which fact would let the next change succeed? Choose at most {MAX_COMMANDS} commands that show it.
Allowed programs: {', '.join(sorted(ALLOWED))}. No shell: no pipes, redirects or globs. Paths relative to the copy.
Reply as JSON: {{"question": "<the fact you are looking for>", "commands": [["grep", "-n", "x", "path"], ...]}}"""
    body = {"model": LLM_MODEL, "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
            "temperature": 0.7, "reasoning_effort": "none"}
    req = urllib.request.Request(LLM_URL, data=json.dumps(body).encode(), headers={"Content-Type": "application/json",
                                                                                   "Authorization": "Bearer local"})
    with urllib.request.urlopen(req, timeout=300) as r:
        content = json.load(r)["choices"][0]["message"]["content"]
    m = re.search(r"\{.*\}", content, re.S)
    try:
        parsed = json.loads(m.group(0)) if m else {}
    except json.JSONDecodeError:
        parsed = {}
    return parsed.get("question"), (parsed.get("commands") or [])[:MAX_COMMANDS], content


def ask(qdir, arm, ledger):
    q = json.load(open(os.path.join(qdir, "question.json")))
    compiler = open(os.path.join(qdir, "COMPILER_OUTPUT.txt")).read()
    work, copy, scratch = read_only_copy(qdir)
    before = tree_hash(copy)
    t0 = time.time()
    proposed_question, raw = None, None
    if arm == "oracle":
        commands = ORACLE[q["id"]]
    else:
        proposed_question, commands, raw = propose(q, compiler, listing(copy))
    results, refused, timeouts = [], 0, 0
    for argv in commands:
        why = refuse_reason(argv, copy)
        if why:
            refused += 1
            results.append({"argv": argv, "refused": why})
            continue
        out, timed_out = run(argv, copy, scratch)
        timeouts += timed_out
        results.append({"argv": argv, "output": out})
    after = tree_hash(copy)
    release(work)
    answered = any(re.search(q["truth_regex"], r.get("output", "")) for r in results)
    record = {"at": time.strftime("%Y-%m-%dT%H:%M:%S"), "arm": arm, "id": q["id"], "signature": q["signature"],
              "proposed_question": proposed_question, "commands": results, "answered": answered,
              "truth_in_words": q["truth_in_words"], "writes": before != after, "refused": refused,
              "timeouts": timeouts, "seconds": round(time.time() - t0, 1)}
    if raw is not None:
        record["model_reply"] = raw
    with open(ledger, "a") as fh:
        fh.write(json.dumps(record) + "\n")
    return record


def main():
    args = sys.argv[1:]
    qroot = "src/test/resources/t4dz"
    ledger = os.path.expanduser("~/.autobuilder/terminal-agent/qa.jsonl")
    if "--questions" in args:
        i = args.index("--questions"); qroot = args[i + 1]; del args[i:i + 2]
    if "--ledger" in args:
        i = args.index("--ledger"); ledger = args[i + 1]; del args[i:i + 2]
    os.makedirs(os.path.dirname(ledger), exist_ok=True)
    arm = args[0] if args else "oracle"
    reps = int(args[1]) if len(args) > 1 else (1 if arm == "oracle" else 3)
    qdirs = sorted(os.path.join(qroot, d) for d in os.listdir(qroot) if os.path.isdir(os.path.join(qroot, d)))
    per_q = {}
    writes = refused = timeouts = 0
    for rep in range(reps):
        for qd in qdirs:
            r = ask(qd, arm, ledger)
            per_q.setdefault(r["id"], []).append(r["answered"])
            writes += r["writes"]; refused += r["refused"]; timeouts += r["timeouts"]
            print(f"rep {rep + 1} {r['id']:<28} answered={r['answered']!s:<5} refused={r['refused']} "
                  f"asked={r['proposed_question']!r}"[:220], flush=True)
    need = 1 if reps == 1 else 2
    counted = sum(1 for v in per_q.values() if sum(v) >= need)
    print(f"\nRESULT arm={arm} reps={reps}: {counted} of {len(per_q)} questions answered "
          f"(a question counts at >= {need} of {reps}); writes={writes} refused={refused} timeouts={timeouts}")
    for k, v in per_q.items():
        print(f"  {k:<28} {''.join('Y' if x else '.' for x in v)}")


if __name__ == "__main__":
    main()
