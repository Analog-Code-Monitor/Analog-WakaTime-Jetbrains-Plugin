package analogwakatime.com.analogwakatimejetbrainsplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@Service(Service.Level.PROJECT)
class AnalogWakaTimeProjectService(private val project: Project) : Disposable {
    private val appService = service<AnalogWakaTimeAppService>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val lock = Any()
    private val activeFiles = ConcurrentHashMap<String, FileActivity>()
    private val knownLanguages = linkedSetOf<String>()

    @Volatile
    private var sessionStart: Long = System.currentTimeMillis()

    @Volatile
    private var totalKeystrokes: Int = 0

    @Volatile
    private var currentActiveFile: String? = null

    @Volatile
    private var lastTickTime: Long = System.currentTimeMillis()

    @Volatile
    private var lastActivityTime: Long = System.currentTimeMillis()

    @Volatile
    private var intervalTimeAccumulated: Long = 0L

    private var tickFuture: ScheduledFuture<*>? = null
    private var saveFuture: ScheduledFuture<*>? = null

    init {
        registerListeners()
        startSchedulers()
        initializeFromCurrentEditor()
    }

    fun getSessionTimeMillis(): Long = synchronized(lock) {
        activeFiles.values.sumOf { it.timeSpentMillis }
    }

    fun getSessionTimeSeconds(): Int = (getSessionTimeMillis() / 1_000L).toInt()

    fun getTotalKeystrokes(): Int = totalKeystrokes

    fun getOpenFileCount(): Int = FileEditorManager.getInstance(project).openFiles.size

    fun getKnownLanguages(): Set<String> = synchronized(lock) {
        knownLanguages.toSet()
    }

    fun showStatsDialog() {
        val savedSeconds = appService.getTotalTimeSeconds()
        val sessionSeconds = getSessionTimeSeconds()
        val totalSeconds = savedSeconds + sessionSeconds
        val unsynced = appService.getUnsyncedCount()

        val message = buildString {
            append("Analog WakaTime\n\n")
            append("Total time: ${formatDetailedDuration(totalSeconds)}\n")
            append("Current session: ${formatDetailedDuration(sessionSeconds)}\n")
            append(
                if (unsynced > 0) {
                    "Waiting for synchronization: $unsynced records"
                } else {
                    "Everything synchronized"
                },
            )
        }

        Messages.showInfoMessage(project, message, "Analog WakaTime Stats")
    }

