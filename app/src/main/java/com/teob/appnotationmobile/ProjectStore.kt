package com.teob.appnotationmobile

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject

object ProjectStore {
    private const val PREFS = "tp-project"
    private const val KEY_PROJECTS = "projects"
    private const val KEY = "data"

    // Lit la nouvelle liste de projets, avec migration douce depuis l'ancien stockage à projet unique.
    fun loadAll(activity: Activity): List<TpProject> {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val rawProjects = prefs.getString(KEY_PROJECTS, null)
        if (rawProjects != null) {
            return runCatching {
                val array = JSONArray(rawProjects)
                (0 until array.length()).map { parseProject(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        val legacy = prefs.getString(KEY, null)
            ?.let { raw -> runCatching { parseProject(JSONObject(raw)) }.getOrNull() }
        return if (legacy != null && legacy.hasContent()) {
            saveAll(activity, listOf(legacy))
            listOf(legacy)
        } else {
            emptyList()
        }
    }

    fun save(activity: Activity, project: TpProject) {
        if (!project.hasContent()) return
        val projects = loadAll(activity).toMutableList()
        val index = projects.indexOfFirst { it.id == project.id }
        if (index >= 0) {
            projects[index] = project
        } else {
            projects += project
        }
        saveAll(activity, projects)
    }

    fun delete(activity: Activity, projectId: String) {
        saveAll(activity, loadAll(activity).filterNot { it.id == projectId })
    }

    private fun saveAll(activity: Activity, projects: List<TpProject>) {
        val array = JSONArray(projects.map { it.toJson() })
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROJECTS, array.toString())
            .apply()
    }

    private fun parseProject(json: JSONObject): TpProject {
        val profileGuide = json.optJSONArray("grandOralProfileGuide")?.toProfileGuide()
        val infoSheets = json.optJSONArray("grandOralInfoSheets").toInfoSheets()
        return TpProject(
            id = json.optString("id").ifBlank { "tp-${System.currentTimeMillis()}" },
            name = json.optString("name"),
            students = json.optJSONArray("students").toList { Student(getString("id"), getString("name")) },
            criteria = json.optJSONArray("criteria").toList {
                Criterion(
                    getString("id"),
                    getString("skill"),
                    getString("label"),
                    getDouble("weight"),
                    optJSONObject("descriptors").toIntStringMap(),
                )
            },
            grades = json.optJSONObject("grades").toGradeMap(),
            pairMode = json.optBoolean("pairMode", false),
            pairings = json.optJSONObject("pairings").toStringMap(),
            gridKind = json.optString("gridKind", GridKind.EP_2I2D),
            gridTemplateBase64 = json.optString("gridTemplateBase64"),
            gridLevelColumns = json.optJSONObject("gridLevelColumns").toIntStringMap(),
            grandOralProfileGuide = profileGuide,
            grandOralInfoSheets = infoSheets.ifEmpty {
                profileGuide?.let { listOf(GrandOralInfoSheet("Profil Notes", it)) }.orEmpty()
            },
        )
    }

    private fun TpProject.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("students", JSONArray(students.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put(
                "criteria",
                JSONArray(criteria.map {
                    JSONObject()
                        .put("id", it.id)
                        .put("skill", it.skill)
                        .put("label", it.label)
                        .put("weight", it.weight)
                        .put("descriptors", JSONObject(it.descriptors.mapKeys { entry -> entry.key.toString() }))
                }),
            )
            .put("grades", JSONObject(grades.mapValues { JSONObject(it.value as Map<*, *>) }))
            .put("pairMode", pairMode)
            .put("pairings", JSONObject(pairings as Map<*, *>))
            .put("gridKind", gridKind)
            .put("gridTemplateBase64", gridTemplateBase64)
            .put("gridLevelColumns", JSONObject(gridLevelColumns.mapKeys { it.key.toString() } as Map<*, *>))
            .put("grandOralProfileGuide", grandOralProfileGuide?.let { guide ->
                if (guide.rawFallback != null) {
                    JSONArray(listOf(JSONObject().put("rawFallback", guide.rawFallback)))
                } else {
                    JSONArray(guide.rows.map { row ->
                        JSONObject()
                            .put("oralQuality", row.oralQuality.code)
                            .put("continuousSpeech", row.continuousSpeech.code)
                            .put("knowledgeQuality", row.knowledgeQuality.code)
                            .put("interactionQuality", row.interactionQuality.code)
                            .put("argumentationQuality", row.argumentationQuality.code)
                            .put("possibleGrade", row.possibleGrade)
                    })
                }
            } ?: JSONArray())
            .put(
                "grandOralInfoSheets",
                JSONArray(grandOralInfoSheets.map { sheet ->
                    JSONObject()
                        .put("name", sheet.name)
                        .put("guide", sheet.guide.toJsonArray())
                        .put("imageBase64", sheet.imageBase64)
                }),
            )
    }

    private fun JSONObject?.toGradeMap(): MutableMap<String, MutableMap<String, Int>> {
        val result = mutableMapOf<String, MutableMap<String, Int>>()
        if (this == null) return result
        keys().forEach { studentId ->
            val gradeObject = getJSONObject(studentId)
            result[studentId] = mutableMapOf<String, Int>().apply {
                gradeObject.keys().forEach { criterionId -> put(criterionId, gradeObject.getInt(criterionId)) }
            }
        }
        return result
    }

    private fun JSONObject?.toStringMap(): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        if (this == null) return result
        keys().forEach { key -> result[key] = optString(key) }
        return result
    }

    private fun JSONObject?.toIntStringMap(): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        if (this == null) return result
        keys().forEach { key ->
            key.toIntOrNull()?.let { result[it] = optString(key) }
        }
        return result
    }

    private inline fun <T> JSONArray?.toList(build: JSONObject.() -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { getJSONObject(it).build() }
    }

    private fun JSONArray?.toProfileGuide(): GrandOralProfileGuide? {
        if (this == null || length() == 0) return null
        // Détecter si c'est un fallback brut (1 élément avec clé "rawFallback")
        if (length() == 1) {
            val first = getJSONObject(0)
            val raw = first.optString("rawFallback", "")
            if (raw.isNotBlank()) return GrandOralProfileGuide(emptyList(), rawFallback = raw)
        }
        val rows = (0 until length()).map { index ->
            val obj = getJSONObject(index)
            GrandOralProfileRow(
                oralQuality = ProfileLevel.fromCode(obj.optString("oralQuality", "?")),
                continuousSpeech = ProfileLevel.fromCode(obj.optString("continuousSpeech", "?")),
                knowledgeQuality = ProfileLevel.fromCode(obj.optString("knowledgeQuality", "?")),
                interactionQuality = ProfileLevel.fromCode(obj.optString("interactionQuality", "?")),
                argumentationQuality = ProfileLevel.fromCode(obj.optString("argumentationQuality", "?")),
                possibleGrade = obj.optString("possibleGrade", ""),
            )
        }
        return GrandOralProfileGuide(rows)
    }

    private fun GrandOralProfileGuide.toJsonArray(): JSONArray {
        return if (rawFallback != null) {
            JSONArray(listOf(JSONObject().put("rawFallback", rawFallback)))
        } else {
            JSONArray(rows.map { row ->
                JSONObject()
                    .put("oralQuality", row.oralQuality.code)
                    .put("continuousSpeech", row.continuousSpeech.code)
                    .put("knowledgeQuality", row.knowledgeQuality.code)
                    .put("interactionQuality", row.interactionQuality.code)
                    .put("argumentationQuality", row.argumentationQuality.code)
                    .put("possibleGrade", row.possibleGrade)
            })
        }
    }

    private fun JSONArray?.toInfoSheets(): List<GrandOralInfoSheet> {
        if (this == null || length() == 0) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val obj = getJSONObject(index)
            val name = obj.optString("name").ifBlank { "Feuille ${index + 1}" }
            val imageBase64 = obj.optString("imageBase64")
            val guide = obj.optJSONArray("guide").toProfileGuide()
                ?: if (imageBase64.isNotBlank()) GrandOralProfileGuide(emptyList()) else return@mapNotNull null
            GrandOralInfoSheet(name, guide, imageBase64)
        }
    }
}
