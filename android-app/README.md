# Mi Telcel Widget — App nativa Android (APK)

Widget de pantalla de inicio que muestra los **GB disponibles (Plan + Promo)**, el consumo, los días restantes y la fecha de corte. Es un port en Kotlin del cliente iOS (`MiTelcelWidget/TelcelNetworkService.swift`).

> [!WARNING]
> **App no oficial y no probada en un teléfono real.** Usa los mismos servicios que la web de Mi Telcel. Si Telcel los cambia, el widget dejará de actualizarse.

## Obtener el APK (sin instalar nada en tu PC)

1. Haz **fork** de este repositorio en GitHub.
2. En tu fork, abre la pestaña **Actions** y habilita los workflows si te lo pide.
3. Elige **Android APK** → **Run workflow**. También corre solo con cada push que modifique `android-app/`.
4. Al terminar (~5 min), abre la ejecución y descarga el artefacto **MiTelcelWidget-apk** (es un `.zip` que contiene `app-debug.apk`).
5. Pasa el APK a tu teléfono, ábrelo y permite **"Instalar apps de origen desconocido"** cuando lo pida.

## Uso

1. Abre **Mi Telcel Widget** y agrega una o varias líneas (apodo, número y contraseña). Pulsa **Guardar y actualizar**.
2. Agrega el widget: mantén presionado el fondo de la pantalla → *Widgets* → *Mi Telcel Widget*. Se abre **Configurar widget**, donde eliges **una o dos líneas** y la **opacidad del fondo** (0% = transparente).
3. ¿Dos líneas? Marca las dos en **Configurar widget** y se verán lado a lado en el mismo widget (agrándalo a 4×2). También puedes poner dos widgets separados.
4. Si tocas el medidor, se actualiza esa línea. Si tocas el encabezado, se abre la configuración de ese widget. Además se actualiza solo cada 30 minutos (una línea a la vez, para no abrir dos sesiones con Telcel al mismo tiempo).

## Actualizar la app sin desinstalarla (opcional)

Si no configuras nada, cada ejecución de Actions firma el APK con una llave distinta, y Android no deja instalar la versión nueva encima de la anterior. Para usar siempre la misma llave:

```sh
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # en macOS: base64 -i debug.keystore
```

Copia la salida a un secreto llamado `DEBUG_KEYSTORE_B64` en tu fork (**Settings → Secrets and variables → Actions → New repository secret**). **No subas `debug.keystore` al repositorio.**

## Estructura

```
android-app/
├── core/   Kotlin puro (sin Android): cliente de red y parser. Con pruebas JUnit.
│   ├── TelcelClient.kt     Login SSO → token B2F → Home Wave → revocar SSO (siempre, en finally)
│   ├── HomeFlowParser.kt   Suma las bolsas tabs-plans-v2-* y extrae fecha y días de corte
│   └── UsageEntry.kt       Modelo y textos del widget ("9 días", "608 MB/d", ...)
└── app/    Todo lo que depende de Android
    ├── data/   CredentialStore (cifrado con Android Keystore), UsageCache, UsageRepository
    ├── widget/ AppWidgetProvider, RemoteViews y medidor dibujado en un Bitmap
    ├── work/   RefreshWorker (WorkManager: cada 30 min y al tocar el widget)
    └── MainActivity.kt  Captura de credenciales y vista previa
```

## Compilar localmente (opcional)

Con Android Studio, abre la carpeta `android-app/` y usa **Build → Build APK(s)**. Desde la terminal, con el Android SDK instalado:

```sh
cd android-app
./gradlew :core:test :app:assembleDebug
```
