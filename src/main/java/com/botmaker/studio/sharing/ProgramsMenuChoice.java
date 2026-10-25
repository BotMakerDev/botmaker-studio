package com.botmaker.studio.sharing;

import java.util.prefs.Preferences;

/**
 * Whether My bots' bots are entries of this computer's programs menu ({@link ProgramEntries}, spec § 5): on until
 * the user turns it off on the home. Kept on this computer only, like {@link ShowCommunity}.
 */
public record ProgramsMenuChoice(boolean on) {

    /** The switch's words. */
    public static final String LABEL = "Add my bots to the programs menu";

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "home.programsMenu";

    /** This computer's choice; on when the preference store cannot be read. */
    public static ProgramsMenuChoice load() {
        try {
            return new ProgramsMenuChoice(Preferences.userRoot().node(PREFS_NODE).getBoolean(PREFS_KEY, true));
        } catch (Exception e) {
            return new ProgramsMenuChoice(true);
        }
    }

    /** Writes this choice as this computer's. Best effort: one that cannot be written stays as it was. */
    public void save() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.putBoolean(PREFS_KEY, on);
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember the programs menu choice: " + e.getMessage());
        }
    }
}
