/*
 * T5-BI: what the narrator reads aloud, rewritten from what the loop WROTE into what a listener can
 * follow. Goal and criterion texts are written for a reader and for the model: full source paths,
 * qualified type names, parameter lists ("String, String, String, ..."), ids and markers. Spoken,
 * they are long and repetitive, and they are what the user heard (2026-09-28: "some criteria or
 * goals are very long and repetitive, e.g. speaking out full paths or method arguments").
 *
 * Pure text in, text out; the page's TTS calls it on every utterance. Nothing is invented: every
 * rule drops or shortens what the text already says.
 */
(function (root) {
  'use strict';

  const MAX_WORDS = 40;

  // "src/main/java/org/ink/Quill.java", "/home/x/data/t/output/target/app.jar" -> the file's own name.
  const PATH = /(?:[\w.-]*\/)+([\w.-]+)/g;
  // "Quill.dry", "Quill.isWet(...)", "Quill.java": a capitalised name, a dot, a lower-case word -- said
  // as both words. A method and a file extension have the same shape ("EditHunks.isEdit to return
  // false" carried no parenthesis), and dropping the second word as an extension lost the method:
  // "Add EditHunks.apply(...)" and "Fix EditHunks.isEdit ..." were heard as "... Edit Hunks"
  // (T5-BO, 2026-11-08). Saying both loses nothing: "Quill java" is still understood.
  const DOTTED = /\b([A-Z][\w$]*)\.([a-z_$][\w$]*)\b/g;
  // "app-0.0.1-SNAPSHOT" -> "app": a version says nothing a listener needs.
  const VERSION = /-\d+(?:\.\d+)+(?:-[A-Z]+)?/g;
  // "org.ink.quill.Quill" (two or more lowercase segments, then a name) -> "Quill".
  const QUALIFIED = /\b(?:[a-z_][a-z0-9_]*\.){2,}([A-Za-z_$][\w$]*)/g;
  // "dries(String, Map<String,Nib>, int)" -> "dries"; an empty list too.
  const PARAMS = /\b([A-Za-z_$][\w$]*)\((?:[^()]|\([^()]*\))*\)/g;
  const INDEX = /\b([A-Za-z_$][\w$]*)\[(\d+)]/g;
  const UUID = /\b(?:[a-z]+-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;
  const HEX_ID = /\b[0-9a-f]{12,}\b/gi;
  const MARKER = /\[([A-Z][A-Z_]+)]/g;
  const HTTP = /\b(GET|POST|PUT|PATCH|DELETE)\s+(\/[\w{}\/.-]*)/g;

  function words(identifier) {
    // "BookController" -> "Book Controller", "aMethodCalledOnAJdkType" -> "a Method Called On A Jdk Type".
    if (!/[a-z][A-Z]|[A-Z]{2}[a-z]/.test(identifier)) return identifier;
    return identifier.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2');
  }

  function httpPath(verb, path) {
    const parts = path.split('/').filter(Boolean).map(s => s.replace(/^\{(.+)}$/, '$1'));
    return verb + ' ' + (parts.length ? parts.join(' ') : 'root');
  }

  function sayable(text) {
    if (text == null) return '';
    let s = String(text);
    s = s.replace(UUID, '').replace(HEX_ID, '');
    s = s.replace(MARKER, (m, word) => word.toLowerCase().replace(/_/g, ' ') + ':');
    s = s.replace(HTTP, (m, verb, path) => httpPath(verb, path));
    // a directory written with its trailing slash ("src/test/.../evolution/") is said by its own name
    s = s.replace(/(?:[\w.-]+\/)*([\w.-]+)\/(?=[\s,.;:)`'"]|$)/g, '$1');
    s = s.replace(PATH, (m, name) => name.replace(/\.[A-Za-z0-9]{1,5}$/, ''));
    s = s.replace(VERSION, '');
    s = s.replace(DOTTED, '$1 $2');
    s = s.replace(QUALIFIED, (m, name) => name);
    // Parameter lists nest ("f(Map<A,B>, g(x))"); strip until nothing changes, then say an
    // invocation index ("f(String)[10]") as the case it is.
    for (let prev = null; prev !== s;) { prev = s; s = s.replace(PARAMS, '$1'); }
    s = s.replace(INDEX, (m, name, n) => name + ', case ' + n);
    s = s.replace(/[`*_]+/g, ' ').replace(/[<>{}\[\]]/g, ' ');
    s = s.replace(/\b[A-Za-z_$][\w$]*\b/g, w => (w.length > 3 ? words(w) : w));
    // Upper-case words of five letters or more are read as shouting or spelled out; they are
    // constants and markers ("MEMBERS", "SNAPSHOT"), not acronyms.
    s = s.replace(/\b[A-Z]{5,}\b/g, w => w.toLowerCase());
    s = s.replace(/\s+([,.;:])/g, '$1').replace(/([,;:])(?=\S)/g, '$1 ');
    s = s.replace(/\s+/g, ' ').trim().replace(/^[,;:.\s-]+/, '');
    s = dropRepeats(s);
    return cap(s);
  }

  // "Foo, Foo, Foo" and a clause said twice in a row are said once.
  function dropRepeats(s) {
    const clauses = s.split(/(?<=[,;.])\s+/);
    const out = [];
    for (const c of clauses) {
      const key = c.replace(/[,;.]$/, '').toLowerCase();
      if (out.length && out[out.length - 1].replace(/[,;.]$/, '').toLowerCase() === key) {
        // keep the clause once, ending as the LAST repeat ends ("Nib, Nib." -> "Nib.")
        out[out.length - 1] = out[out.length - 1].replace(/[,;.]?$/, (c.match(/[,;.]$/) || [''])[0]);
        continue;
      }
      out.push(c);
    }
    return out.join(' ');
  }

  function cap(s) {
    const w = s.split(' ');
    if (w.length <= MAX_WORDS) return s;
    return w.slice(0, MAX_WORDS).join(' ').replace(/[,;:]$/, '') + ' ... and more.';
  }

  // T5-BO: the files a change touches, as a listener hears them: at most `max` names, the rest
  // counted ("Quill.java, Nib.java and 3 more"). Paths are left for sayable() to shorten, so the
  // names stay written; the narrator speaks them through sayable() like every other line.
  function fileNames(files, max) {
    const list = Array.isArray(files) ? files.filter(f => f != null && String(f).trim()) : [];
    if (!list.length) return '';
    const n = Math.max(1, max || 2);
    // the file's own name without its extension: "EditHunks", said "Edit Hunks"
    const named = list.slice(0, n).map(f => { const t = String(f); return t.slice(t.lastIndexOf('/') + 1).replace(/\.[A-Za-z0-9]{1,10}$/, ''); });
    const rest = list.length - named.length;
    if (rest > 0) return named.join(', ') + ' and ' + rest + ' more';
    return named.length === 1 ? named[0] : named.slice(0, -1).join(', ') + ' and ' + named[named.length - 1];
  }

  // T5-BO: a goal announced by its headline -- the first sentence, without its parenthesised asides
  // (type signatures, return shapes), up to the first clause break, at most HEADLINE_WORDS words.
  // The operator heard "Add EditHunks.apply ..." run on through "(Map<String,String> path->whole new
  // content) and problems() (List<String> ...)" -- the subgoal's name, then its specification.
  const HEADLINE_WORDS = 24;
  function headline(text) {
    let t = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
    t = ((t.match(/^.*?[.!?](?=\s|$)/) || [t])[0]).trim().replace(/[.!?]$/, '');
    for (let prev = null; prev !== t;) { prev = t; t = t.replace(/\s*\([^()]*\)/g, ''); }
    t = t.split(/[,;](?=\s)|\s[\u2014\u2013-]\s/)[0].trim();
    const w = t.split(' ');
    return w.length > HEADLINE_WORDS ? w.slice(0, HEADLINE_WORDS).join(' ') : t;
  }

  const api = { sayable, fileNames, headline, MAX_WORDS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Speakable = api;
})(typeof window !== 'undefined' ? window : this);
