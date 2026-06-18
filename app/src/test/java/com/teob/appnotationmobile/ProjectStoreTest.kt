package com.teob.appnotationmobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class ProjectStoreTest {
    @Test
    fun keepsGrandOralImageOnlyInfoSheetWhenLoadingProject() {
        val project = parseProject(
            JSONObject()
                .put("id", "tp-1")
                .put("name", "Grand oral")
                .put("students", JSONArray())
                .put("criteria", JSONArray())
                .put("grades", JSONObject())
                .put("gridKind", GridKind.GRAND_ORAL_2I2D)
                .put(
                    "grandOralInfoSheets",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("name", "Profil notes")
                                .put("guide", JSONArray())
                                .put("imageBase64", "png-data"),
                        ),
                    ),
                ),
        )

        assertEquals(1, project.grandOralInfoSheets.size)
        val sheet = project.grandOralInfoSheets.single()
        assertEquals("Profil notes", sheet.name)
        assertEquals("png-data", sheet.imageBase64)
        assertTrue(sheet.guide.rows.isEmpty())
    }

    @Test
    fun keepsJuryNotesWhenLoadingProject() {
        val project = parseProject(
            JSONObject()
                .put("id", "tp-1")
                .put("name", "Grand oral")
                .put("students", JSONArray())
                .put("criteria", JSONArray())
                .put("grades", JSONObject())
                .put("gridKind", GridKind.GRAND_ORAL_2I2D)
                .put("juryNotes", JSONObject().put("student-1", "14,5")),
        )

        assertEquals("14,5", project.juryNotes["student-1"])
    }

    private fun parseProject(json: JSONObject): TpProject {
        val method = ProjectStore::class.java.getDeclaredMethod("parseProject", JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(ProjectStore, json) as TpProject
    }
}
