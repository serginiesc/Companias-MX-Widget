Para validar los endpoints de **Mi Telcel** (arquitectura SPA 2026 con **WebLogin SSO + JWT**) desde tu MacBook Pro M3 antes de integrarlos en el widget, el método más rápido y limpio es utilizar `curl` y `jq` en la terminal de macOS (`zsh`) o ejecutar directamente el script `.agents/skills/telcel-api-probe/scripts/probe_telcel.sh`.

A continuación tienes el plan de pruebas actualizado para la nueva arquitectura de microservicios de Mi Telcel (`wbl.telcel-id.com:8443`, `mi-telcel.smapps.mx`, `api.journeys.telcel.wavebybemobi.com` y `dev.smapps.mx:9000`).

---

### **Fase 1: Plan de Pruebas en Terminal de macOS (`curl`)**

El portal web actual de Mi Telcel (`https://www.mitelcel.com/login`) reemplazó el antiguo login monolítico de Java EE (`/mitelcel/login/auth` con `JSESSIONID`, que ahora responde `405 Not Allowed`) por una arquitectura desacoplada en 3 etapas:
1. **Autenticación SSO (WebLogin Iframe):** `POST https://wbl.telcel-id.com:8443/login` que devuelve un `<sso_token>` JWT.
2. **Intercambio de Tokens (`token:mit|token:b2f`):** `GET https://mi-telcel.smapps.mx/security/1.0.0/v2/token` autenticado con Basic Auth de aplicación (`MTL_AUTHORIZATION_BASIC`), que entrega `MTL_TOKEN` (para microservicios `mi-telcel.smapps.mx`) y `B2F_TOKEN` (para bloques de inicio en `api.journeys.telcel.wavebybemobi.com`).
3. **Validación de Perfil y Consultas de Consumo/Paquetes/Facturación:** Peticiones a `mi-telcel.smapps.mx` y `api.journeys.telcel.wavebybemobi.com`, seguidas de la revocación del token SSO en `POST https://dev.smapps.mx:9000/sso/revoke`.

#### **Paso 1: Definir tus variables de sesión**

```zsh
export TELCEL_PHONE="5512345678"
export TELCEL_PASS="TU_PASSWORD"
export PASS_B64="$(printf '%s' "$TELCEL_PASS" | tr -d '[:space:]' | base64 | tr -d '\r\n')"
export MTL_BASIC="bXRwc2VydmljZXM6NyQ4N3JmRnN3ZSQyMV8="
export UA="Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
```

#### **Paso 2: Autenticación SSO en `wbl.telcel-id.com:8443` y obtención de `SSO_TOKEN`**

Primero inicializa las cookies del iframe de WebLogin y luego envía las credenciales (incluyendo `pB64` con la contraseña codificada en Base64):

```zsh
curl -s -c cookies.txt -b cookies.txt \
  -X GET "https://wbl.telcel-id.com:8443/login?sitedomain=https://www.mitelcel.com&CCode=52&Type=MSISDN&Invocation=iFrame" \
  -H "User-Agent: $UA" > /dev/null

SSO_TOKEN=$(curl -s -L -c cookies.txt -b cookies.txt \
  "https://wbl.telcel-id.com:8443/login" \
  -H "Origin: https://wbl.telcel-id.com:8443" \
  -H "Referer: https://wbl.telcel-id.com:8443/login?sitedomain=https://www.mitelcel.com&CCode=52&Type=MSISDN&Invocation=iFrame" \
  -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
  -H "X-Requested-With: XMLHttpRequest" \
  -H "User-Agent: $UA" \
  --data-urlencode "username=$TELCEL_PHONE" \
  --data-urlencode "telcel-pass=$TELCEL_PASS" \
  --data-urlencode "sitedomain=https://www.mitelcel.com" \
  --data-urlencode "CCode=52" \
  --data-urlencode "Type=MSISDN" \
  --data-urlencode "Invocation=iFrame" \
  --data-urlencode "MSISDN=$TELCEL_PHONE" \
  --data-urlencode "Source=" \
  --data-urlencode "pB64=$PASS_B64" \
  --data-urlencode "url=https://wbl.telcel-id.com:8443" | jq -r '.token')
```

#### **Paso 3: Intercambio de Tokens (`MTL_TOKEN` y `B2F_TOKEN`)**

```zsh
TOKEN_JSON=$(curl -s \
  -X GET "https://mi-telcel.smapps.mx/security/1.0.0/v2/token?user=${TELCEL_PHONE}&token=${SSO_TOKEN}" \
  -H "Authorization: Basic $MTL_BASIC" \
  -H "scope: token:mit|token:b2f" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -H "App-Version: 0.14" \
  -H "Code-Version: 38" \
  -H "Device-Id: DSK" \
  -H "tipoRequest: 1" \
  -H "Os: WEB" \
  -H "Os-Version: 13" \
  -H "X-Channel: web-browser" \
  -H "User-Agent: ktor-client")

MTL_TOKEN=$(echo "$TOKEN_JSON" | jq -r '.tokenResponse.tokens[] | select(.id=="token:mit") | .access_token')
B2F_TOKEN=$(echo "$TOKEN_JSON" | jq -r '.tokenResponse.tokens[] | select(.id=="token:b2f") | .access_token')
```

