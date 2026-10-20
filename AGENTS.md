# Guía del Proyecto: Mi Telcel Widget para Android

## Reglas de Seguridad y Sesión
- **NUNCA** leas, imprimas ni incluyas el contenido de `.env`, `cookies.txt` ni de `artifacts/` en tus respuestas o commits: contienen credenciales o datos reales de la cuenta.
- Para probar los endpoints reales de Telcel usa exclusivamente la skill `.agents/skills/telcel-api-probe/SKILL.md` (o el comando `/telcel-api-probe`).
- El backend de Mi Telcel bloquea sesiones concurrentes si no se revoca el token SSO al terminar (`POST https://dev.smapps.mx:9000/sso/revoke`).
  - En la app, `TelcelClient.fetchUsage` lo revoca en un `finally` y `UsageRepository.refresh` serializa las consultas con un lock. **No elimines ninguno de los dos.**
  - El script del probe ya incluye un `trap` de salida que revoca la sesión.

## Estructura
- `android-app/core`: Kotlin puro (sin Android). Contiene la red (`TelcelClient.kt`), el parser (`HomeFlowParser.kt`) y el modelo (`UsageEntry.kt`). Pruebas: `./gradlew :core:test`.
- `android-app/app`: widget, pantallas, almacenamiento cifrado y WorkManager. Pruebas de humo con Robolectric: `./gradlew :app:testDebugUnitTest`.
- `docs/`: referencia de la API de Telcel (bloques Wave B2F y validación de endpoints).
- CI: `.github/workflows/android-apk.yml` compila el APK y publica los errores como anotaciones.

## Flujo de Trabajo si Telcel cambia su API
1. Ejecuta `.agents/skills/telcel-api-probe/scripts/probe_telcel.sh`.
2. Inspecciona únicamente el esquema sanitizado `artifacts/schema_summary.json` y el resumen `artifacts/probe_report.txt`. Consulta [`docs/telcel-api.md`](docs/telcel-api.md) para la especificación de bloques Wave B2F.
3. Con base en las llaves reales del JSON de `api.journeys.telcel.wavebybemobi.com/api/components/flow/home`, actualiza `HomeFlowParser.kt`. Debe sumar todas las bolsas (`0-0-0` Plan Base + `0-1-0` Datos Promocionales) y extraer la fecha de corte (`plan-details-row-template-col-2-0-0`).
   - Agrega a `HomeFlowParserTest.kt` un fixture con la estructura nueva. Usa datos inventados, nunca reales.
4. Si cambia el login SSO (`wbl.telcel-id.com:8443/login`) o el intercambio de token (`mi-telcel.smapps.mx/security/1.0.0/v2/token`), actualiza `TelcelClient.kt` y `TelcelClientTest.kt`.
   - Usa el navegador integrado para inspeccionar el iframe o bundle, o indica al usuario cómo capturar el comando cURL.

## Reglas de Código
- Los `RemoteViews` del widget se inflan con `applicationContext`, nunca con una `Activity`. AppCompat reemplaza `ImageView` por `AppCompatImageView` y `RemoteViews` truena.
- Cada `PendingIntent` del widget lleva un `requestCode` único por widget y por panel.
- Cualquier cambio en `android-app/` debe pasar el workflow de CI antes de darse por terminado.
