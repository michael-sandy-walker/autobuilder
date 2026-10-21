// T4-EC stage 4, slice 2: the merge canvas's logic, kept apart from drawing so it can be checked without a browser
// (node test: src/test/js/merge.test.mjs). The server's edit log is the only state: every act becomes an edit sent to
// POST /autobuilder/merge-sessions/{id}/edits, and the page redraws from the graph that comes back.
(function (root) {
  'use strict';

  const KINDS = ['PROJECT', 'GOAL', 'CRITERION', 'TYPE', 'OPERATION', 'SUPER'];

  /** The session graph as the canvas needs it: nodes by id, children per node, and every node's depth below a super. */
  function model(graph) {
    const nodes = new Map(graph.nodes.map(n => [n.id, n]));
    const children = new Map(), parents = new Map();
    for (const e of graph.edges) {
      if (e.kind === 'STRUCTURE' || e.kind === 'SUPER_OF') {
        if (!children.has(e.from)) children.set(e.from, []);
        children.get(e.from).push(e.to);
        if (!parents.has(e.to)) parents.set(e.to, []);
        parents.get(e.to).push(e.from);
      }
    }
    // A super node sits one level above the highest of its children; a super of supers above that.
    const level = new Map();
    const lift = id => {
      if (level.has(id)) return level.get(id);
      const n = nodes.get(id);
      if (!n || n.kind !== 'SUPER') { level.set(id, 0); return 0; }
      level.set(id, 1);
      const l = 1 + Math.max(0, ...(children.get(id) || []).map(lift));
      level.set(id, l);
      return l;
    };
    for (const id of nodes.keys()) lift(id);
    return { graph, nodes, children, parents, level };
  }

  /**
   * What is shown: nodes whose kind is not hidden and that no folded node hides (a folded node hides everything below it
   * through project structure -- not through a super node, which stays visible over a folded child); and the edges
   * between shown nodes.
   */
  function visible(m, hiddenKinds, folded) {
    const hidden = new Set();
    const hide = id => {
      for (const e of m.graph.edges) {
        if (e.kind === 'STRUCTURE' && e.from === id && !hidden.has(e.to)) { hidden.add(e.to); hide(e.to); }
      }
    };
    for (const id of folded) if (m.nodes.has(id)) hide(id);
    const shown = new Set(m.graph.nodes.filter(n => !hiddenKinds.has(n.kind) && !hidden.has(n.id)).map(n => n.id));
    // What a shown super node was made from stays shown whatever its kind: the merge is what the canvas is for.
    let grew = true;
    while (grew) {
      grew = false;
      for (const e of m.graph.edges) {
        if (e.kind === 'SUPER_OF' && shown.has(e.from) && !shown.has(e.to) && !hidden.has(e.to)) { shown.add(e.to); grew = true; }
      }
    }
    const nodes = m.graph.nodes.filter(n => shown.has(n.id));
    const edges = m.graph.edges.filter(e => shown.has(e.from) && shown.has(e.to));
    return { nodes, edges };
  }

  /** The edit that links two nodes, drawn by the operator. */
  function linkEdit(a, b, id) {
    return { op: 'addEdge', by: 'operator', args: { id, from: a, to: b, kind: 'LINK', metadata: { status: 'confirmed' } } };
  }

  /** The edit that makes a super node over a selection of at least two nodes. */
  function superEdit(selection, id, title) {
    return { op: 'addSuper', by: 'operator', args: { id, title, children: [...selection] } };
  }

  function removeLinkEdit(id) { return { op: 'removeEdge', by: 'operator', args: { id } }; }
  function removeSuperEdit(id) { return { op: 'removeSuper', by: 'operator', args: { id } }; }

  /** Setting a value; an empty value removes the key. */
  function metaEdit(target, key, value) {
    return { op: 'setMeta', by: 'operator', args: { target, key, value: value === '' ? null : value } };
  }

  /** A fresh id with a prefix, not used by any node or edge of the graph. */
  function freshId(graph, prefix) {
    const used = new Set([...graph.nodes.map(n => n.id), ...graph.edges.map(e => e.id)]);
    for (let i = 1; ; i++) if (!used.has(prefix + i)) return prefix + i;
  }

  /** What the canvas allows for a selection: which acts are offered, and why the others are not. */
  function offers(m, selection, selectedEdge) {
    const sel = [...selection];
    const out = {};
    out.link = sel.length === 2 ? null : 'select exactly two nodes';
    out.super = sel.length >= 2 ? null : 'select two or more nodes';
    const edge = selectedEdge ? m.graph.edges.find(e => e.id === selectedEdge) : null;
    out.removeLink = edge && edge.kind === 'LINK' ? null : 'select a link';
    const one = sel.length === 1 ? m.nodes.get(sel[0]) : null;
    out.removeSuper = one && one.kind === 'SUPER'
        ? (m.parents.get(one.id) || []).some(p => (m.nodes.get(p) || {}).kind === 'SUPER') ? 'it is itself merged into another' : null
        : 'select one super node';
    return out;
  }

  /** How a link reads on the canvas: its line by status, its colour by verdict. */
  function linkStyle(edge) {
    const md = edge.metadata || {};
    const dashed = md.status !== 'confirmed';
    const colour = { SAME: 'same', RELATED: 'related', DIFFERENT: 'different' }[md.verdict] || 'operator';
    return { dashed, colour };
  }

  function titleEdit(target, title) { return { op: 'setTitle', by: 'operator', args: { target, title } }; }

  /** Confirming a proposal -- the matcher's link or the model's super node: its status becomes confirmed, by the operator. */
  function confirmEdit(target) { return metaEdit(target, 'status', 'confirmed'); }

  /** Whether a node or an edge is still a proposal (status proposed). */
  function proposed(x) { return !!x && (x.metadata || {}).status === 'proposed'; }

  /**
   * Every act the canvas has for the current selection -- one list read by the right-click menu, the keys and the status
   * line, so the three cannot disagree. {@code why} is null when the act is available, else the reason it is not.
   */
  function actions(m, selection, edge) {
    const o = offers(m, selection, edge);
    const sel = [...selection];
    const one = sel.length === 1 ? m.nodes.get(sel[0]) : null;
    const foldable = one && (one.kind === 'GOAL' || one.kind === 'PROJECT');
    const edgeObj = edge ? m.graph.edges.find(e => e.id === edge) : null;
    return [
      { act: 'link', label: 'Link the two selected nodes', key: 'L', why: o.link },
      { act: 'super', label: 'Make a super node over the selection', key: 'S', why: o.super },
      { act: 'removeLink', label: 'Remove this link', key: 'Delete', why: o.removeLink },
      { act: 'removeSuper', label: 'Remove this super node', key: 'Delete', why: o.removeSuper },
      { act: 'rename', label: 'Rename this super node', key: 'F2', why: one && one.kind === 'SUPER' ? null : 'select one super node' },
      { act: 'edit', label: 'Edit metadata', key: 'E', why: one || edge ? null : 'select one node or a link' },
      { act: 'fold', label: 'Fold / unfold what lies below', key: 'F', why: foldable ? null : 'select one goal or project' },
      { act: 'confirm', label: 'Confirm this proposal', key: 'C', why: proposed(edgeObj) || proposed(one) ? null : 'select a proposed link or node' },
      { act: 'refine', label: 'Ask the model to merge/refine the selection', key: 'P', why: sel.length >= 2 ? null : 'select two or more nodes' },
    ];
  }

  /** The line under the toolbar: what is selected, and what can be done with it. */
  function statusLine(m, selection, edge) {
    const n = selection.size;
    const what = edge ? 'a link' : n === 0 ? 'nothing' : n === 1 ? (m.nodes.get([...selection][0]) || {}).kind.toLowerCase()
        : n + ' nodes';
    const can = actions(m, selection, edge).filter(a => a.why === null);
    const seen = new Set();
    const keys = can.filter(a => !seen.has(a.act) && seen.add(a.act)).map(a => a.key + ' ' + a.label.toLowerCase());
    return 'Selected: ' + what + (keys.length ? ' -- ' + keys.join(' \u00b7 ') : ' -- click a node or a link; ctrl-click or shift-drag for several')
        + (n === 1 && !edge ? ' \u00b7 drag the + handle onto another node to link' : '');
  }

  root.MergeCanvas = { KINDS, model, visible, linkEdit, superEdit, removeLinkEdit, removeSuperEdit, metaEdit, titleEdit, confirmEdit,
    proposed, freshId, offers, linkStyle, actions, statusLine };
})(typeof module !== 'undefined' ? module.exports : window);
