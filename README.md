# Mis Datos MX: widget de datos móviles para Android

Widget nativo de Android (Kotlin) que muestra en la pantalla de inicio los **GB disponibles (plan + promociones)**, el consumo, los días restantes y la fecha de corte de tus líneas de **Telcel** y **Bait**. AT&T viene en camino.

> [!WARNING]
> **App no oficial.** Usa los mismos servicios que la web de cada compañía. Si alguna los cambia, el widget dejará de actualizarse para esa compañía.

## 📲 Descargar

<p align="center">
  <a href="https://github.com/serginiesc/companias-mx-widget/releases/latest/download/CompaniasMxWidget.apk">
    <img src="https://img.shields.io/github/v/release/serginiesc/companias-mx-widget?label=Descargar%20APK&style=for-the-badge&logo=android&color=33D64A" alt="Descargar APK">
  </a>
</p>

**¿Primera vez instalando una app fuera de la Play Store?** Desde tu celular Android:

1. **Toca el botón verde de arriba.** Se descarga `CompaniasMxWidget.apk`.
2. **Abre el archivo** desde la notificación de descarga o desde *Mis archivos → Descargas*.
3. Si aparece **"Por tu seguridad, no se permite instalar apps desconocidas"**, toca **Configuración**, activa **Permitir de esta fuente** y regresa.
   - En **Samsung**, si no te deja, apaga primero *Ajustes → Seguridad y privacidad → **Protección automática***.
4. Toca **Instalar**. Si Play Protect avisa que la app es desconocida, elige **Instalar de todas formas**. Pasa porque no viene de la Play Store.

