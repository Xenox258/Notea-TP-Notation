package com.teob.appnotationmobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object XlsxGridExporter {
    // Remplit une copie du modèle XLSX importé sans modifier le fichier source.
    fun fill(
        project: TpProject,
        template: ByteArray,
        candidateLabel: String,
        grades: Map<String, Int>,
        juryNote: String? = null,
    ): ByteArray {
        val entries = readZipEntries(template)
        val sheetPath = when (project.gridKind) {
            GridKind.ETLV -> "xl/worksheets/sheet1.xml"
            GridKind.GRAND_ORAL_2I2D -> "xl/worksheets/sheet1.xml"
            else -> "xl/worksheets/sheet2.xml"
        }
        val sheet = entries[sheetPath]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Feuille export introuvable")
        entries[sheetPath] = when (project.gridKind) {
            GridKind.ETLV -> fillEtlvSheet(sheet, project, candidateLabel, grades)
            GridKind.GRAND_ORAL_2I2D -> fillGoSheet(sheet, project, candidateLabel, grades, juryNote)
            else -> fillEpSheet(sheet, project, candidateLabel, grades)
        }.toByteArray(Charsets.UTF_8)
        prepareWorkbookForRecalculation(entries)
        return writeZipEntries(entries)
    }

    private fun fillEpSheet(
        sheet: String,
        project: TpProject,
        candidateLabel: String,
        grades: Map<String, Int>,
    ): String {
        var xml = sheet
        xml = upsertCell(xml, "B28", candidateLabel, text = true)
        xml = upsertCell(xml, "C26", exportDateLabel(), text = true)
        xml = upsertCell(xml, "D28", project.name, text = true)
        xml = upsertCell(xml, "F24", "")
        project.criteria.forEach { criterion ->
            val row = criterion.rowNumber() ?: return@forEach
            listOf("E", "F", "G", "H", "I").forEach { col -> xml = upsertCell(xml, "$col$row", "") }
            val level = grades[criterion.id] ?: return@forEach
            val col = when (level) {
                -1 -> "E"
                0 -> "F"
                1 -> "G"
                2 -> "H"
                3 -> "I"
                else -> null
            } ?: return@forEach
            xml = upsertCell(xml, "$col$row", "x", text = true)
        }
        return xml
    }

    private fun fillEtlvSheet(
        sheet: String,
        project: TpProject,
        candidateLabel: String,
        grades: Map<String, Int>,
    ): String {
        var xml = sheet
        xml = upsertCell(xml, "B6", etlvLastNames(candidateLabel), text = true)
        xml = upsertCell(xml, "B7", etlvFirstNames(candidateLabel), text = true)
        xml = upsertCell(xml, "B24", exportDateLabel(), text = true)
        xml = upsertCell(xml, "D24", project.name, text = true)
        project.criteria.forEach { criterion ->
            val row = criterion.rowNumber() ?: return@forEach
            listOf("E", "F", "G", "H").forEach { col -> xml = upsertCell(xml, "$col$row", "") }
            val level = grades[criterion.id] ?: return@forEach
            val col = when (level.coerceIn(0, 3)) {
                0 -> "E"
                1 -> "F"
                2 -> "G"
                else -> "H"
            }
            xml = upsertCell(xml, "$col$row", "X", text = true)
        }
        return xml
    }

    private fun fillGoSheet(
        sheet: String,
        project: TpProject,
        candidateLabel: String,
        grades: Map<String, Int>,
        juryNote: String?,
    ): String {
        var xml = sheet
        xml = upsertCell(xml, "C22", exportDateLabel(), text = true)
        xml = upsertCell(xml, "B24", candidateLabel, text = true)
        juryNote?.toExcelNumber()?.let { note ->
            xml = upsertCell(xml, "E20", note)
        }
        val levelColumns = project.gridLevelColumns
        if (levelColumns.isEmpty()) return xml

        project.criteria.forEach { criterion ->
            val descriptorRow = criterion.rowNumber() ?: return@forEach
            val inputRow = descriptorRow + 1
            val level = grades[criterion.id] ?: return@forEach
            if (level < 0) return@forEach  // NE = pas de croix
            val col = levelColumns[level] ?: return@forEach
            // Effacer les autres colonnes de niveaux sur cette ligne
            levelColumns.values.forEach { otherCol ->
                if (otherCol != col) xml = upsertCell(xml, "$otherCol$inputRow", "")
            }
            xml = upsertCell(xml, "$col$inputRow", "x", text = true)
        }
        return xml
    }

    private fun String.toExcelNumber(): String? {
        val normalized = trim().replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        return if (value in 0.0..20.0) normalized else null
    }

    private fun Criterion.rowNumber(): Int? {
        return id.substringAfter("criterion-", "").toIntOrNull()
    }

    private fun exportDateLabel(): String {
        return SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
    }

    private fun etlvLastNames(candidateLabel: String): String {
        return candidateLabel.split(" + ")
            .map { name -> exportFamilyName(name) }
            .joinToString(" + ")
    }

    private fun etlvFirstNames(candidateLabel: String): String {
        return candidateLabel.split(" + ")
            .map { name -> exportGivenNames(name) }
            .filter { it.isNotBlank() }
            .joinToString(" + ")
    }

    private fun exportFamilyName(fullName: String): String {
        val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return fullName.trim()
        val uppercasePrefix = parts.takeWhile { part ->
            part.any { it.isLetter() } && part == part.uppercase(Locale.ROOT)
        }
        return uppercasePrefix.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: parts.first()
    }

    private fun exportGivenNames(fullName: String): String {
        val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size <= 1) return ""
        val familyParts = parts.takeWhile { part ->
            part.any { it.isLetter() } && part == part.uppercase(Locale.ROOT)
        }.takeIf { it.isNotEmpty() } ?: parts.take(1)
        return parts.drop(familyParts.size).joinToString(" ")
    }

    private fun upsertCell(xml: String, ref: String, value: String, text: Boolean = false): String {
        val row = ref.dropWhile { it.isLetter() }.toIntOrNull() ?: return xml
        val cellRegex = cellRegex(ref)
        val existingCell = cellRegex.find(xml)
        if (existingCell != null) {
            val cell = cellXml(ref, value, text, existingCell.value)
            return xml.replaceRange(existingCell.range, cell)
        }

        val cell = cellXml(ref, value, text)
        val rowRegex = Regex("(<row\\b[^>]*\\br=\"$row\"[^>]*>)(.*?)(</row>)", RegexOption.DOT_MATCHES_ALL)
        val rowMatch = rowRegex.find(xml)
        if (rowMatch != null) {
            val content = insertCellInOrder(rowMatch.groupValues[2], cell, ref)
            return xml.replaceRange(rowMatch.range, rowMatch.groupValues[1] + content + rowMatch.groupValues[3])
        }
        val newRow = "<row r=\"$row\">$cell</row>"
        return xml.replace("</sheetData>", "$newRow</sheetData>")
    }

    private fun cellRegex(ref: String): Regex {
        return Regex(
            "<c\\b(?=[^>]*\\br=\"$ref\")[^>]*/>|<c\\b(?=[^>]*\\br=\"$ref\")[^>]*>.*?</c>",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    private fun insertCellInOrder(rowContent: String, cell: String, ref: String): String {
        val targetColumn = columnIndex(ref)
        val cellRegex = Regex("<c\\b[^>]*\\br=\"([A-Z]+)\\d+\"[^>]*/>|<c\\b[^>]*\\br=\"([A-Z]+)\\d+\"[^>]*>.*?</c>", RegexOption.DOT_MATCHES_ALL)
        val insertBefore = cellRegex.findAll(rowContent).firstOrNull { match ->
            val column = match.groupValues[1].ifBlank { match.groupValues[2] }
            columnIndex(column) > targetColumn
        }
        return if (insertBefore != null) {
            rowContent.substring(0, insertBefore.range.first) + cell + rowContent.substring(insertBefore.range.first)
        } else {
            rowContent + cell
        }
    }

    private fun columnIndex(ref: String): Int {
        return ref.takeWhile { it.isLetter() }.fold(0) { total, char ->
            total * 26 + (char.uppercaseChar() - 'A' + 1)
        }
    }

    private fun cellXml(ref: String, value: String, text: Boolean, existingCell: String? = null): String {
        val attrs = cellAttributes(ref, existingCell)
        if (value.isBlank()) return "<c $attrs/>"
        return if (text) {
            "<c $attrs t=\"str\"><v>${escapeXml(value)}</v></c>"
        } else {
            "<c $attrs><v>$value</v></c>"
        }
    }

    private fun cellAttributes(ref: String, existingCell: String?): String {
        val rawAttrs = existingCell
            ?.let { Regex("<c\\b([^>]*)").find(it)?.groupValues?.getOrNull(1) }
            .orEmpty()
        val style = Regex("\\bs=\"[^\"]*\"").find(rawAttrs)?.value
        return listOfNotNull("r=\"$ref\"", style).joinToString(" ")
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // Force Excel/LibreOffice à recalculer les formules après insertion des croix et métadonnées.
    private fun prepareWorkbookForRecalculation(entries: MutableMap<String, ByteArray>) {
        entries.remove("xl/calcChain.xml")
        entries["[Content_Types].xml"] = entries["[Content_Types].xml"]
            ?.toString(Charsets.UTF_8)
            ?.replace(Regex("<Override\\b[^>]*PartName=\"/xl/calcChain.xml\"[^>]*/>"), "")
            ?.toByteArray(Charsets.UTF_8)
            ?: return
        entries["xl/_rels/workbook.xml.rels"] = entries["xl/_rels/workbook.xml.rels"]
            ?.toString(Charsets.UTF_8)
            ?.replace(Regex("<Relationship\\b[^>]*Type=\"[^\"]*/calcChain\"[^>]*/>"), "")
            ?.toByteArray(Charsets.UTF_8)
            ?: return
        entries["xl/workbook.xml"] = entries["xl/workbook.xml"]
            ?.toString(Charsets.UTF_8)
            ?.let { workbook ->
                val calcPr = "<calcPr calcMode=\"auto\" fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/>"
                val calcRegex = Regex("<calcPr\\b[^>]*/>|<calcPr\\b[^>]*>.*?</calcPr>", RegexOption.DOT_MATCHES_ALL)
                if (calcRegex.containsMatchIn(workbook)) {
                    workbook.replace(calcRegex, calcPr)
                } else {
                    workbook.replace("</workbook>", "$calcPr</workbook>")
                }
            }
            ?.toByteArray(Charsets.UTF_8)
            ?: return
    }

    private fun readZipEntries(bytes: ByteArray): MutableMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                zip.copyTo(output)
                entries[entry.name] = output.toByteArray()
            }
        }
        return entries
    }

    private fun writeZipEntries(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