#### **Paso 4: Validar Perfil de la Línea (`profile`, `region`, `account`)**

```zsh
VALIDATE_JSON=$(curl -s \
  -X POST "https://mi-telcel.smapps.mx/users/1.0.0/v2/info/validate" \
  -H "Authorization: Basic $MTL_BASIC" \
  -H "Token: $MTL_TOKEN" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -H "App-Version: 0.14" \
  -H "Code-Version: 38" \
  -H "Device-Id: DSK" \
  -H "tipoRequest: 1" \
  -H "Os: WEB" \
  -H "Os-Version: 13" \
  -H "X-Channel: web-browser" \
  -H "User-Agent: ktor-client" \
  -d "{\"user\":\"$TELCEL_PHONE\"}")

PROFILE=$(echo "$VALIDATE_JSON" | jq -r '.validateUserInfoResponse.line.profile')
REGION=$(echo "$VALIDATE_JSON" | jq -r '.validateUserInfoResponse.line.region')
ACCOUNT=$(echo "$VALIDATE_JSON" | jq -r '.validateUserInfoResponse.line.account // "0"')
IS_SUSPENDED=$(echo "$VALIDATE_JSON" | jq -r '.validateUserInfoResponse.line.isSuspended // false')
```

#### **Paso 5: Consultar Consumo en Vivo (Home B2F), Detalle de Consumo, Paquetes y Facturación**

