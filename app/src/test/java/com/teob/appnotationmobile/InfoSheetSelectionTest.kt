package com.teob.appnotationmobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InfoSheetSelectionTest {
    private val guide = GrandOralProfileGuide(
        rows = emptyList(),
        rawFallback = "contenu",
    )

    @Test
    fun opensProfilNotesAutomaticallyWhenPresent() {
        val sheets = listOf(
            GrandOralInfoSheet("Barème", guide),
            GrandOralInfoSheet("Profil Notes", guide),
            GrandOralInfoSheet("Archives", guide),
        )

        val decision = InfoSheetSelection.decide(sheets)

        val open = assertIs<InfoSheetDecision.Open>(decision)
        assertEquals("Profil Notes", open.sheet.name)
    }

    @Test
    fun opensImageOnlyProfilNotesAutomaticallyWhenPresent() {
        val sheets = listOf(
            GrandOralInfoSheet("Profil notes", GrandOralProfileGuide(emptyList()), imageBase64 = "png-data"),
        )

        val decision = InfoSheetSelection.decide(sheets)

        val open = assertIs<InfoSheetDecision.Open>(decision)
        assertEquals("Profil notes", open.sheet.name)
    }

    @Test
    fun asksWhenSeveralSheetsAreAvailableWithoutProfilNotes() {
        val sheets = listOf(
            GrandOralInfoSheet("Barème", guide),
            GrandOralInfoSheet("Aide jury", guide),
        )

        val decision = InfoSheetSelection.decide(sheets)

        val ask = assertIs<InfoSheetDecision.Ask>(decision)
        assertEquals(listOf("Barème", "Aide jury"), ask.sheets.map { it.name })
    }

    @Test
    fun opensSingleAvailableSheetWithoutAsking() {
        val sheets = listOf(GrandOralInfoSheet("Aide jury", guide))

        val decision = InfoSheetSelection.decide(sheets)

        val open = assertIs<InfoSheetDecision.Open>(decision)
        assertEquals("Aide jury", open.sheet.name)
    }
}
