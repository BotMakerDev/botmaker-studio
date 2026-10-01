package com.botmaker.studio.runtime.agent;

import com.botmaker.shared.Diag;

import java.lang.annotation.Annotation;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime half of the trace agent: links each rewritten call site ({@link CallSites}) to its method, and
 * writes one line per call, {@code [ImageFinder] find(ORE) → Match[…]  12 ms}.
 *
 * <p><b>Through the bot's own {@link Diag}</b>, so a traced call is one more debug line: printed to the console,
 * sent to Studio's Trace tab with the bot line that made it, and silent when debugging is off. The source is the
 * called class's simple name, and the writer is that class and method, so the tab's class and method filter hides
 * a call's lines like any other. A bot with no {@code botmaker-shared} on its classpath gets the same line on
 * stderr.
 *
 * <p><b>A repeat is counted, not printed</b>: a bot that polls {@code find(ORE)} until it appears writes the first
 * miss, then one line saying how many more there were, when the call changes or every five seconds.
 *
 * <p>Must be public, with a public bootstrap: a bot class links its call sites from its own package.
 */
public final class TracedCalls {

    /** How long a run of one repeated call may go unreported. */
    private static final long REPORT_AFTER_NANOS = 5_000_000_000L;
    /** The longest a rendered argument or result may be. */
    private static final int MAX_VALUE = 80;
    private static final String UNTRACED = "com.botmaker.plugin.api.palette.Untraced";

    private static final MethodHandle INVOKE;

