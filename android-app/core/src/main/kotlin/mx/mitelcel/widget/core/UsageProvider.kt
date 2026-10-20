package mx.mitelcel.widget.core

import kotlinx.serialization.Serializable

/**
 * Compañías soportadas. Agregar una nueva = un valor aquí + una clase que implemente
 * [UsageProvider]; el widget, la caché y las pantallas no cambian (principio Abierto/Cerrado).
 */
@Serializable
enum class Carrier(val displayName: String, val needsPassword: Boolean) {
    TELCEL("Telcel", needsPassword = true),
    BAIT("Bait", needsPassword = false),
}

/**
 * Consulta el consumo de una línea. Cada compañía tiene su propia implementación (patrón Strategy).
 * Bloqueante: llamar solo desde un hilo de fondo.
 */
interface UsageProvider {
    /** @param password null o vacío si la compañía no la necesita ([Carrier.needsPassword]). */
    fun fetchUsage(rawPhone: String, password: String?): UsageEntry
}
