---
name: telcel-api-probe
description: Autentica contra Mi Telcel vía WebLogin SSO (wbl.telcel-id.com:8443), intercambia tokens JWT (mi-telcel.smapps.mx), prueba los endpoints REST de perfil, Home (Wave B2F con Plan Base + Datos Promocionales + Fecha de Corte), detalle de consumo, paquetes activos y facturación/plan, revoca la sesión SSO de forma segura y analiza el esquema JSON resultante. Úsala cuando el usuario pida probar las llamadas API de Telcel o mapear el JSON para el widget.
---

# Protocolo de Ejecución: Telcel API Probe

Sigue estos pasos en orden estricto:

1. **Verificación de Entorno (Sin leer secretos):**
   Comprueba que existe el archivo `.env` en la raíz del proyecto y que las variables no tienen los valores por defecto (`5512345678`) ejecutando únicamente una validación booleana en consola (nunca hagas `cat .env`).

2. **Ejecución del Probe como Caja Negra:**
   Ejecuta el script de pruebas incluido en la skill:
   `zsh .agents/skills/telcel-api-probe/scripts/probe_telcel.sh`
   *(El script ejecuta el login SSO en `wbl.telcel-id.com:8443/login`, intercambia tokens `token:mit|token:b2f` en `mi-telcel.smapps.mx/security/1.0.0/v2/token`, valida el perfil en `/users/1.0.0/v2/info/validate`, consulta el Home Flow en `api.journeys.telcel.wavebybemobi.com/api/components/flow/home` —extrayendo tanto **Datos de mi plan (`0-0-0`)** como **Datos Promocionales (`0-1-0`)** y la **Fecha de corte (`plan-details-row-template-col-2-0-0`)**—, consulta consumo/paquetes/plan y revoca el token SSO en `dev.smapps.mx:9000/sso/revoke` al salir).*

3. **Análisis de Resultados y Artefactos:**
   - Revisa el código de salida y el desglose de buckets (Plan + Promo + Vigencia) en `artifacts/probe_report.txt` (carpeta ignorada por git: contiene datos de tu cuenta).
   - Inspecciona la estructura de llaves generada en `artifacts/schema_summary.json` (y consulta `docs/telcel-api.md` para la referencia completa de bloques).
   - Si la prueba fue exitosa (`HTTP 200` y JSON válido), actualiza `android-app/core/src/main/kotlin/mx/mitelcel/widget/core/HomeFlowParser.kt` (y su prueba `HomeFlowParserTest.kt`) para sumar todas las bolsas `tabs-plans-v2-*-0-*-*` (Plan + Promo), el consumo total, los días restantes y la fecha de corte (`cutoffDate`).
