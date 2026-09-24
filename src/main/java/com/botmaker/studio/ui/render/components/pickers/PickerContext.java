package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.IMethodBinding;

/**
 * Everything a {@link SpecialTypePicker} needs to decide whether it applies and to build its editor:
 * the slot being edited ({@code arg} — a {@link ValueSlot}, so a picker works as well over a variable's
 * initializer as over a block on the canvas), the expected {@code paramType}, the {@code argIndex} of this
 * argument within the enclosing call, and — for method-specific pickers (e.g. the Steam game picker for
 * {@code Game.launchSteam}) — the enclosing call's {@code call} binding, which a plugin is handed as the
 * resolved {@code Executable}. Use {@link #of} when there is no call context (e.g. a header slot or list
 * element), which leaves the call null and the index {@code -1}.
 */
public record PickerContext(CodeEditorService context, ValueSlot arg, ResolvedType paramType,
                            IMethodBinding call, int argIndex, boolean readOnly) {

    /**
     * The same for a caller that says nothing about writability — editable, which is what every caller meant
     * before {@code readOnly} existed (2026-09-19).
     */
    public PickerContext(CodeEditorService context, ValueSlot arg, ResolvedType paramType,
                         IMethodBinding call, int argIndex) {
        this(context, arg, paramType, call, argIndex, false);
    }

    /** A context with no enclosing call (null, index -1) — for header slots and list elements. */
    public static PickerContext of(CodeEditorService context, ValueSlot arg, ResolvedType paramType) {
        return new PickerContext(context, arg, paramType, null, -1);
    }

    /**
     * The same, saying whether the block that draws this slot may be edited. A read-only slot gets a plugin's
     * preview — a thumbnail, a swatch — never a control that opens a chooser and then has its write refused.
     *
     * <p><b>The owning block is asked, not {@code LockResolver}.</b> Both know the answer, but only the block
     * knows it at the moment its node is built: a render reads the resolver once for the whole file and
     * writes the verdict into its blocks, so asking the resolver again here answers about whatever file the
     * editor has open by then — which, during the re-render an edit causes, is not reliably this one.
     */
    public static PickerContext of(CodeEditorService context, ValueSlot arg, ResolvedType paramType,
                                   boolean readOnly) {
        return new PickerContext(context, arg, paramType, null, -1, readOnly);
    }

    /** True when {@code paramType} is the SDK type {@code sdkType}. */
    public boolean isType(Class<?> sdkType) {
        return paramType != null && paramType.is(sdkType);
    }

    /** True when {@code paramType} is (by simple or qualified name) {@code simpleName} — for JDK types. */
    public boolean isType(String simpleName) {
        return paramType != null
                && (paramType.simpleName().equals(simpleName)
                    || paramType.qualifiedName().endsWith("." + simpleName));
    }

    // The four Game predicates — the program path, the trailing launch options, and the Steam and Epic launch
    // ids — went with their editors on 2026-08-28 (plugin platform, phase 12c). They are
    // that plugin's own call-site matchers now, written against SlotContext's enclosingExecutable / argIndex,
    // which is the same two facts this record carries and is what the contract exposes them for.

    // isEmulatorNameArg and isEmulatorMethod went on 2026-08-31 with the picker they selected. The same four
    // calls are matched by
    // the SDK's CallSites.EMULATOR_NAME now, through the toolkit's own call-site matcher — which is where a
    // predicate about somebody else's API belonged all along.

    // The Time facade has no entry here: every one of its arguments is a java.time type (LocalTime, DayOfWeek,
    // Month) and dispatches on that. It used to need an isTimeHourArg() hook for the bare hours of
    // isBetween(int, int) / isBetweenUtc(int, int); those overloads were removed in favour of the LocalTime
    // pair, and the hook went with them.

    // The BotSettings predicate went the same way, and with it the one thing it duplicated: the list of which
    // setters are bounded now lives beside the ranges it selects (SettingsEditors.bounds), so a setter cannot
    // be claimed by a predicate that a table has no entry for.
}
