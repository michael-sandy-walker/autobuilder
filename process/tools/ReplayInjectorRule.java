import com.walpro.autobuilder.core.build.MissingTypeExtractor;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** args: base-tree candidates-dir work-dir. For each candidate: overlay, compile (main+test), rewrite moved nested types, recompile. */
public class ReplayInjectorRule {
  static String compile(Path dir) throws Exception {
    Process p = new ProcessBuilder("mvn", "-q", "-o", "test-compile").directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    return p.waitFor() == 0 ? null : out;
  }
  static void copy(Path from, Path to) throws Exception {
    try (Stream<Path> s = Files.walk(from)) {
      for (Path p : s.toList()) {
        Path t = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
  static Map<String,String> sources(Path root) throws Exception {
    Map<String,String> m = new LinkedHashMap<>();
    try (Stream<Path> s = Files.walk(root.resolve("src"))) {
      for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) m.put(root.relativize(p).toString(), Files.readString(p));
    }
    return m;
  }
  static String symbols(String out) { return out.replaceAll("\u001B\\[[;\\d]*m","").lines().filter(l -> l.contains("symbol:")).map(String::strip).distinct().limit(3).collect(Collectors.joining(","));}
  static String firstErrors(String out) {
    return out.replaceAll("\u001B\\[[;\\d]*m","").lines().filter(l -> l.contains("[ERROR] /") && l.contains(".java:[")).map(l -> l.replaceAll(".*/", "")).distinct().limit(3).collect(Collectors.joining(" | "));
  }
  public static void main(String[] a) throws Exception {
    Class<?> c = Class.forName("com.walpro.autobuilder.core.build.inject.MovedNestedTypeResolver");
    Method rewrite = c.getDeclaredMethod("rewrite", Map.class, String.class, String.class);
    rewrite.setAccessible(true);
    Path base = Path.of(a[0]), cands = Path.of(a[1]), work = Path.of(a[2]);
    int failedBefore = 0, fixedByRewrite = 0, fired = 0;
    List<Path> dirs;
    try (Stream<Path> s = Files.list(cands)) { dirs = s.filter(Files::isDirectory).sorted().toList(); }
    for (Path cand : dirs) {
      if (!Files.exists(cand.resolve("src"))) continue;
      Path w = work.resolve(cand.getFileName().toString());
      if (Files.exists(w)) try (Stream<Path> s = Files.walk(w)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
      copy(base, w);
      try (Stream<Path> s = Files.walk(w.resolve("target"))) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignored) {}
      copy(cand.resolve("src"), w.resolve("src"));
      String err = compile(w);
      if (err == null) { System.out.println(cand.getFileName() + " compiles as it is"); continue; }
      failedBefore++;
      String before = firstErrors(err);
      boolean any = false;
      for (int pass = 0; pass < 5 && err != null; pass++) {
        List<MissingTypeExtractor.Entry> moved = MissingTypeExtractor.extract(err).stream().filter(e -> e.insideClass() != null).toList();
        boolean changed = false;
        for (MissingTypeExtractor.Entry e : (a.length > 4 ? List.<MissingTypeExtractor.Entry>of() : moved)) {
          String owner = e.insideClass().substring(e.insideClass().lastIndexOf('.') + 1);
          @SuppressWarnings("unchecked") Map<String,String> out = (Map<String,String>) rewrite.invoke(null, sources(w), owner, e.typeName());
          for (var f : out.entrySet()) { Files.writeString(w.resolve(f.getKey()), f.getValue()); changed = true; }
        }
        if (!changed && a.length > 3) {
          // emulate symbol-import:local -- a bare type the tree declares at top level, imported where it is used
          Method imp = Class.forName("com.walpro.autobuilder.core.build.inject.SymbolImportResolver").getDeclaredMethod("insertImport", String.class, String.class);
          imp.setAccessible(true);
          Map<String,String> src = sources(w);
          for (MissingTypeExtractor.Entry e : MissingTypeExtractor.extract(err)) {
            if (e.referencedBy() == null) continue;
            String refClass = e.referencedBy().replaceAll(".*/", "").replace(".java", "");
            if (e.insideClass() != null && !e.insideClass().endsWith("." + refClass) && !e.insideClass().equals(refClass)) continue;
            String fqn = null;
            for (var f : src.entrySet()) if (f.getKey().endsWith("/" + e.typeName() + ".java")) {
              var m = java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+);").matcher(f.getValue());
              if (m.find()) fqn = m.group(1) + "." + e.typeName();
            }
            if (fqn == null) continue;
            String rel = e.referencedBy().replaceAll(".*/output/", "").replaceAll(".*?(src/)", "$1");
            Path file = w.resolve(rel);
            if (!Files.exists(file)) continue;
            String content = Files.readString(file);
            if (content.contains("import " + fqn + ";")) continue;
            Files.writeString(file, (String) imp.invoke(null, content, fqn)); changed = true;
          }
        }
        if (!changed) break;
        any = true;
        err = compile(w);
      }
      if (any) fired++;
      if (any && err == null) fixedByRewrite++;
      System.out.println(cand.getFileName() + " FAILED [" + before + "] -> " + (any ? (err == null ? "COMPILES after rewrite" : "still fails [" + firstErrors(err) + "] {" + symbols(err) + "}") : "rule did not fire"));
    }
    System.out.println("failed before: " + failedBefore + ", rule fired: " + fired + ", compile after rewrite: " + fixedByRewrite);
  }
}
