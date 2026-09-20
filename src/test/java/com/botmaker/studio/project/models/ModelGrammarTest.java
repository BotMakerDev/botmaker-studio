package com.botmaker.studio.project.models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a plugin's model may be made of, and what a refusal says when it may not.
 *
 * <p>The refusal <em>message</em> is asserted as well as the fact of it: the requirement
 * ({@code docs/refactor/33-plugin-java.md}) is that the offending component is named, because a plugin
 * author reading "illegal model" learns nothing and one reading "nodes.action is java.lang.Runnable" is
 * done.
 */
class ModelGrammarTest {

    @Test
    void everyRowOfTheTableIsLegal() {
        assertTrue(ModelGrammar.isLegal(ModelValues.Flow.class, ModelValues.CATALOG));
        assertTrue(ModelGrammar.isLegal(ModelValues.Primitives.class, ModelValues.CATALOG));
    }

    @Test
    void aModelIsARecordAndNothingElse() {
        Optional<ModelGrammar.Refusal> refusal = ModelGrammar.refusal(String.class, ModelValues.CATALOG);
        assertTrue(refusal.isPresent());
        assertEquals("String", refusal.get().path());
        assertTrue(refusal.get().reason().contains("is a record"), refusal.get().reason());
    }

    @Test
    void theOffendingComponentIsNamedAllTheWayDown() {
        Optional<ModelGrammar.Refusal> refusal =
                ModelGrammar.refusal(ModelValues.HasAnInterface.class, ModelValues.CATALOG);
        assertTrue(refusal.isPresent());
        assertEquals("HasAnInterface.action", refusal.get().path());
        assertTrue(refusal.get().reason().contains("java.lang.Runnable"), refusal.get().reason());
    }

    @Test
    void aRawContainerIsRefusedBecauseNothingCouldNameWhatItHolds() {
        Optional<ModelGrammar.Refusal> refusal =
                ModelGrammar.refusal(ModelValues.HasARawList.class, ModelValues.CATALOG);
        assertTrue(refusal.isPresent());
        assertEquals("HasARawList.items", refusal.get().path());
    }

    @Test
    void aGenericRecordIsRefusedWhereItIsReached() {
        assertFalse(ModelGrammar.isLegal(ModelValues.Box.class, ModelValues.CATALOG));
    }

    /**
     * A graph of nodes each holding a list of nodes is the ordinary shape of a model, so the walk has to
     * terminate on it rather than refuse it. The alternative — refusing every recursive type — would refuse
     * the thing this feature exists to store.
     */
    @Test
    void aRecursiveModelTerminatesAndIsLegal() {
        assertTrue(ModelGrammar.isLegal(ModelValues.Tree.class, ModelValues.CATALOG));
    }

    @Test
    void aComponentsFormIsTheCaseItsTypeBelongsTo() {
        List<ModelGrammar.Component> components =
                ModelGrammar.componentsOf(ModelValues.Node.class, ModelValues.CATALOG).orElseThrow();
        assertEquals(3, components.size());
        assertInstanceOf(ModelForm.Builtin.class, components.get(0).form());
        assertInstanceOf(ModelForm.Catalogued.class, components.get(1).form());
        assertInstanceOf(ModelForm.Constant.class, components.get(2).form());
    }

    /**
     * The host asks the container what its parts are typed as; it does not know that a map's parts are
     * entries. A contributed container answers whatever it declared, and the substitution has to carry the
     * real arguments into that answer — which is what makes an unregistered arity work with no change here.
     */
    @Test
    void aMapsPartsAreEntriesOfItsOwnArgumentsBecauseTheContainerSaysSo() {
        ModelForm form = ModelGrammar.componentsOf(ModelValues.Flow.class, ModelValues.CATALOG)
                .orElseThrow().get(2).form();
        ModelForm.Composite map = assertInstanceOf(ModelForm.Composite.class, form);

        List<ModelForm> parts = ModelGrammar.partForms(map, 2).orElseThrow();
        assertEquals(2, parts.size());
        ModelForm.Composite entry = assertInstanceOf(ModelForm.Composite.class, parts.getFirst());
        assertEquals(2, entry.arguments().size());
        assertInstanceOf(ModelForm.Builtin.class, entry.arguments().getFirst());
        assertInstanceOf(ModelForm.Declared.class, entry.arguments().get(1));
    }
}
