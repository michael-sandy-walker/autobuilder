// T4-EC stage 4 slice 2: the merge canvas's logic (src/main/resources/static/merge.js), without a browser.
// Run: node --test src/test/js/merge.test.mjs   (optionally MERGE_SESSION_GRAPH=<graph.json> for a real session)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import fs from 'node:fs';
const require = createRequire(import.meta.url);
const { MergeCanvas: C } = require('../../main/resources/static/merge.js');

const g = {
  nodes: [
    { id: 'wick', kind: 'PROJECT', project: 'wick', title: 'wick', metadata: {} },
    { id: 'wick:goal:1', kind: 'GOAL', project: 'wick', title: 'Burn', metadata: {} },
    { id: 'wick:criterion:a', kind: 'CRITERION', project: 'wick', title: 'It burns', metadata: {} },
    { id: 'wick:type:Flame', kind: 'TYPE', project: 'wick', title: 'Flame', metadata: {} },
    { id: 'lamp', kind: 'PROJECT', project: 'lamp', title: 'lamp', metadata: {} },
    { id: 'lamp:type:Glow', kind: 'TYPE', project: 'lamp', title: 'Glow', metadata: {} },
    { id: 's1', kind: 'SUPER', project: null, title: 'Light', metadata: {} },
    { id: 's2', kind: 'SUPER', project: null, title: 'All', metadata: {} },
  ],
  edges: [
    { id: 'e1', from: 'wick', to: 'wick:goal:1', kind: 'STRUCTURE', metadata: {} },
    { id: 'e2', from: 'wick:goal:1', to: 'wick:criterion:a', kind: 'STRUCTURE', metadata: {} },
    { id: 'e3', from: 'wick', to: 'wick:type:Flame', kind: 'STRUCTURE', metadata: {} },
    { id: 'e4', from: 'lamp', to: 'lamp:type:Glow', kind: 'STRUCTURE', metadata: {} },
    { id: 'l1', from: 'wick:type:Flame', to: 'lamp:type:Glow', kind: 'LINK', metadata: { verdict: 'RELATED', status: 'proposed' } },
    { id: 's1->a', from: 's1', to: 'wick:type:Flame', kind: 'SUPER_OF', metadata: {} },
    { id: 's1->b', from: 's1', to: 'lamp:type:Glow', kind: 'SUPER_OF', metadata: {} },
    { id: 's2->s1', from: 's2', to: 's1', kind: 'SUPER_OF', metadata: {} },
    { id: 's2->w', from: 's2', to: 'wick', kind: 'SUPER_OF', metadata: {} },
  ],
};

test('a super node stands one level above its highest child', () => {
  const m = C.model(g);
  assert.equal(m.level.get('wick:type:Flame'), 0);
  assert.equal(m.level.get('s1'), 1);
  assert.equal(m.level.get('s2'), 2);
});

test('folding hides what lies below through project structure, and hidden kinds are left out', () => {
  const m = C.model(g);
  const v = C.visible(m, new Set(['CRITERION']), new Set(['wick']));
  const ids = v.nodes.map(n => n.id);
  assert.ok(ids.includes('wick') && ids.includes('s1') && ids.includes('lamp:type:Glow'));
  assert.ok(!ids.includes('wick:goal:1') && !ids.includes('wick:type:Flame'));
  assert.ok(v.edges.every(e => ids.includes(e.from) && ids.includes(e.to)));
});

test('what a shown super node was made from stays shown, whatever its kind', () => {
  const m = C.model(g);
  const v = C.visible(m, new Set(['TYPE']), new Set());
  const ids = v.nodes.map(n => n.id);
  assert.ok(ids.includes('wick:type:Flame') && ids.includes('lamp:type:Glow'));
});

test('the acts offered follow the selection', () => {
  const m = C.model(g);
  assert.equal(C.offers(m, new Set(['wick', 'lamp']), null).link, null);
  assert.notEqual(C.offers(m, new Set(['wick']), null).super, null);
  assert.equal(C.offers(m, new Set(), 'l1').removeLink, null);
  assert.notEqual(C.offers(m, new Set(), 'e1').removeLink, null);
  assert.match(C.offers(m, new Set(['s1']), null).removeSuper, /merged into another/);
  assert.equal(C.offers(m, new Set(['s2']), null).removeSuper, null);
});

test('acts become the edits the session takes, by the operator', () => {
  assert.deepEqual(C.linkEdit('a', 'b', 'x').args, { id: 'x', from: 'a', to: 'b', kind: 'LINK', metadata: { status: 'confirmed' } });
  assert.equal(C.superEdit(new Set(['a', 'b']), 's', 'T').args.children.length, 2);
  assert.equal(C.metaEdit('t', 'k', '').args.value, null);
  assert.equal(C.freshId(g, 's'), 's3');
  for (const e of [C.linkEdit('a', 'b', 'x'), C.removeLinkEdit('x'), C.removeSuperEdit('s'), C.metaEdit('t', 'k', 'v')]) {
    assert.equal(e.by, 'operator');
  }
  assert.deepEqual(C.linkStyle(g.edges[4]), { dashed: true, colour: 'related' });
});

test('a real session graph reads without loss', { skip: !process.env.MERGE_SESSION_GRAPH }, () => {
  const real = JSON.parse(fs.readFileSync(process.env.MERGE_SESSION_GRAPH, 'utf8'));
  const m = C.model(real);
  const v = C.visible(m, new Set(), new Set());
  assert.equal(v.nodes.length, real.nodes.length);
  assert.equal(v.edges.length, real.edges.length);
  for (const n of real.nodes.filter(n => n.kind === 'SUPER')) assert.ok(m.level.get(n.id) >= 1);
});
