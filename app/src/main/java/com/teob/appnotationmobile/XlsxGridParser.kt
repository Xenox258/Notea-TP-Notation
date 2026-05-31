package com.teob.appnotationmobile

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object XlsxGridParser {
    private const val TAG = "XlsxGridParser"
    private const val MIN_NON_EMPTY_CELLS = 5

    // ── Point d'entrée principal ──────────────────────────────────────────

    fun parse(bytes: ByteArray): GridImport {
        val entries = readZipEntries(ByteArrayInputStream(bytes))
        val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"] ?: ByteArray(0))
        val sheets = workbookSheets(entries)

        Log.d(TAG, "Onglets détectés : ${sheets.keys.joinToString(", ")}")

        // Corpus texte par onglet
        val sheetCorpora = sheets.mapValues { (_, path) ->
            entries[path]?.let { parseCells(it, sharedStrings) } ?: emptyMap()
        }

        // Filtrer les onglets vides
        val nonEmptyCorpora = sheetCorpora.filter { (name, cells) ->
            val count = cells.values.count { it.isNotBlank() }
            if (count < MIN_NON_EMPTY_CELLS) {
                Log.d(TAG, "Onglet ignoré (vide) : $name ($count cellules)")
            }
            count >= MIN_NON_EMPTY_CELLS
        }

        if (nonEmptyCorpora.isEmpty()) {
            throw IllegalArgumentException(
                "Aucun onglet exploitable trouvé dans ce fichier."
            )
        }

        // Détection du type de fiche
        val detection = detectSheetType(nonEmptyCorpora, sheets)
        Log.d(TAG, "Type détecté : ${detection.type.label} (onglet « ${detection.sheetName} », score=${detection.score})")

        if (detection.type == EvaluationSheetType.UNKNOWN) {
            val foundAnchors = detection.debugAnchors.ifEmpty { "aucun indice reconnu" }
            Log.w(TAG, "Rejet — $foundAnchors")
            throw IllegalArgumentException(
                "Fiche détectée mais format non supporté.\n" +
                    "Formats reconnus : ETLV, Épreuve pratique 2I2D, Grand oral 2I2D.\n" +
                    "Indices trouvés : $foundAnchors"
            )
        }

        val bestSheetCells = sheetCorpora[detection.sheetName] ?: emptyMap()

        return when (detection.type) {
            EvaluationSheetType.EP_2I2D_AC -> parseEp(bestSheetCells, entries, sheets, sharedStrings)
            EvaluationSheetType.ETLV -> parseEtlv(bestSheetCells)
            EvaluationSheetType.GRAND_ORAL_2I2D -> parseGrandOral(bestSheetCells, entries, sheets, sharedStrings, detection.sheetName)
            EvaluationSheetType.UNKNOWN -> throw IllegalStateException("UNKNOWN ne devrait pas arriver ici")
        }
    }

    // ── Détection du type de fiche ────────────────────────────────────────

    private data class DetectionResult(
        val type: EvaluationSheetType,
        val sheetName: String,
        val score: Int,
        val debugAnchors: String = "",
    )

    private fun detectSheetType(
        sheetCorpora: Map<String, Map<String, String>>,
        sheets: Map<String, String>,
    ): DetectionResult {
        data class SheetScore(val name: String, val score: Int, val anchors: List<String>)

        // Pour chaque type, on marque chaque onglet et on prend le meilleur score
        val types = listOf(
            EvaluationSheetType.ETLV,
            EvaluationSheetType.EP_2I2D_AC,
            EvaluationSheetType.GRAND_ORAL_2I2D,
        )

        var bestOverall = DetectionResult(EvaluationSheetType.UNKNOWN, "", 0, "")

        for (type in types) {
            val anchors = anchorsFor(type)
            var bestForType: SheetScore? = null

            for ((sheetName, cells) in sheetCorpora) {
                val corpus = cells.values.joinToString(" ") { normalizeCellText(it) }
                val hits = anchors.filter { anchor ->
                    corpus.contains(normalizeCellText(anchor))
                }
                val score = hits.size
                Log.d(TAG, "  Score $type → « $sheetName » : $score (${hits.joinToString(", ") { "\"$it\"" }})")

                if (score > (bestForType?.score ?: 0)) {
                    bestForType = SheetScore(sheetName, score, hits)
                }
            }

            if (bestForType != null && bestForType.score > bestOverall.score) {
                bestOverall = DetectionResult(
                    type = type,
                    sheetName = bestForType.name,
                    score = bestForType.score,
                    debugAnchors = bestForType.anchors.joinToString(", ") { "\"$it\"" },
                )
            }
        }

        // Minimum 2 ancres pour considérer un type comme détecté
        if (bestOverall.score < 2) {
            // Chercher aussi les ancres faibles dans le meilleur onglet
            val bestSheet = bestOverall.sheetName.ifEmpty { sheetCorpora.keys.firstOrNull() ?: "" }
            val cells = sheetCorpora[bestSheet] ?: emptyMap()
            val corpus = cells.values.joinToString(" ") { normalizeCellText(it) }
            val allWeakAnchors = allAnchorsForDiagnostic().filter { anchor ->
                corpus.contains(normalizeCellText(anchor))
            }
            return DetectionResult(
                type = EvaluationSheetType.UNKNOWN,
                sheetName = bestSheet,
                score = 0,
                debugAnchors = allWeakAnchors.joinToString(", ") { "\"$it\"" },
            )
        }

        return bestOverall
    }

    private fun anchorsFor(type: EvaluationSheetType): List<String> {
        return when (type) {
            EvaluationSheetType.ETLV -> listOf(
                "présentation orale en langue vivante",
                "fiche d'évaluation de la première partie",
                "C01", "C02",
                "Nom:", "Prenom:",
            )
            EvaluationSheetType.EP_2I2D_AC -> listOf(
                "2I2D - Grille d'évaluation de l'épreuve pratique",
                "épreuve pratique",
                "NON EVALUE DANS LE SUJET",
                "Critères d'évaluation",
                "Analyser",
                "Concevoir",
                "Simuler",
                "Expérimenter",
                "Compétences évaluées",
            )
            EvaluationSheetType.GRAND_ORAL_2I2D -> listOf(
                "grand oral",
                "Qualité orale",
                "Prise de parole en continu",
                "Interaction",
                "Connaissances",
                "Argumentation",
                "Très insatisfaisant",
                "Insatisfaisant",
                "Très satisfaisant",
                "Note brute /20",
            )
            EvaluationSheetType.UNKNOWN -> emptyList()
        }
    }

    private fun allAnchorsForDiagnostic(): List<String> {
        return EvaluationSheetType.entries
            .filter { it != EvaluationSheetType.UNKNOWN }
            .flatMap { anchorsFor(it) }
    }

    // ── Parser EP 2I2D AC ─────────────────────────────────────────────────

    private fun parseEp(
        cells: Map<String, String>,
        entries: Map<String, ByteArray>,
        sheets: Map<String, String>,
        sharedStrings: List<String>,
    ): GridImport {
        val descriptors = entries[sheets["Descripteurs"] ?: "xl/worksheets/sheet3.xml"]
            ?.let { parseDescriptors(it, sharedStrings) }
            .orEmpty()
        val criteria = (8..21).mapNotNull { row ->
            val label = cells["D$row"]?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            val skill = cells["B$row"]?.trim().takeUnless { it.isNullOrBlank() }
                ?: when (row) {
                    in 8..9 -> "Analyser"
                    in 10..13 -> "Concevoir"
                    in 14..17 -> "Simuler"
                    else -> "Expérimenter"
                }
            Criterion(
                id = "criterion-$row",
                skill = skill,
                label = label,
                weight = cells["M$row"]?.toDoubleOrNull() ?: 1.0,
                descriptors = descriptors[normalizeHeader(label)]
                    ?: descriptors["criterion-$row"]
                    .orEmpty(),
            )
        }
        if (criteria.isEmpty()) throw IllegalArgumentException("Aucun critère reconnu dans la grille EP.")
        return GridImport(criteria, GridKind.EP_2I2D)
    }

    // ── Parser ETLV ───────────────────────────────────────────────────────

    private fun parseEtlv(cells: Map<String, String>): GridImport {
        val criteria = (1..80).mapNotNull { row ->
            val label = cells["A$row"]?.trim().orEmpty()
            val weight = cells["J$row"]?.toDoubleOrNull() ?: return@mapNotNull null
            if (!label.contains(" - ") || weight <= 0.0) return@mapNotNull null
            val code = label.substringBefore(" - ").trim()
            Criterion(
                id = "criterion-$row",
                skill = code,
                label = label,
                weight = weight,
            )
        }
        if (criteria.isEmpty()) throw IllegalArgumentException("Aucun critère reconnu dans la grille ETLV.")
        return GridImport(criteria, GridKind.ETLV)
    }

    // ── Parser Grand Oral 2I2D ────────────────────────────────────────────

    private fun parseGrandOral(
        cells: Map<String, String>,
        entries: Map<String, ByteArray>,
        sheets: Map<String, String>,
        sharedStrings: List<String>,
        mainSheetName: String,
    ): GridImport {
        // 1. Trouver la ligne des niveaux
        val levelRow = findLevelHeaderRow(cells)
            ?: throw IllegalArgumentException(
                "Impossible de trouver les niveaux d'évaluation " +
                    "(Très insatisfaisant, Insatisfaisant, Satisfaisant, Très satisfaisant) " +
                    "dans la grille Grand oral."
            )
        Log.d(TAG, "GO : ligne des niveaux = $levelRow")

        // 2. Déterminer les colonnes pour chaque niveau
        val levelColumns = findLevelColumns(cells, levelRow)
        if (levelColumns.size < 4) {
            throw IllegalArgumentException(
                "Colonnes de niveaux incomplètes dans la grille Grand oral " +
                    "(trouvé : ${levelColumns.entries.joinToString { "${it.key}→${it.value}" }})."
            )
        }
        Log.d(TAG, "GO : colonnes de niveaux = $levelColumns")

        // 3. Trouver les critères GO
        val goCriteriaNames = listOf(
            "Qualité orale",
            "Prise de parole en continu",
            "Interaction",
            "Connaissances",
            "Argumentation",
        )

        // Chercher les lignes contenant ces critères
        val criteriaRows = findCriteriaRows(cells, levelRow, goCriteriaNames)
        Log.d(TAG, "GO : lignes de critères = ${criteriaRows.map { it.first to it.second }}")

        if (criteriaRows.isEmpty()) {
            throw IllegalArgumentException(
                "Aucun critère Grand oral reconnu (Qualité orale, Prise de parole en continu, " +
                    "Interaction, Connaissances, Argumentation)."
            )
        }

        // 4. Lire la deuxième feuille (profil notes)
        Log.d(TAG, "GO : mainSheetName=$mainSheetName, sheets=${sheets.keys}")
        val profileGuide = parseGrandOralProfileGuide(entries, sheets, sharedStrings, mainSheetName)
        Log.d(TAG, "GO : profileGuide=${profileGuide != null}, rows=${profileGuide?.rows?.size}, fallback=${profileGuide?.rawFallback?.length}")

        // 5. Construire les Criterion
        val criteria = criteriaRows.map { (row, label) ->
            val weight = findWeightInRow(cells, row, levelColumns)
            val skill = "Grand oral"
            val descriptors = levelColumns.mapValues { (level, col) ->
                cells["$col$levelRow"]?.trim().orEmpty()
            }.filterValues { it.isNotBlank() }

            Criterion(
                id = "criterion-$row",
                skill = skill,
                label = label,
                weight = weight,
                descriptors = descriptors,
            )
        }

        Log.d(TAG, "GO : ${criteria.size} critères parsés, profil guide = ${profileGuide?.rows?.size ?: 0} lignes")
        return GridImport(criteria, GridKind.GRAND_ORAL_2I2D, levelColumns, profileGuide)
    }

    /** Colonnes attendues dans le tableau Profil notes (normalisées). */
    private val profileColumnAnchors = listOf(
        "qualite orale",
        "qualite de la prise de parole en continu",
        "qualite des connaissances",
        "qualite de l'interaction",
        "qualite et construction de l'argumentation",
        "note possible",
    )

    /**
     * Parse la deuxième feuille du Grand Oral pour extraire le tableau
     * "Exemples de profils de candidat". Si le parsing structuré échoue,
     * retourne le contenu brut en fallback.
     */
    private fun parseGrandOralProfileGuide(
        entries: Map<String, ByteArray>,
        sheets: Map<String, String>,
        sharedStrings: List<String>,
        mainSheetName: String,
    ): GrandOralProfileGuide? {
        val secondSheetEntry = sheets.entries.firstOrNull { (name, _) -> name != mainSheetName }
        if (secondSheetEntry == null) {
            Log.d(TAG, "GO Profil notes : aucune 2e feuille (sheets=${sheets.keys}, main=$mainSheetName)")
            return null
        }
        Log.d(TAG, "GO Profil notes : 2e feuille « ${secondSheetEntry.key} » → ${secondSheetEntry.value}")
        val secondCells = entries[secondSheetEntry.value]
            ?.let { parseCells(it, sharedStrings) }
        if (secondCells == null) {
            Log.d(TAG, "GO Profil notes : impossible de parser la 2e feuille (clé=${secondSheetEntry.value} non trouvée dans entries)")
            return null
        }

        if (secondCells.values.none { it.isNotBlank() }) {
            Log.d(TAG, "GO Profil notes : 2e feuille vide")
            return null
        }

        val nonBlankCount = secondCells.values.count { it.isNotBlank() }
        Log.d(TAG, "GO Profil notes : « ${secondSheetEntry.key} » → $nonBlankCount cellules non vides")

        // Grouper par ligne
        val rows = secondCells.entries
            .groupBy({ it.key.filter { it.isDigit() }.toIntOrNull() ?: 0 }, { it })
            .toSortedMap()

        // Construire le texte brut de fallback (toujours disponible)
        val rawFallback = rows.entries.joinToString("\n") { (_, entriesInRow) ->
            entriesInRow.joinToString("  ") { (ref, value) ->
                val trimmed = value.trim()
                if (trimmed.isNotBlank()) "$ref:$trimmed" else ""
            }.trim()
        }.trim().takeIf { it.isNotBlank() }
        Log.d(TAG, "GO Profil notes : fallback brut = ${rawFallback?.take(200)}...")

        // 1. Trouver la ligne d'ancrage "Exemples de profils"
        val anchorRow = findAnchorRow(rows)
        if (anchorRow == null) {
            Log.d(TAG, "GO Profil notes : ancre « exemples de profils » non trouvée → fallback brut")
            return rawFallback?.let { GrandOralProfileGuide(emptyList(), rawFallback = it) }
        }
        Log.d(TAG, "GO Profil notes : ancre trouvée ligne $anchorRow")

        // 2. Trouver la ligne d'en-tête (juste après l'ancre)
        val headerResult = findHeaderRow(rows, anchorRow)
        if (headerResult == null) {
            Log.d(TAG, "GO Profil notes : ligne d'en-tête non trouvée → fallback brut")
            return rawFallback?.let { GrandOralProfileGuide(emptyList(), rawFallback = it) }
        }
        val (headerRow, headerColumns) = headerResult
        Log.d(TAG, "GO Profil notes : en-tête ligne $headerRow, ${headerColumns.size} colonnes")

        // 3. Lire les lignes de données
        val profileRows = mutableListOf<GrandOralProfileRow>()
        for ((row, entriesInRow) in rows) {
            if (row <= headerRow) continue
            val rowProfile = parseProfileDataRow(entriesInRow, headerColumns)
            if (rowProfile != null) {
                profileRows.add(rowProfile)
            } else if (profileRows.isNotEmpty()) {
                break
            }
        }

        Log.d(TAG, "GO Profil notes : ${profileRows.size} profils parsés (structuré)")
        return if (profileRows.isNotEmpty()) {
            GrandOralProfileGuide(profileRows)
        } else {
            rawFallback?.let { GrandOralProfileGuide(emptyList(), rawFallback = it) }
        }
    }

    /** Cherche la ligne contenant l'ancrage du tableau de profils. */
    private fun findAnchorRow(rows: Map<Int, List<Map.Entry<String, String>>>): Int? {
        val anchorPatterns = listOf(
            "exemples de profils",
            "exemple de profil",
            "profils de candidat",
            "profil de candidat",
            "profils candidats",
        )
        for ((row, entriesInRow) in rows) {
            val text = entriesInRow.joinToString(" ") { normalizeCellText(it.value) }
            Log.d(TAG, "GO Profil notes : ligne $row → \"${text.take(120)}\"")
            for (pattern in anchorPatterns) {
                val normPattern = normalizeCellText(pattern)
                if (text.contains(normPattern)) {
                    Log.d(TAG, "GO Profil notes : ancre \"$pattern\" trouvée ligne $row")
                    return row
                }
            }
        }
        Log.d(TAG, "GO Profil notes : aucune ancre trouvée parmi ${anchorPatterns}")
        return null
    }

    /** Cherche la ligne d'en-tête des colonnes après l'ancrage. */
    private fun findHeaderRow(
        rows: Map<Int, List<Map.Entry<String, String>>>,
        anchorRow: Int,
    ): Pair<Int, Map<Int, String>>? {
        for ((row, entriesInRow) in rows) {
            if (row <= anchorRow) continue
            val cols = detectProfileColumns(entriesInRow)
            if (cols.size >= 2) {  // seuil abaissé à 2 colonnes
                return row to cols
            }
        }
        return null
    }

    /** Détecte quelles colonnes correspondent à quelles catégories dans la ligne d'en-tête. */
    private fun detectProfileColumns(entries: List<Map.Entry<String, String>>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for ((ref, value) in entries) {
            val colIdx = columnIndex(ref.filter { it.isLetter() })
            val norm = normalizeCellText(value).takeIf { it.isNotBlank() } ?: continue

            // Chercher la meilleure correspondance parmi les ancres de colonnes
            val bestMatch = profileColumnAnchors.maxByOrNull { anchor ->
                overlapScore(norm, anchor)
            } ?: continue

            val score = overlapScore(norm, bestMatch)
            if (score >= 3) {  // seuil minimum de chevauchement
                result[colIdx] = bestMatch
            }
        }
        return result
    }

    /** Score de chevauchement entre un texte normalisé et une ancre. */
    private fun overlapScore(text: String, anchor: String): Int {
        if (text.contains(anchor)) return anchor.length
        if (anchor.contains(text)) return text.length
        // Compter les mots communs
        val textWords = text.split(" ").toSet()
        val anchorWords = anchor.split(" ").toSet()
        return textWords.intersect(anchorWords).sumOf { it.length }
    }

    /** Parse une ligne de données du tableau Profil notes. */
    private fun parseProfileDataRow(
        entries: List<Map.Entry<String, String>>,
        headerCols: Map<Int, String>,
    ): GrandOralProfileRow? {
        // Extraire les valeurs par colonne
        val valuesByCategory = mutableMapOf<String, String>()
        for ((ref, value) in entries) {
            val colIdx = columnIndex(ref.filter { it.isLetter() })
            val category = headerCols[colIdx] ?: continue
            val trimmed = value.trim()
            if (trimmed.isNotBlank()) {
                valuesByCategory[category] = trimmed
            }
        }

        // Vérifier qu'on a au moins 4 valeurs de profil sur 5
        val profileCategories = profileColumnAnchors.dropLast(1) // les 5 premières
        val profileCount = profileCategories.count { valuesByCategory.containsKey(it) }
        if (profileCount < 4) return null

        // Extraire chaque niveau
        fun levelFor(category: String): ProfileLevel {
            val raw = valuesByCategory[category] ?: return ProfileLevel.UNKNOWN
            return parseProfileLevelCode(raw)
        }

        return GrandOralProfileRow(
            oralQuality = levelFor(profileColumnAnchors[0]),
            continuousSpeech = levelFor(profileColumnAnchors[1]),
            knowledgeQuality = levelFor(profileColumnAnchors[2]),
            interactionQuality = levelFor(profileColumnAnchors[3]),
            argumentationQuality = levelFor(profileColumnAnchors[4]),
            possibleGrade = valuesByCategory[profileColumnAnchors[5]] ?: "",
        )
    }

    /** Convertit un code brut (TS, S, I, TI, ou texte plus long) en ProfileLevel. */
    private fun parseProfileLevelCode(raw: String): ProfileLevel {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")

        // Essayer le code exact d'abord
        ProfileLevel.fromCode(cleaned).takeIf { it != ProfileLevel.UNKNOWN }?.let { return it }

        // Chercher le code dans un texte plus long (ex: "TS" dans "TS - Très satisfaisant")
        for (level in ProfileLevel.entries) {
            if (level == ProfileLevel.UNKNOWN) continue
            if (cleaned.equals(level.code, ignoreCase = true) ||
                cleaned.startsWith("${level.code} ", ignoreCase = true) ||
                cleaned.startsWith("${level.code}-", ignoreCase = true) ||
                cleaned.startsWith("${level.code}/", ignoreCase = true)
            ) {
                return level
            }
        }

        // Chercher par signification (ex: "Très satisfaisant" → VERY_SATISFACTORY)
        val norm = normalizeCellText(cleaned)
        return when {
            norm.contains("tres satisfaisant") || norm == "ts" -> ProfileLevel.VERY_SATISFACTORY
            norm.contains("satisfaisant") && !norm.contains("tres") || norm == "s" -> ProfileLevel.SATISFACTORY
            norm.contains("tres insatisfaisant") || norm == "ti" -> ProfileLevel.VERY_UNSATISFACTORY
            norm.contains("insatisfaisant") && !norm.contains("tres") || norm == "i" -> ProfileLevel.UNSATISFACTORY
            else -> ProfileLevel.UNKNOWN
        }
    }

    /** Trouve la ligne contenant les en-têtes de niveaux. */
    private fun findLevelHeaderRow(cells: Map<String, String>): Int? {
        // On cherche une ligne qui contient au moins "insatisfaisant" et "satisfaisant"
        val rowTexts = cells.entries
            .groupBy({ it.key.filter { it.isDigit() }.toIntOrNull() ?: 0 }, { it.value })
            .mapValues { (_, texts) -> texts.joinToString(" ") { normalizeCellText(it) } }

        // Chercher la ligne la plus prometteuse
        val scored = rowTexts.map { (row, text) ->
            var score = 0
            if (text.contains("tres insatisfaisant")) score += 3
            if (text.contains("insatisfaisant") && !text.contains("tres insatisfaisant")) score += 2
            if (text.contains("tres satisfaisant")) score += 3
            if (text.contains("satisfaisant") && !text.contains("tres satisfaisant")) score += 2
            if (text.contains("tres insatisfaisant 0") || text.contains("insatisfaisant 1")) score += 1
            row to score
        }

        val best = scored.maxByOrNull { it.second } ?: return null
        return if (best.second >= 3) best.first else null
    }

    /** Détermine les colonnes de chaque niveau (0→3) dans la ligne d'en-tête. */
    private fun findLevelColumns(
        cells: Map<String, String>,
        levelRow: Int,
    ): Map<Int, String> {
        val result = mutableMapOf<Int, String>()

        for ((ref, value) in cells) {
            val row = ref.filter { it.isDigit() }.toIntOrNull() ?: continue
            if (row != levelRow) continue
            val col = ref.filter { it.isLetter() }
            val norm = normalizeCellText(value)

            when {
                norm.contains("tres insatisfaisant") -> result[0] = col
                norm.contains("insatisfaisant") && !norm.contains("tres insatisfaisant") -> result[1] = col
                norm.contains("satisfaisant") && !norm.contains("tres satisfaisant") -> result[2] = col
                norm.contains("tres satisfaisant") -> result[3] = col
            }
        }

        return result
    }

    /** Trouve les lignes contenant les critères GO après la ligne des niveaux. */
    private fun findCriteriaRows(
        cells: Map<String, String>,
        levelRow: Int,
        criteriaNames: List<String>,
    ): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        val seenNames = mutableSetOf<String>()

        // Grouper le texte par ligne pour chercher les critères
        val rowTexts = cells.entries
            .filter { (ref, _) ->
                val row = ref.filter { it.isDigit() }.toIntOrNull() ?: return@filter false
                row > levelRow
            }
            .groupBy({ it.key.filter { it.isDigit() }.toIntOrNull() ?: 0 }) { it }

        for ((row, entries) in rowTexts.toSortedMap()) {
            for (criterionName in criteriaNames) {
                val normName = normalizeCellText(criterionName)
                for ((ref, value) in entries) {
                    val normValue = normalizeCellText(value)
                    if (normValue.contains(normName) && normName !in seenNames) {
                        // Prendre la valeur originale (non normalisée) comme label
                        found.add(row to value.trim())
                        seenNames.add(normName)
                    }
                }
            }
        }

        return found
    }

    /** Cherche une pondération dans la ligne (à droite des colonnes de niveaux). */
    private fun findWeightInRow(
        cells: Map<String, String>,
        row: Int,
        levelColumns: Map<Int, String>,
    ): Double {
        // Chercher une valeur numérique dans une colonne au-delà des niveaux
        val maxLevelCol = levelColumns.values.maxByOrNull { columnIndex(it) } ?: return 1.0
        val maxLevelIdx = columnIndex(maxLevelCol)

        for ((ref, value) in cells) {
            val cellRow = ref.filter { it.isDigit() }.toIntOrNull() ?: continue
            if (cellRow != row) continue
            val col = ref.filter { it.isLetter() }
            if (columnIndex(col) <= maxLevelIdx) continue

            val weight = value.replace(',', '.').toDoubleOrNull()
            if (weight != null && weight > 0.0) return weight
        }
        return 1.0
    }

    private fun columnIndex(ref: String): Int {
        return ref.fold(0) { total, char ->
            total * 26 + (char.uppercaseChar() - 'A' + 1)
        }
    }

    // ── Utilitaires partagés ──────────────────────────────────────────────

    private fun workbookSheets(entries: Map<String, ByteArray>): Map<String, String> {
        val workbook = entries["xl/workbook.xml"] ?: return emptyMap()
        val relationships = entries["xl/_rels/workbook.xml.rels"] ?: return emptyMap()
        val targetsById = workbookRelationships(relationships)
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(workbook), null)
        val sheets = linkedMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name").orEmpty()
                val id = parser.getAttributeValue(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                    "id"
                ).orEmpty()
                val target = targetsById[id].orEmpty()
                if (name.isNotBlank() && target.isNotBlank()) {
                    sheets[name] = if (target.startsWith("xl/")) target else "xl/$target"
                }
            }
            event = parser.next()
        }
        return sheets
    }

    private fun workbookRelationships(bytes: ByteArray): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        val relationships = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id").orEmpty()
                val target = parser.getAttributeValue(null, "Target").orEmpty()
                if (id.isNotBlank() && target.isNotBlank()) relationships[id] = target
            }
            event = parser.next()
        }
        return relationships
    }

    private fun parseDescriptors(
        bytes: ByteArray,
        sharedStrings: List<String>,
    ): Map<String, Map<Int, String>> {
        val cells = parseCells(bytes, sharedStrings)
        val result = mutableMapOf<String, Map<Int, String>>()
        (1..80).forEach { row ->
            val label = cells["C$row"]?.trim().orEmpty()
            if (label.isBlank() || normalizeHeader(label).startsWith("criteres d'evaluation")) return@forEach
            val levels = mapOf(
                0 to cells["D$row"].orEmpty().trim(),
                1 to cells["E$row"].orEmpty().trim(),
                2 to cells["F$row"].orEmpty().trim(),
                3 to cells["G$row"].orEmpty().trim(),
            ).filterValues { it.isNotBlank() }
            if (levels.isNotEmpty()) {
                result[normalizeHeader(label)] = levels
                result["criterion-${row + 3}"] = levels
            }
        }
        return result
    }

    private fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                zip.copyTo(output)
                entries[entry.name] = output.toByteArray()
            }
        }
        return entries
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        val strings = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "si") {
                strings += readSharedString(parser)
            }
            event = parser.next()
        }
        return strings
    }

    private fun readSharedString(parser: XmlPullParser): String {
        val builder = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") builder.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") return builder.toString()
            }
        }
    }

    private fun parseCells(bytes: ByteArray, sharedStrings: List<String>): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        val cells = mutableMapOf<String, String>()
        var ref = ""
        var type = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "c") {
                ref = parser.getAttributeValue(null, "r").orEmpty()
                type = parser.getAttributeValue(null, "t").orEmpty()
            }
            if (event == XmlPullParser.START_TAG && parser.name == "v" && ref.isNotBlank()) {
                val raw = parser.nextText()
                cells[ref] = if (type == "s") sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            event = parser.next()
        }
        return cells
    }
}
