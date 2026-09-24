package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.slot.TypeRef;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a plugin's editor is handed for a slot: the slot's type and the call around it, both resolved from
 * the binding and compared by class — never by how the source spelled a name.
 */
class HostTypesTest {

    private static final String SOURCE = """
            package com.example.bot;

            import java.time.Duration;
            import java.util.Map;

            public class Bot {
                /** The bot's own Duration, which a name-matching editor used to claim. */
                static final class Pause {}

                void run() {
                    Duration wait = Duration.ofSeconds(3);
                    Duration.ofSeconds(3, 500);
                    String joined = String.join(",", "a", "b");
                    Pause own = new Pause();
                    Map<String, Integer> counts = Map.of();
                }
            }
            """;

    private static final CompilationUnit UNIT =
            ProjectAnalyzer.createCompilationUnit(List.of(), SOURCE, null, "Bot.java");

    private static List<MethodInvocation> calls() {
        List<MethodInvocation> calls = new ArrayList<>();
        UNIT.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                calls.add(node);
                return true;
            }
        });
        return calls;
    }

    private static TypeRef typeOf(String variable) {
        List<TypeRef> found = new ArrayList<>();
        UNIT.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                if (node.getName().getIdentifier().equals(variable)) {
                    found.add(HostTypes.of(node.resolveBinding().getType()));
                }
                return true;
            }
        });
        return found.getFirst();
    }

    @Test
    void aCallIsTheOverloadTheSourceCalled() throws NoSuchMethodException {
        Executable one = HostTypes.executable(calls().get(0).resolveMethodBinding()).orElseThrow();
        Executable two = HostTypes.executable(calls().get(1).resolveMethodBinding()).orElseThrow();

        assertEquals(java.time.Duration.class.getMethod("ofSeconds", long.class), one);
        assertEquals(java.time.Duration.class.getMethod("ofSeconds", long.class, long.class), two);
    }

    @Test
    void aVarargsCallResolvesToItsArrayParameter() throws NoSuchMethodException {
        Method join = String.class.getMethod("join", CharSequence.class, CharSequence[].class);

        assertEquals(join, HostTypes.executable(calls().get(2).resolveMethodBinding()).orElseThrow());
        assertTrue(join.isVarArgs());
    }

    @Test
    void aTypeIsItsClassWithEverySupertype() {
        TypeRef wait = typeOf("wait");

        assertTrue(wait.is(java.time.Duration.class));
        assertTrue(wait.isSubtypeOf(TemporalAmount.class));
        assertTrue(wait.isSubtypeOf(Object.class));
        assertEquals("Duration", wait.displayName());
    }

    @Test
    void aParameterizedTypeIsItsErasure() {
        assertTrue(typeOf("counts").is(Map.class));
    }

    /** The bot's own class resolves too — to itself, which no plugin class is. */
    @Test
    void aBotsOwnClassIsNoPluginsClass() {
        TypeRef own = typeOf("own");

        assertTrue(own.isResolved());
        assertFalse(own.is(java.time.Duration.class));
        assertTrue(own.isSubtypeOf(Object.class));
    }

    /** A bare name the binding never resolved is a spelling, and nothing claims it. */
    @Test
    void aBareNameIsUnresolved() {
        TypeRef bare = HostTypes.named("Duration", "Duration");

        assertFalse(bare.isResolved());
        assertFalse(bare.is(java.time.Duration.class));
    }

    @Test
    void anUnresolvedCallIsNoCall() {
        assertTrue(HostTypes.executable(null).isEmpty());
    }
}
