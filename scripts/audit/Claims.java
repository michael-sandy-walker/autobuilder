import com.walpro.autobuilder.core.build.UnchangedClaims;
import java.nio.file.Files;
import java.nio.file.Path;

/** One line per criterion read from the file: "YES | " when UnchangedClaims recognises it as a no-change claim, "no  | " otherwise. */
public class Claims {
    public static void main(String[] a) throws Exception {
        for (String l : Files.readAllLines(Path.of(a[0]))) System.out.println((UnchangedClaims.isClaim(l) ? "YES | " : "no  | ") + l);
    }
}
