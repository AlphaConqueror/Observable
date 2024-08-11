package observable.server

import dev.architectury.platform.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val configFile = Platform.getConfigFolder().resolve("observable.json")
var ServerSettings = loadSettings()

@Serializable
data class ServerSettingsData(
    var traceInterval: Int = 3,
    var deviation: Int = 1,
    var notifyInterval: Int = 120 * 60 * 1000,
    var allPlayersAllowed: Boolean = false,
    var allowedPlayers: MutableSet<String> = mutableSetOf(),
    var includeJvmArgs: Boolean = true,
    var uploadURL: String = "https://observable.tas.sh/v1/add"
) {
    fun sync() = configFile.writeText(Json.encodeToString(this))
}

fun loadSettings(): ServerSettingsData {
    if (!configFile.exists()) {
        val settings = ServerSettingsData()
        configFile.writeText(Json.encodeToString(settings))
        return settings
    }
    return Json.decodeFromString(configFile.readText())
}

fun resetSettings() {
    configFile.deleteIfExists()
    ServerSettings = loadSettings()
}
