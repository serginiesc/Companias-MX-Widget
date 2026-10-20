package mx.mitelcel.widget.data

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import mx.mitelcel.widget.core.UsageEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageHistoryStoreTest {
    private val store = UsageHistoryStore(ApplicationProvider.getApplicationContext())
    private val base = 1_800_000_000_000L

    private fun entry(minutesLater: Long, consumed: Double) = UsageEntry.errorFallback.copy(
        timestampMillis = base + TimeUnit.MINUTES.toMillis(minutesLater),
        consumedMB = consumed,
        totalMB = 10000.0,
    )

    @Test
    fun `guarda máximo una foto por hora`() {
        store.record("a", entry(0, 100.0))
        store.record("a", entry(20, 150.0)) // misma hora: reemplaza
        store.record("a", entry(90, 300.0)) // otra hora: se agrega

        assertEquals(listOf(150.0, 300.0), store.samples("a").map { it.consumedMB })
    }

    @Test
    fun `borra fotos de más de 45 días y cada línea tiene su historial`() {
        store.record("a", entry(0, 100.0))
        store.record("a", entry(TimeUnit.DAYS.toMinutes(46), 200.0))
        store.record("b", entry(0, 999.0))

        assertEquals(listOf(200.0), store.samples("a").map { it.consumedMB })
        assertEquals(listOf(999.0), store.samples("b").map { it.consumedMB })
    }

    @Test
    fun `sin plan no se guarda nada`() {
        store.record("c", entry(0, 50.0).copy(totalMB = 0.0))
        assertEquals(emptyList<Any>(), store.samples("c"))
    }
}
