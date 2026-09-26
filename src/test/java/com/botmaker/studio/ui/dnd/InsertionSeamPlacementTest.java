package com.botmaker.studio.ui.dnd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The "+" follows the pointer and steps aside for a neighbour's control — never onto one. */
class InsertionSeamPlacementTest {

    private static final double SIZE = InsertionSeam.BUTTON_SIZE;

    @Test
    void withNothingInTheWayItSitsUnderThePointer() {
        assertEquals(200, InsertionSeam.freeX(200, 48, 500, SIZE, List.of()));
    }

    @Test
    void itStaysInsideTheSeam() {
        assertEquals(48, InsertionSeam.freeX(0, 48, 500, SIZE, List.of()));
        assertEquals(500, InsertionSeam.freeX(900, 48, 500, SIZE, List.of()));
    }

    @Test
    void onAControlItStepsToTheNearerSide() {
        double[] delete = {480, 510};
        double x = InsertionSeam.freeX(485, 48, 520, SIZE, List.of(delete));
        double m = InsertionSeam.MARGIN;
        assertTrue(x + SIZE <= delete[0] - m || x >= delete[1] + m, "not on the delete button: " + x);
        assertEquals(delete[1] + m, x, "the right side is nearer from here");
        assertEquals(delete[0] - SIZE - m, InsertionSeam.freeX(470, 48, 520, SIZE, List.of(delete)),
                "and from further left, the left side");
    }

    @Test
    void betweenTwoControlsItFindsTheGap() {
        List<double[]> blocked = List.of(new double[]{100, 140}, new double[]{170, 210});
        double x = InsertionSeam.freeX(150, 48, 500, SIZE, blocked);
        double m = InsertionSeam.MARGIN;
        assertTrue(blocked.stream().noneMatch(b -> x < b[1] + m && x + SIZE > b[0] - m), "free spot: " + x);
    }
}
