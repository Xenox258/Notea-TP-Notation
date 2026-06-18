package com.teob.appnotationmobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GradeOptionTest {
    @Test
    fun grandOralOptionsUseSatisfactionLabelsWithoutNonEvaluated() {
        val options = gradeOptionsFor(GridKind.GRAND_ORAL_2I2D)

        assertEquals(
            listOf(
                GradeOption(0, "Très insatisfaisant"),
                GradeOption(1, "Insatisfaisant"),
                GradeOption(2, "Satisfaisant"),
                GradeOption(3, "Très satisfaisant"),
            ),
            options,
        )
        assertFalse(options.any { it.value < 0 || it.label == "NE" })
    }

    @Test
    fun defaultOptionsKeepNonEvaluatedAndNumericLabels() {
        assertEquals(
            listOf(
                GradeOption(-1, "NE"),
                GradeOption(0, "0"),
                GradeOption(1, "1"),
                GradeOption(2, "2"),
                GradeOption(3, "3"),
            ),
            gradeOptionsFor(GridKind.EP_2I2D),
        )
    }

    @Test
    fun grandOralOptionRowsHaveSpacingBetweenRows() {
        val rows = gradeOptionRowsFor(GridKind.GRAND_ORAL_2I2D)

        assertEquals(2, rows.size)
        assertEquals(8, gradeOptionRowBottomMarginDp(GridKind.GRAND_ORAL_2I2D, rowIndex = 0, rowCount = rows.size))
        assertEquals(0, gradeOptionRowBottomMarginDp(GridKind.GRAND_ORAL_2I2D, rowIndex = 1, rowCount = rows.size))
    }
}
