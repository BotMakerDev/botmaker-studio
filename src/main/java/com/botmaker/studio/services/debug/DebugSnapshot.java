package com.botmaker.studio.services.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the Debug tab shows while the bot is paused (2026-09-26): the paused thread's frames, and each frame's
 * variables with one level of fields. Read <b>once, on the JDI event thread, while the VM is suspended</b> —
 * a {@code StackFrame} is invalid the moment the thread resumes, so nothing here keeps a JDI object; the FX
 * side only ever sees these records.
 *
 * <p>No method of the bot is invoked to show a value (no {@code toString()}): invoking code in a paused VM
 * can deadlock on a lock the paused thread holds, and runs the bot's own code behind the user's back. A value
 * is what JDI can read: a primitive, a string, an enum's constant, a boxed number, an array's first elements,
 * or an object's class and fields.
 *
 * @param frames innermost first
 */
public record DebugSnapshot(String thread, List<Frame> frames) {

    /** Frames past this are counted, not listed: a deep recursion must not stall the pause. */
    static final int MAX_FRAMES = 60;
    /** Array elements and object fields shown under one value. */
    static final int MAX_CHILDREN = 20;
    /** A string longer than this is cut. */
    static final int MAX_TEXT = 200;

    /** Classes whose frames are the JDK's, not the bot's; shown greyed and never jumped to. */
    private static final Set<String> PLATFORM = Set.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    /**
     * One frame.
     *
     * @param file the bot source it is in, or null for a library or JDK frame
     */
    public record Frame(String method, String className, Path file, int line, List<Variable> variables) {
        /** {@code Bot.play:18} — what the frame list shows. */
        public String label() {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            return simple + "." + method + (line > 0 ? ":" + line : "");
        }

        public boolean inBot() {
            return file != null;
        }
    }

    /** One variable or field, and what is inside it (an array's elements, an object's fields). */
    public record Variable(String name, String type, String value, List<Variable> children) {}

    public static DebugSnapshot empty() {
        return new DebugSnapshot("", List.of());
    }

    /** Reads {@code thread}'s stack. {@code targets} says which frames are the bot's own sources. */
    public static DebugSnapshot of(ThreadReference thread, DebugTargets targets) {
        List<Frame> frames = new ArrayList<>();
        try {
            List<StackFrame> stack = thread.frames();
            for (int i = 0; i < stack.size() && i < MAX_FRAMES; i++) frames.add(frame(stack.get(i), targets));
            if (stack.size() > MAX_FRAMES) {
                frames.add(new Frame("… " + (stack.size() - MAX_FRAMES) + " more", "", null, 0, List.of()));
            }
        } catch (IncompatibleThreadStateException notSuspended) {
            // Resumed between the event and this read: nothing to show, and the next pause reads again.
        }
        return new DebugSnapshot(thread.name(), List.copyOf(frames));
    }

    private static Frame frame(StackFrame frame, DebugTargets targets) {
        Location at = frame.location();
        String className = at.declaringType().name();
        Path file = targets == null ? null
                : targets.forClass(className).map(DebugTargets.FileTargets::file).orElse(null);
        List<Variable> variables = new ArrayList<>();
        ObjectReference self = frame.thisObject();
        if (self != null && file != null) variables.add(variable("this", self.referenceType().name(), self, 1));
        if (!isPlatform(className)) {
            try {
                for (LocalVariable local : frame.visibleVariables()) {
                    variables.add(variable(local.name(), local.typeName(), frame.getValue(local), 1));
                }
            } catch (AbsentInformationException noDebugInfo) {
                // A library compiled without -g: its frame is listed, its locals are not.
            }
        }
        return new Frame(at.method().name(), className, file, at.lineNumber(), List.copyOf(variables));
    }

    private static boolean isPlatform(String className) {
        return PLATFORM.stream().anyMatch(className::startsWith);
    }

    /** A value and, {@code depth} levels down, what it holds. */
    static Variable variable(String name, String type, Value value, int depth) {
        return new Variable(name, simple(type), describe(value), depth > 0 ? children(value, depth - 1) : List.of());
    }

    private static List<Variable> children(Value value, int depth) {
        List<Variable> out = new ArrayList<>();
        if (value instanceof ArrayReference array) {
            int n = Math.min(array.length(), MAX_CHILDREN);
            for (int i = 0; i < n; i++) {
                Value element = array.getValue(i);
                out.add(variable("[" + i + "]", element == null ? "" : element.type().name(), element, depth));
            }
        } else if (value instanceof ObjectReference object && !(value instanceof StringReference)
                && !(value instanceof ClassObjectReference) && !isPlatform(object.referenceType().name())) {
            for (Field field : object.referenceType().allFields()) {
                if (field.isStatic()) continue;
                if (out.size() == MAX_CHILDREN) break;
                out.add(variable(field.name(), field.typeName(), object.getValue(field), depth));
            }
        }
        return List.copyOf(out);
    }

    /** What a value reads as, without running any of the bot's code. */
    static String describe(Value value) {
        return switch (value) {
            case null -> "null";
            case StringReference s -> quote(s.value());
            case CharValue c -> "'" + c.value() + "'";
            case BooleanValue b -> String.valueOf(b.value());
            case PrimitiveValue p -> p.toString();
            case ArrayReference a -> simple(a.referenceType().name()).replace("[]", "[" + a.length() + "]");
            case ObjectReference o -> object(o);
            default -> value.toString();
        };
    }

    private static String object(ObjectReference o) {
        String type = o.referenceType().name();
        if (o.referenceType() instanceof com.sun.jdi.ClassType c && c.isEnum()) {
            Field name = c.fieldByName("name");
            if (name != null && o.getValue(name) instanceof StringReference s) return simple(type) + "." + s.value();
        }
        if (type.startsWith("java.lang.") && o.referenceType().fieldByName("value") != null) {
            Value boxed = o.getValue(o.referenceType().fieldByName("value"));
            if (boxed instanceof PrimitiveValue) return describe(boxed);
        }
        return simple(type) + " #" + o.uniqueID();
    }

    private static String quote(String s) {
        String cut = s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
        return "\"" + cut.replace("\n", "\\n") + "\"";
    }

    private static String simple(String type) {
        if (type == null) return "";
        int generic = type.indexOf('<');
        String raw = generic < 0 ? type : type.substring(0, generic);
        return raw.substring(raw.lastIndexOf('.') + 1).replace('$', '.');
    }
}
