package analogwakatime.com.analogwakatimejetbrainsplugin

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AnalogWakaTimeRoutes {
    private const val BACKEND_BASE_URL = "https://api.testingmyproject.space"
    private const val WEBSITE_BASE_URL = "https://analogwakatime.com"
    private const val API_BASE_URL = "$BACKEND_BASE_URL/main/api/v1"
    private const val DEVICE_POLL_URL = "$API_BASE_URL/start/device/auth/poll"

    const val HEALTH = "$API_BASE_URL/"
    const val DASHBOARD = "$WEBSITE_BASE_URL/dashboard"
    const val TELEMETRY = "$API_BASE_URL/start/agent-telemetry"
    const val ACTIVITY = "$API_BASE_URL/create/activity"
    const val ACTIVITY_SYNC = "$API_BASE_URL/sync/activities"
    const val DEVICE_INIT = "$API_BASE_URL/start/device/auth/init"

    fun devicePoll(deviceCode: String): String {
        return "$DEVICE_POLL_URL?device_code=${encodeQueryParameter(deviceCode)}"
    }

    private fun encodeQueryParameter(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
