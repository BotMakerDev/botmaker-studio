package com.botmaker.studio.project.managed;

/**
 * A set of classes' element, as a test plugin would ship it: a bot's {@code static final class Market extends
 * TestScreen} compiles against it, its {@link #setup} is the class's value, and the host reads a reference to one of
 * its fields as {@link #field} of the two names.
 */
public abstract class TestScreen {

    /** The rest of the class: here only its name. */
    public abstract String setup();

    /** What a reference to a field reads as: equal to another by the two names. */
    public record Field(String screen, String name) {}

    /** What a reference to a whole class reads as: equal to another by its constant's name. */
    public static final class Named extends TestScreen {

        private final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        public String setup() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Named that && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    public static Field field(String screen, String name) {
        return new Field(screen, name);
    }

    public static TestScreen named(String name) {
        return new Named(name);
    }
}
