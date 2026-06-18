package com.teob.appnotationmobile

import kotlin.test.Test
import kotlin.test.assertEquals

class GradingLogicTest {
    @Test
    fun displayScoreUsesJuryNoteWhenPresent() {
        val project = scoringProject(
            students = listOf(Student("student-1", "DUPONT Ada")),
            grades = mutableMapOf("student-1" to mutableMapOf("criterion-1" to 3)),
            juryNotes = mutableMapOf("student-1" to "14,5"),
        )

        assertEquals(14.5, displayScoreForStudent(project, "student-1"))
    }

    @Test
    fun displayScoreFallsBackToComputedScoreWithoutJuryNote() {
        val project = scoringProject(
            students = listOf(Student("student-1", "DUPONT Ada")),
            grades = mutableMapOf("student-1" to mutableMapOf("criterion-1" to 3)),
        )

        assertEquals(20.0, displayScoreForStudent(project, "student-1"))
    }

    @Test
    fun averageScoreMixesJuryNotesAndComputedScores() {
        val project = scoringProject(
            students = listOf(
                Student("student-1", "DUPONT Ada"),
                Student("student-2", "MARTIN Alan"),
            ),
            grades = mutableMapOf(
                "student-1" to mutableMapOf("criterion-1" to 3),
                "student-2" to mutableMapOf("criterion-1" to 3),
            ),
            juryNotes = mutableMapOf("student-1" to "10"),
        )

        assertEquals(15.0, averageScore(project))
    }

    @Test
    fun invalidJuryNoteFallsBackToComputedScore() {
        val project = scoringProject(
            students = listOf(Student("student-1", "DUPONT Ada")),
            grades = mutableMapOf("student-1" to mutableMapOf("criterion-1" to 3)),
            juryNotes = mutableMapOf("student-1" to "abc"),
        )

        assertEquals(20.0, displayScoreForStudent(project, "student-1"))
    }

    @Test
    fun computedScoreIgnoresJuryNote() {
        val project = scoringProject(
            students = listOf(Student("student-1", "DUPONT Ada")),
            grades = mutableMapOf("student-1" to mutableMapOf("criterion-1" to 3)),
            juryNotes = mutableMapOf("student-1" to "12"),
        )

        assertEquals(20.0, computedScoreForStudent(project, "student-1"))
    }

    private fun scoringProject(
        students: List<Student>,
        grades: MutableMap<String, MutableMap<String, Int>>,
        juryNotes: MutableMap<String, String> = mutableMapOf(),
    ): TpProject {
        return TpProject(
            students = students,
            criteria = listOf(Criterion("criterion-1", "Grand oral", "Qualité orale", 1.0)),
            grades = grades,
            juryNotes = juryNotes,
            gridKind = GridKind.GRAND_ORAL_2I2D,
        )
    }
}
