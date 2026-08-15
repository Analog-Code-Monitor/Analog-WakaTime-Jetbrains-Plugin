package analogwakatime.com.analogwakatimejetbrainsplugin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.UIManager

@Service(Service.Level.APP)
@State(name = "AnalogWakaTimeAppState", storages = [Storage("analog-wakatime.xml")])
class AnalogWakaTimeAppService : PersistentStateComponent<AnalogWakaTimeAppService.AppState>, Disposable {
    class AppState {
        var activities: MutableList<StoredActivity> = mutableListOf()
        var installationId: String = ""
        var authToken: String = ""
    }

    private data class HttpTextResponse(val statusCode: Int, val body: String)

    companion object {
        private const val TOKEN_ACCOUNT_NAME = "AnalogWakaTimeToken"
    }

    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val started = AtomicBoolean(false)
    private val credentialServiceName = generateServiceName("Analog WakaTime", "API Token")
    private val credentialAttributes = CredentialAttributes(
        credentialServiceName,
        TOKEN_ACCOUNT_NAME,
    )
    private val legacyCredentialAttributes = CredentialAttributes(credentialServiceName)

    @Volatile
    private var online: Boolean = true

    @Volatile
    private var lastAuthReminderAt: Long = 0

    private var state = AppState()
    private var pingFuture: ScheduledFuture<*>? = null
    private var syncFuture: ScheduledFuture<*>? = null
    private var telemetryFuture: ScheduledFuture<*>? = null
    private var cleanupFuture: ScheduledFuture<*>? = null
    private var authReminderFuture: ScheduledFuture<*>? = null

    init {
        ensureInstallationId()
    }

    override fun getState(): AppState = state

    override fun loadState(state: AppState) {
        this.state = state
        ensureInstallationId()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val application = ApplicationManager.getApplication()
        if (application.isHeadlessEnvironment || application.isUnitTestMode) {
            return
        }

        validateCurrentAuthentication()
        schedulePing()
        schedulePeriodicSync()
        scheduleTelemetry()
        scheduleCleanup()
        scheduleAuthReminder()
    }

    fun isAuthenticated(): Boolean = !getToken().isNullOrBlank()

    fun isOnline(): Boolean = online

    fun getToken(): String? {
        val persistedToken = state.authToken.trim()
        if (persistedToken.isNotEmpty()) {
            return persistedToken
        }

        val currentToken = PasswordSafe.instance.get(credentialAttributes)?.getPasswordAsString()
        if (!currentToken.isNullOrBlank()) {
            saveToken(currentToken)
            return currentToken
        }

        val legacyToken = PasswordSafe.instance.get(legacyCredentialAttributes)?.getPasswordAsString()
        if (!legacyToken.isNullOrBlank()) {
            saveToken(legacyToken)
            PasswordSafe.instance.set(legacyCredentialAttributes, null)
            return legacyToken
        }

        return null
    }

    fun getUnsyncedCount(): Int = synchronized(this) {
        state.activities.count { !it.synced }
    }

    fun getTotalTimeSeconds(): Int = synchronized(this) {
        state.activities.sumOf { it.time }
    }

    fun getUnsyncedActivities(): List<StoredActivity> = synchronized(this) {
        state.activities.filter { !it.synced }.map { it.copy() }
    }

