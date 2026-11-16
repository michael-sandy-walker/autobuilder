// T5-BV: which project the run context serving this page has open -- read from its own GET /autobuilder/start-state,
// again every 10 seconds, so a page left open follows when another project is started (or says none is open once the
// run has ended). onChange(project) gets {name, lineageId} or null; it is called only when the answer changes.
function watchOpenProject(onChange) {
  let last;
  async function read() {
    let now = null;
    try {
      const r = await fetch('/autobuilder/start-state', { cache: 'no-store' });
      if (r.ok) {
        const s = await r.json();
        if (s.target) now = { name: s.target, lineageId: s.lineageId || null };
      }
    } catch (e) { /* no run context answers: no project open */ }
    const key = JSON.stringify(now);
    if (key !== last) { last = key; onChange(now); }
  }
  read();
  setInterval(read, 10000);
}

// The header pill both graph pages show.
function showOpenProject(el, project) {
  el.textContent = project ? 'project: ' + project.name : 'no project open';
  el.title = project ? 'The project this run has open' + (project.lineageId ? ' (lineage ' + project.lineageId + ')' : '')
      : 'No run context answered: no project is open';
}
