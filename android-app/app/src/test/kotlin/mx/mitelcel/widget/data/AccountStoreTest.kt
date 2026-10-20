package mx.mitelcel.widget.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Cifrado" falso: el simulador no tiene Android Keystore, y aquí no probamos criptografía sino la lógica. */
private class FakeSecretBox : SecretBox {
    override fun encrypt(plain: String) = "enc:$plain"
    override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        store = AccountStore(context, FakeSecretBox())
    }

    @Test
    fun `guarda varias líneas con su contraseña`() {
        val mine = store.save("Mía", "5511111111", "clave1")
        val other = store.save("Esposa", "5522222222", "clave2")

        assertEquals(listOf("Mía", "Esposa"), store.list().map { it.alias })
        assertEquals("clave1", store.password(mine.id))
        assertEquals("clave2", store.password(other.id))
    }

    @Test
    fun `editar sin contraseña conserva la anterior`() {
        val mine = store.save("Mía", "5511111111", "clave1")

        store.save("Personal", "5511111111", password = "", id = mine.id)

        assertEquals("Personal", store.get(mine.id)?.alias)
        assertEquals("clave1", store.password(mine.id))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `línea nueva sin contraseña es un error`() {
        assertThrows(IllegalArgumentException::class.java) { store.save("Mía", "5511111111", "") }
    }

    @Test
    fun `borrar elimina solo esa línea`() {
        val mine = store.save("Mía", "5511111111", "clave1")
        val other = store.save("Esposa", "5522222222", "clave2")

        store.delete(mine.id)

        assertEquals(listOf(other.id), store.list().map { it.id })
        assertNull(store.password(mine.id))
    }

    @Test
    fun `migra la línea única de la versión 1_1`() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit()
            .putString("phone", "5533333333")
            .putString("password_enc", "enc:vieja")
            .commit()

        val migrated = AccountStore(context, FakeSecretBox())

        val account = migrated.list().single()
        assertEquals("Mi línea", account.alias)
        assertEquals("5533333333", account.phone)
        assertEquals("vieja", migrated.password(account.id))
        // El archivo viejo se borra para no migrar dos veces.
        assertNull(context.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString("phone", null))
    }

    @Test
    fun `la configuración del widget tiene valores por defecto y se puede borrar`() {
        val configs = WidgetConfigStore(context)
        assertEquals(WidgetConfig(null, 100), configs.get(42))

        configs.save(42, WidgetConfig("abc", 140))
        assertEquals(WidgetConfig("abc", 100), configs.get(42)) // se limita a 0..100

        configs.delete(42)
        assertEquals(WidgetConfig(null, 100), configs.get(42))
    }
}
