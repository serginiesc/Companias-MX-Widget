package mx.mitelcel.widget.data

import android.content.Context
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mx.mitelcel.widget.core.Carrier

/** Una línea guardada. La contraseña NO está aquí: se pide aparte con [AccountStore.password]. */
@Serializable
data class Account(val id: String, val alias: String, val phone: String, val carrier: Carrier = Carrier.TELCEL)

/**
 * Formato en disco. `carrier` tiene valor por defecto: las líneas guardadas por la v1.3
 * (que no tenían ese campo) se leen como Telcel sin necesidad de migrarlas.
 * `passwordEnc` vacío = la compañía no usa contraseña (ej. Bait).
 */
@Serializable
private data class StoredAccount(
    val id: String,
    val alias: String,
    val phone: String,
    val passwordEnc: String,
    val carrier: Carrier = Carrier.TELCEL,
)

/** Lista de líneas guardadas. Las contraseñas se guardan cifradas con [SecretBox]. */
class AccountStore(
    context: Context,
    private val box: SecretBox = KeystoreSecretBox(),
) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredAccount.serializer())

    init {
        migrateFromSingleAccount(context)
    }

    fun list(): List<Account> = readAll().map { Account(it.id, it.alias, it.phone, it.carrier) }

    fun get(id: String): Account? = list().firstOrNull { it.id == id }

    /**
     * null si no existe o si ya no se puede descifrar (ej. se borraron los datos de seguridad del teléfono).
     * "" si la compañía no usa contraseña.
     */
    fun password(id: String): String? {
        val stored = readAll().firstOrNull { it.id == id } ?: return null
        if (stored.passwordEnc.isEmpty()) return ""
        return runCatching { box.decrypt(stored.passwordEnc) }.getOrNull()
    }

    /**
     * Crea una línea nueva ([id] = null) o edita una existente.
     * Al editar, si [password] viene vacío se conserva la contraseña anterior (si es la misma compañía).
     */
    @Synchronized
    fun save(
        alias: String,
        phone: String,
        password: String,
        id: String? = null,
        carrier: Carrier = Carrier.TELCEL,
    ): Account {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        val previous = all.getOrNull(index)

        val passwordEnc = when {
            !carrier.needsPassword -> "" // no guardamos nada que no se necesita
            password.isNotEmpty() -> box.encrypt(password)
            previous != null && previous.carrier == carrier && previous.passwordEnc.isNotEmpty() -> previous.passwordEnc
            else -> throw IllegalArgumentException("La contraseña es obligatoria para ${carrier.displayName}")
        }
        val stored = StoredAccount(previous?.id ?: UUID.randomUUID().toString(), alias, phone, passwordEnc, carrier)

        if (index >= 0) all[index] = stored else all += stored
        writeAll(all)
        return Account(stored.id, stored.alias, stored.phone, stored.carrier)
    }

    @Synchronized
    fun delete(id: String) {
        writeAll(readAll().filterNot { it.id == id })
    }

    private fun readAll(): List<StoredAccount> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun writeAll(accounts: List<StoredAccount>) {
        prefs.edit().putString(KEY_LIST, json.encodeToString(serializer, accounts)).apply()
    }

    /**
     * La versión 1.1 guardaba una sola línea en otro archivo. La pasamos a la lista nueva
     * tal cual (la contraseña ya cifrada se copia sin descifrarla) y borramos el archivo viejo.
     */
    private fun migrateFromSingleAccount(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val phone = legacy.getString("phone", null) ?: return
        val passwordEnc = legacy.getString("password_enc", null) ?: return
        if (readAll().none { it.phone == phone }) {
            writeAll(readAll() + StoredAccount(UUID.randomUUID().toString(), "Mi línea", phone, passwordEnc))
        }
        legacy.edit().clear().apply()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val LEGACY_PREFS = "credentials"
    }
}
