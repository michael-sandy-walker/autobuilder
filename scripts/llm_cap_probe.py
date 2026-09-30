#!/usr/bin/env python3
"""Replays a real prompt against the local model with an output cap, to find where answers end, where they run
away, and what still gives a usable answer (the user, 2026-10-01: "test the limits by trial-and-error ... find out
where the timeouts are and what gives actual results").

For every call: completion tokens, seconds, finish reason ('stop' = the model ended it, 'length' = the cap did),
the fenced code blocks and whether each is closed, and how repetitive the tail is (share of the last 60 non-blank
lines that already occurred earlier in the answer -- a runaway repeats itself).

usage: llm_cap_probe.py --system FILE --user FILE --caps 16384[,8192,...] [--repeat N] [--timeout S]
       [--temperature T] [--frequency-penalty F] [--presence-penalty P]
       [--model qwen3.8] [--url http://localhost:11434/v1/chat/completions] [--reasoning none]
One JSON line per call on stdout.
"""
import argparse, json, os, re, time, urllib.request

def call(url, model, system, user, cap, timeout, reasoning, extra):
    body = {'model': model, 'temperature': 0.0, 'max_tokens': cap, **extra,
            'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}]}
    if reasoning: body['reasoning_effort'] = reasoning
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.load(r)
    except Exception as e:
        return {'error': str(e)[:120], 'seconds': round(time.time() - t0, 1)}
    ch = d['choices'][0]; text = ch['message'].get('content') or ''
    return {'seconds': round(time.time() - t0, 1), 'finish': ch.get('finish_reason'),
            'completion_tokens': d.get('usage', {}).get('completion_tokens'), 'text': text}

def shape(text):
    opens = re.findall(r'(?m)^[ \t]*```(\w+) \[([^\]\n]+)\]', text)
    closes = len(re.findall(r'(?m)^[ \t]*```[ \t]*$', text))
    lines = [l.strip() for l in text.split('\n') if l.strip()]
    tail = lines[-60:]; head = set(lines[:-60])
    rep = round(sum(1 for l in tail if l in head) / len(tail), 2) if tail else 0.0
    return {'blocks': len(opens), 'closed': min(closes, len(opens)), 'files': sorted({p for _, p in opens}),
            'tail_repeat': rep, 'chars': len(text)}

def main():
    a = argparse.ArgumentParser()
    a.add_argument('--system', required=True); a.add_argument('--user', required=True)
    a.add_argument('--caps', default='16384'); a.add_argument('--repeat', type=int, default=1)
    a.add_argument('--timeout', type=float, default=900); a.add_argument('--model', default='qwen3.8')
    a.add_argument('--url', default='http://localhost:11434/v1/chat/completions'); a.add_argument('--reasoning', default='none')
    a.add_argument('--keep', help='directory to keep each answer in')
    a.add_argument('--temperature', type=float); a.add_argument('--frequency-penalty', type=float)
    a.add_argument('--presence-penalty', type=float)
    o = a.parse_args()
    system, user = open(o.system).read(), open(o.user).read()
    extra = {k: v for k, v in (('temperature', o.temperature), ('frequency_penalty', o.frequency_penalty),
                               ('presence_penalty', o.presence_penalty)) if v is not None}
    for cap in [int(c) for c in o.caps.split(',')]:
        for i in range(o.repeat):
            r = call(o.url, o.model, system, user, cap, o.timeout, o.reasoning, extra)
            text = r.pop('text', '')
            if text: r.update(shape(text))
            if o.keep and text:
                tag = '-'.join(f'{k}{v}' for k, v in extra.items()) or 'default'
                stem = os.path.splitext(os.path.basename(o.user))[0]
                open(f'{o.keep}/{stem}-{tag}-cap{cap}-{i}-{int(time.time())}.txt', 'w').write(text)
            print(json.dumps({'cap': cap, 'try': i, **extra, **r}), flush=True)

if __name__ == '__main__':
    main()