* **Bloques de Inicio / Consumo en Vivo (Wave B2F — Incluye `Datos de mi plan`, `Datos Promocionales` y `Fecha de corte`):**
```zsh
curl -s \
  -X GET "https://api.journeys.telcel.wavebybemobi.com/api/components/flow/home?externalCode=52${TELCEL_PHONE}" \
  -H "Authorization: Bearer $B2F_TOKEN" \
  -H "Accept: application/json" \
  -H "X-Is-iPad: false" \
  -H "x-wave-sdk: WEB" \
  -H "x-wave-device: Desktop" \
  -H "x-wave-device-type: browser" \
  -H "x-wave-os: macos-web" \
  -H "x-wave-os-version: 10.15.7" | tee home_flow.json | jq '
  [.. | objects | select(.id? | type == "string" and test("^tabs-plans-v2-plan-name-[0-9]+-[0-9]+-[0-9]+$")) | .id | sub("tabs-plans-v2-plan-name-"; "")] as $sfx
  | [.. | objects | select(.id? and .config?) | {key: .id, value: .config}] | from_entries as $byId
  | {
      plan: $byId["text-plan-name"].value,
      fecha_corte: $byId["plan-details-row-template-col-2-0-0"].value,
      vigencia_msg: ([$byId["tabs-plans-v2-limited-cutoff-message-0-0-0"].segments[]?.text] | join("")),
      bolsas: ($sfx | map({
        bucket: .,
        nombre: $byId["tabs-plans-v2-plan-name-\(.)"].value,
        incluidos: "\($byId["tabs-plans-v2-total-data-value-\(.)"].value) \($byId["tabs-plans-v2-total-data-unit-\(.)"].value)",
        usados: "\($byId["tabs-plans-v2-used-data-value-\(.)"].value) \($byId["tabs-plans-v2-used-data-unit-\(.)"].value)",
        disponibles: "\($byId["tabs-plans-v2-available-data-value-\(.)"].value) \($byId["tabs-plans-v2-available-data-unit-\(.)"].value)",
        chart_kb: $byId["tabs-plans-v2-usage-chart-\(.)"].data
      }))
    }'
```
*(El sufijo `0-0-0` corresponde a la pestaña **Datos de mi plan**, el sufijo `0-1-0` a **Datos Promocionales** como `PROMO 50% DE DATOS 24 MDP`, y `plan-details-row-template-col-2-0-0` entrega la **Fecha de corte** como `"28 de octubre"`. Consulta [`Telcel Widget API.md`](file:///Users/armandomaynez/Code/Repos/telcel-widget-agy/Telcel%20Widget%20API.md) para el detalle completo).*

* **Detalle de Consumo (`/users/1.0.0/consumption/v2/details`):**
```zsh
curl -s \
  -X GET "https://mi-telcel.smapps.mx/users/1.0.0/consumption/v2/details?user=${TELCEL_PHONE}&profile=${PROFILE}&region=${REGION}&typeRequest=getModule&account=${ACCOUNT}" \
  -H "Authorization: Basic $MTL_BASIC" \
  -H "Token: $MTL_TOKEN" \
  -H "user: $TELCEL_PHONE" \
  -H "profile: $PROFILE" \
  -H "region: $REGION" \
  -H "Accept: application/json" \
  -H "App-Version: 0.15" \
  -H "Code-Version: 38" \
  -H "Device-Id: DSK" \
  -H "tipoRequest: 1" \
  -H "Os: WEB" \
  -H "Os-Version: 13" \
  -H "X-Channel: web-browser" \
  -H "User-Agent: ktor-client" | jq .
```

* **Paquetes Activos (`/packages/1.0.0/v2/active/list`):**
```zsh
curl -s \
  -X GET "https://mi-telcel.smapps.mx/packages/1.0.0/v2/active/list?user=${TELCEL_PHONE}&profile=${PROFILE}&region=${REGION}&isSuspended=${IS_SUSPENDED}" \
  -H "Authorization: Basic $MTL_BASIC" \
  -H "Token: $MTL_TOKEN" \
  -H "user: $TELCEL_PHONE" \
  -H "profile: $PROFILE" \
  -H "region: $REGION" \
  -H "Accept: application/json" \
  -H "App-Version: 0.14" \
  -H "Code-Version: 38" \
  -H "Device-Id: DSK" \
  -H "tipoRequest: 1" \
  -H "Os: WEB" \
  -H "Os-Version: 13" \
  -H "X-Channel: web-browser" \
  -H "User-Agent: ktor-client" | jq .
```

* **Facturación (`/services/1.0.0/v2/telcelBilling`):**
```zsh
curl -s \
  -X GET "https://mi-telcel.smapps.mx/services/1.0.0/v2/telcelBilling" \
  -H "Authorization: Basic $MTL_BASIC" \
  -H "Token: $MTL_TOKEN" \
  -H "user: $TELCEL_PHONE" \
  -H "profile: $PROFILE" \
  -H "region: $REGION" \
  -H "account: $ACCOUNT" \
  -H "Accept: application/json" \
  -H "App-Version: 0.14" \
  -H "Code-Version: 38" \
  -H "Device-Id: DSK" \
  -H "tipoRequest: 1" \
  -H "Os: WEB" \
  -H "Os-Version: 13" \
  -H "X-Channel: web-browser" \
  -H "User-Agent: ktor-client" | jq .
```

#### **Paso 6: Cerrar la sesión (`Revoke SSO Token`)**

```zsh
curl -s -X POST "https://dev.smapps.mx:9000/sso/revoke" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Accept: application/json" \
  --data-urlencode "token=$SSO_TOKEN" \
  --data-urlencode "token_type=revoke"

rm -f cookies.txt
```

---

### **Fase 2: Resumen de Endpoints en Postman / HTTP Client**

| Orden | Operación | Método | URL Completa | Autenticación / Headers Clave |
| :---: | :--- | :---: | :--- | :--- |
| **1** | `01 - WebLogin SSO` | `POST` | `https://wbl.telcel-id.com:8443/login` | `Content-Type: application/x-www-form-urlencoded`<br>Body: `username`, `telcel-pass`, `pB64` (Base64), `MSISDN`, `CCode=52`, `Type=MSISDN`, `Invocation=iFrame`, `sitedomain=https://www.mitelcel.com` |
| **2** | `02 - Token Exchange` | `GET` | `https://mi-telcel.smapps.mx/security/1.0.0/v2/token?user={{phone}}&token={{sso_token}}` | `Authorization: Basic bXRwc2VydmljZXM6NyQ4N3JmRnN3ZSQyMV8=`<br>`scope: token:mit\|token:b2f` |
| **3** | `03 - Validate Line Info` | `POST` | `https://mi-telcel.smapps.mx/users/1.0.0/v2/info/validate` | `Authorization: Basic ...`, `Token: {{mtl_token}}`<br>Body JSON: `{"user": "{{phone}}"}` |
| **4** | `04 - Home Flow (Wave B2F)` | `GET` | `https://api.journeys.telcel.wavebybemobi.com/api/components/flow/home?externalCode=52{{phone}}` | `Authorization: Bearer {{b2f_token}}`<br>`x-wave-sdk: WEB`, `x-wave-os: macos-web` |
| **5** | `05 - Consumo Details` | `GET` | `https://mi-telcel.smapps.mx/users/1.0.0/consumption/v2/details?user={{phone}}&profile={{profile}}&region={{region}}&typeRequest=getModule&account={{account}}` | `Authorization: Basic ...`, `Token: {{mtl_token}}`, `App-Version: 0.15`, `user`, `profile`, `region` |
| **6** | `06 - Paquetes Activos` | `GET` | `https://mi-telcel.smapps.mx/packages/1.0.0/v2/active/list?user={{phone}}&profile={{profile}}&region={{region}}&isSuspended={{isSuspended}}` | `Authorization: Basic ...`, `Token: {{mtl_token}}`, `user`, `profile`, `region` |
| **7** | `07 - Factura Telcel` | `GET` | `https://mi-telcel.smapps.mx/services/1.0.0/v2/telcelBilling` | `Authorization: Basic ...`, `Token: {{mtl_token}}`, `user`, `profile`, `region`, `account` |
| **8** | `08 - Revoke SSO (Logout)` | `POST` | `https://dev.smapps.mx:9000/sso/revoke` | `Content-Type: application/x-www-form-urlencoded`<br>Body: `token={{sso_token}}&token_type=revoke` |
