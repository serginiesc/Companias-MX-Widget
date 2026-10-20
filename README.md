# Mi Telcel Widget para Android

Widget nativo de Android (Kotlin) que muestra en la pantalla de inicio los **GB disponibles (Plan + Promo)**, el consumo, los días restantes y la fecha de corte de una o varias líneas **Mi Telcel**.

> [!WARNING]
> **App no oficial.** Usa los mismos servicios que la web de Mi Telcel. Si Telcel los cambia, el widget dejará de actualizarse.

---

## Características

- **Varias líneas:** guarda tantas líneas como quieras, cada una con un apodo ("Mía", "Esposa").
- **Una o dos líneas por widget:** con dos, se muestran lado a lado (recomendado 4×2).
- **Fondo transparente configurable:** deslizador de opacidad de 0% a 100% por widget, con sombra en los textos para que se lean sobre cualquier fondo de pantalla.
- **Medidor semicircular:** rojo = gastado, verde = disponible, porcentaje usado y presupuesto diario (`MB/d` o `GB/d`).
- **Contraseñas cifradas** con AES-GCM y una llave del **Android Keystore**. Nunca salen del teléfono, excepto hacia Telcel.
- **Caché local:** si no hay red, muestra el último dato con ☁.
- **Actualización automática** cada 30 minutos (WorkManager) y manual al tocar el medidor.
- **Sesión segura:** el token SSO se revoca siempre al terminar, aunque algo falle, y las consultas van una por una para no abrir sesiones simultáneas.
- **Reporte de errores integrado:** si la app se cierra, al abrirla de nuevo muestra el error con un botón para copiarlo.

---

## Instalación

### 1. Obtener el APK
1. En la pestaña **Actions** de este repositorio, abre la ejecución más reciente de **Android APK** con ✅. Si no hay ninguna, elige **Run workflow**.
2. En *Artifacts*, descarga **MiTelcelWidget-apk**. Es un `.zip` que contiene `app-debug.apk`.

### 2. Instalar en el teléfono

**Opción A, desde el teléfono:** abre `app-debug.apk` y permite **"Instalar apps de origen desconocido"**.
> En Samsung, si está activa **Ajustes → Seguridad y privacidad → Protección automática**, apágala para poder instalar.

**Opción B, con `adb` desde la PC (recomendada para actualizar):**
```cmd
adb uninstall mx.mitelcel.widget
adb install app-debug.apk
adb shell dumpsys package mx.mitelcel.widget | findstr versionName
```
`adb` viene en [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools). En el teléfono hay que activar *Opciones de desarrollador → Depuración USB*.

> [!IMPORTANT]
> Cada ejecución de Actions firma el APK con una llave distinta, y Android rechaza instalar una versión encima de otra firmada con otra llave. Si después de "actualizar" sigues viendo la versión anterior, **desinstala primero**. Para evitarlo, configura una [llave de firma fija](#llave-de-firma-fija-opcional).

---

## Uso

1. Abre **Mi Telcel Widget** y agrega tus líneas (apodo, número a 10 dígitos y contraseña). Pulsa **Guardar y actualizar**.
2. Agrega el widget: mantén presionado el fondo de la pantalla → *Widgets* → *Mi Telcel Widget*.
3. Se abre **Configurar widget**:
   - Marca **una o dos líneas**.
   - Ajusta la **opacidad del fondo** (0% = transparente).
4. En el widget:
   - **Toca el medidor** para actualizar esa línea.
   - **Toca el encabezado** para cambiar las líneas o la transparencia de ese widget.

---

## Estructura del proyecto

```
.
├── .github/workflows/android-apk.yml   CI: pruebas + compilación + APK como artefacto
└── android-app/
    ├── settings.gradle.kts             Módulos :core y :app
    ├── core/                           Kotlin puro (sin Android), probado con JUnit
    │   └── src/main/kotlin/mx/mitelcel/widget/core/
    │       ├── TelcelClient.kt         Login SSO → token B2F → consumo (Wave B2F) → revocar SSO
    │       ├── HomeFlowParser.kt       Suma las bolsas de datos y extrae fecha y días de corte
    │       └── UsageEntry.kt           Modelo y textos del widget ("9 días", "608 MB/d"…)
    └── app/                            Todo lo que depende de Android
        └── src/main/kotlin/mx/mitelcel/widget/
            ├── MainActivity.kt         Administrar líneas (agregar, editar, borrar) y vista previa
            ├── WidgetConfigActivity.kt Elegir líneas y transparencia de cada widget
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
            │   ├── GaugeRenderer.kt    Dibuja el medidor semicircular en un Bitmap
            │   └── WidgetColors.kt
            └── work/
                └── RefreshWorker.kt    Actualización en segundo plano (WorkManager)
```

### Decisiones de diseño
- **`:core` sin Android:** la red y el parser no dependen del framework (Inversión de Dependencias), así que se prueban en segundos en la JVM.
- **Un solo punto de acceso a datos:** el widget, el Worker y las pantallas pasan por `UsageRepository`. Ninguno habla directo con la red.
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

Si algo falla, los errores del compilador y de las pruebas aparecen como **anotaciones** en el resumen de la ejecución.

### Llave de firma fija (opcional)
Para poder actualizar la app sin desinstalarla, ni perder las líneas guardadas:

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
| Después de actualizar sigue la versión vieja | Firma distinta: Android no reemplazó la app | `adb uninstall mx.mitelcel.widget` y reinstalar |
| La app se cierra al abrir | Error en el código | Ábrela de nuevo y usa **Copiar error**, o `adb logcat -d -b crash` |
| ⚠ con mensaje de Telcel | Contraseña incorrecta o Telcel cambió su API | Revisa la contraseña en la app oficial |
| No se actualiza sola | Ahorro de batería | *Ajustes → Apps → Mi Telcel Widget → Batería → Sin restricciones* |
| Muestra ☁ | Sin red: es el último dato guardado | Toca el medidor cuando haya conexión |

---

## Créditos

El flujo de autenticación y el parser de la respuesta de Telcel están basados en [amaynez/telcel-widget-agy](https://github.com/amaynez/telcel-widget-agy) (widget para iOS), de Armando Maynez.
