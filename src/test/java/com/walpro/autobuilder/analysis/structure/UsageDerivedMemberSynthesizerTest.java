package com.walpro.autobuilder.analysis.structure;

import com.walpro.autobuilder.analysis.structure.UsageDerivedMemberSynthesizer.MemberSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BQ slice 1: the walker reports the member signatures call sites prove must exist on a
 * missing type, from constructor calls and from method calls on receivers declared of that type.
 */
class UsageDerivedMemberSynthesizerTest {

    private final UsageDerivedMemberSynthesizer synth = new UsageDerivedMemberSynthesizer();

    private static void write(Path root, String pkgPath, String name, String body) throws Exception {
        Path dir = root.resolve(pkgPath);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"), body);
    }

    @Test
    void method_calls_on_a_typed_receiver_become_methods(@TempDir Path root) throws Exception {
        write(root, "com/example/demo/service", "LoanService", """
                package com.example.demo.service;
                import com.example.demo.repository.LoanRepository;
                public class LoanService {
                    private final LoanRepository loanRepository;
                    public LoanService(LoanRepository loanRepository) { this.loanRepository = loanRepository; }
                    public Object find(Long bookId) { return loanRepository.findByBookId(bookId); }
                }
                """);

        List<MemberSignature> members = synth.synthesize("LoanRepository", root);

        assertThat(members)
                .anySatisfy(m -> {
                    assertThat(m.constructor()).isFalse();
                    assertThat(m.name()).isEqualTo("findByBookId");
                    assertThat(m.paramTypes()).hasSize(1);
                });
    }

    @Test
    void constructor_calls_become_a_constructor(@TempDir Path root) throws Exception {
        write(root, "com/example/demo/controller", "LoanController", """
                package com.example.demo.controller;
                import com.example.demo.model.ErrorResponse;
                public class LoanController {
                    public Object handle() { return new ErrorResponse("not found", 404); }
                }
                """);

        List<MemberSignature> members = synth.synthesize("ErrorResponse", root);

        assertThat(members)
                .anySatisfy(m -> {
                    assertThat(m.constructor()).isTrue();
                    assertThat(m.paramTypes()).hasSize(2);
                });
    }

    @Test
    void the_types_own_declaration_file_is_ignored(@TempDir Path root) throws Exception {
        // A method *declared* on the type is not a usage to learn from — only call sites count.
        write(root, "com/example/demo/model", "Book", """
                package com.example.demo.model;
                public class Book {
                    public String getTitle() { return null; }
                }
                """);

        assertThat(synth.synthesize("Book", root)).isEmpty();
    }

    @Test
    void unknown_argument_types_default_to_object(@TempDir Path root) throws Exception {
        write(root, "com/example/demo/controller", "Caller", """
                package com.example.demo.controller;
                public class Caller {
                    public Object go(Object req) { return new Widget(req); }
                }
                """);

        List<MemberSignature> members = synth.synthesize("Widget", root);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).paramTypes()).containsExactly("Object");
    }
}
