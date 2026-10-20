# Mi Telcel iOS Widget (WidgetKit & Scriptable)

Widget para iOS que muestra en tiempo real los **GB disponibles combinados (Plan Base + Datos Promocionales)**, el **consumo acumulado**, los **días restantes** y la **siguiente fecha de corte/vigencia** de tu línea Mi Telcel utilizando la arquitectura de microservicios (**WebLogin SSO + JWT + Wave B2F**).

<p align="center">
  <img src="assets/widget_preview.png" alt="Mi Telcel Widget Preview" width="280">
</p>

---

## Características

- **Suma inteligente de bolsas:** Combina automáticamente los datos del Plan Base (`0-0-0`) y los Datos Promocionales (`0-1-0`).
- **Fecha de corte y vigencia:** Muestra los días restantes y la fecha de corte exacta reportada por Telcel.
- **Medidor semicircular y presupuesto diario:** Arco bicolor (rojo gastado vs. verde disponible), porcentaje usado (`%`) y cálculo de ritmo diario disponible (`MB/d` o `GB/d`).
- **Almacenamiento seguro:** Las credenciales nunca se guardan en texto plano; se almacenan en el **Keychain de iOS**.
- **Caché y desconexión segura:** Guarda la última consulta en caché para carga instantánea y revoca el token SSO de Telcel al terminar para evitar bloqueos por sesiones concurrentes.
- **Dos métodos de uso:**
  1. **Scriptable:** Sin Mac, sin Xcode, listo en 2 minutos desde el iPhone.
  2. **Nativo WidgetKit / SwiftUI:** Proyecto Xcode completo con app contenedora y widget para iOS 17+.

---

## Método 1: Instalación vía Scriptable (Recomendado, Sin Mac)

Instala y ejecuta el widget en tu iPhone directamente sin necesidad de cuenta de desarrollador ni computadora.

