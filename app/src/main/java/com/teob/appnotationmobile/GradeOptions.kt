package com.teob.appnotationmobile

data class GradeOption(val value: Int, val label: String)

fun gradeOptionsFor(gridKind: String): List<GradeOption> {
    return if (gridKind == GridKind.GRAND_ORAL_2I2D) {
        listOf(
            GradeOption(0, "Très insatisfaisant"),
            GradeOption(1, "Insatisfaisant"),
            GradeOption(2, "Satisfaisant"),
            GradeOption(3, "Très satisfaisant"),
        )
    } else {
        listOf(
            GradeOption(-1, "NE"),
            GradeOption(0, "0"),
            GradeOption(1, "1"),
            GradeOption(2, "2"),
            GradeOption(3, "3"),
        )
    }
}

fun gradeOptionRowsFor(gridKind: String): List<List<GradeOption>> {
    val options = gradeOptionsFor(gridKind)
    return options.chunked(if (gridKind == GridKind.GRAND_ORAL_2I2D) 2 else options.size)
}

fun gradeOptionRowBottomMarginDp(gridKind: String, rowIndex: Int, rowCount: Int): Int {
    return if (gridKind == GridKind.GRAND_ORAL_2I2D && rowIndex < rowCount - 1) 8 else 0
}
