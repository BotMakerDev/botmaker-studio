package com.botmaker.studio.services;

import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;

import java.util.List;

/**
 * Where Studio's editor sends what the user does to the open bot: the core's process ({@link CoreEdits}) since the
 * plan's phase 10a1. What lands comes back as the core's {@code CoreEvent}s, which {@link CodeEditorService} redraws
 * from; a file is named by its path below the source root.
 */
public interface EditPort {

    /** One edit the user made by hand on the open file. */
    EditResult userEdit(Edit edit);

    /** An undo or a redo of the project's history. */
    EditResult step(HistoryStep step);

    /** A write of the open file made outside an edit (an open-time migration), recorded as one step. */
    EditResult rewritten(String file, String original, String source);

    /** An assistant's finished session, by its texts: lands while the open file still reads {@code original}. */
    EditResult commit(String file, String original, String source, String label);

    /** Makes {@code file} the open file, as Studio just did. */
    EditResult show(String file);

    /** A view that edits nothing: a preview of another version of a file. */
    EditPort NONE = new EditPort() {
        private static final EditResult READ_ONLY = new EditResult.Refused("This view is read-only.");

        @Override
        public EditResult userEdit(Edit edit) {
            return READ_ONLY;
        }

        @Override
        public EditResult step(HistoryStep step) {
            return READ_ONLY;
        }

        @Override
        public EditResult rewritten(String file, String original, String source) {
            return READ_ONLY;
        }

        @Override
        public EditResult commit(String file, String original, String source, String label) {
            return READ_ONLY;
        }

        @Override
        public EditResult show(String file) {
            return new EditResult.Accepted(List.of());
        }
    };
}