    static {
        try {
            INVOKE = MethodHandles.lookup().findStatic(TracedCalls.class, "invoke",
                    MethodType.methodType(Object.class, Call.class, MethodHandle.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ThreadLocal<Repeat> REPEATS = ThreadLocal.withInitial(TracedCalls::opened);
    /** Every thread's open repeat, so the run's end reports what is still being counted. */
    private static final Set<Repeat> OPEN = ConcurrentHashMap.newKeySet();
    private static volatile boolean noDiag;

    private TracedCalls() {}

    /** One traced method: what a line is written under, and whether there is a result to show. */
    record Call(Class<?> owner, String method, boolean instance, boolean returnsVoid) {
        String source() {
            return owner.getNestHost().getSimpleName();
        }
    }

    /** One thread's run of a repeated call. Guarded by itself: the run's end reads it from another thread. */
    static final class Repeat {
        Call call;
        String text;
        int count;
        long since;
        long nanos;
    }

    /**
     * Links a call site to {@code name} of {@code owner}: traced, unless the method carries {@code @Untraced}.
     *
     * @param kind {@link CallSites#STATIC}, or {@link CallSites#VIRTUAL} when the site's first argument is the
     *             receiver
     */
    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, int kind,
                                     Class<?> owner) throws ReflectiveOperationException {
        boolean instance = kind != CallSites.STATIC;
        MethodType declared = instance ? type.dropParameterTypes(0, 1) : type;
        MethodHandle target = (instance ? caller.findVirtual(owner, name, declared)
                : caller.findStatic(owner, name, declared)).asFixedArity().asType(type);
        if (untraced(owner, name, declared)) return new ConstantCallSite(target);
        int arity = type.parameterCount();
        MethodHandle spread = target.asSpreader(Object[].class, arity)
                .asType(MethodType.methodType(Object.class, Object[].class));
        Call call = new Call(owner, name, instance, type.returnType() == void.class);
        MethodHandle traced = MethodHandles.insertArguments(INVOKE, 0, call, spread)
                .asCollector(Object[].class, arity)
                .asType(type);
        return new ConstantCallSite(traced);
    }

    private static Object invoke(Call call, MethodHandle target, Object[] args) throws Throwable {
        long started = System.nanoTime();
        Object result;
        try {
            result = (Object) target.invokeExact(args);
        } catch (Throwable thrown) {
            if (enabled()) threw(call, args, thrown, System.nanoTime() - started);
            throw thrown;
        }
        if (enabled()) returned(call, args, result, System.nanoTime() - started);
        return result;
    }

    private static boolean untraced(Class<?> owner, String name, MethodType declared) {
        try {
            Method method = owner.getMethod(name, declared.parameterArray());
            for (Annotation annotation : method.getAnnotations()) {
                if (annotation.annotationType().getName().equals(UNTRACED)) return true;
            }
        } catch (NoSuchMethodException | SecurityException | LinkageError e) {
            // Not a public method of the class itself: traced, as the plan asked.
        }
        return false;
    }

    private static void returned(Call call, Object[] args, Object result, long nanos) {
        String text = call(call, args) + (call.returnsVoid() ? "" : " → " + render(result));
        Repeat repeat = REPEATS.get();
        synchronized (repeat) {
            if (call.equals(repeat.call) && text.equals(repeat.text)) {
                repeat.count++;
                repeat.nanos += nanos;
                if (System.nanoTime() - repeat.since >= REPORT_AFTER_NANOS) report(repeat);
                return;
            }
            report(repeat);
            repeat.call = call;
            repeat.text = text;
            repeat.since = System.nanoTime();
        }
        write(call, text + "  " + elapsed(nanos), 1);
    }

    private static void threw(Call call, Object[] args, Throwable thrown, long nanos) {
        Repeat repeat = REPEATS.get();
        synchronized (repeat) {
            report(repeat);
            repeat.call = null;
            repeat.text = null;
        }
        write(call, call(call, args) + " threw " + thrown.getClass().getSimpleName()
                + (thrown.getMessage() == null ? "" : ": " + clip(thrown.getMessage())) + "  " + elapsed(nanos), 1);
    }

    /** Writes how many more times the repeated call happened, if it did, and starts counting again. */
    private static void report(Repeat repeat) {
        if (repeat.count > 0 && repeat.call != null) {
            long over = System.nanoTime() - repeat.since;
            write(repeat.call, repeat.text + "  again, " + elapsed(repeat.nanos / repeat.count) + " each, over "
                    + elapsed(over), repeat.count);
        }
        repeat.count = 0;
        repeat.nanos = 0;
        repeat.since = System.nanoTime();
    }

    /** Reports every thread's open repeat: the run is ending, and the last of a poll loop is still being counted. */
    static void reportOpen() {
        for (Repeat repeat : OPEN) {
            synchronized (repeat) {
                report(repeat);
            }
        }
    }

    private static Repeat opened() {
        Repeat repeat = new Repeat();
        OPEN.add(repeat);
        return repeat;
    }

    private static boolean enabled() {
        if (noDiag) return true;
        try {
            return Diag.isEnabled();
        } catch (LinkageError e) {
            noDiag = true;
            return true;
        }
    }

    private static void write(Call call, String text, int count) {
        if (!noDiag) {
            try {
                Diag.log(new Diag.Origin(call.source(), call.owner().getName(), call.method()), text, count, null);
                return;
            } catch (LinkageError e) {
                noDiag = true;
            }
        }
        System.err.println("[" + call.source() + "] " + text + (count > 1 ? "  (×" + count + ")" : ""));
    }

    /** {@code method(arg, arg)}: the receiver of an instance call is left out, its class is the source. */
    static String call(Call call, Object[] args) {
        StringBuilder text = new StringBuilder(call.method()).append('(');
        for (int i = call.instance() ? 1 : 0; i < args.length; i++) {
            if (text.charAt(text.length() - 1) != '(') text.append(", ");
            text.append(render(args[i]));
        }
        return text.append(')').toString();
    }

    /** A value as its own {@code toString} says it, cut to {@value #MAX_VALUE} characters. */
    static String render(Object value) {
        if (value == null) return "null";
        String text;
        try {
            text = switch (value) {
                case String s -> "\"" + s + "\"";
                case Object[] array -> Arrays.deepToString(array);
                case int[] array -> Arrays.toString(array);
                case long[] array -> Arrays.toString(array);
                case double[] array -> Arrays.toString(array);
                case boolean[] array -> Arrays.toString(array);
                default -> String.valueOf(value);
            };
        } catch (RuntimeException e) {
            text = value.getClass().getSimpleName();
        }
        return clip(text);
    }

    private static String clip(String text) {
        return text.length() <= MAX_VALUE ? text : text.substring(0, MAX_VALUE - 1) + "…";
    }

    /** {@code 340 ms} below a second, {@code 1.4 s} above, as the SDK's own lines read. */
    static String elapsed(long nanos) {
        long millis = nanos / 1_000_000;
        return millis < 1_000 ? millis + " ms" : String.format(Locale.ROOT, "%.1f s", millis / 1_000.0);
    }
}
