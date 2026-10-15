package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.managed.ManagedRepairs;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fields a plugin's move leaves behind, put right one at a time (2026-10-13): a plugin's parameter set back to its
 * first value — or, written as another type than the plugin declares now, removed and written anew — and a field
 * nothing needs deleted. The assistant's {@code reset_param} and {@code delete_field}.
 *
 * <p><b>Pure, over the bot's one parse</b>: each answers a plan compiled as the whole bot, or why there is none;
 * nothing is written here.
 */
public final class LeftoverFields {

    private LeftoverFields() {}

    /**
     * {@code parameter}, a plugin's, as its plugin declares it now: its value set back to the declaration's first
     * one, or — a field of another type, which the upgrade's own test finds ({@link ManagedRepairs#fieldFits}) —
     * removed and written anew in its holder. Refused for the bot's own parameter, one no plugin declares now, and
     * a plan that would stop the bot compiling.
     *
     * @param live every value the bound plugins declare
     */
    public static Refactor.Outcome reset(BotIndex index, JavaParameter parameter, List<ManagedValue<?>> live,
                                         ValueGrammar grammar) {
        String name = parameter.qualified();
        if (!parameter.declaredByPlugin()) {
            return new Refactor.Refused(name + " is the bot's own parameter: params set_default sets it.");
        }
        Optional<ManagedValue<?>> declared = live.stream()
                .filter(value -> value != null && value.isParam() && value.id().equals(parameter.managedId()))
                .findFirst();
        if (declared.isEmpty()) {
            return new Refactor.Refused(name + " is no longer one of its plugin's parameters: delete_field removes it"
                    + " once nothing uses it.");
        }
        ManagedValue<?> value = declared.get();
        Path file = parameter.file().toAbsolutePath().normalize();
        String text = index.sources().get(file);
        if (text == null) return new Refactor.Refused(name + "'s file is not part of the bot any more.");
        Optional<Boolean> fits = ManagedRepairs.fieldFits(index, value, live, file, parameter.className());
        if (fits.isEmpty()) {
            return new Refactor.Refused(name + " could not be read as its plugin's field, so nothing was changed.");
        }
        Refactor.Planned plan;
        if (fits.get()) {
            Optional<JavaValue> first = value.initial() == null ? Optional.empty()
                    : grammar.initializer(value.form(), value.initial());
            if (first.isEmpty()) {
                return new Refactor.Refused(name + "'s first value cannot be written as Java, so nothing was changed.");
            }
            String reset = JavaParameterEdits.setValue(text, parameter.className(), parameter.name(), first.get());
            if (reset.equals(text)) {
                return new Refactor.Refused(parameter.initializer().strip().equals(first.get().source().strip())
                        ? name + " already has its first value, " + first.get().source() + "."
                        : name + "'s value could not be rewritten, so nothing was changed.");
            }
            plan = new Refactor.Planned("Set " + name + " back to its first value, " + first.get().source(),
                    Map.of(file, reset));
        } else {
            // Written anew only in its holder, where the plugin looks for it: anywhere else it would just be gone.
            if (!parameter.className().equals(value.holder())) {
                return new Refactor.Refused(name + " is another type than its plugin declares, and it is not in "
                        + value.holder() + ", where its plugin writes it: move it there, or delete it by hand.");
            }
            String removed = JavaParameterEdits.remove(text, parameter.className(), parameter.name());
            String written = ManagedHolders.keptValues(removed, List.of(value), grammar);
            if (removed.equals(text) || written.equals(removed)) {
                return new Refactor.Refused(name + " could not be written anew, so nothing was changed.");
            }
            plan = new Refactor.Planned("Removed " + name + ", which is not the type its plugin declares now, and"
                    + " wrote it anew as the plugin declares it", Map.of(file, written));
        }
        return Refactor.checked(index, plan, "Resetting " + name);
    }

    /**
     * The field {@code field} — {@code name}, or {@code Class.name} when two classes of {@code file} have one —
     * deleted from {@code file}. Refused, with every use, while anything uses it; for a plugin's parameter, which
     * its plugin writes back; and when the bot would stop compiling.
     *
     * @param live every value the bound plugins declare
     */
    public static Refactor.Outcome delete(BotIndex index, Path file, String field, List<ManagedValue<?>> live) {
        Path at = file.toAbsolutePath().normalize();
        String wanted = field == null ? "" : field.strip();
        int dot = wanted.lastIndexOf('.');
        String className = dot < 0 ? null : wanted.substring(0, dot);
        String fieldName = wanted.substring(dot + 1);
        String text = index.sources().get(at);
        if (text == null) return new Refactor.Refused(file.getFileName() + " is not one of the bot's files.");
        Located located = index.read(units -> {
            CompilationUnit unit = units.get(at);
            List<VariableDeclarationFragment> found = new ArrayList<>();
            if (unit != null) {
                unit.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(VariableDeclarationFragment fragment) {
                        if (fragment.getParent() instanceof FieldDeclaration
                                && fragment.getName().getIdentifier().equals(fieldName)
                                && fragment.getParent().getParent() instanceof AbstractTypeDeclaration type
                                && (className == null || type.getName().getIdentifier().equals(className))) {
                            found.add(fragment);
                        }
                        return false;
                    }
                });
            }
            if (found.isEmpty()) {
                return Located.refused(file.getFileName() + " has no field " + wanted + ".");
            }
            if (found.size() > 1) {
                return Located.refused("More than one class of " + file.getFileName() + " has a field "
                        + fieldName + ": give Class." + fieldName + ".");
            }
            VariableDeclarationFragment fragment = found.getFirst();
            FieldDeclaration declaration = (FieldDeclaration) fragment.getParent();
            String owner = ((AbstractTypeDeclaration) declaration.getParent()).getName().getIdentifier();
            String qualified = owner + "." + fieldName;
            Annotation mark = ManagedIds.on(declaration, live);
            String id = mark == null ? "" : ManagedIds.idOf(mark, live);
            if (!id.isEmpty() && live.stream().anyMatch(value -> value != null && id.equals(value.id()))) {
                return Located.refused(qualified + " is a plugin's, which BotMaker writes back while the plugin"
                        + " is in the project: params reset sets a parameter back to how its plugin declares it.");
            }
            ASTRewrite rewriter = ASTRewrite.create(unit.getAST());
            rewriter.remove(declaration.fragments().size() == 1 ? declaration : fragment, null);
            return new Located(null, qualified, fragment.getName().getStartPosition(),
                    AstRewriteHelper.applyRewrite(rewriter, text));
        });
        if (located.refused() != null) return located.refused();
        String qualified = located.qualified();
        Optional<List<Usages.Usage>> uses = Refactor.uses(index, at, located.start());
        if (uses.isEmpty()) {
            return new Refactor.Refused(qualified + " could not be resolved, so whether anything still uses it"
                    + " cannot be told. Nothing was deleted.");
        }
        if (!uses.get().isEmpty()) {
            return new Refactor.Refused(qualified + " is still used " + uses.get().size()
                    + (uses.get().size() == 1 ? " time" : " times") + ": change those first.", uses.get(), List.of());
        }
        Refactor.Planned plan = new Refactor.Planned("Deleted the field " + qualified, Map.of(at, located.without()));
        return Refactor.checked(index, plan, "Deleting " + qualified);
    }

    /** The field found under the parse's lock, or why there is none; checked once the lock is let go. */
    private record Located(Refactor.Refused refused, String qualified, int start, String without) {

        static Located refused(String reason) {
            return new Located(new Refactor.Refused(reason), null, -1, null);
        }
    }
}