### 1. Instalar Scriptable
Descarga la app gratuita **[Scriptable](https://apps.apple.com/app/scriptable/id1405459188)** desde la App Store en tu iPhone.

### 2. Copiar el código del Widget
1. Abre el archivo [`src/MiTelcelWidget.js`](src/MiTelcelWidget.js) en este repositorio.
2. Copia todo su contenido al portapapeles de tu iPhone (puedes usar el Portapapeles Universal de Apple, AirDrop, o enviártelo por mensaje).

### 3. Crear el script en Scriptable
1. Abre **Scriptable** en tu iPhone.
2. Pulsa el botón **`+`** en la esquina superior derecha para crear un nuevo script.
3. Toca el título en la parte superior (dice *Untitled Script*) y cámbialo a **`MiTelcel`**.
4. Pega el código copiado dentro del editor.
5. Pulsa **Done** en la esquina superior izquierda.

### 4. Configurar tus credenciales por primera vez
1. Abre nuevamente el script **MiTelcel** dentro de Scriptable.
2. Pulsa el botón **Play (▶)** en la esquina inferior derecha.
3. Aparecerá una alerta: **"Configurar Mi Telcel"**.
4. Ingresa tu **número a 10 dígitos** y tu **contraseña de Mi Telcel**.
5. Pulsa **Guardar**. Tus datos se guardarán de forma cifrada en el **Keychain de iOS**.
6. El script ejecutará la consulta con Telcel y mostrará la vista previa del widget. Pulsa **Done**.

### 5. Agregar el Widget a tu Pantalla de Inicio
1. Ve a la pantalla de inicio de tu iPhone.
2. Mantén presionada cualquier área vacía hasta que los íconos comiencen a temblar.
3. Toca el botón **`+`** en la esquina superior izquierda.
4. Busca y selecciona **Scriptable**.
5. Elige el tamaño **Pequeño (Small)** y toca **Agregar widget**.
6. Mientras los íconos sigan temblando, toca el widget recién agregado (o mantenlo presionado y selecciona **Editar widget**).
7. En el campo **Script**, selecciona **MiTelcel**.
8. En **When Interacting** (Al interactuar), selecciona **Run Script** (Ejecutar script).
9. Toca fuera del widget para guardar. ¡Listo! El widget se actualizará periódicamente.

---

## Método 2: Compilación Nativa en Xcode (`WidgetKit` / SwiftUI)

Para usuarios con Mac y Xcode que deseen compilar la app y extensión nativa:

1. Abre `MiTelcelWidget.xcodeproj` en **Xcode 15+ / 16+**.
2. En la pestaña **Signing & Capabilities** de los targets `MiTelcelApp` y `MiTelcelWidgetExtension`, selecciona tu equipo (*Personal Team* o *Apple Developer Program*) y ajusta el `Bundle Identifier` si es necesario.
3. Conecta tu iPhone, habilita el **Modo de desarrollador** en *Ajustes > Privacidad y seguridad*.
4. Compila y ejecuta el esquema `MiTelcelApp` (`Cmd + R`).
5. Abre la app en el iPhone, ingresa tus credenciales y pulsa **Actualizar Consumo Ahora**.
6. Añade el widget nativo **MiTelcelWidget** desde el menú de widgets de iOS.

---

## Método Android nativo (APK)

App Kotlin con widget nativo. GitHub Actions compila el APK automáticamente. Instrucciones en [`android-app/README.md`](android-app/README.md).

---

## Método 3: Prototipo para Android (Termux:Widget / KWGT)

> [!WARNING]
> **Nota:** Como no cuento con un teléfono Android físico, **este prototipo de Android no ha sido probado**. Comparte exactamente el mismo parser y flujo de red de iOS (`src/MiTelcelWidget.js`), pero se incluye únicamente como referencia experimental.

1. Instala **Termux** y **Termux:Widget** desde **F-Droid**, luego instala Node.js y Git:
   ```sh
   pkg update && pkg install -y nodejs git
   ```
2. Clona este repositorio en Termux y configura tu archivo `.env` (`TELCEL_PHONE` y `TELCEL_PASS`).
3. Copia [`android/shortcuts_mitelcel.sh`](android/shortcuts_mitelcel.sh) a `~/.shortcuts/MiTelcel` para usar con **Termux:Widget**, o lee el archivo `/sdcard/Download/mitelcel_widget.json` generado por [`android/mitelcel_widget.mjs`](android/mitelcel_widget.mjs) desde **KWGT**.
4. Consulta la guía completa en [`android/README.md`](android/README.md).

---

## Validación de Endpoints desde Terminal (macOS)

Puedes probar las llamadas y verificar el formato devuelto por Mi Telcel usando el script automatizado:

1. Copia `.env.example` a `.env` y configura tus credenciales:
   ```zsh
   cp .env.example .env
   ```
2. Ejecuta el probe:
   ```zsh
   zsh .agents/skills/telcel-api-probe/scripts/probe_telcel.sh
   ```
3. El reporte con el desglose de bolsas se guardará en `artifacts/probe_report.txt`.

---

## Estructura del Repositorio

```text
.
├── assets/
│   └── widget_preview.png          # Captura del widget en pantalla de inicio
├── src/
│   └── MiTelcelWidget.js           # Implementación autónoma para Scriptable (iOS)
├── android/
│   ├── mitelcel_widget.mjs         # Prototipo Node.js para Termux / KWGT (Android)
│   ├── shortcuts_mitelcel.sh       # Lanzador para Termux:Widget (~/.shortcuts)
│   └── README.md                   # Guía de configuración en Android (sin probar)
├── MiTelcelApp/
│   └── MiTelcelApp.swift           # App contenedora iOS (SwiftUI)
├── MiTelcelWidget/
│   ├── TelcelNetworkService.swift  # Cliente SSO + JWT + Parser Wave B2F
│   ├── MiTelcelWidget.swift        # Extensión WidgetKit y TimelineProvider
│   └── Info.plist                  # Configuración de extensión WidgetKit
├── MiTelcelWidget.xcodeproj/       # Proyecto Xcode (iOS 17+)
├── .agents/skills/telcel-api-probe/
│   ├── SKILL.md                    # Skill para pruebas de API Telcel
│   └── scripts/probe_telcel.sh     # CLI de autenticación y extracción de esquema
├── Telcel Widget API.md            # Especificación técnica del API Wave B2F
├── API Validation.md               # Guía de validación manual con curl
└── Research.md                     # Bitácora técnica y arquitectura de endpoints
```

---

## Seguridad y Privacidad

- **Sin servidores intermediarios:** Las peticiones viajan directamente desde tu iPhone a los servidores oficiales de Telcel (`wbl.telcel-id.com`, `mi-telcel.smapps.mx`, `api.journeys.telcel.wavebybemobi.com`).
- **Keychain de iOS:** Las contraseñas se almacenan mediante el subsistema de seguridad de iOS (`Keychain`), no en texto plano en el código.
- **Revocación de sesión:** Cada consulta revoca activamente el token SSO generado (`/sso/revoke`), evitando bloqueos por inicios de sesión simultáneos en la app oficial de Telcel.
