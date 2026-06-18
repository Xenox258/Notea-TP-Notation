package com.teob.appnotationmobile

import java.text.Normalizer
import java.util.Locale

fun averageScore(project: TpProject): Double? {
    val scores = pairGroups(project).mapNotNull { group -> displayScoreForStudent(project, group.first().id) }
    return scores.takeIf { it.isNotEmpty() }?.average()
}

fun gradedCount(project: TpProject): Int {
    return pairGroups(project).count { group -> displayScoreForStudent(project, group.first().id) != null }
}

fun gradesForStudent(project: TpProject, studentId: String): Map<String, Int> {
    return project.grades[gradeOwnerId(project, studentId)]
        ?: project.grades[studentId]
        ?: emptyMap()
}

fun gradeOwnerId(project: TpProject, studentId: String): String {
    val partnerId = project.pairings[studentId]
    return if (project.pairMode && partnerId != null) canonicalPairId(studentId, partnerId) else studentId
}

fun scoreGroups(project: TpProject): List<Pair<List<Student>, Map<String, Int>>> {
    return pairGroups(project).map { group -> group to gradesForStudent(project, group.first().id) }
}

fun displayScoreForStudent(project: TpProject, studentId: String): Double? {
    val ownerId = gradeOwnerId(project, studentId)
    return project.juryNotes[ownerId]?.toJuryScore()
        ?: computeScore(project, gradesForStudent(project, studentId))
}

fun computedScoreForStudent(project: TpProject, studentId: String): Double? {
    return computeScore(project, gradesForStudent(project, studentId))
}

fun String.toJuryScore(): Double? {
    val value = trim().replace(',', '.').toDoubleOrNull() ?: return null
    return value.takeIf { it in 0.0..20.0 }
}

// Construit les groupes affichés et notés : élèves seuls ou binômes selon le mode du TP.
fun pairGroups(project: TpProject): List<List<Student>> {
    val seen = mutableSetOf<String>()
    return project.students.mapNotNull { student ->
        if (!seen.add(student.id)) return@mapNotNull null
        val partner = project.pairings[student.id]
            ?.let { partnerId -> project.students.firstOrNull { it.id == partnerId } }
        if (project.pairMode && partner != null && seen.add(partner.id)) {
            listOf(student, partner)
        } else {
            listOf(student)
        }
    }
}

fun computeScore(project: TpProject, grades: Map<String, Int>): Double? {
    if (project.criteria.isEmpty()) return null
    // Une note n'est calculée que lorsque tous les critères ont été renseignés.
    val evaluated = project.criteria.mapNotNull { criterion ->
        val level = grades[criterion.id] ?: return@mapNotNull null
        if (level < 0) null else criterion to level
    }
    if (evaluated.isEmpty()) return null
    val missing = project.criteria.any { criterion -> !grades.containsKey(criterion.id) }
    if (missing) return null
    val totalWeight = evaluated.sumOf { it.first.weight }
    if (totalWeight <= 0.0) return null
    return evaluated.sumOf { (criterion, level) -> (level / 3.0) * criterion.weight } / totalWeight * 20.0
}

fun groupedCriteria(project: TpProject): List<Pair<String, List<Criterion>>> {
    return project.criteria
        .groupBy { normalizeHeader(it.skill) }
        .map { (_, criteria) -> preferredSkillLabel(criteria) to criteria }
}

fun canonicalPairId(firstId: String, secondId: String): String {
    return listOf(firstId, secondId).sorted().joinToString("+")
}

fun familyName(fullName: String): String {
    val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return fullName
    val uppercasePrefix = parts.takeWhile { part ->
        part.any { it.isLetter() } && part == part.uppercase(Locale.ROOT)
    }
    return uppercasePrefix.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: parts.first()
}

private fun preferredSkillLabel(criteria: List<Criterion>): String {
    return criteria.firstOrNull { hasAccent(it.skill) }?.skill
        ?: criteria.firstOrNull()?.skill
        ?: ""
}

private fun hasAccent(text: String): Boolean {
    return Normalizer.normalize(text, Normalizer.Form.NFD).any { char ->
        Character.getType(char) == Character.NON_SPACING_MARK.toInt()
    }
}
