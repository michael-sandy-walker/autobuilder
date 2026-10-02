import com.walpro.autobuilder.core.build.*;
import java.nio.file.*; import java.util.*;
public class RecordFirstReplay { public static void main(String[] a) throws Exception {
  for (String line : Files.readAllLines(Path.of(a[0]))) {
    String[] f = line.split("\t", -1); // run status crit goal modified added removed
    var ch = new AdoptionManifest.Changes(sp(f[4]), sp(f[5]), sp(f[6]));
    Boolean ans = UnchangedClaims.answer(f[2], ch, f[3]);
    if (ans == null) continue;
    System.out.println(f[0] + "\t" + f[1] + "\t" + ans + "\t" + f[2] + (ans ? "" : "\t  changed=" + ch.changedOrAdded() + " removed=" + ch.removed()));
  }}
  static List<String> sp(String s){ return s.isEmpty()? List.of() : Arrays.asList(s.split(",")); } }
