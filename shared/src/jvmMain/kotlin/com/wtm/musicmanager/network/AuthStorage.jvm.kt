package com.wtm.musicmanager.network

import java.io.File
import java.util.Properties

/**
 * JVM actual: stores the pairing token in a small properties file under
 * the user's home directory. NOT for production — production is Android
 * (EncryptedSharedPreferences) and iOS (NSUserDefaults). This exists so
 * the commonTest suite has an in-memory implementation to bind, and so
 * the `:shared:jvmTest` task can exercise SyncCoordinator end-to-end with
 * the JDBC sqlite-driver.
 */
actual class AuthStorage(storageDir: File = File(System.getProperty("user.home"), ".musicmanager")) {
    private val file: File = File(storageDir, "auth.properties")

    actual fun loadToken(): String? {
        if (!file.exists()) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.getProperty("token")
    }

    actual fun saveToken(token: String) {
        file.parentFile.mkdirs()
        val props = Properties()
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        props.setProperty("token", token)
        file.outputStream().use { props.store(it, null) }
    }

    actual fun clearToken() {
        if (file.exists()) file.delete()
    }
}
