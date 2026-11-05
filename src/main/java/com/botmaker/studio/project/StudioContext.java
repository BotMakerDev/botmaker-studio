package com.botmaker.studio.project;

import com.botmaker.studio.core.EditorState;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.validation.DiagnosticsManager;

/**
 * The open project's services, as one immutable value — what the window is built <em>against</em>.
 *
 * <p>{@link BotProject} is the composition root and the owner: it constructs these in order, fills its lazy
 * ones, and {@link BotProject#close() closes} what outlives the JVM. This is the read-only view of the same
 * set, taken once the wiring is finished and handed to the UI, which needs to <em>use</em> the services and
 * must never be able to end them. That is the whole difference between the two types, and the reason this one
 * is a {@code record} with no behaviour.
 *
 * <p>It exists because the shell's constructors had grown to 11 and 13 parameters — the same run of services,
 * re-listed at each layer, four of them only ever forwarded. A record of this width edges toward a service
 * locator, which cuts against the module's "pass dependencies in via constructors" style; it is accepted here
 * because the alternative in practice was those parameter lists. Keep it immutable, give it no behaviour, and
 * let nothing reach it statically.
 *
 * <p>The {@code Stage} is deliberately <b>not</b> in here: a window is not a project service, and keeping it
 * out is what keeps the window out of the project's services. Its {@code state} is Studio's {@link EditorState}, the
 * drawn blocks beside the project (2026-11-05), so this record stays Studio's when the project moves to the core.
 */
public record StudioContext(ProjectConfig config,
                            EditorState state,
                            EventBus eventBus,
                            DiagnosticsManager diagnosticsManager,
                            BlockDragAndDropManager dragAndDropManager,
                            ProjectAnalyzer projectAnalyzer,
                            LibraryService libraryService,
                            ProjectSettingsService projectSettingsService,
                            CodeEditorService codeEditorService,
                            CodeExecutionService codeExecutionService) {
}