    fun promptForApiToken(project: Project?) {
        val token = Messages.showPasswordDialog(
            project,
            "Enter the API token from your Analog WakaTime profile.",
            "Analog WakaTime",
            null,
        ) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analog WakaTime: Verifying API token", false) {
            override fun run(indicator: ProgressIndicator) {
                val isValid = validateToken(token)
                saveToken(token)

                if (isValid) {
                    AnalogWakaTimeNotifications.info(project, "Analog WakaTime: API token saved and verified.")
                } else {
                    AnalogWakaTimeNotifications.warn(project, "Analog WakaTime: Token saved, but verification failed.")
                }
            }
        })
    }

    fun login(project: Project?) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analog WakaTime: Starting authorization", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val deviceFlow = startDeviceFlow()
                    ApplicationManager.getApplication().invokeLater {
                        val choice = Messages.showYesNoDialog(
                            project,
                            "Authorization code: ${deviceFlow.userCode}\n\nOpen the browser to complete authorization?",
                            "Analog WakaTime Authorization",
                            "Open Browser",
                            "Cancel",
                            Messages.getInformationIcon(),
                        )

                        if (choice != Messages.YES) {
                            return@invokeLater
                        }

                        BrowserUtil.browse(deviceFlow.verificationUrl)
                        waitForToken(project, deviceFlow)
                    }
                } catch (error: Exception) {
                    AnalogWakaTimeNotifications.error(project, "Analog WakaTime: Authorization error: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        })
    }

    fun logout(project: Project?) {
        clearToken()
        AnalogWakaTimeNotifications.info(project, "Analog WakaTime: You have been logged out.")
    }

    fun flushStats(project: Project, stats: ActivityStats): Boolean {
        if (!hasActivity(stats)) {
            return false
        }

        val token = getToken()
        if (token.isNullOrBlank()) {
            persistStats(project, stats, synced = false)
            return false
        }

        return if (online) {
            try {
                sendActivity(project, stats, token)
                persistStats(project, stats, synced = true)
                true
            } catch (_: Exception) {
                persistStats(project, stats, synced = false)
                false
            }
        } else {
            persistStats(project, stats, synced = false)
            false
        }
    }

    fun syncUnsynced(): Int {
        val token = getToken() ?: throw IllegalStateException("API token is not configured")
        if (!online) {
            return 0
        }

        val unsynced = getUnsyncedActivities()
        if (unsynced.isEmpty()) {
            return 0
        }

        syncActivities(token, unsynced)
        markAsSynced(unsynced.map { it.id })
        return unsynced.size
    }

    @Synchronized
    fun persistStats(project: Project, stats: ActivityStats, synced: Boolean): List<StoredActivity> {
        val saved = mutableListOf<StoredActivity>()
        val now = System.currentTimeMillis()
        val ideName = currentIdeName()

        for ((filePath, fileActivity) in stats.activeFiles) {
            val timeSpentSeconds = (fileActivity.timeSpentMillis / 1_000L).toInt()
            if (timeSpentSeconds <= 0 && fileActivity.linesAdded == 0 && fileActivity.linesDeleted == 0) {
                continue
            }

            val (date, hour) = midpointDateInfo(fileActivity.firstActive, fileActivity.lastActive)
            val projectContext = resolveProjectContext(project, filePath)
            val activity = StoredActivity(
                language = fileActivity.language.ifBlank { "unknown" },
                lines = sanitizedNetLines(fileActivity.linesAdded, fileActivity.linesDeleted),
                time = timeSpentSeconds,
                date = date,
                hour = hour,
                path = projectContext.path,
                projectName = projectContext.projectName,
                ideName = ideName,
                fileName = resolveFileName(filePath),
                synced = synced,
                timestamp = now,
            )
            state.activities.add(activity)
            saved.add(activity.copy())
        }

        return saved
    }

    @Synchronized
    fun markAsSynced(activityIds: Collection<String>) {
        if (activityIds.isEmpty()) {
            return
        }
        val ids = activityIds.toSet()
        state.activities.forEach { activity ->
            if (activity.id in ids) {
                activity.synced = true
            }
        }
    }

    @Synchronized
    fun cleanupOldRecords() {
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        state.activities = state.activities
            .filter { !it.synced || it.timestamp > thirtyDaysAgo }
            .toMutableList()
    }

    override fun dispose() {
        pingFuture?.cancel(true)
        syncFuture?.cancel(true)
        telemetryFuture?.cancel(true)
        cleanupFuture?.cancel(true)
        authReminderFuture?.cancel(true)

        try {
            syncUnsynced()
        } catch (_: Exception) {
        }

        try {
            sendTelemetry()
        } catch (_: Exception) {
        }
    }

    private fun waitForToken(project: Project?, deviceFlow: DeviceFlowResult) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analog WakaTime: Waiting for authorization", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val token = pollForToken(deviceFlow.deviceCode, deviceFlow.interval, deviceFlow.expiresIn) {
                        indicator.isCanceled
                    }

                    if (token.isNullOrBlank()) {
                        AnalogWakaTimeNotifications.warn(project, "Analog WakaTime: Authorization cancelled or expired.")
                        return
                    }

                    saveToken(token)
                    AnalogWakaTimeNotifications.info(project, "Analog WakaTime: Authorization successful.")
                } catch (error: Exception) {
                    AnalogWakaTimeNotifications.error(project, "Analog WakaTime: Authorization error: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        })
    }

    private fun saveToken(token: String) {
        val normalizedToken = token.trim()
        state.authToken = normalizedToken
        PasswordSafe.instance.set(credentialAttributes, Credentials(TOKEN_ACCOUNT_NAME, normalizedToken))
    }

    private fun clearToken() {
        state.authToken = ""
        PasswordSafe.instance.set(credentialAttributes, null)
        PasswordSafe.instance.set(legacyCredentialAttributes, null)
    }

    private fun validateCurrentAuthentication() {
        val project = firstOpenProject()
        val token = getToken()
        if (token.isNullOrBlank()) {
            if (project != null) {
                AnalogWakaTimeNotifications.info(
                    project,
                    "Analog WakaTime: Please log in to start tracking time.",
                    AnalogWakaTimeNotifications.createLoginAction(project),
                )
            }
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analog WakaTime: Validating token", false) {
            override fun run(indicator: ProgressIndicator) {
                val valid = validateToken(token)
                if (!valid) {
                    val action = project?.let { AnalogWakaTimeNotifications.createLoginAction(it) }
                    AnalogWakaTimeNotifications.warn(project, "Analog WakaTime: Stored token looks invalid. Please log in again.", action)
                }
            }
        })
    }

    private fun schedulePing() {
        pingFuture = scheduler.scheduleWithFixedDelay(
            {
                try {
                    online = ping()
                } catch (_: Exception) {
                    online = false
                }
            },
            0L,
            PING_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun schedulePeriodicSync() {
        syncFuture = scheduler.scheduleWithFixedDelay(
            {
                if (!isAuthenticated()) {
                    return@scheduleWithFixedDelay
                }
                try {
                    syncUnsynced()
                } catch (_: Exception) {
                }
            },
            PERIODIC_SYNC_INTERVAL_MS,
            PERIODIC_SYNC_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleTelemetry() {
        telemetryFuture = scheduler.scheduleWithFixedDelay(
            {
                try {
                    sendTelemetry()
                } catch (_: Exception) {
                }
            },
            0L,
            TELEMETRY_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleCleanup() {
        cleanupFuture = scheduler.scheduleWithFixedDelay(
            { cleanupOldRecords() },
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleAuthReminder() {
        authReminderFuture = scheduler.scheduleWithFixedDelay(
            {
                if (isAuthenticated()) {
                    return@scheduleWithFixedDelay
                }

                val now = System.currentTimeMillis()
                if (now - lastAuthReminderAt < AUTH_REMINDER_INTERVAL_MS) {
                    return@scheduleWithFixedDelay
                }

                val project = firstOpenProject() ?: return@scheduleWithFixedDelay
                lastAuthReminderAt = now
                AnalogWakaTimeNotifications.warn(
                    project,
                    "Analog WakaTime: Please authenticate to start tracking time.",
                    AnalogWakaTimeNotifications.createLoginAction(project),
                )
            },
            AUTH_REMINDER_INTERVAL_MS,
            AUTH_REMINDER_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun sendTelemetry() {
        val payload = buildTelemetryPayload()
        postJson(
            AnalogWakaTimeRoutes.TELEMETRY,
            payload,
            extraHeaders = mapOf("ngrok-skip-browser-warning" to "true"),
        )
    }

    private fun buildTelemetryPayload(): Map<String, Any?> {
        val openProjects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        val projectServices = openProjects.map { it.service<AnalogWakaTimeProjectService>() }
        val pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(ANALOG_WAKATIME_PLUGIN_ID))?.version ?: "unknown"
        val themeName = UIManager.getLookAndFeel()?.name ?: "unknown"
        val languages = openProjects
            .flatMap { project -> project.service<AnalogWakaTimeProjectService>().getKnownLanguages() }
            .toSortedSet()
            .joinToString(",")

        return mapOf(
            "installation_id" to state.installationId,
            "plugin_version" to pluginVersion,
            "vscode_version" to ApplicationInfo.getInstance().fullVersion,
            "os" to "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}",
            "session_duration" to projectServices.sumOf { it.getSessionTimeSeconds() },
            "files_opened" to projectServices.sumOf { it.getOpenFileCount() },
            "keystrokes_total" to projectServices.sumOf { it.getTotalKeystrokes() },
            "languages" to languages,
            "has_token" to isAuthenticated(),
            "vscode_theme" to themeName,
            "workspace_folders" to openProjects.size,
            "active_extensions" to 0,
            "machine_id" to state.installationId,
            "session_id" to UUID.randomUUID().toString(),
            "is_new_app_install" to false,
            "ui_kind" to "jetbrains",
            "remote_name" to "local",
        )
    }

    private fun ensureInstallationId() {
        if (state.installationId.isBlank()) {
            state.installationId = UUID.randomUUID().toString()
        }
    }

    private fun currentIdeName(): String {
        val fullProductName = ApplicationNamesInfo.getInstance().fullProductName
        if (fullProductName.isNotBlank()) {
            return fullProductName
        }

        return ApplicationInfo.getInstance().versionName.ifBlank { "JetBrains IDE" }
    }

    private fun firstOpenProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    }

    private fun ping(timeoutMillis: Long = 3_000L): Boolean {
        return try {
            val request = HttpRequest.newBuilder(URI.create(AnalogWakaTimeRoutes.HEALTH))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun validateToken(token: String): Boolean {
        if (token.isBlank()) {
            return false
        }

        return try {
            val response = postJson(
                AnalogWakaTimeRoutes.ACTIVITY,
                mapOf(
                    "language" to "test",
                    "lines" to 0,
                    "time" to 0,
                    "path" to "/test-project",
                    "project_name" to "test-project",
                    "ide_name" to currentIdeName(),
                    "filename" to "test-file.kt",
                ),
                bearerToken = token,
            )
            response.statusCode == 200 || response.statusCode == 400
        } catch (_: Exception) {
            false
        }
    }

    private fun startDeviceFlow(): DeviceFlowResult {
        val response = postJson(AnalogWakaTimeRoutes.DEVICE_INIT, emptyMap<String, String>())
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to initialize device flow: ${response.body}")
        }

        val data = gson.fromJson(response.body, JsonObject::class.java)
        return DeviceFlowResult(
            deviceCode = data.get("device_code").asString,
            userCode = data.get("user_code").asString,
            verificationUrl = data.get("verification_url").asString,
            expiresIn = data.get("expires_in").asInt,
            interval = data.get("interval").asInt,
        )
    }

    private fun pollForToken(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
        isCancelled: () -> Boolean,
    ): String? {
        val deadline = System.currentTimeMillis() + expiresInSeconds * 1_000L
        val url = AnalogWakaTimeRoutes.devicePoll(deviceCode)

        while (System.currentTimeMillis() <= deadline) {
            if (isCancelled()) {
                return null
            }

            val response = get(url)
            when (response.statusCode) {
                200 -> {
                    val payload = gson.fromJson(response.body, JsonObject::class.java)
                    if (payload.get("status")?.asString == "authorized" && payload.has("token")) {
                        return payload.get("token").asString
                    }
                }

                202 -> {
                }

                404, 410 -> throw IllegalStateException("Authorization expired or is no longer valid.")
            }

            TimeUnit.SECONDS.sleep(intervalSeconds.toLong())
        }

        throw IllegalStateException("Authorization timed out. Please try again.")
    }
    

    private fun sendActivity(project: Project, stats: ActivityStats, token: String) {
        val groupedActivities = groupActivityForUpload(project, stats)
        if (groupedActivities.isEmpty()) {
            return
        }

        groupedActivities.forEach { activity ->
            val response = postJson(
                AnalogWakaTimeRoutes.ACTIVITY,
                activity,
                bearerToken = token,
            )
            if (response.statusCode !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode}: ${response.body}")
            }
        }
    }

    private fun syncActivities(token: String, activities: List<StoredActivity>) {
        val currentIdeName = currentIdeName()
        val response = postJson(
            AnalogWakaTimeRoutes.ACTIVITY_SYNC,
            mapOf(
                "activities" to activities.map {
                    mapOf(
                        "language" to it.language,
                        "lines" to it.lines,
                        "time" to it.time,
                        "date" to it.date,
                        "hour" to it.hour,
                        "path" to it.path,
                        "project_name" to it.projectName,
                        "ide_name" to (it.ideName ?: currentIdeName),
                        "filename" to it.fileName,
                    )
                },
            ),
            bearerToken = token,
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode}: ${response.body}")
        }
    }

    private fun groupActivityForUpload(project: Project, stats: ActivityStats): List<ActivityUploadRequest> {
        val ideName = currentIdeName()
        val grouped = linkedMapOf<String, ActivityUploadRequest>()

        for ((filePath, activity) in stats.activeFiles) {
            val timeSpentSeconds = (activity.timeSpentMillis / 1_000L).toInt()
            if (timeSpentSeconds <= 0) {
                continue
            }

            val (date, hour) = midpointDateInfo(activity.firstActive, activity.lastActive)
            val projectContext = resolveProjectContext(project, filePath)
            val fileName = resolveFileName(filePath)
            val key = listOf(
                activity.language,
                date,
                hour.toString(),
                projectContext.path.orEmpty(),
                projectContext.projectName.orEmpty(),
                ideName,
                fileName.orEmpty(),
            ).joinToString("|")

            val existing = grouped.getOrPut(key) {
                ActivityUploadRequest(
                    language = activity.language.ifBlank { "unknown" },
                    lines = 0,
                    time = 0,
                    date = date,
                    hour = hour,
                    path = projectContext.path,
                    project_name = projectContext.projectName,
                    ide_name = ideName,
                    filename = fileName,
                )
            }

            existing.lines += sanitizedNetLines(activity.linesAdded, activity.linesDeleted)
            existing.time += timeSpentSeconds
        }

        return grouped.values.toList()
    }

    private fun postJson(
        url: String,
        body: Any,
        bearerToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 8_000L,
    ): HttpTextResponse {
        val jsonBody = gson.toJson(body)
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))

        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        extraHeaders.forEach(builder::header)

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpTextResponse(response.statusCode(), response.body())
    }

    private fun get(url: String, timeoutMillis: Long = 8_000L): HttpTextResponse {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMillis))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpTextResponse(response.statusCode(), response.body())
    }
}
