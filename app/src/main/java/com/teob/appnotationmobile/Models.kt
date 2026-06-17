package com.teob.appnotationmobile

data class TpProject(
    var id: String = "tp-${System.currentTimeMillis()}",
    var name: String = "",
    var students: List<Student> = emptyList(),
    var criteria: List<Criterion> = emptyList(),
    var grades: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    var pairMode: Boolean = false,
    var pairings: MutableMap<String, String> = mutableMapOf(),
    var gridKind: String = GridKind.EP_2I2D,
    var gridTemplateBase64: String = "",
    /** Colonnes de niveaux (0→col, 1→col, …). Renseigné pour GRAND_ORAL_2I2D. */
    var gridLevelColumns: Map<Int, String> = emptyMap(),
    /** Guide des profils candidats (2e feuille du Grand Oral). */
    var grandOralProfileGuide: GrandOralProfileGuide? = null,
    /** Onglets consultables depuis l'icône info pour le Grand Oral. */
    var grandOralInfoSheets: List<GrandOralInfoSheet> = emptyList(),
)

fun TpProject.hasContent(): Boolean {
    return name.isNotBlank() ||
        students.isNotEmpty() ||
        criteria.isNotEmpty() ||
        grades.isNotEmpty() ||
        pairings.isNotEmpty() ||
        gridTemplateBase64.isNotBlank()
}

enum class StudentFilter {
    ALL,
    TO_GRADE,
    GRADED,
}

data class Student(val id: String, val name: String)

data class Criterion(
    val id: String,
    val skill: String,
    val label: String,
    var weight: Double,
    val descriptors: Map<Int, String> = emptyMap(),
)

data class GridImport(
    val criteria: List<Criterion>,
    val kind: String,
    val levelColumns: Map<Int, String> = emptyMap(),
    val profileGuide: GrandOralProfileGuide? = null,
    val infoSheets: List<GrandOralInfoSheet> = emptyList(),
)

object GridKind {
    const val EP_2I2D = "ep_2i2d"
    const val ETLV = "etlv"
    const val GRAND_ORAL_2I2D = "grand_oral_2i2d"
}

enum class EvaluationSheetType(val kind: String, val label: String) {
    ETLV(GridKind.ETLV, "ETLV"),
    EP_2I2D_AC(GridKind.EP_2I2D, "Épreuve pratique 2I2D"),
    GRAND_ORAL_2I2D(GridKind.GRAND_ORAL_2I2D, "Grand oral 2I2D"),
    UNKNOWN("unknown", "Format non supporté");

    companion object {
        fun fromKind(kind: String): EvaluationSheetType {
            return entries.firstOrNull { it.kind == kind } ?: UNKNOWN
        }
    }
}

// ── Profil notes Grand Oral ──────────────────────────────────────────────

enum class ProfileLevel(val code: String, val label: String) {
    VERY_SATISFACTORY("TS", "Très satisfaisant"),
    SATISFACTORY("S", "Satisfaisant"),
    UNSATISFACTORY("I", "Insatisfaisant"),
    VERY_UNSATISFACTORY("TI", "Très insatisfaisant"),
    UNKNOWN("?", "Inconnu");

    companion object {
        fun fromCode(raw: String): ProfileLevel {
            val cleaned = raw.trim().uppercase()
            return entries.firstOrNull { it.code == cleaned } ?: UNKNOWN
        }
    }
}

data class GrandOralProfileRow(
    val oralQuality: ProfileLevel,
    val continuousSpeech: ProfileLevel,
    val knowledgeQuality: ProfileLevel,
    val interactionQuality: ProfileLevel,
    val argumentationQuality: ProfileLevel,
    val possibleGrade: String,
)

data class GrandOralProfileGuide(
    val rows: List<GrandOralProfileRow>,
    /** Si le parsing structuré échoue, contient le texte brut de la 2e feuille. */
    val rawFallback: String? = null,
) {
    val isStructured: Boolean get() = rawFallback == null && rows.isNotEmpty()
}

data class GrandOralInfoSheet(
    val name: String,
    val guide: GrandOralProfileGuide,
    val imageBase64: String = "",
)

sealed interface InfoSheetDecision {
    data class Open(val sheet: GrandOralInfoSheet) : InfoSheetDecision
    data class Ask(val sheets: List<GrandOralInfoSheet>) : InfoSheetDecision
    data object None : InfoSheetDecision
}

object InfoSheetSelection {
    fun decide(sheets: List<GrandOralInfoSheet>): InfoSheetDecision {
        val available = sheets.filter {
            it.guide.rows.isNotEmpty() || !it.guide.rawFallback.isNullOrBlank() || it.imageBase64.isNotBlank()
        }
        if (available.isEmpty()) return InfoSheetDecision.None

        val profilNotes = available.firstOrNull { normalizeCellText(it.name) == "profil notes" }
        if (profilNotes != null) return InfoSheetDecision.Open(profilNotes)

        return if (available.size == 1) {
            InfoSheetDecision.Open(available.first())
        } else {
            InfoSheetDecision.Ask(available)
        }
    }
}
