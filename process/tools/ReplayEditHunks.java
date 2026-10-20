import com.walpro.autobuilder.protocol.evolution.EditHunks;
import java.nio.file.*; import java.util.*; import java.util.stream.*;
public class ReplayEditHunks {
  static Map<String,String> tree(String root) throws Exception {
    Path r=Path.of(root); Map<String,String> m=new HashMap<>();
    try (Stream<Path> s=Files.walk(r)) { for (Path p: s.filter(Files::isRegularFile).filter(x->x.toString().endsWith(".java")).toList()) m.put(r.relativize(p).toString(), Files.readString(p)); }
    return m;
  }
  public static void main(String[] a) throws Exception {
    Map<String,Map<String,String>> trees=new LinkedHashMap<>();
    for (int i=1;i<a.length;i++) trees.put(a[i], tree(a[i]));
    try (Stream<Path> s=Files.list(Path.of(a[0]))) {
      for (Path p: s.filter(x->x.toString().endsWith(".txt")).sorted().toList()) {
        String ans=Files.readString(p);
        StringBuilder line=new StringBuilder(p.getFileName().toString());
        for (var e: trees.entrySet()) {
          var ap=EditHunks.apply(ans, e.getValue());
          line.append(" | ").append(Path.of(e.getKey()).getFileName()).append(": applied ").append(ap.files().size())
              .append(" refused ").append(ap.problems().size());
          if (!ap.problems().isEmpty()) line.append(" ").append(ap.problems().get(0).replaceAll(".*/","").substring(0, Math.min(90, ap.problems().get(0).replaceAll(".*/","").length())));
        }
        System.out.println(line);
      }
    }
  }
}