Luego sigue los pasos de [Uso](#uso). Requiere **Android 8.0 o superior**.

---

## Características

- **Varias compañías:** Telcel (número y contraseña) y Bait (solo número). Eliges la compañía al agregar cada línea.
- **Varias líneas:** guarda tantas como quieras, cada una con un apodo ("Mía", "Esposa").
- **Una o dos líneas por widget:** con dos, se muestran lado a lado (recomendado 4×2).
- **Apariencia por widget:** opacidad del fondo de 0% a 100%, **imagen de fondo** de tu galería y **color de texto** blanco, negro o automático (según tu fondo de pantalla o la imagen).
- **Medidor semicircular:** rojo = gastado, verde = disponible, porcentaje usado y presupuesto diario (`MB/d` o `GB/d`).
- **Contraseñas cifradas** con AES-GCM y una llave del **Android Keystore**. Nunca salen del teléfono, excepto hacia Telcel.
- **Caché local:** si no hay red, muestra el último dato con ☁.
- **Actualización automática** cada 30 minutos (WorkManager) y manual al tocar el medidor.
- **Sesión segura:** el token SSO se revoca siempre al terminar, aunque algo falle, y las consultas van una por una para no abrir sesiones simultáneas.
- **Actualizaciones desde la app:** al abrirla avisa si hay una versión nueva y la instala con un toque.
- **Reporte de errores integrado:** si la app se cierra, al abrirla de nuevo muestra el error con un botón para copiarlo.

---

## Instalación avanzada

### Actualizar
Desde la v1.4, la app avisa sola: al abrirla aparece **"Hay una versión nueva → Actualizar"**. La primera vez Android te pedirá permitir que *Mis Datos MX* instale apps; después solo confirmas **Actualizar**. Tus líneas y widgets se conservan.

También puedes descargar el APK nuevo con el botón de arriba e instalarlo encima.

### Con `adb` desde la PC
```cmd
adb install -r CompaniasMxWidget.apk
adb shell dumpsys package mx.mitelcel.widget | findstr versionName
```
`adb` viene en [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools). En el teléfono hay que activar *Opciones de desarrollador → Depuración USB*.

### Versiones de prueba (sin publicar)
Cada push que modifique `android-app/` genera un APK en **Actions → Android APK → Artifacts**. Requiere iniciar sesión en GitHub, viene en `.zip` y caduca a los 90 días.

> [!IMPORTANT]
> Las versiones de prueba se firman con una llave temporal distinta en cada ejecución, salvo que exista la [llave de firma fija](#llave-de-firma-fija). Android rechaza instalar una app encima de otra con distinta firma: si después de "actualizar" sigues viendo la versión anterior, **desinstala primero**.

---

## Uso

1. Abre **Mis Datos MX** y agrega tus líneas: compañía, apodo, número a 10 dígitos y, en Telcel, contraseña. Pulsa **Guardar y actualizar**.
2. Agrega el widget: mantén presionado el fondo de la pantalla → *Widgets* → *Mis Datos MX*.
3. Se abre **Configurar widget**:
   - Marca **una o dos líneas** (pueden ser de compañías distintas).
   - Ajusta la **opacidad del fondo** (0% = transparente).
   - Elige el **color del texto**: Automático, Blanco o Negro.
   - Opcional: **Elegir imagen** para usar una foto como fondo.
4. En el widget:
   - **Toca el medidor** para actualizar esa línea.
   - **Toca el encabezado** para cambiar las líneas o la transparencia de ese widget.

---

## Estructura del proyecto

```
.
├── .github/workflows/android-apk.yml   CI: pruebas + compilación + APK como artefacto
├── .agents/skills/telcel-api-probe/    Script para probar la API real de Telcel (usa .env)
├── docs/                               Referencia de la API de Telcel (endpoints y bloques Wave B2F)
├── AGENTS.md                           Instrucciones para asistentes de IA que trabajen en el repo
└── android-app/
    ├── settings.gradle.kts             Módulos :core y :app
    ├── core/                           Kotlin puro (sin Android), probado con JUnit
    │   └── src/main/kotlin/mx/mitelcel/widget/core/
    │       ├── UsageProvider.kt        Interfaz común + enum Carrier (compañías soportadas)
    │       ├── TelcelClient.kt         Telcel: login SSO → token B2F → consumo (Wave B2F) → revocar SSO
    │       ├── BaitClient.kt           Bait: consulta por número (sin login) + parser de bolsas
    │       ├── UpdateChecker.kt        Busca versiones nuevas en GitHub Releases y descarga el APK
    │       ├── HomeFlowParser.kt       Suma las bolsas de datos y extrae fecha y días de corte
    │       └── UsageEntry.kt           Modelo y textos del widget ("9 días", "608 MB/d"…)
    └── app/                            Todo lo que depende de Android
        └── src/main/kotlin/mx/mitelcel/widget/
            ├── MainActivity.kt         Administrar líneas (agregar, editar, borrar) y vista previa
            ├── WidgetConfigActivity.kt Elegir líneas, opacidad, color de texto e imagen de cada widget
            ├── UpdateManager.kt        Aviso de versión nueva e instalación (FileProvider)
            ├── CrashReporter.kt        Guarda el último error que cerró la app
            ├── CrashReportActivity.kt  Muestra ese error para copiarlo
            ├── data/
            │   ├── SecretBox.kt        Interfaz de cifrado + implementación con Android Keystore
            │   ├── AccountStore.kt     Lista de líneas (contraseñas cifradas)
            │   ├── UsageCache.kt       Último consumo de cada línea
            │   ├── WidgetConfigStore.kt Líneas y opacidad de cada widget
            │   └── UsageRepository.kt  Decide caché vs. Telcel; una consulta a la vez
            ├── widget/
            │   ├── MiTelcelWidgetProvider.kt  Eventos del widget (agregar, quitar, tocar)
            │   ├── WidgetRenderer.kt   Arma el widget: contenedor + un panel por línea
            │   ├── WidgetStyle.kt      Opacidad, paleta (texto claro/oscuro) y modo Automático
            │   ├── BackgroundImageStore.kt Copia y reduce la foto de fondo de cada widget
            │   └── GaugeRenderer.kt    Dibuja el medidor semicircular en un Bitmap
            └── work/
                └── RefreshWorker.kt    Actualización en segundo plano (WorkManager)
```

### Decisiones de diseño
- **`:core` sin Android:** la red y el parser no dependen del framework (Inversión de Dependencias), así que se prueban en segundos en la JVM.
- **Un solo punto de acceso a datos:** el widget, el Worker y las pantallas pasan por `UsageRepository`. Ninguno habla directo con la red.
- **Una clase por compañía (`UsageProvider`):** agregar una compañía es crear una clase nueva sin tocar el widget ni las pantallas (principio Abierto/Cerrado).
- **`SecretBox` como interfaz:** en producción cifra con el Keystore; en las pruebas se sustituye por una versión falsa, porque el simulador no tiene Keystore.
- **Widget = contenedor + paneles:** cada línea es un `RemoteViews` que se agrega con `addView`, así 1 o 2 líneas usan el mismo código.
- **El Provider nunca hace red:** Android lo detiene si tarda más de unos 10 s. El trabajo pesado va en `RefreshWorker`.

---

## Desarrollo

Requisitos: JDK 17 y Android SDK (o Android Studio, abriendo la carpeta `android-app/`).

```sh
cd android-app
./gradlew :core:test              # parser y cliente de red (JUnit + MockWebServer)
./gradlew :app:testDebugUnitTest  # pruebas de humo de pantallas y widget (Robolectric, API 34 y 35)
./gradlew :app:assembleDebug      # genera app/build/outputs/apk/debug/app-debug.apk
```

### CI (GitHub Actions)
`.github/workflows/android-apk.yml` corre con cada push que modifique `android-app/`:
1. Pruebas de `:core`.
2. Compilación del APK y publicación como artefacto.
3. Pruebas de humo con Robolectric.
4. **Solo con etiquetas `v*`:** publica el APK en **Releases**.

Si algo falla, los errores del compilador y de las pruebas aparecen como **anotaciones** en el resumen de la ejecución.

### Si Telcel cambia su API
1. Copia `.env.example` a `.env` y escribe tu número y contraseña. `.env` está en `.gitignore`.
2. Ejecuta `zsh .agents/skills/telcel-api-probe/scripts/probe_telcel.sh`. Prueba los endpoints, revoca la sesión al terminar y deja un resumen en `artifacts/`, carpeta que también ignora git.
3. Compara con [`docs/telcel-api.md`](docs/telcel-api.md) y ajusta `HomeFlowParser.kt` o `TelcelClient.kt`, junto con sus pruebas.

### Publicar una versión nueva
1. Sube `versionCode` (+1) y `versionName` en `android-app/app/build.gradle.kts`.
2. Crea y sube la etiqueta con **el mismo** `versionName`:
   ```sh
   git tag v1.4 && git push origin v1.4
   ```
3. El workflow compila, prueba y publica el Release con `CompaniasMxWidget.apk`. El botón **Descargar APK** apunta solo a la versión nueva.

El Release se rechaza si la etiqueta no coincide con `versionName` o si falta la llave de firma fija.

### Llave de firma fija
**Obligatoria para publicar Releases**: sin ella, nadie podría actualizar la app sin desinstalarla y perder sus líneas guardadas.

```sh
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # en Windows PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("debug.keystore"))
```

Guarda la salida como secreto `DEBUG_KEYSTORE_B64` en **Settings → Secrets and variables → Actions**. **No subas `debug.keystore` al repositorio.**

---

## Solución de problemas

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| Después de actualizar sigue la versión vieja | Firma distinta (versión de prueba de Actions) | Desinstala y vuelve a instalar |
| La app se cierra al abrir | Error en el código | Ábrela de nuevo y usa **Copiar error**, o `adb logcat -d -b crash` |
| ⚠ con mensaje de la compañía | Contraseña incorrecta o la compañía cambió su API | Revisa la contraseña en la app oficial |
| No se actualiza sola | Ahorro de batería | *Ajustes → Apps → Mis Datos MX → Batería → Sin restricciones* |
| Muestra ☁ | Sin red: es el último dato guardado | Toca el medidor cuando haya conexión |

---

> [!NOTE]
> **Privacidad en Bait:** su servicio entrega el consumo con solo el número, sin contraseña. Cualquiera que conozca tu número de Bait puede consultarlo en mibait.com. Esta app no cambia eso.

## Créditos

- Telcel y Bait: el flujo de autenticación y los parsers están basados en [amaynez/telcel-widget-agy](https://github.com/amaynez/telcel-widget-agy) (widgets para iOS), de Armando Maynez.
