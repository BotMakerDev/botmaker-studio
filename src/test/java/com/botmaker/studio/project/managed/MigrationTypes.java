package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.value.Drawn;
import com.botmaker.plugin.api.value.PluginType;

import java.util.List;

/**
 * A plugin's types before and after it moved its values to new classes, as {@link ManagedMigrationsTest} needs
 * them on the test classpath: a plain call, a call holding another type that moved, one holding an enum that
 * moved, one whose part is now built from its old one, and one gaining a part of the same type as an old one.
 */
@SuppressWarnings("DeprecatedIsStillUsed")
public final class MigrationTypes {

    private MigrationTypes() {}

    @Deprecated(forRemoval = true)
    public record OldShot(String file, int threshold) {}

    public record Shot(String file, int threshold, boolean grey) {}

    @Deprecated(forRemoval = true)
    public record OldArea(int x, int y) {}

    public record Area(int x, int y, int w) {}

    @Deprecated(forRemoval = true)
    public record OldFrame(String file, OldArea area) {}

    public record Frame(String file, Area area) {}

    @Deprecated(forRemoval = true)
    public enum OldTint { RED, BLUE }

    public enum Tint { CRIMSON, NAVY }

    @Deprecated(forRemoval = true)
    public record OldPaint(OldTint tint) {}

    public record Paint(Tint tint) {}

    @Deprecated(forRemoval = true)
    public record OldBadge(String name) {}

    public record Tag(String text) {}

    public record Badge(Tag tag) {}

    @Deprecated(forRemoval = true)
    public record OldLabel(String name) {}

    public record Label(String title, String name) {}

    private static final Drawn NONE = () -> ctx -> null;

    /** Every declaration, old and new; the bot's author pairs them. */
    @SuppressWarnings("removal")
    static List<PluginType<?>> types() {
        return List.of(
                PluginType.value(OldShot.class).fresh(() -> new OldShot("", 0)).editor(NONE).writtenAsRecord(),
                PluginType.value(Shot.class).fresh(() -> new Shot("", 0, false)).editor(NONE).writtenAsRecord(),
                PluginType.value(OldArea.class).fresh(() -> new OldArea(0, 0)).editor(NONE).writtenAsRecord(),
                PluginType.value(Area.class).fresh(() -> new Area(0, 0, 0)).editor(NONE).writtenAsRecord(),
                PluginType.value(OldFrame.class).fresh(() -> new OldFrame("", new OldArea(0, 0))).editor(NONE)
                        .writtenAsRecord(),
                PluginType.value(Frame.class).fresh(() -> new Frame("", new Area(0, 0, 0))).editor(NONE)
                        .writtenAsRecord(),
                PluginType.value(OldTint.class).firstConstant().editor(NONE).writtenAsConstant(),
                PluginType.value(Tint.class).firstConstant().editor(NONE).writtenAsConstant(),
                PluginType.value(OldPaint.class).fresh(() -> new OldPaint(OldTint.RED)).editor(NONE).writtenAsRecord(),
                PluginType.value(Paint.class).fresh(() -> new Paint(Tint.CRIMSON)).editor(NONE).writtenAsRecord(),
                PluginType.value(OldBadge.class).fresh(() -> new OldBadge("")).editor(NONE).writtenAsRecord(),
                PluginType.value(Tag.class).fresh(() -> new Tag("")).editor(NONE).writtenAsRecord(),
                PluginType.value(Badge.class).fresh(() -> new Badge(new Tag(""))).editor(NONE).writtenAsRecord(),
                PluginType.value(OldLabel.class).fresh(() -> new OldLabel("")).editor(NONE).writtenAsRecord(),
                PluginType.value(Label.class).fresh(() -> new Label("", "")).editor(NONE).writtenAsRecord());
    }
}
