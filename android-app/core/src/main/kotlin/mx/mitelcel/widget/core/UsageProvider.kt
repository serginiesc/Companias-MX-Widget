package mx.mitelcel.widget.core

import kotlinx.serialization.Serializable

/** Qué necesita cada compañía para consultar el consumo. */
enum class CredentialKind {
    /** Número + contraseña (Telcel). */
    PASSWORD,

    /** Solo el número (Bait). */
    NONE,

    /** Iniciar sesión en su portal dentro de la app; se guarda el token de sesión (Movistar, por reCAPTCHA). */
    WEB_SESSION,
}

/**
 * Compañías soportadas. Agregar una nueva = un valor aquí + una clase que implemente
 * [UsageProvider]; el widget, la caché y las pantallas no cambian (principio Abierto/Cerrado).
 */
@Serializable
enum class Carrier(val displayName: String, val credential: CredentialKind) {
    TELCEL("Telcel", CredentialKind.PASSWORD),
    BAIT("Bait", CredentialKind.NONE),
    MOVISTAR("Movistar", CredentialKind.WEB_SESSION),
    ;

    /** Si hay un secreto (contraseña o token) que guardar cifrado. */
    val needsPassword: Boolean get() = credential != CredentialKind.NONE
}

/**
 * Consulta el consumo de una línea. Cada compañía tiene su propia implementación (patrón Strategy).
 * Bloqueante: llamar solo desde un hilo de fondo.
 */
interface UsageProvider {
    /** @param password contraseña o token de sesión; null o vacío si la compañía no lo necesita. */
    fun fetchUsage(rawPhone: String, password: String?): UsageEntry
}

/** La sesión web (ej. Movistar) caducó: hay que volver a iniciar sesión en la app. No se reintenta. */
class SessionExpiredException(message: String) : TelcelException(message)

/**
 * Guarda llaves PÚBLICAS que las compañías publican en el JavaScript de su sitio y a veces rotan
 * (Telcel: Basic auth; Bait: x-ord-service; Movistar: guardString). No son secretos del usuario.
 */
interface KeyCache {
    fun get(name: String): String?

    fun put(name: String, value: String)
}

/** Para pruebas o cuando no importa recordar la llave entre ejecuciones. */
class InMemoryKeyCache : KeyCache {
    private val values = mutableMapOf<String, String>()

    @Synchronized override fun get(name: String): String? = values[name]

    @Synchronized override fun put(name: String, value: String) {
        values[name] = value
    }
}
