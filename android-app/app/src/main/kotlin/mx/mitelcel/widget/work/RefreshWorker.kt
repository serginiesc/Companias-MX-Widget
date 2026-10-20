package mx.mitelcel.widget.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import mx.mitelcel.widget.data.UsageRepository
import mx.mitelcel.widget.widget.WidgetRenderer

/**
 * Consulta a Telcel en segundo plano y redibuja el widget.
 * `Worker.doWork()` ya corre en un hilo de fondo, así que aquí sí se puede hacer red bloqueante.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val minAge = inputData.getLong(KEY_MIN_AGE, PERIODIC_MIN_AGE_MS)
        val result = UsageRepository(applicationContext).refresh(minAge)
        WidgetRenderer.updateAll(applicationContext, result.entry)
        // Siempre success: si devolviéramos retry() ante un error de contraseña,
        // WorkManager reintentaría el login una y otra vez y Telcel podría bloquear la cuenta.
        return Result.success()
    }

    companion object {
        /** Periódico: no volver a consultar si hay un dato de hace menos de 5 min. */
        const val PERIODIC_MIN_AGE_MS = 5 * 60 * 1000L

        /** Manual: solo evita el doble toque accidental. */
        const val MANUAL_MIN_AGE_MS = 10 * 1000L

        private const val KEY_MIN_AGE = "min_age_ms"
        private const val PERIODIC_WORK = "mitelcel_periodic_refresh"
        private const val ONE_SHOT_WORK = "mitelcel_refresh_now"

        private val NEEDS_NETWORK = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            // 30 min, como la versión iOS. Android no permite menos de 15 min para trabajo periódico.
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(NEEDS_NETWORK)
                .build()
            // KEEP: si ya estaba programado, no lo reinicia (evita que cada evento reinicie el reloj).
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshNow(context: Context, minAgeMillis: Long) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(NEEDS_NETWORK)
                .setInputData(workDataOf(KEY_MIN_AGE to minAgeMillis))
                .build()
            // KEEP: si el usuario toca 5 veces seguidas, solo corre una consulta.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
