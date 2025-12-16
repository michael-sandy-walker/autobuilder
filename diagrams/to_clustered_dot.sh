DOT_IN=autobuilder-1.0.0-SNAPSHOT.jar.dot
DOT_OUT=autobuilder-clustered.dot
CLUSTER_MAP=cluster-map.txt

awk '
FNR==NR {
  # read cluster map: pattern clustername
  if ($1 !~ /^#/ && NF >= 2) {
    pat[++n] = $1
    cl[n]   = $2
  }
  next
}

/->/ {
  src = $1
  tgt = $3
  gsub(/"/, "", src)
  gsub(/"/, "", tgt)

  sc = "OTHER"
  tc = "OTHER"

  # find first matching pattern (order matters)
  for (i = 1; i <= n; i++) if (src ~ "^" pat[i]) { sc = cl[i]; break }
  for (i = 1; i <= n; i++) if (tgt ~ "^" pat[i]) { tc = cl[i]; break }

  # skip edges involving OTHER (too noisy)
  if (sc == "OTHER" || tc == "OTHER") next

  # skip self-loops
  if (sc == tc) next

  edges[sc "->" tc] = 1
  next
}

END {
  print "digraph G {"
  for (e in edges) {
    split(e, a, "->")
    print "  \"" a[1] "\" -> \"" a[2] "\";"
  }
  print "}"
}
' "$CLUSTER_MAP" "$DOT_IN" > "$DOT_OUT"
