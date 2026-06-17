package com.teob.appnotationmobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var projects = mutableListOf<TpProject>()
    private var project = TpProject()
    private var selectedStudentId: String? = null
    private var pairEditMode = false
    private var pendingStudentListScrollY: Int? = null
    private var studentFilter = StudentFilter.ALL
    private var isDarkMode = false
    private var gradeTextScale = 1.0f
    private val ui: UiPalette
        get() = if (isDarkMode) UiTheme.dark else UiTheme.light
    private val gradeFormat = DecimalFormat("0.0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDarkMode = ThemePrefs.loadDarkMode(this)
        projects = ProjectStore.loadAll(this).toMutableList()
        showHome()
    }

    // Point d'entrée des imports/exports Android : CSV élèves, XLSX grille, puis fichier XLSX généré.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            restoreProjectPendingImport()
            when (requestCode) {
                REQUEST_STUDENTS -> {
                    val bytes = contentResolver.openInputStream(uri).useRequired { it.readBytes() }
                    applyStudentList(bytes)
                    ImportCacheStore.save(this, ImportCacheKind.STUDENTS, displayName(uri), bytes)
                    persistAndShowSetup("Liste élèves importée")
                }

                REQUEST_GRID -> {
                    val bytes = contentResolver.openInputStream(uri).useRequired { it.readBytes() }
                    applyGrid(bytes)
                    ImportCacheStore.save(this, ImportCacheKind.GRIDS, displayName(uri), bytes)
                    val typeLabel = EvaluationSheetType.fromKind(project.gridKind).label
                    persistAndShowSetup("Grille $typeLabel importée")
                }

                REQUEST_EXPORT_GRID -> {
                    exportFilledGrid(uri)
                }

                REQUEST_EXPORT_ALL_GRIDS -> {
                    exportAllGradedGrids(uri)
                }
            }
        } catch (error: Exception) {
            toast("Opération impossible : ${error.message ?: "fichier invalide"}")
        }
    }

    // Écran de configuration du TP : nom, élèves, grille et actions de préparation.
    private fun showTpSetup() {
        selectedStudentId = null
        pairEditMode = false
        var nameInput: EditText? = null
        setContentView(
            verticalRoot {
                addView(headerRow(
                    title = if (project.name.isBlank()) "Nouveau TP" else "Réglages TP",
                    leading = {
                    addView(homeButton {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        saveCurrentProject()
                        showHome()
                    })
                    },
                    trailing = {
                        addView(themeToggleButton {
                            project.name = nameInput?.text?.toString()?.trim().orEmpty()
                            saveCurrentProject()
                            showTpSetup()
                        })
                    },
                ))
                addView(spacer(18))
                addView(label("Nom du TP"))
                nameInput = EditText(this@MainActivity).apply {
                    setSingleLine(true)
                    hint = "Ex: TP Acquisition capteur"
                    setText(project.name)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    styleInput()
                }
                addView(nameInput, matchWrap())

                addView(infoLine("Élèves", "${project.students.size} importés"))
                addView(row {
                    addView(iconActionButton(
                        iconRes = R.drawable.ic_file_import,
                        description = "Importer liste d'élèves CSV",
                    ) {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        showImportCacheDialog(
                            kind = ImportCacheKind.STUDENTS,
                            title = "Importer une liste",
                            requestCode = REQUEST_STUDENTS,
                            mimeType = "text/*",
                        ) { cached -> importCachedStudentList(cached) }
                    })
                    addView(iconActionButton(
                        iconRes = R.drawable.ic_person_add,
                        description = "Ajouter un élève",
                    ) {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        showAddStudentDialog { showTpSetup() }
                    })
                    addView(iconActionButton(
                        iconRes = R.drawable.ic_person_remove,
                        description = "Enlever un élève",
                        enabled = project.students.isNotEmpty(),
                    ) {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        showRemoveStudentDialog { showTpSetup() }
                    })
                })

                if (project.pairings.isNotEmpty()) {
                    addView(spacer(12))
                    addView(actionButton("Réinitialiser les binômes") {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        unlinkAllStudents()
                        selectedStudentId = null
                        pairEditMode = false
                        saveCurrentProject()
                        toast("Binômes réinitialisés")
                        showTpSetup()
                    })
                }

                addView(spacer(12))
                addView(infoLine("Grille", "${project.criteria.size} critères"))
                addView(row {
                    addView(iconActionButton(
                        iconRes = R.drawable.ic_file_import,
                        description = "Importer feuille de notation XLSX",
                    ) {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        showImportCacheDialog(
                            kind = ImportCacheKind.GRIDS,
                            title = "Importer une grille",
                            requestCode = REQUEST_GRID,
                            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        ) { cached -> importCachedGrid(cached) }
                    })
                    addView(iconActionButton(
                        iconRes = R.drawable.ic_grid_export,
                        description = "Exporter grille remplie",
                        enabled = project.criteria.isNotEmpty(),
                    ) {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        saveCurrentProject()
                        showExportCandidateDialog()
                    })
                })
                if (project.criteria.isNotEmpty()) {
                    addView(spacer(8))
                    addView(actionButton("Modifier les pondérations") {
                        project.name = nameInput?.text?.toString()?.trim().orEmpty()
                        saveCurrentProject()
                        showWeightSettings()
                    })
                }

                addView(spacer(16))
                addView(actionButton("Ouvrir le TP") {
                    project.name = nameInput?.text?.toString()?.trim().orEmpty()
                    when {
                        project.name.isBlank() -> toast("Donne d'abord un nom au TP.")
                        project.students.isEmpty() -> toast("Importe ou charge une liste d'élèves.")
                        project.criteria.isEmpty() -> toast("Importe ou charge une grille de notation.")
                        else -> {
                            saveCurrentProject()
                            showStudentList()
                        }
                    }
                })

                if (projects.any { it.id == project.id }) {
                    addView(spacer(12))
                    addView(actionButton("Supprimer ce TP") {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Supprimer ce TP ?")
                            .setMessage("Le TP et ses notes locales seront supprimés de la liste.")
                            .setPositiveButton("Supprimer") { _, _ ->
                                ProjectStore.delete(this@MainActivity, project.id)
                                projects = ProjectStore.loadAll(this@MainActivity).toMutableList()
                                project = TpProject()
                                showHome()
                            }
                            .setNegativeButton("Annuler", null)
                            .show()
                    })
                }
            },
        )
    }

    private fun showHome() {
        selectedStudentId = null
        projects = ProjectStore.loadAll(this).toMutableList()
        setContentView(
            verticalRoot {
                addView(homeHeader())
                addView(spacer(18))
                addView(title("Vos TP"))
                if (projects.isEmpty()) {
                    addView(TextView(this@MainActivity).apply {
                        text = "Aucun TP pour le moment."
                        textSize = 16f
                        setTextColor(ui.muted)
                        setPadding(0, 0, 0, dp(12))
                    })
                }
                projects.forEach { existingProject ->
                    addView(projectRow(existingProject))
                }
                addView(spacer(10))
                addView(newProjectButton())
                addView(spacer(28))
                addView(homeLogo())
            },
        )
    }

    private fun homeHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = headerSurface()
            setPadding(dp(18), dp(16), dp(12), dp(16))

            addView(TextView(this@MainActivity).apply {
                text = "Notéa"
                textSize = 24f
                setTextColor(ui.headerText)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(themeToggleButton { showHome() })
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun homeLogo(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
            runCatching {
                assets.open("logo.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { bitmap ->
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    alpha = if (isDarkMode) 0.88f else 0.78f
                }, LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun projectRow(existingProject: TpProject): View {
        return Button(this).apply {
            val subtitle = "${existingProject.students.size} élèves - Moyenne ${averageLabel(existingProject)}"
            text = "${existingProject.name.ifBlank { "TP sans nom" }}\n$subtitle"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setAllCaps(false)
            setTextColor(ui.text)
            background = cardSurface()
            stateListAnimator = null
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener {
                project = existingProject
                if (project.students.isEmpty() || project.criteria.isEmpty()) showTpSetup() else showStudentList()
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
    }

    private fun newProjectButton(): View {
        return Button(this).apply {
            text = "Nouveau TP"
            textSize = 18f
            setAllCaps(false)
            setTextColor(ui.iconOnAccent)
            background = accentSurface()
            stateListAnimator = null
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener {
                project = TpProject()
                showTpSetup()
            }
        }
    }

    private fun showWeightSettings() {
        val inputs = mutableMapOf<String, EditText>()
        setContentView(
            verticalRoot {
                addView(headerRow(
                    title = "Pondérations",
                    leading = {
                    addView(backButton {
                        saveWeights(inputs)
                        showTpSetup()
                    })
                    },
                    trailing = {
                        addView(themeToggleButton { showWeightSettings() })
                    },
                ))

                addView(TextView(this@MainActivity).apply {
                    text = "Ces valeurs sont utilisées pour calculer les notes et la moyenne du TP."
                    textSize = 15f
                    setTextColor(ui.muted)
                    setPadding(dp(2), dp(10), dp(2), dp(16))
                })

                groupedCriteria().forEach { (skill, criteria) ->
                    addView(skillHeader(skill))
                    criteria.forEach { criterion ->
                        addView(weightEditor(criterion, inputs))
                    }
                }

                addView(actionButton("Enregistrer") {
                    saveWeights(inputs)
                    toast("Pondérations enregistrées")
                    showTpSetup()
                })
            },
        )
    }

    private fun weightEditor(criterion: Criterion, inputs: MutableMap<String, EditText>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurface()
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = criterion.skill
                textSize = 13f
                setTextColor(ui.primary)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = criterion.label
                textSize = 15f
                setTextColor(ui.text)
                setPadding(0, dp(3), 0, dp(6))
            })
            val input = EditText(this@MainActivity).apply {
                setSingleLine(true)
                hint = "Poids"
                setText(weightLabel(criterion.weight))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                styleInput()
            }
            inputs[criterion.id] = input
            addView(input, matchWrap())
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }
    }

    private fun saveWeights(inputs: Map<String, EditText>) {
        val parsedWeights = inputs.mapValues { (_, input) ->
            input.text.toString().replace(',', '.').toDoubleOrNull()
        }
        val invalid = parsedWeights.values.any { it == null || it < 0.0 }
        if (invalid) {
            toast("Certaines pondérations invalides ont été ignorées.")
        }
        project.criteria.forEach { criterion ->
            val weight = parsedWeights[criterion.id]
            if (weight != null && weight >= 0.0) criterion.weight = weight
        }
        saveCurrentProject()
    }

    private fun showStudentList() {
        if (!pairEditMode) selectedStudentId = null
        val scrollY = pendingStudentListScrollY
        pendingStudentListScrollY = null
        val root = verticalRoot {
                addView(headerRow(
                    title = project.name,
                    leading = {
                    addView(homeButton {
                        saveCurrentProject()
                        showHome()
                    })
                    },
                    trailing = {
                        addView(themeToggleButton { showStudentList() })
                    addView(gearButton { showTpSetup() })
                    },
                ))

                addView(recapView(), matchWrap().apply {
                    setMargins(0, dp(10), 0, dp(12))
                })

                if (project.students.size >= 5) {
                    addView(filterControl())
                    addView(spacer(10))
                }

                if (project.students.size >= 2) {
                    addView(actionButton(pairModeActionLabel()) {
                        when {
                            !project.pairMode -> {
                                project.pairMode = true
                                pairEditMode = true
                            }
                            pairEditMode -> {
                                pairEditMode = false
                                selectedStudentId = null
                            }
                            else -> pairEditMode = true
                        }
                        saveCurrentProject()
                        pendingStudentListScrollY = currentScrollY()
                        showStudentList()
                    })
                }
                addView(spacer(12))

                if (pairEditMode) {
                    addView(TextView(this@MainActivity).apply {
                        text = selectedStudentId
                            ?.let { "Sélectionne le deuxième élève du binôme." }
                            ?: "Sélectionne deux élèves pour créer un binôme. Utilise la croix pour défaire un lien."
                        textSize = 15f
                        setTextColor(ui.muted)
                        setPadding(dp(2), 0, dp(2), dp(10))
                    })
                }

                studentDisplayGroups().forEach { group ->
                    addView(studentGroupView(group))
                }
            }
        setContentView(root)
        scrollY?.let { y -> root.post { root.scrollTo(0, y) } }
    }

    private fun filteredStudents(): List<Student> {
        return when (studentFilter) {
            StudentFilter.ALL -> project.students
            StudentFilter.TO_GRADE -> project.students.filter { scoreForStudent(it.id) == null }
            StudentFilter.GRADED -> project.students.filter { scoreForStudent(it.id) != null }
        }
    }

    private fun filterControl(): View {
        return row {
            listOf(
                StudentFilter.ALL to "Tous",
                StudentFilter.TO_GRADE to "A noter",
                StudentFilter.GRADED to "Notes",
            ).forEach { (filter, label) ->
                addView(segmentButton(label, studentFilter == filter) {
                    studentFilter = filter
                    showStudentList()
                })
            }
        }
    }

    // Écran de notation d'un élève ou d'un binôme, avec score recalculé à chaque saisie.
    private fun showStudentGrade(student: Student) {
        selectedStudentId = student.id
        val grades = project.grades.getOrPut(gradeOwnerId(student.id)) { mutableMapOf() }
        val root = verticalRoot {
                addView(headerRow(
                    title = gradeTitle(student),
                    leading = {
                    addView(backButton {
                        saveCurrentProject()
                        showStudentList()
                    })
                    if (hasDescriptors()) {
                        addView(infoButton {
                            showCompetencyDescriptions()
                        })
                    }
                    },
                    trailing = {
                        addView(fontScaleButton("-") {
                            gradeTextScale = (gradeTextScale - 0.1f).coerceIn(0.8f, 1.4f)
                            showStudentGrade(student)
                        })
                        addView(fontScaleButton("+") {
                            gradeTextScale = (gradeTextScale + 0.1f).coerceIn(0.8f, 1.4f)
                            showStudentGrade(student)
                        })
                        addView(themeToggleButton { showStudentGrade(student) })
                    },
                ))

                val scoreText = TextView(this@MainActivity).apply {
                    text = "Note: ${scoreLabel(student.id)}"
                    textSize = scaledGradeTextSize(22f)
                    setTextColor(scoreColor(student.id))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = cardSurface()
                    setPadding(dp(14), dp(16), dp(14), dp(16))
                }
                addView(scoreText, matchWrap().apply {
                    setMargins(0, dp(12), 0, dp(12))
                })

                groupedCriteria().forEach { (skill, criteria) ->
                    addView(skillHeader(skill))
                    criteria.forEach { criterion ->
                        addView(criterionEditor(criterion, grades, scoreText))
                    }
                }
            }
        setContentView(root)
    }

    private fun criterionEditor(
        criterion: Criterion,
        grades: MutableMap<String, Int>,
        scoreText: TextView,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurface()
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = "${criterion.label}\nPondération : ${weightLabel(criterion.weight)}"
                textSize = scaledGradeTextSize(16f)
                setTextColor(ui.text)
                setPadding(0, dp(3), 0, dp(8))
            })
            addView(row {
                val options = listOf(-1 to "NE", 0 to "0", 1 to "1", 2 to "2", 3 to "3")
                val buttons = mutableListOf<Pair<Int, Button>>()
                fun refreshButtons() {
                    buttons.forEach { (value, button) ->
                        val selected = grades[criterion.id] == value
                        button.setTextColor(if (selected) ui.iconOnAccent else ui.text)
                        button.background = if (selected) accentSurface() else glassSurface()
                    }
                }
                options.forEach { (value, text) ->
                    val button = scoreButton(text, grades[criterion.id] == value) {
                        grades[criterion.id] = value
                        saveCurrentProject()
                        scoreText.text = "Note: ${scoreLabel(selectedStudentId)}"
                        scoreText.setTextColor(scoreColor(selectedStudentId))
                        refreshButtons()
                    }
                    buttons += value to button
                    addView(button)
                }
            })
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }
    }

    private fun studentRow(student: Student): View {
        val selected = pairEditMode && selectedStudentId == student.id
        return Button(this).apply {
            text = "${student.name}\n${studentSubtitle(student)}"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setAllCaps(false)
            setTextColor(if (selected) ui.iconOnAccent else ui.text)
            background = if (selected) accentSurface() else cardSurface()
            stateListAnimator = null
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { handleStudentClick(student) }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
    }

    private fun pickFile(requestCode: Int, mimeType: String) {
        prepareProjectForImport()
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(mimeType, "text/comma-separated-values", "*/*"))
            },
            requestCode,
        )
    }

    private fun showImportCacheDialog(
        kind: ImportCacheKind,
        title: String,
        requestCode: Int,
        mimeType: String,
        onCachedSelected: (CachedImport) -> Unit,
    ) {
        val cachedFiles = ImportCacheStore.load(this, kind)
        if (cachedFiles.isEmpty()) {
            pickFile(requestCode, mimeType)
            return
        }
        val labels = (cachedFiles.map { it.name } + "Ouvrir l'explorateur").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, index ->
                val cached = cachedFiles.getOrNull(index)
                if (cached != null) {
                    onCachedSelected(cached)
                } else {
                    pickFile(requestCode, mimeType)
                }
            }
            .show()
    }

    private fun importCachedStudentList(cached: CachedImport) {
        applyStudentList(Base64.decode(cached.contentBase64, Base64.DEFAULT))
        persistAndShowSetup("Liste élèves chargée depuis le cache")
    }

    private fun importCachedGrid(cached: CachedImport) {
        try {
            applyGrid(Base64.decode(cached.contentBase64, Base64.DEFAULT))
            persistAndShowSetup("Grille chargée depuis le cache")
        } catch (error: Exception) {
            toast("Le cache est obsolète : ${error.message}. Réimporte le fichier.")
            pickFile(REQUEST_GRID, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
    }

    private fun applyStudentList(bytes: ByteArray) {
        project.students = CsvStudentParser.parse(bytes.inputStream())
        project.grades = project.grades.filterKeys { id -> project.students.any { it.id == id } }.toMutableMap()
    }

    private fun applyGrid(bytes: ByteArray) {
        val grid = XlsxGridParser.parse(bytes)
        if (grid.criteria.isEmpty()) {
            throw IllegalArgumentException("Aucun critère reconnu dans cette grille.")
        }
        project.criteria = grid.criteria
        project.gridKind = grid.kind
        project.gridLevelColumns = grid.levelColumns
        project.grandOralProfileGuide = grid.profileGuide
        project.grandOralInfoSheets = grid.infoSheets
        project.gridTemplateBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        android.util.Log.d("InfoDialog", "applyGrid: kind=${grid.kind}, profileGuide=${grid.profileGuide != null}, rows=${grid.profileGuide?.rows?.size}, fallback=${grid.profileGuide?.rawFallback?.length}")
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "fichier importé"
    }

    private fun persistAndShowSetup(message: String) {
        saveCurrentProject()
        toast(message)
        showTpSetup()
    }

    private fun prepareProjectForImport() {
        ActiveProjectPrefs.save(this, project.id)
        saveCurrentProject()
    }

    private fun restoreProjectPendingImport() {
        val activeProjectId = ActiveProjectPrefs.load(this) ?: return
        val storedProject = ProjectStore.loadAll(this).firstOrNull { it.id == activeProjectId }
        if (storedProject != null) project = storedProject
    }

    private fun saveCurrentProject() {
        ProjectStore.save(this, project)
        projects = ProjectStore.loadAll(this).toMutableList()
    }

    private fun currentScrollY(): Int {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
    }

    private fun scoreLabel(studentId: String?): String {
        val score = studentId?.let { scoreForStudent(it) }
        return score?.let { "${gradeFormat.format(it)} / 20" } ?: "A noter"
    }

    private fun scoreColor(studentId: String?): Int {
        val score = studentId?.let { scoreForStudent(it) } ?: return ui.muted
        return when {
            score < 8.0 -> ui.danger
            score < 13.0 -> ui.warning
            else -> ui.success
        }
    }

    private fun scoreColor(score: Double?): Int {
        score ?: return ui.muted
        return when {
            score < 8.0 -> ui.danger
            score < 13.0 -> ui.warning
            else -> ui.success
        }
    }

    private fun hasDescriptors(): Boolean {
        if (project.gridKind == GridKind.GRAND_ORAL_2I2D && currentGrandOralInfoSheets().isNotEmpty()) return true
        return project.criteria.any { it.descriptors.isNotEmpty() }
    }

    private fun scaledGradeTextSize(baseSize: Float): Float {
        return baseSize * gradeTextScale
    }

    private fun recapView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = quietSurface()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (!project.pairMode) {
                addView(recapLine("Moyenne classe", averageLabel(), scoreColor(averageScore(project))))
                addView(TextView(this@MainActivity).apply {
                    text = "${gradedCount()} / ${project.students.size} notes"
                    textSize = 15f
                    setTextColor(ui.muted)
                    setPadding(0, dp(4), 0, 0)
                })
            } else {
                val groups = pairGroups(project)
                addView(recapLine("Moyenne binômes", averageLabel(), scoreColor(averageScore(project))))
                addView(TextView(this@MainActivity).apply {
                    text = "${gradedCount()} / ${groups.size} groupes"
                    textSize = 15f
                    setTextColor(ui.muted)
                    setPadding(0, dp(4), 0, dp(6))
                })
                groups.forEach { group ->
                    val score = computeScore(project, gradesForStudent(project, group.first().id))
                    val label = group.joinToString(" + ") { familyName(it.name) }
                    addView(recapLine(label, scoreLabel(score), scoreColor(score)))
                }
            }
        }
    }

    // Dialogue de consultation : descripteurs de compétences ou profil notes Grand Oral.
    private fun showCompetencyDescriptions() {
        if (!hasDescriptors()) {
            toast("Aucun descripteur trouvé dans la grille.")
            return
        }
        val gridKind = project.gridKind
        val infoSheets = currentGrandOralInfoSheets()
        if (gridKind == GridKind.GRAND_ORAL_2I2D && infoSheets.isNotEmpty()) {
            when (val decision = InfoSheetSelection.decide(infoSheets)) {
                is InfoSheetDecision.Open -> showGrandOralInfoSheet(decision.sheet)
                is InfoSheetDecision.Ask -> showGrandOralInfoSheetPicker(decision.sheets)
                InfoSheetDecision.None -> toast("Aucun contenu trouvé dans les feuilles d'information.")
            }
            return
        }

        android.util.Log.d("InfoDialog", "gridKind=$gridKind")
        android.util.Log.d("InfoDialog", "criteriaCount=${project.criteria.size}")

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(TextView(this).apply {
                text = "Descripteurs des compétences"
                textSize = 20f
                setTextColor(ui.text)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(22), dp(18), dp(22), dp(8))
                background = cardSurface()
            })
            .setView(ScrollView(this).apply {
                background = cardSurface()
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = dp(18)
                    setPadding(padding, dp(12), padding, dp(4))
                    groupedCriteria().forEach { (skill, criteria) ->
                        addView(TextView(this@MainActivity).apply {
                            text = skill
                            textSize = 18f
                            setTextColor(ui.primary)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setPadding(0, dp(10), 0, dp(6))
                        })
                        criteria.forEach { criterion ->
                            if (criterion.descriptors.isNotEmpty()) addView(descriptorBlock(criterion))
                        }
                    }
                })
            })
            .setPositiveButton("Fermer", null)
            .show()
        dialog.window?.setBackgroundDrawable(cardSurface())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ui.primary)
    }

    private fun currentGrandOralInfoSheets(): List<GrandOralInfoSheet> {
        if (project.gridKind != GridKind.GRAND_ORAL_2I2D) return emptyList()
        return project.grandOralInfoSheets.ifEmpty {
            project.grandOralProfileGuide?.let { listOf(GrandOralInfoSheet("Profil Notes", it)) }.orEmpty()
        }
    }

    private fun showGrandOralInfoSheetPicker(sheets: List<GrandOralInfoSheet>) {
        AlertDialog.Builder(this)
            .setTitle("Afficher les infos de quelle feuille ?")
            .setItems(sheets.map { it.name }.toTypedArray()) { _, index ->
                sheets.getOrNull(index)?.let { showGrandOralInfoSheet(it) }
            }
            .setNegativeButton("Annuler", null)
            .show()
            .also { dialog ->
                dialog.window?.setBackgroundDrawable(cardSurface())
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ui.primary)
            }
    }

    private fun showGrandOralInfoSheet(sheet: GrandOralInfoSheet) {
        android.util.Log.d("InfoDialog", "showGrandOralInfoSheet=${sheet.name}")
        android.util.Log.d("InfoDialog", "profileRows=${sheet.guide.rows.size}")
        android.util.Log.d("InfoDialog", "rawFallbackLength=${sheet.guide.rawFallback?.length}")

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(TextView(this).apply {
                text = "${sheet.name} — Grand oral"
                textSize = 20f
                setTextColor(ui.text)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(22), dp(18), dp(22), dp(8))
                background = cardSurface()
            })
            .setView(ScrollView(this).apply {
                background = cardSurface()
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = dp(18)
                    setPadding(padding, dp(12), padding, dp(4))
                    addGrandOralInfoSheetView(this, sheet)
                })
            })
            .setPositiveButton("Fermer", null)
            .show()
        dialog.window?.setBackgroundDrawable(cardSurface())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ui.primary)
    }

    private fun addGrandOralInfoSheetView(container: LinearLayout, sheet: GrandOralInfoSheet) {
        if (sheet.imageBase64.isNotBlank()) {
            val bytes = Base64.decode(sheet.imageBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                container.addView(ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                return
            }
        }

        addProfileGuideView(container, sheet.guide)
    }

    /** Construit la vue du tableau Profil notes pour le Grand Oral. */
    private fun addProfileGuideView(container: LinearLayout, guide: GrandOralProfileGuide) {
        // Fallback texte brut si le parsing structuré a échoué
        if (guide.rawFallback != null) {
            container.addView(TextView(this).apply {
                text = "Contenu de la feuille Profil notes :"
                textSize = 14f
                setTextColor(ui.primary)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(8))
            })
            // Grouper par ligne pour un affichage plus lisible
            val lines = guide.rawFallback.split("\n").filter { it.isNotBlank() }
            lines.forEach { line ->
                container.addView(TextView(this).apply {
                    text = line
                    textSize = 12f
                    setTextColor(ui.muted)
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
            return
        }

        if (guide.rows.isEmpty()) return

        val levelColors = mapOf(
            ProfileLevel.VERY_SATISFACTORY to ui.success,
            ProfileLevel.SATISFACTORY to ui.primary,
            ProfileLevel.UNSATISFACTORY to ui.warning,
            ProfileLevel.VERY_UNSATISFACTORY to ui.danger,
            ProfileLevel.UNKNOWN to ui.muted,
        )

        // Légende
        container.addView(TextView(this).apply {
            text = "TS = Très satisfaisant   S = Satisfaisant   I = Insatisfaisant   TI = Très insatisfaisant"
            textSize = 12f
            setTextColor(ui.muted)
            setPadding(0, 0, 0, dp(12))
        })

        // Ligne d'en-tête du tableau
        container.addView(profileGuideHeaderRow())

        // Lignes de profils
        guide.rows.forEach { row ->
            container.addView(profileGuideDataRow(row, levelColors))
        }
    }

    private fun profileGuideHeaderRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
            val headers = listOf("Oral", "Parole", "Conn.", "Inter.", "Arg.", "Note")
            headers.forEach { header ->
                addView(TextView(this@MainActivity).apply {
                    text = header
                    textSize = 11f
                    setTextColor(ui.muted)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(2), 0, dp(2), 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private fun profileGuideDataRow(
        row: GrandOralProfileRow,
        levelColors: Map<ProfileLevel, Int>,
    ): View {
        val levels = listOf(
            row.oralQuality to "O",
            row.continuousSpeech to "P",
            row.knowledgeQuality to "C",
            row.interactionQuality to "I",
            row.argumentationQuality to "A",
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardSurface()
            setPadding(dp(6), dp(8), dp(6), dp(8))
            levels.forEach { (level, _) ->
                addView(profileLevelBadge(level, levelColors))
            }
            addView(TextView(this@MainActivity).apply {
                text = row.possibleGrade
                textSize = 12f
                setTextColor(ui.text)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(5)) }
        }
    }

    private fun profileLevelBadge(level: ProfileLevel, colors: Map<ProfileLevel, Int>): View {
        val color = colors[level] ?: ui.muted
        return TextView(this).apply {
            text = level.code
            textSize = 11f
            setTextColor(if (level == ProfileLevel.VERY_SATISFACTORY || level == ProfileLevel.VERY_UNSATISFACTORY) Color.WHITE else color)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(
                    when (level) {
                        ProfileLevel.VERY_SATISFACTORY -> color
                        ProfileLevel.VERY_UNSATISFACTORY -> color
                        else -> Color.TRANSPARENT
                    }
                )
                cornerRadius = dp(4).toFloat()
                setStroke(
                    if (level == ProfileLevel.SATISFACTORY || level == ProfileLevel.UNSATISFACTORY) dp(1) else 0,
                    color
                )
            }
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }
    }

    private fun descriptorBlock(criterion: Criterion): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = criterion.label
                textSize = 15f
                setTextColor(ui.text)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            criterion.descriptors.entries
                .sortedBy { it.key }
                .forEach { (level, description) ->
                    if (description.isNotBlank()) {
                        val prefix = when {
                            level in 0..3 -> "$level - "
                            else -> ""
                        }
                        addView(TextView(this@MainActivity).apply {
                            text = "$prefix$description"
                            textSize = 14f
                            setTextColor(ui.muted)
                            setPadding(0, dp(3), 0, 0)
                        })
                    }
                }
        }
    }

    private fun showExportCandidateDialog() {
        val groups = pairGroups(project)
        if (groups.isEmpty()) {
            toast("Aucun élève à exporter.")
            return
        }
        if (project.gridTemplateBase64.isBlank()) {
            toast("Réimporte la grille pour activer l'export XLSX.")
            return
        }
        val labels = groups.map { exportGroupLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Exporter pour")
            .setItems(labels) { _, index ->
                val group = groups.getOrNull(index) ?: return@setItems
                val ownerId = gradeOwnerId(group.first().id)
                PendingExportPrefs.save(this, project.id, ownerId, labels[index])
                createExportDocument(labels[index])
            }
            .setNeutralButton("Tout exporter") { _, _ ->
                startBulkExport()
            }
            .show()
    }

    private fun startBulkExport() {
        val gradedGroups = gradedExportGroups(project)
        if (gradedGroups.isEmpty()) {
            toast("Aucune note complète à exporter.")
            return
        }
        saveCurrentProject()
        PendingBulkExportPrefs.save(this, project.id)
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            },
            REQUEST_EXPORT_ALL_GRIDS,
        )
    }

    private fun createExportDocument(label: String) {
        val cleanName = label
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .ifBlank { "candidat" }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_TITLE, "${project.name.ifBlank { "TP" }} - $cleanName.xlsx")
            },
            REQUEST_EXPORT_GRID,
        )
    }

    private fun exportFilledGrid(uri: Uri) {
        val pending = PendingExportPrefs.load(this)
            ?: throw IllegalArgumentException("export non préparé")
        val storedProject = ProjectStore.loadAll(this).firstOrNull { it.id == pending.projectId }
        if (storedProject != null) project = storedProject
        val template = Base64.decode(project.gridTemplateBase64, Base64.DEFAULT)
        val grades = project.grades[pending.ownerId] ?: emptyMap()
        val output = XlsxGridExporter.fill(project, template, pending.label, grades)
        contentResolver.openOutputStream(uri).useRequired { it.write(output) }
        toast("Grille exportée")
    }

    private fun exportAllGradedGrids(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val projectId = PendingBulkExportPrefs.load(this)
            ?: throw IllegalArgumentException("export groupé non préparé")
        val storedProject = ProjectStore.loadAll(this).firstOrNull { it.id == projectId }
            ?: throw IllegalArgumentException("TP introuvable")
        project = storedProject
        val groups = gradedExportGroups(storedProject)
        if (groups.isEmpty()) {
            toast("Aucune note complète à exporter.")
            return
        }

        val exportsDirectory = findOrCreateExportsDirectory(treeUri)
        val template = Base64.decode(storedProject.gridTemplateBase64, Base64.DEFAULT)
        groups.forEach { group ->
            val label = exportGroupLabel(group)
            val ownerId = gradeOwnerId(storedProject, group.first().id)
            val grades = storedProject.grades[ownerId] ?: emptyMap()
            val output = XlsxGridExporter.fill(storedProject, template, label, grades)
            val fileUri = DocumentsContract.createDocument(
                contentResolver,
                exportsDirectory,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "${exportFileBaseName(label)}.xlsx",
            ) ?: throw IllegalArgumentException("création du fichier impossible")
            contentResolver.openOutputStream(fileUri).useRequired { it.write(output) }
        }
        toast("${groups.size} grille(s) exportée(s)")
    }

    private fun gradedExportGroups(targetProject: TpProject): List<List<Student>> {
        return pairGroups(targetProject).filter { group ->
            computeScore(targetProject, gradesForStudent(targetProject, group.first().id)) != null
        }
    }

    private fun findOrCreateExportsDirectory(treeUri: Uri): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        findChildDirectory(treeUri, treeDocumentId, "exports")?.let { return it }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        return DocumentsContract.createDocument(contentResolver, parentUri, Document.MIME_TYPE_DIR, "exports")
            ?: throw IllegalArgumentException("création du dossier exports impossible")
    }

    private fun findChildDirectory(treeUri: Uri, parentDocumentId: String, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        contentResolver.query(
            childrenUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val childName = cursor.getString(nameColumn)
                val childMime = cursor.getString(mimeColumn)
                if (childName == name && childMime == Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun exportGroupLabel(group: List<Student>): String {
        return group.joinToString(" + ") { it.name }
    }

    private fun exportFileBaseName(label: String): String {
        val projectName = project.name.ifBlank { "TP" }
        val cleanLabel = label
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .ifBlank { "candidat" }
        return "$projectName - $cleanLabel"
    }

    private fun recapLine(label: String, score: String, color: Int): View {
        return row {
            addView(TextView(this@MainActivity).apply {
                text = "$label :"
                textSize = 16f
                setTextColor(ui.muted)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = score
                textSize = 16f
                setTextColor(color)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
            })
        }
    }

    private fun handleStudentClick(student: Student) {
        if (!pairEditMode) {
            showStudentGrade(student)
            return
        }
        val firstId = selectedStudentId
        when {
            firstId == null -> {
                selectedStudentId = student.id
                pendingStudentListScrollY = currentScrollY()
                showStudentList()
            }
            firstId == student.id -> {
                selectedStudentId = null
                pendingStudentListScrollY = currentScrollY()
                showStudentList()
            }
            else -> {
                linkStudents(firstId, student.id)
                selectedStudentId = null
                saveCurrentProject()
                pendingStudentListScrollY = currentScrollY()
                showStudentList()
            }
        }
    }

    private fun pairModeActionLabel(): String {
        return when {
            !project.pairMode -> "Mode binôme"
            pairEditMode -> "Terminer les binômes"
            else -> "Mode binôme"
        }
    }

    private fun studentDisplayGroups(): List<List<Student>> {
        val visibleIds = filteredStudents().map { it.id }.toSet()
        return if (project.pairMode) {
            pairGroups(project).filter { group -> group.any { it.id in visibleIds } }
        } else {
            filteredStudents().map { listOf(it) }
        }
    }

    private fun studentGroupView(group: List<Student>): View {
        return if (project.pairMode && group.size == 2) {
            pairGroupView(group[0], group[1])
        } else {
            studentRow(group.first())
        }
    }

    private fun pairGroupView(first: Student, second: Student): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = cardSurface()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(this@MainActivity).apply {
                    text = "["
                    textSize = 58f
                    gravity = Gravity.CENTER
                    setTextColor(ui.primary)
                }, LinearLayout.LayoutParams(dp(30), dp(112)))
                if (pairEditMode) {
                    addView(TextView(this@MainActivity).apply {
                        text = "\u2715"
                        textSize = 18f
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                        setTextColor(ui.iconOnAccent)
                        background = accentSurface()
                        setOnClickListener {
                            unlinkStudent(first.id)
                            selectedStudentId = null
                            saveCurrentProject()
                            pendingStudentListScrollY = currentScrollY()
                            showStudentList()
                        }
                    }, LinearLayout.LayoutParams(dp(30), dp(30)))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(studentPairButton(first))
                addView(spacer(6))
                addView(studentPairButton(second))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = scoreLabel(first.id)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(scoreColor(first.id))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(98)))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
    }

    private fun studentPairButton(student: Student): View {
        val selected = pairEditMode && selectedStudentId == student.id
        return Button(this).apply {
            text = student.name
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setAllCaps(false)
            setTextColor(if (selected) ui.iconOnAccent else ui.text)
            background = if (selected) accentSurface() else quietSurface()
            stateListAnimator = null
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { handleStudentClick(student) }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun showAddStudentDialog(onDone: () -> Unit) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Nom de l'élève"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            styleInput()
        }
        AlertDialog.Builder(this)
            .setTitle("Ajouter un élève")
            .setView(input)
            .setPositiveButton("Ajouter") { _, _ ->
                val name = cleanStudentName(input.text.toString())
                if (name.isBlank()) {
                    toast("Nom invalide.")
                    return@setPositiveButton
                }
                project.students = project.students + Student(uniqueStudentId(name), name)
                saveCurrentProject()
                onDone()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showRemoveStudentDialog(onDone: () -> Unit) {
        if (project.students.isEmpty()) {
            toast("Aucun élève à enlever.")
            return
        }
        val labels = project.students.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Enlever un élève")
            .setItems(labels) { _, index ->
                val student = project.students.getOrNull(index) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle("Enlever ${student.name} ?")
                    .setMessage("Ses notes locales seront supprimées.")
                    .setPositiveButton("Enlever") { _, _ ->
                        removeStudent(student)
                        saveCurrentProject()
                        onDone()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun averageLabel(): String {
        return averageLabel(project)
    }

    private fun averageLabel(targetProject: TpProject): String {
        return scoreLabel(averageScore(targetProject))
    }

    private fun gradedCount(): Int = gradedCount(project)

    private fun scoreForStudent(studentId: String): Double? {
        return computeScore(project, gradesForStudent(project, studentId))
    }

    private fun scoreLabel(score: Double?): String {
        return score?.let { "${gradeFormat.format(it)} / 20" } ?: "-- / 20"
    }

    private fun recapText(): String {
        if (!project.pairMode) {
            return "Moyenne classe: ${averageLabel()}   |   ${gradedCount()} / ${project.students.size} notes"
        }
        val groups = pairGroups(project)
        val lines = groups.joinToString("\n") { group ->
            val label = group.joinToString(" + ") { familyName(it.name) }
            val score = computeScore(project, gradesForStudent(project, group.first().id))
                ?.let { "${gradeFormat.format(it)} / 20" } ?: "A noter"
            "$label: $score"
        }
        return "Moyenne binômes : ${averageLabel()}   |   ${gradedCount()} / ${groups.size} groupes\n$lines"
    }

    private fun studentSubtitle(student: Student): String {
        val partner = partnerOf(student.id)
        return if (project.pairMode && partner != null) {
            "Binôme avec ${partner.name} - ${scoreLabel(student.id)}"
        } else {
            scoreLabel(student.id)
        }
    }

    private fun gradeTitle(student: Student): String {
        val partner = partnerOf(student.id)
        return if (project.pairMode && partner != null) "${student.name} + ${partner.name}" else student.name
    }

    private fun partnerOf(studentId: String): Student? {
        val partnerId = project.pairings[studentId] ?: return null
        return project.students.firstOrNull { it.id == partnerId }
    }

    // En mode binôme, les deux élèves partagent une même entrée de notes identifiée par un id canonique.
    private fun linkStudents(firstId: String, secondId: String) {
        if (firstId == secondId) return
        unlinkStudent(firstId)
        unlinkStudent(secondId)
        project.pairings[firstId] = secondId
        project.pairings[secondId] = firstId

        val ownerId = canonicalPairId(firstId, secondId)
        val firstGrades = project.grades[firstId]
        val secondGrades = project.grades[secondId]
        val sharedGrades = firstGrades ?: secondGrades
        if (sharedGrades != null) {
            project.grades[ownerId] = sharedGrades.toMutableMap()
        }
        if (ownerId != firstId) project.grades.remove(firstId)
        if (ownerId != secondId) project.grades.remove(secondId)
    }

    private fun unlinkStudent(studentId: String) {
        val partnerId = project.pairings[studentId] ?: return
        val ownerId = canonicalPairId(studentId, partnerId)
        val sharedGrades = project.grades[ownerId]?.toMutableMap()
        if (sharedGrades != null) {
            project.grades.putIfAbsent(studentId, sharedGrades.toMutableMap())
            project.grades.putIfAbsent(partnerId, sharedGrades.toMutableMap())
        }
        project.pairings.remove(studentId)
        project.pairings.remove(partnerId)
    }

    private fun unlinkAllStudents() {
        project.pairings.keys.toList().forEach { studentId -> unlinkStudent(studentId) }
    }

    private fun removeStudent(student: Student) {
        val partnerId = project.pairings[student.id]
        val pairOwnerId = partnerId?.let { canonicalPairId(student.id, it) }
        unlinkStudent(student.id)
        project.students = project.students.filterNot { it.id == student.id }
        project.grades.remove(student.id)
        pairOwnerId?.let { project.grades.remove(it) }
        project.pairings.remove(student.id)
        project.pairings.entries.removeAll { it.value == student.id }
        if (project.students.size < 2) {
            project.pairMode = false
            pairEditMode = false
            selectedStudentId = null
        }
        toast("Élève enlevé")
    }

    private fun gradeOwnerId(studentId: String): String {
        return gradeOwnerId(project, studentId)
    }

    private fun uniqueStudentId(name: String): String {
        val base = "student-${System.currentTimeMillis()}-${name.lowercase(Locale.ROOT).hashCode()}"
        var candidate = base
        var suffix = 1
        val existingIds = project.students.map { it.id }.toSet()
        while (candidate in existingIds) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun groupedCriteria(): List<Pair<String, List<Criterion>>> {
        return groupedCriteria(project)
    }

    private fun weightLabel(weight: Double): String {
        return if (weight % 1.0 == 0.0) weight.roundToInt().toString() else gradeFormat.format(weight)
    }

    private fun verticalRoot(content: LinearLayout.() -> Unit): ScrollView {
        return ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ui.backgroundTop, ui.backgroundMid, ui.backgroundBottom),
            )
            clipToPadding = false

            val baseHorizontalPadding = dp(18)
            val baseTopPadding = dp(18)
            val baseBottomPadding = dp(24)
            val contentView = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(baseHorizontalPadding, baseTopPadding, baseHorizontalPadding, baseBottomPadding)
                content()
            }

            setOnApplyWindowInsetsListener { _, insets ->
                val (systemTopInset, systemBottomInset) = systemBarVerticalInsets(insets)
                contentView.setPadding(
                    baseHorizontalPadding,
                    baseTopPadding + systemTopInset,
                    baseHorizontalPadding,
                    baseBottomPadding + systemBottomInset,
                )
                insets
            }

            addView(contentView)
        }
    }

    private fun systemBarVerticalInsets(insets: WindowInsets): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            return bars.top to bars.bottom
        }
        return legacySystemBarVerticalInsets(insets)
    }

    @Suppress("DEPRECATION")
    private fun legacySystemBarVerticalInsets(insets: WindowInsets): Pair<Int, Int> {
        return insets.systemWindowInsetTop to insets.systemWindowInsetBottom
    }

    private fun row(content: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            content()
        }
    }

    private fun headerRow(
        title: String,
        leading: LinearLayout.() -> Unit = {},
        trailing: LinearLayout.() -> Unit = {},
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val leadingGroup = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                leading()
            }
            addView(leadingGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTextColor(ui.text)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val trailingGroup = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                trailing()
            }
            addView(trailingGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            post {
                leadingGroup.layoutParams = leadingGroup.layoutParams.apply { width = leadingGroup.width }
                trailingGroup.layoutParams = trailingGroup.layoutParams.apply { width = trailingGroup.width }
            }
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextColor(ui.text)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(16))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ui.muted)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun infoLine(label: String, value: String) = TextView(this).apply {
        text = "$label: $value"
        textSize = 15f
        setTextColor(ui.muted)
        setPadding(dp(2), dp(16), dp(2), dp(6))
    }

    private fun skillHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = scaledGradeTextSize(19f)
        setTextColor(ui.primary)
        setTypeface(null, android.graphics.Typeface.BOLD)
        background = sectionHeaderSurface()
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(14), 0, dp(10)) }
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        setAllCaps(false)
        setTextColor(ui.iconOnAccent)
        background = accentSurface()
        stateListAnimator = null
        minHeight = dp(50)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        setAllCaps(false)
        setTextColor(ui.iconOnAccent)
        background = accentSurface()
        stateListAnimator = null
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, dp(10), 0) }
    }

    private fun iconActionButton(
        iconRes: Int,
        description: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) = ImageButton(this).apply {
        background = if (enabled) accentSurface() else quietSurface()
        setImageResource(iconRes)
        setColorFilter(if (enabled) ui.iconOnAccent else ui.muted)
        contentDescription = description
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun themeToggleButton(onThemeChanged: () -> Unit): View {
        return ImageButton(this).apply {
            background = quietSurface()
            setImageResource(if (isDarkMode) R.drawable.ic_sun else R.drawable.ic_moon)
            setColorFilter(ui.muted)
            contentDescription = if (isDarkMode) "Mode clair" else "Mode sombre"
            setOnClickListener {
                isDarkMode = !isDarkMode
                ThemePrefs.saveDarkMode(this@MainActivity, isDarkMode)
                onThemeChanged()
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(dp(8), 0, 0, 0)
            }
        }
    }

    private fun gearButton(onClick: () -> Unit) = ImageButton(this).apply {
        background = quietSurface()
        setImageResource(R.drawable.ic_settings)
        setColorFilter(ui.primary)
        contentDescription = "Réglages"
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(dp(10), 0, 0, 0)
        }
    }

    private fun backButton(onClick: () -> Unit) = ImageButton(this).apply {
        background = quietSurface()
        setImageResource(R.drawable.ic_back)
        setColorFilter(ui.primary)
        contentDescription = "Retour"
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(0, 0, dp(10), 0)
        }
    }

    private fun homeButton(onClick: () -> Unit) = ImageButton(this).apply {
        background = quietSurface()
        setImageResource(R.drawable.ic_home)
        setColorFilter(ui.primary)
        contentDescription = "Accueil"
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(0, 0, dp(10), 0)
        }
    }

    private fun infoButton(onClick: () -> Unit) = ImageButton(this).apply {
        background = quietSurface()
        setImageResource(R.drawable.ic_info)
        setColorFilter(ui.primary)
        contentDescription = "Descripteurs des compétences"
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(0, 0, dp(10), 0)
        }
    }

    private fun fontScaleButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        setAllCaps(false)
        setTextColor(ui.primary)
        background = quietSurface()
        stateListAnimator = null
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, dp(2))
        contentDescription = if (text == "+") "Agrandir la police" else "Diminuer la police"
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(40), dp(48)).apply {
            setMargins(0, 0, dp(6), 0)
        }
    }

    private fun scoreButton(text: String, selected: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setAllCaps(false)
        setTextColor(if (selected) ui.iconOnAccent else ui.text)
        background = if (selected) accentSurface() else glassSurface()
        stateListAnimator = null
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
    }

    private fun segmentButton(text: String, selected: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setAllCaps(false)
        setTextColor(if (selected) ui.iconOnAccent else ui.text)
        background = if (selected) accentSurface() else glassSurface()
        stateListAnimator = null
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun EditText.styleInput() {
        textSize = 16f
        setTextColor(ui.text)
        setHintTextColor(ui.muted)
        background = quietSurface()
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun cardSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(ui.surface)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), ui.glassStroke)
        }
    }

    private fun quietSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(ui.surfaceAlt)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), ui.glassStroke)
        }
    }

    private fun sectionHeaderSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (isDarkMode) ui.surfaceAlt else Color.rgb(224, 242, 236))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), if (isDarkMode) ui.primary else Color.rgb(116, 184, 164))
        }
    }

    private fun glassSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(ui.glassTop)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), ui.glassStroke)
        }
    }

    private fun accentSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(ui.primary)
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun headerSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(ui.header)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), ui.glassStroke)
        }
    }

    private fun headerChipSurface(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.argb(if (isDarkMode) 34 else 26, 255, 255, 255))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.argb(if (isDarkMode) 58 else 42, 255, 255, 255))
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_STUDENTS = 10
        private const val REQUEST_GRID = 11
        private const val REQUEST_EXPORT_GRID = 12
        private const val REQUEST_EXPORT_ALL_GRIDS = 13
    }
}
