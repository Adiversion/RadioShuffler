package com.example.radioshuffle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val releaseUrl: String
)

class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    suspend fun checkForUpdate(): AppUpdateInfo? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RadioShuffler/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Could not check update: HTTP ${response.code}")
            }

            val json = JSONObject(response.body?.string().orEmpty())
            val tagName = json.optString("tag_name").trim()
            val versionName = tagName.removePrefix("v").ifBlank {
                json.optString("name").removePrefix("v").trim()
            }

            if (!isNewerVersion(versionName, BuildConfig.VERSION_NAME)) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null

            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val downloadUrl = asset.optString("browser_download_url")
                if (name.equals("RadioShuffler.apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                    apkUrl = downloadUrl
                    break
                }
                if (apkUrl == null && name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                    apkUrl = downloadUrl
                }
            }

            return apkUrl?.let {
                AppUpdateInfo(
                    versionName = versionName.ifBlank { tagName.ifBlank { "latest" } },
                    apkUrl = it,
                    releaseUrl = json.optString("html_url")
                )
            }
        }
    }

    fun download(
        updateInfo: AppUpdateInfo,
        onProgress: (bytesRead: Long, totalBytes: Long, percent: Int) -> Unit = { _, _, _ -> }
    ): File {
        createNotificationChannel()

        val request = Request.Builder()
            .url(updateInfo.apkUrl)
            .header("User-Agent", "RadioShuffler/${BuildConfig.VERSION_NAME}")
            .build()

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "RadioShuffler-${updateInfo.versionName}.apk")
        val notificationManager = NotificationManagerCompat.from(context)

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Update download was empty")
            val totalBytes = body.contentLength()
            var bytesRead = 0L
            val buffer = ByteArray(8 * 1024)
            var lastReportedPercent = -1

            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read

                        val percent = if (totalBytes > 0) {
                            ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }

                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(bytesRead, totalBytes, percent)

                            if (notificationManager.areNotificationsEnabled()) {
                                val notifBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                                    .setSmallIcon(R.drawable.media3_notification_small_icon)
                                    .setContentTitle("Downloading Radio Shuffler")
                                    .setContentText(
                                        if (percent >= 0) "Version ${updateInfo.versionName} ($percent%)"
                                        else "Downloading version ${updateInfo.versionName}..."
                                    )
                                    .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                                    .setOngoing(true)
                                    .setOnlyAlertOnce(true)
                                try {
                                    notificationManager.notify(NOTIFICATION_ID, notifBuilder.build())
                                } catch (_: SecurityException) { }
                            }
                        }
                    }
                }
            }
        }

        // Post completion notification
        if (notificationManager.areNotificationsEnabled()) {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                101,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val completeNotif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.media3_notification_small_icon)
                .setContentTitle("Radio Shuffler Update Ready")
                .setContentText("v${updateInfo.versionName} downloaded. Tap to install.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setProgress(0, 0, false)

            try {
                notificationManager.notify(NOTIFICATION_ID, completeNotif.build())
            } catch (_: SecurityException) { }
        }

        return apkFile
    }

    fun needsInstallPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openInstaller(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications for app updates"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.versionParts()
        val localParts = local.versionParts()
        val maxSize = maxOf(remoteParts.size, localParts.size)

        if (remoteParts.isEmpty() || localParts.isEmpty()) {
            return remote.isNotBlank() && remote != local
        }

        for (index in 0 until maxSize) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart > localPart) return true
            if (remotePart < localPart) return false
        }

        return false
    }

    private fun String.versionParts(): List<Int> {
        return trim()
            .removePrefix("v")
            .split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/Adiversion/radioshuffler/releases/latest"
        const val NOTIFICATION_CHANNEL_ID = "radioshuffler_update_channel"
        const val NOTIFICATION_ID = 2002
    }
}
