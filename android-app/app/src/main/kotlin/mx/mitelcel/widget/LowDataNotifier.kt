package mx.mitelcel.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mx.mitelcel.widget.core.LowData
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.Account

/**
 * Notificación "Te quedan pocos datos". Una sola vez por ciclo y por línea:
 * se recuerda la fecha de corte con la que ya se avisó, y al empezar un ciclo nuevo se vuelve a armar.
 */
class LowDataNotifier(private val context: Context) {
    private val prefs = context.getSharedPreferences("low_data_alerts", Context.MODE_PRIVATE)

    fun check(account: Account, entry: UsageEntry) {
        if (entry.isCached || !LowData.isLow(entry)) return
        val cycle = entry.cutoffDate.ifEmpty { "sin-fecha" }
        if (prefs.getString(account.id, null) == cycle) return // ya avisamos en este ciclo
        if (!canNotify()) return

        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            account.id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_data)
            .setContentTitle("${account.alias}: te quedan ${entry.remainingText}")
            .setContentText("${entry.daysLeftText} para el corte · ${entry.dailyAllowanceText} para que te alcance")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // El id por línea hace que cada línea tenga su propia notificación (no se pisan entre sí).
        NotificationManagerCompat.from(context).notify(account.id.hashCode(), notification)
        prefs.edit().putString(account.id, cycle).apply()
    }

    /** Android 13+ exige permiso explícito para notificar; si el usuario lo negó, no insistimos. */
    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Datos bajos", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Aviso cuando a una línea le quedan pocos datos"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "low_data"
    }
}
