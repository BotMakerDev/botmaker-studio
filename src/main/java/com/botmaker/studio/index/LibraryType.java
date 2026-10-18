package com.botmaker.studio.index;

import java.lang.reflect.Modifier;
import java.util.List;

/**
 * One library class as the type index keeps it: what Studio reads off a jar's class files, and what the jar's
 * cache file holds (2026-10-18).
 *
 * <p>The index handed out ClassGraph's own {@code ClassInfo} until ClassGraph 6, which removed the JSON
 * serialization the cache was. A record of only what Studio reads also leaves no scan open for the
 * manager's lifetime and loads without a ClassGraph thread pool.
 *
 * <p>Modifiers are the class file's access flags, so {@code ACC_ENUM} and {@code ACC_VARARGS} answer too. Type
 * names are as ClassGraph spells them: {@code a.b.Outer$Inner}, generic arguments in {@link Parameter#type()}.
 *
 * @param name           the binary name, {@code a.b.Outer$Inner}
 * @param simpleName     {@code Inner}
 * @param typeParameters how many type parameters the class declares
 * @param superclasses   every superclass in the jar, nearest first
 * @param interfaces     every interface in the jar it or a superclass implements
 * @param methods        its public methods, those it inherits from the jar's classes included
 * @param constructors   its public constructors
 * @param fields         its public fields, inherited ones included
 * @param since          its {@code @Since} value, {@code ""} when it carries none
 */
public record LibraryType(String name, String simpleName, int modifiers, int typeParameters,
                          List<String> superclasses, List<String> interfaces, List<Method> methods,
                          List<Method> constructors, List<Field> fields, boolean deprecated, String since) {

    /** The {@code @Since} read off every element, by name, so the upgrade can say what each release added. */
    public static final String SINCE = "com.botmaker.plugin.api.meta.Since";

    private static final int ANNOTATION = 0x2000;
    private static final int ENUM = 0x4000;
    private static final int SYNTHETIC = 0x1000;
    private static final int VARARGS = 0x0080;

    /** {@code a.b} for {@code a.b.Outer$Inner}; {@code ""} in the default package. */
    public String packageName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    public boolean isPublic()    { return Modifier.isPublic(modifiers); }
    public boolean isAbstract()  { return Modifier.isAbstract(modifiers); }
    public boolean isInterface() { return Modifier.isInterface(modifiers) && (modifiers & ANNOTATION) == 0; }
    public boolean isEnum()      { return (modifiers & ENUM) != 0; }

    /** Whether {@code superclass} is one of this class's superclasses, by binary name. */
    public boolean extendsSuperclass(String superclass) {
        return superclasses.contains(superclass);
    }

    /** The methods called {@code methodName}, every overload. */
    public List<Method> methods(String methodName) {
        return methods.stream().filter(m -> m.name().equals(methodName)).toList();
    }

    /**
     * A method or a constructor ({@code <init>}).
     *
     * @param returnType the generic return type, {@code void} for a constructor
     */
    public record Method(String name, int modifiers, List<Parameter> parameters, String returnType,
                         boolean deprecated, String since) {
        public boolean isPublic()    { return Modifier.isPublic(modifiers); }
        public boolean isStatic()    { return Modifier.isStatic(modifiers); }
        public boolean isSynthetic() { return (modifiers & SYNTHETIC) != 0; }
        public boolean isVarArgs()   { return (modifiers & VARARGS) != 0; }
    }

    /**
     * @param name       the source name when the jar was compiled with {@code -parameters}, else null
     * @param type       the generic type, {@code java.util.function.Consumer<a.b.Matches>}
     * @param erasedType the descriptor's type, {@code java.util.function.Consumer}
     */
    public record Parameter(String name, String type, String erasedType) {}

    /**
     * @param type       the generic type
     * @param erasedType the descriptor's type
     */
    public record Field(String name, int modifiers, String type, String erasedType, boolean deprecated,
                        String since) {
        public boolean isPublic()    { return Modifier.isPublic(modifiers); }
        public boolean isStatic()    { return Modifier.isStatic(modifiers); }
        public boolean isFinal()     { return Modifier.isFinal(modifiers); }
        public boolean isSynthetic() { return (modifiers & SYNTHETIC) != 0; }
        public boolean isEnum()      { return (modifiers & ENUM) != 0; }
    }
}
