package com.teob.appnotationmobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class XlsxGridExporterTest {
    @Test
    fun grandOralWritesMarksOnInputRowBelowDescriptorRow() {
        val project = TpProject(
            name = "Grand oral",
            criteria = listOf(Criterion("criterion-8", "Grand oral", "Qualite orale", 1.0)),
            gridKind = GridKind.GRAND_ORAL_2I2D,
            gridLevelColumns = mapOf(0 to "D", 1 to "E", 2 to "G", 3 to "H"),
        )

        val output = XlsxGridExporter.fill(
            project = project,
            template = minimalWorkbook(
                """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="8"><c r="G8" s="4"><v>descriptor</v></c></row>
                    <row r="9"><c r="D9" s="5"/><c r="E9" s="5"/><c r="G9" s="5"/><c r="H9" s="5"/></row>
                  </sheetData>
                </worksheet>
                """.trimIndent(),
            ),
            candidateLabel = "CANDIDAT",
            grades = mapOf("criterion-8" to 2),
        )

        val sheet = unzip(output)["xl/worksheets/sheet1.xml"]!!.toString(Charsets.UTF_8)

        assertFalse(sheet.contains("""<c r="G8" s="4" t="str"><v>x</v></c>"""))
        assertContains(sheet, """<c r="G9" s="5" t="str"><v>x</v></c>""")
    }

    @Test
    fun grandOralWritesExportDateAndCandidateName() {
        val project = TpProject(
            name = "Grand oral",
            criteria = emptyList(),
            gridKind = GridKind.GRAND_ORAL_2I2D,
            gridLevelColumns = mapOf(0 to "E", 1 to "F", 2 to "G", 3 to "H"),
        )

        val output = XlsxGridExporter.fill(
            project = project,
            template = minimalWorkbook(
                """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="22"><c r="B22" s="47" t="s"><v>9</v></c><c r="C22" s="48"/></row>
                    <row r="23"><c r="B23" s="89" t="s"><v>12</v></c></row>
                    <row r="24"><c r="B24" s="91"/><c r="C24" s="91"/></row>
                  </sheetData>
                </worksheet>
                """.trimIndent(),
            ),
            candidateLabel = "DUPONT Ada",
            grades = emptyMap(),
        )

        val sheet = unzip(output)["xl/worksheets/sheet1.xml"]!!.toString(Charsets.UTF_8)
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())

        assertContains(sheet, """<c r="C22" s="48" t="str"><v>$today</v></c>""")
        assertContains(sheet, """<c r="B24" s="91" t="str"><v>DUPONT Ada</v></c>""")
    }

    @Test
    fun grandOralWritesJuryNoteToProposedJuryCell() {
        val project = TpProject(
            name = "Grand oral",
            criteria = emptyList(),
            gridKind = GridKind.GRAND_ORAL_2I2D,
            gridLevelColumns = mapOf(0 to "E", 1 to "F", 2 to "G", 3 to "H"),
        )

        val output = XlsxGridExporter.fill(
            project = project,
            template = minimalWorkbook(
                """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="20"><c r="D20" s="44" t="s"><v>6</v></c><c r="E20" s="95"><v>0</v></c><c r="F20" s="95"/></row>
                  </sheetData>
                </worksheet>
                """.trimIndent(),
            ),
            candidateLabel = "DUPONT Ada",
            grades = emptyMap(),
            juryNote = "14,5",
        )

        val sheet = unzip(output)["xl/worksheets/sheet1.xml"]!!.toString(Charsets.UTF_8)

        assertContains(sheet, """<c r="E20" s="95"><v>14.5</v></c>""")
    }

    private fun minimalWorkbook(sheetXml: String): ByteArray {
        return zip(
            mapOf(
                "xl/worksheets/sheet1.xml" to sheetXml,
                "[Content_Types].xml" to "<Types/>",
                "xl/_rels/workbook.xml.rels" to "<Relationships/>",
                "xl/workbook.xml" to "<workbook/>",
            ),
        )
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
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
}
