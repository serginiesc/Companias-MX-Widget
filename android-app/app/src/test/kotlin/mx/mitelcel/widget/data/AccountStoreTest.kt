package mx.mitelcel.widget.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import mx.mitelcel.widget.core.Carrier
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
    fun `la configuración del widget guarda hasta 2 líneas y se puede borrar`() {
        val configs = WidgetConfigStore(context)
        assertEquals(WidgetConfig(emptyList(), 100), configs.get(42))

        configs.save(42, WidgetConfig(listOf("a", "b", "c"), 140))
        assertEquals(WidgetConfig(listOf("a", "b"), 100), configs.get(42)) // máx. 2 líneas, opacidad 0..100

        configs.delete(42)
        assertEquals(WidgetConfig(emptyList(), 100), configs.get(42))
    }

    @Test
    fun `lee la configuración de una sola línea de la versión 1_2`() {
        context.getSharedPreferences("widget_configs", Context.MODE_PRIVATE).edit()
            .putString("account_9", "vieja").putInt("opacity_9", 40).commit()

        assertEquals(WidgetConfig(listOf("vieja"), 40), WidgetConfigStore(context).get(9))
    }

    @Test
    fun `Bait se guarda sin contraseña`() {
        val bait = store.save("Bait", "5544444444", password = "", carrier = Carrier.BAIT)

        assertEquals(Carrier.BAIT, store.get(bait.id)?.carrier)
        assertEquals("", store.password(bait.id)) // "" = no la necesita (distinto de null = se perdió)
    }

    @Test
    fun `cambiar de Bait a Telcel exige contraseña`() {
        val line = store.save("Mía", "5544444444", password = "", carrier = Carrier.BAIT)
        assertThrows(IllegalArgumentException::class.java) {
            store.save("Mía", "5544444444", password = "", id = line.id, carrier = Carrier.TELCEL)
        }
    }

    @Test
    fun `las líneas de la versión 1_3 sin compañía se leen como Telcel`() {
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit()
            .putString("list", """[{"id":"x1","alias":"Vieja","phone":"5555555555","passwordEnc":"enc:p"}]""")
            .commit()

        val account = AccountStore(context, FakeSecretBox()).list().single()
        assertEquals(Carrier.TELCEL, account.carrier)
    }

    @Test
    fun `guarda color de texto e imagen de fondo del widget`() {
        val configs = WidgetConfigStore(context)
        val config = WidgetConfig(listOf("a"), 30, TextTone.BLACK, hasBackgroundImage = true, imageIsLight = true)

        configs.save(5, config)

        assertEquals(config, configs.get(5))
    }
}