    fun forceSync() {
        if (!appService.isAuthenticated()) {
            AnalogWakaTimeNotifications.warn(project, "Analog WakaTime: Configure or log in with an API token first.")
            return
        }

        scheduler.execute {
            val stats = getStatsSnapshot()
            if (hasActivity(stats)) {
                appService.flushStats(project, stats)
                resetStats()
            }

            val pending = appService.getUnsyncedCount()
            if (pending == 0) {
                AnalogWakaTimeNotifications.info(project, "Analog WakaTime: No data waiting for synchronization.")
                return@execute
            }

            try {
                val synced = appService.syncUnsynced()
                AnalogWakaTimeNotifications.info(project, "Analog WakaTime: Synchronized $synced records.")
            } catch (error: Exception) {
                AnalogWakaTimeNotifications.error(project, "Analog WakaTime: Synchronization error: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun dispose() {
        tickFuture?.cancel(true)
        saveFuture?.cancel(true)

        val finalStats = getStatsSnapshot()
        if (hasActivity(finalStats)) {
            appService.persistStats(project, finalStats, synced = false)
        }

        try {
            appService.syncUnsynced()
        } catch (_: Exception) {
        }
    }

    private fun registerListeners() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (isTrackable(file)) {
                    trackFileOpen(file)
                }
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (isTrackable(file)) {
                    trackFileClose(file)
                }
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                val file = event.newFile
                if (file != null && isTrackable(file)) {
                    setActiveFile(file)
                } else {
                    currentActiveFile = null
                }
            }
        })

        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                handleDocumentChange(event)
            }
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                handleEditorActivity(event.editor)
            }
        }, this)
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                handleEditorActivity(e.editor)
            }
        }, this)
    }

    private fun startSchedulers() {
        tickFuture = scheduler.scheduleWithFixedDelay(
            { tickTime() },
            TIME_UPDATE_INTERVAL_MS,
            TIME_UPDATE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )

        saveFuture = scheduler.scheduleWithFixedDelay(
            { flushCurrentStats() },
            DEFAULT_SEND_INTERVAL_MS,
            DEFAULT_SEND_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun initializeFromCurrentEditor() {
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        if (!isTrackable(selectedFile)) {
            return
        }
        trackFileOpen(selectedFile)
        setActiveFile(selectedFile)
    }

    private fun tickTime() {
        if (!appService.isAuthenticated()) {
            return
        }

        val now = System.currentTimeMillis()
        val timeSinceLastTick = now - lastTickTime
        lastTickTime = now

        if (!ApplicationManager.getApplication().isActive) {
            return
        }

        val activeFile = currentActiveFile ?: return
        if (now - lastActivityTime > INACTIVITY_THRESHOLD_MS) {
            return
        }

        val fileActivity = activeFiles[activeFile] ?: return
        val timeToAdd = min(timeSinceLastTick, MAX_TIME_PER_TICK_MS)
        if (intervalTimeAccumulated + timeToAdd > MAX_TIME_PER_SAVE_INTERVAL_MS) {
            return
        }

        synchronized(lock) {
            fileActivity.timeSpentMillis += timeToAdd
            fileActivity.lastActive = now
            intervalTimeAccumulated += timeToAdd
        }
    }

    private fun flushCurrentStats() {
        if (!appService.isAuthenticated()) {
            return
        }

        val stats = getStatsSnapshot()
        if (!hasActivity(stats)) {
            return
        }

        appService.flushStats(project, stats)
        resetStats()
    }

    private fun handleDocumentChange(event: DocumentEvent) {
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (!isTrackable(file)) {
            return
        }

        val filePath = file.path
        val now = System.currentTimeMillis()
        val activity = synchronized(lock) {
            activeFiles.computeIfAbsent(filePath) {
                createFileActivity(filePath, file.fileType.name.lowercase(), now)
            }
        }

        val newText = event.newFragment.toString()
        val oldText = event.oldFragment.toString()
        var linesAdded = newText.count { it == '\n' }
        var linesDeleted = oldText.count { it == '\n' }

        if (linesAdded > 0 && linesDeleted > 0) {
            val replaced = min(linesAdded, linesDeleted)
            linesAdded -= replaced
            linesDeleted -= replaced
        }

        val keystrokes = max(newText.length, event.oldLength)

        synchronized(lock) {
            activity.lastActive = now
            activity.keystrokes += keystrokes
            activity.linesAdded += linesAdded
            activity.linesDeleted += linesDeleted
            totalKeystrokes += keystrokes
            knownLanguages.add(activity.language)
        }

        markActivity()
    }

    private fun handleEditorActivity(editor: Editor?) {
        val currentProject = editor?.project ?: return
        if (currentProject != project) {
            return
        }

        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!isTrackable(file)) {
            return
        }

        setActiveFile(file)
        markActivity()
    }

    private fun trackFileOpen(file: VirtualFile) {
        val filePath = file.path
        val language = file.fileType.name.lowercase()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            activeFiles.computeIfAbsent(filePath) {
                createFileActivity(filePath, language, now)
            }
            knownLanguages.add(language)
        }

        markActivity()
    }

    private fun trackFileClose(file: VirtualFile) {
        val filePath = file.path
        val now = System.currentTimeMillis()

        synchronized(lock) {
            activeFiles[filePath]?.lastActive = now
        }

        if (currentActiveFile == filePath) {
            currentActiveFile = null
        }
    }

    private fun setActiveFile(file: VirtualFile) {
        currentActiveFile = file.path
        trackFileOpen(file)
    }

    private fun markActivity() {
        val now = System.currentTimeMillis()
        lastActivityTime = now
        lastTickTime = now
    }

    private fun getStatsSnapshot(): ActivityStats = synchronized(lock) {
        val snapshot = activeFiles.mapValues { (_, activity) -> activity.copy() }
        ActivityStats(
            sessionStart = sessionStart,
            sessionEnd = System.currentTimeMillis(),
            activeFiles = snapshot,
            totalKeystrokes = totalKeystrokes,
            totalTimeSpentMillis = snapshot.values.sumOf { it.timeSpentMillis },
        )
    }

    private fun resetStats() = synchronized(lock) {
        val now = System.currentTimeMillis()
        activeFiles.replaceAll { _, activity ->
            activity.copy(
                keystrokes = 0,
                linesAdded = 0,
                linesDeleted = 0,
                timeSpentMillis = 0,
                firstActive = now,
                lastActive = now,
            )
        }
        sessionStart = now
        totalKeystrokes = 0
        intervalTimeAccumulated = 0
        lastTickTime = now
    }

    private fun createFileActivity(filePath: String, language: String, now: Long): FileActivity {
        return FileActivity(
            filePath = filePath,
            language = language,
            firstActive = now,
            lastActive = now,
        )
    }

    private fun isTrackable(file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem || file.isDirectory) {
            return false
        }

        val fileEditorManager = FileEditorManager.getInstance(project)
        if (fileEditorManager.isFileOpen(file)) {
            return true
        }

        return ProjectFileIndex.getInstance(project).isInContent(file)
    }
}
