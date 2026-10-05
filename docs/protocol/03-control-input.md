# 03 — Protocolo de control y entrada (táctil / teclas) — QDLink 1.9.7

> **Fuente**: APK `com.neusoft.qdrivelink` v1.9.7 (versionCode 107, minSdk 21, targetSdk 35), decompilado con jadx en
> `decomp/qdlink/sources`. Todas las referencias `archivo:línea` son relativas a ese directorio salvo que se indique otra
> cosa. El código está ofuscado: los nombres de clases/métodos/campos son los que asigna jadx.
>
> **Alcance**: protocolo de mensajes de control (framing, catálogo, handshake) y entrada desde el coche (táctil y teclas).
> El descubrimiento/transporte y el codificador de vídeo los cubren otros documentos; aquí solo se tocan en lo
> imprescindible.
>
> **Convención**: "coche" = unidad principal (HU) del Leapmotor C10; "teléfono" = QDLink (o nuestro cliente Kotlin).
> Lo que se afirma sin matiz está leído en el código. Lo marcado como **[INFERENCIA]** es deducción razonada y debe
> confirmarse con captura (ver §12).

---

## 0. Resumen ejecutivo

- Coexisten **dos protocolos**, y es el **coche** quien elige: el teléfono mira los 4 primeros bytes de cada mensaje
  entrante. **`"5A5A"`** = protocolo nuevo (cabecera binaria de 16 bytes + JSON o binario). **`"!BIN"`** = protocolo
  legado (bloques de 512 bytes). Un coche reciente como el C10 casi seguro usa `5A5A` **[INFERENCIA]**.
- **Cabecera 5A5A (16 B, big-endian)**: `"5A5A"` · `totalSize u32` · `extHeaderLen u16` · `msgType u8` · `u8 (0)` ·
  `u8 (0)` · `payloadFormat u8` · `reserved u8` · `pad u8`. Sin CRC, sin número de secuencia, sin cifrado.
- **Control** = JSON `{"CMD":…, "PARA":{…}}` (msgType 0) o `{"AppID":…, "FunctionID":…, "Para":{…}}` (msgType 13),
  generado con `org.json` por reflexión sobre los beans `newmessage/bean/*Para`.
- **Handshake**: coche `CAR_INFO` → tel `PHONE_INFO` + `UPDATE_NOTIFY` → coche `VIDEO_SUP_REQ` → tel (tras el permiso de
  MediaProjection) `VIDEO_SUP_RSP {VideoFormat:3, VideoSupport:1}` → coche `VIDEO_ARGS` → tel `SPEECH_ARGS` → coche
  `VIDEO_CTRL {PlayStatus:1}` → el teléfono empieza a enviar vídeo (msgType 1). El teléfono manda
  `{"CMD":"HEARTBEAT"}` cada 3 s y corta si el coche no envía nada en > 5 s.
- **Táctil**: msgType 2, payload **binario**: `action i32` + `nDedos u8` + N × (`id u8`, `acción u8` [1=down,
  2=up, 3=move], `x f32`, `y f32`), todo big-endian.
- **Inyección**: (a) dentro de la propia UI de QDLink proyectada (Presentation + `dispatchTouchEvent`, solo el dedo 0) o
  (b) fuera de la app / espejo completo (`AccessibilityService.dispatchGesture`, trazos rectos troceados cada 200 ms:
  sin pulsación larga real y con arrastres "a saltos").
- **Teclas**: `PHONE_KEYS` 1=Home, 2=Atrás, 3=Recientes (`performGlobalAction`). Música por msgType 13
  `AppID:"Music"` → `PlayControl` / `PlayControlPlay` / `PlayControlPause` / `Prev` / `Next` / `MuteControl`.
  No hay volumen ni tecla de voz.
- En 1.9.7 **no existe** ningún canal de metadatos de música, estado de llamada, notificaciones ni navegación. Solo se
  envía `PlayState` 0/1 del reproductor interno de QDLink.
- La BD interna (`res/raw/linkmanager.db3`) identifica al **C10**: `CarFactory="018"`, `CarType="2D4"` (volante a la
  izquierda) / `"2D5"` (a la derecha), `HUFactory="119"`.

---

## 1. Mapa de clases relevantes

| Clase (jadx) | Rol |
|---|---|
| `com/neusoft/interconnection/linkconnection/a.java` | **Núcleo del protocolo**: hilo lector, parseo (`U` nuevo, `W` legado), envío de todos los mensajes, heartbeat y watchdog |
| `com/neusoft/interconnection/linkconnection/c.java` | Transporte USB AOA (accesorio). No aplica a Wi-Fi |
| `com/neusoft/interconnection/wificonnection/d.java` | Transporte Wi-Fi: `ServerSocket` + `accept()` |
| `h0/a.java` | Cabecera "5A5A" de 16 bytes (serializar/parsear) |
| `h0/b.java`, `h0/d.java` | Cabecera extendida de 32 bytes de los frames de vídeo |
| `h0/c.java` | Constantes de comandos y (de)serializador JSON |
| `h0/e.java`, `h0/f.java` | Evento táctil binario (msgType 2) y dedo |
| `com/neusoft/interconnection/linkconnection/newmessage/bean/*.java` | Beans JSON (`CarInfoPara`, `PhoneInfoPara`, …) |
| `com/neusoft/interconnection/linkconnection/message/*.java` | Mensajes del protocolo legado `!BIN` (512 B) |
| `com/neusoft/interconnection/utils/i.java` | Utilidades de bytes (big-endian) |
| `com/neusoft/interconnection/utils/a.java`, `utils/e.java` | Constantes (`"5A5A"`, `"!BIN"`, sabor `"QDriveLink"`, flags) |
| `g0/a.java` → `f0/b.java` → `f0/a.java` | Cadena de callbacks: protocolo → `LinkManager` → servicio de app |
| `com/neusoft/qdrivelink/interconnection/DLinkNotifyL.java` | Servicio que implementa `f0.a`: teclas, música, BT, lista blanca, táctil por accesibilidad |
| `com/neusoft/qdrivelink/interconnection/MouseAccessibilityService.java` | Inyección táctil y teclas globales con `AccessibilityService` |
| `com/neu/ssp/mirror/screencap/service/ScreenCaptureService.java` | Binder de captura; cálculo de tamaños; reenvío del táctil "in-app" |
| `com/neu/ssp/mirror/screencap/managers/a.java`, `managers/d.java` | Captura/VirtualDisplay; `Presentation` que recibe el táctil "in-app" |
| `com/neusoft/interconnection/MirrorActivity.java` | Permiso MediaProjection y arranque del espejo |
| `com/neusoft/parse/DataParser.java`, `com/neusoft/ssp/protocol/SSPProtocol.java` | Payload ASCII "A6A6" + serialización nativa SSP (solo protocolo legado) |
| `com/neusoft/qdrivelink/server/MyAccessibilityService.java` | Servicio vacío (solo logs). **No** está declarado en el manifiesto; no interviene |

Cadena de callbacks (útil para seguir qué hace cada mensaje):

```
linkconnection.a  --g0.a-->  f0.b (LinkManager)  --f0.a-->  DLinkNotifyL (Service)
                                         \--f0.c-->  MirrorActivity (permiso de captura / play)
```

La tabla completa de reenvíos está en `f0/b.java:111-535` (por ejemplo, `g0.a.e()` → `DLinkNotifyL.e()` = Home).

---

## 2. Contexto mínimo de transporte

- **Wi-Fi**: el teléfono es **servidor TCP**. Abre un `ServerSocket(mirrorPort)`, envía al coche un datagrama UDP
  "AddDevice" con `MirrorPort` y espera (timeout de 20 s) a que el coche conecte. Ver
  `wificonnection/d.java:76-104` y el JSON del UDP en `wificonnection/a.java:118-137`. Tras `accept()`:
  `setTcpNoDelay(true)`, buffers de envío/recepción de 4 MB/6 MB y keep-alive (`d.java:88-94`). Después se marca
  `linkMode=1` (Wi-Fi) y se llama a `g0.b.a()` (`d.java:102-104`).
- **Arranque de la sesión** (`a.java:1690-1708`, `K0()`): cuando el socket está listo **y** el binder de
  `ScreenCaptureService` está enlazado (`a.java:1004-1010`), se arrancan a la vez:
  1. el hilo lector (`f()`, `a.java:2594-2603`);
  2. **un mensaje legado `!BIN` "AppStatus"** de 512 B (`f0()`, `a.java:1344-1352`). Se envía **siempre**, también en
     sesiones 5A5A;
  3. el heartbeat 5A5A (primero a 1 s, luego cada 3 s; `M0()`, `a.java:1742-1764`);
  4. el watchdog de lectura (cada 5 s; `J0()`, `a.java:1659-1681`).
- **Detección de protocolo** (hilo `b0`, `a.java:314-442`). En Wi-Fi se leen 16 bytes:
  - si empiezan por `"!BIN"`, se leen 496 bytes más y se procesa con `W()` (legado);
  - si empiezan por `"5A5A"`, `Y()` lee `totalSize-16` bytes y se procesa con `U()` (nuevo).

  El último formato visto se guarda en el campo `W` (`"DEFULT"` → `"!BIN"` | `"5A5A"`), que decide el formato de varias
  respuestas.
- **USB AOA** (`linkconnection/c.java`): se lee en bloques de 512 B y los mensajes salientes se rellenan con ceros hasta
  un múltiplo de 512 (`a.java:1306-1315`). **En Wi-Fi no hay relleno** (`a.java:1295-1304`).

---

## 3. Framing

### 3.1 Cabecera del protocolo nuevo "5A5A" (16 bytes)

Serialización en `h0/a.java:35-56` y parseo en `h0/a.java:114-122`. Todos los enteros son **big-endian**
(`utils/i.java:58-68` lee int32 BE; `213-225` escribe int32/int16 BE).

| Offset | Tamaño | Campo | Valores observados |
|---|---|---|---|
| 0 | 4 | magic ASCII `"5A5A"` (`35 41 35 41`) | fijo (`h0/a.java:33`) |
| 4 | 4 | `totalSize` u32 BE = 16 + extHeaderLen + payload | p. ej. heartbeat = 35 |
| 8 | 2 | `extendHeaderTotalSize` u16 BE | 0 en control/táctil; 32 en vídeo |
| 10 | 1 | `msgType` | 0, 1, 2, 12, 13, 99 (§3.2) |
| 11 | 1 | desconocido (setter `n()`) | siempre 0 al enviar; se ignora al recibir |
| 12 | 1 | desconocido (setter `i()`) | siempre 0 al enviar; se ignora al recibir |
| 13 | 1 | `payLoadFormat` | 0 = binario, 1 = JSON, 2 = vídeo |
| 14 | 1 | `reservedOne` | 0; en msgType 99 es el subtipo de datos personalizados |
| 15 | 1 | relleno | siempre 0 |

> *Fragmento del código decompilado de QDLink omitido en la versión pública (referencia: `h0/a.java:35-56`).*

Al recibir, `U()` separa la cabecera extendida y trata el resto como payload (`a.java:1926-1941`):
`payload = body[extLen:]`, con `new String(payload)` (UTF-8 en Android) si es JSON.

### 3.2 `msgType` y `payloadFormat`

| msgType | Nombre interno (logs) | Dirección | payloadFormat | Contenido |
|---|---|---|---|---|
| 0 | comando | ambas | 1 (JSON) | `{"CMD":…, "PARA":{…}}`. Si el formato no es 1, se ignora (`a.java:1945`) |
| 1 | vídeo | tel → coche | 2 | Cabecera ext. de 32 B + H.264 (`a.java:1453-1481`). Ver doc. de vídeo |
| 2 | "KeyEvent" (= **táctil**) | coche → tel | 0 (binario) | Evento táctil (§6). Si el formato no es 0, se ignora (`a.java:2076`) |
| 12 | speechStatus | coche → tel | (cualquiera) | Datos de voz. Se reenvían a un callback **vacío** (`a.java:2167-2175`, `DLinkNotifyL.java:1737-1739`) |
| 13 | appData | ambas | 1 (JSON) | `{"AppID":…, "FunctionID":…, "Para":{…}}` (`a.java:2176-2318`) |
| 99 | customStatus | ambas | 1 al enviar | Datos opacos; `reservedOne` = subtipo. Entrada → callback vacío (`a.java:2159-2166`); salida `h0()` sin uso (`a.java:2685-2701`) |

(`message/e.java` define además 3, 5 y 10, que solo se usan en el protocolo legado.)

### 3.3 Payload JSON

- **Escritura** (`h0/c.java:348-373`, método `q(cmd, obj)`): construye un `HashMap` con `"CMD"` y `"PARA"`. `PARA` es
  otro mapa con **todos los campos declarados** del bean (`getDeclaredFields()`), cuyo valor se obtiene invocando por
  reflexión `get<Campo>()`. Si `obj == null` se envía solo `{"CMD":cmd}`.
  Variante `p(appId, functionId, obj)` (`h0/c.java:319-346`) → `{"AppID":…, "FunctionID":…, "Para":{…}}`.
  - Las claves coinciden **exactamente** con el nombre del campo Java (`PhoneWidth`, `MirrorTypeSupport`…), incluidas
    mayúsculas.
  - Como se usa `HashMap`, el **orden de claves no es determinista**. Un `String` nulo sale como `null` JSON.
  - Nombres distintos según el tipo de mensaje: **`"PARA"`** (msgType 0) frente a **`"Para"`** (msgType 13).
- **Lectura**: `org.json.JSONObject`, sensible a mayúsculas: `getString("CMD")` (`h0/c.java:202-209`),
  `getJSONObject("PARA")`, y en msgType 13 `getString("AppID")` / `getString("FunctionID")` / `getJSONObject("Para")`.
- El heartbeat es un literal: `"{\"CMD\":\"HEARTBEAT\"}"` (`a.java:208`).

> *Fragmento del código decompilado de QDLink omitido en la versión pública (referencia: `h0/c.java:348-369`).*

### 3.4 Cabecera extendida de vídeo (32 B, solo msgType 1)

Se documenta aquí solo porque forma parte del framing (`h0/d.java:71-88`, rellenada en `a.java:1458-1478`). Es
relevante para el táctil porque informa al coche del modo y la orientación.

| Off | Tam | Campo |
|---|---|---|
| 0 | 2 | longitud de la cabecera ext. = 32 |
| 2 | 1 | tipo = 1 |
| 3 | 1 | 0 |
| 4 | 4 | `dataWidth` (ancho del frame) |
| 8 | 4 | `dataHeight` (alto del frame) |
| 12 | 2 | ángulo (`ang`: 0/90/180/270) |
| 14 | 1 | orientación (`orls`) |
| 15 | 1 | `EncodingType` |
| 16 | 4 | FrameRate |
| 20 | 4 | BitRate |
| 24 | 4 | FrameInterval |
| 28 | 1 | **`iAppCapture`**: 1 = UI de QDLink (in-app), 2 = pantalla completa (out-of-app) |
| 29 | 3 | 0 |

`totalSize` = longitud total del buffer, incluidos los 16 + 32 bytes de cabecera.

### 3.5 Detalles de implementación de QDLink (importantes para interoperar)

- **Lecturas no robustas**: en Wi-Fi la cabecera se lee con un único `read(…,16)` (`a.java:330`) y el cuerpo con otro
  único `read(…, totalSize-16)` (`a.java:2382`). Un mensaje partido en varios segmentos TCP desincroniza el flujo.
  **Nuestro cliente debe usar `readFully`**.
- **Envíos desde hilos nuevos**: casi cada mensaje JSON se envía con `new Thread(...)`. Un `ReentrantLock` evita que se
  intercalen dentro del socket (`a.java:2656-2680`), pero el **orden relativo** entre mensajes enviados casi a la vez no
  está garantizado (por ejemplo `PHONE_INFO` frente a `UPDATE_NOTIFY`). `SPEECH_ARGS`, `BT_RESULT` y
  `LOCK_SCREEN_STATUS` sí se envían de forma síncrona (`d0()`).
- Algunos envíos **no comprueban** el protocolo detectado y siempre salen en formato 5A5A: heartbeat, `PHONE_INFO_CHANGE`,
  `WhitelistAppOn` y `PlayState`. Otros solo se envían si `W=="5A5A"`: `BT_RESULT`, `LOCK_SCREEN_STATUS` y
  `DISCONNECT_RSP`.

### 3.6 Protocolo legado `!BIN` (resumen)

Se incluye para tener un cliente tolerante. Todos los mensajes tienen una cabecera de 512 B
(ver `message/b.java:65-83`, `message/f.java:56-94`, etc.):

| Off | Tam | Campo |
|---|---|---|
| 0 | 4 | `"!BIN"` |
| 4 | 4 | `dataType` (0 comando, 1 pantalla/vídeo, 3 mensaje HU "A6A6", 10 táctil, 12 voz, 99 personalizado) |
| 8 | 4 | `totalSize` (cabecera + datos, múltiplo de 512) |
| 12 | 4 | `headerSize` = 512 |
| 16 | 4 | `commonHeaderSize` = 64 |
| 20 | 4 | `requestHeaderSize` (128; 28 vídeo; 16 voz; 64 HU) |
| 24 | 4 | `responseHeaderSize` (128; 88 vídeo; 16 voz; 0 HU) |
| 28 | 4 | `action` (1 = petición del coche, 2 = mensaje del teléfono; 18 = parámetros de captura en dataType 1) |
| 32 | 32 | "mark": bytes 0x20…0x3F |
| 64 | … | cabecera de petición: `[64]` timestamp/tamaño, `[68]` **cmd**, `[72]` **value**, … |
| 192 | … | cabecera de respuesta: `[192]` **ret**, `[196]` … |

Comandos legados de dataType 0 (`a.java:1107-1164`, `message/c.java`):

| cmd | Dirección | Significado |
|---|---|---|
| 1 | tel → coche | AppStatus (value 1, `[76]` = SDK, `[80]` = 2) |
| 3 | coche → tel | Versión/info del coche (`[104]` CarType de 16 B, `[128]`/`[132]` ancho/alto). El teléfono responde con sus tamaños en `[192..208]` |
| 5 | ambas | LandMode |
| 10 | ambas | Heartbeat (el teléfono hace eco con ret=1) |
| 12 | coche → tel | PlayStatus=1 → iniciar espejo (por defecto 800×480) |
| 13 | tel → coche | Aviso de actualización (value 5) |
| 16 | coche → tel | MirrorSupport (respuesta con ret 1=sí, 2=no) |
| 17 | coche → tel | Petición de key frame |
| 19 | tel → coche | PhoneClickMirrOff |
| 20 | coche → tel | sspHome |
| 21 | tel → coche | SpeechStatus |

- **Táctil legado** (dataType 10, `a.java:1243-1262`, `message/g.java:103-128`): `[66]` int16 acción
  (`0x8000` = DOWN, `0x8001` = MOVE, `0x8002` y cualquier otro = UP), `[68]` int16 x, `[70]` int16 y. Un solo dedo.
- **Mensajes HU "A6A6"** (dataType 3): payload ASCII generado por `DataParser.createData`
  (`com/neusoft/parse/DataParser.java:20-49`):
  `"A6A6" + flowId(%02x) + totalLen(%08x) + len(appId)(%02x) + appId + len(logicId)(%02x) + logicId + n(%02x) +
  n×(len(%08x)+dato) + CRC16(%04x)`. El CRC es **CRC-16/XMODEM** (polinomio 0x1021, valor inicial 0;
  `CRC16.java:23-35`). Los datos se serializan con la librería nativa `libsspLib.so` (basada en cJSON; formato exacto
  desconocido). AppID `"QDRIVE_ASSISTANT"`; logic IDs en `qdrivelink/interconnection/h.java:7-49` (`HUINFO`,
  `BTADDRESS`, `BT_AUTO_CONNECTED`, `LEGAL_APP_ON`, `phoneready`, `SUBAPP_PKGMD5`, …). **En el protocolo 5A5A no se usa
  nada de esto.**

---

## 4. Catálogo de mensajes (protocolo 5A5A)

### 4.1 Coche → teléfono

| msgType | CMD / AppID·FunctionID | PARA / payload | Qué hace QDLink | Ref. |
|---|---|---|---|---|
| 0 | `CAR_INFO` | ver §9 (`Version`, `CarType`, `Platform`, `PlatformVersion`, `CarWidth`, `CarHeight`, `CarFactory`, `HUFactory`, `MirrorTypeReq`, `ProjectID`?, `CarUUID`?, `CarFeature`?) | Calcula los tamaños y responde `PHONE_INFO` + `UPDATE_NOTIFY`. Prepara `LegalApp` (`CarFeature.legal_app_watch`) y el modo de pantalla | `a.java:1951-1966`, `h0/c.java:166-200` |
| 0 | `VIDEO_SUP_REQ` | `{VideoFormat:int}` (se registra en el log, no se usa) | Comprueba si hay codificador H.264 y pide permiso de MediaProjection; al obtenerlo responde `VIDEO_SUP_RSP` | `a.java:1972-2002`, `1507-1519` |
| 0 | `VIDEO_ARGS` | `{Width, Height, EncodingType, FrameRate, BitRate, FrameInterval}` | Los guarda y responde `SPEECH_ARGS`. **Ojo**: el tamaño real del codificador sale de `CAR_INFO`, no de `Width/Height` | `a.java:1967-1971`, `h0/c.java:283-297` |
| 0 | `VIDEO_CTRL` | `{PlayStatus:int}` | Con `PlayStatus==1` arranca la captura con los `VIDEO_ARGS`; cualquier otro valor **no hace nada** | `a.java:2003-2016`, `MirrorActivity.java:226-247` |
| 0 | `KEY_FRAME_REQ` | — | Reenvía SPS/PPS antes del siguiente frame (`resetSpsFlag`). No pide un IDR al `MediaCodec` | `a.java:2048-2054`, `managers/a.java:627-631` |
| 0 | `LAND_MODE_REQ` | `{Orientation:int}` (1 = forzar horizontal, 2 = liberar) | Fuerza o libera la rotación con una ventana superpuesta (`RotateScreenService`) y responde `LAND_MODE_RSP` | `a.java:2025-2027`, `1557-1606`, `2917-2935` |
| 0 | `BT_ADDR` | `{BluetoothAddr:str, BluetoothStatus:int, NeedAutoConnect:int}` | Intenta enlazar/conectar A2DP con esa MAC y responde `BT_RESULT` | `a.java:2028-2039`, `DLinkNotifyL.java:1529-1541` |
| 0 | `PHONE_KEYS` | `{PhoneKeys:int}` 1=Home, 2=Atrás, 3=Recientes | `performGlobalAction` (§7.1) | `a.java:2055-2069` |
| 0 | `GO_IN_LINK_APP` | — | Trae al frente la `MainActivity` de QDLink (con 1 s de antirrebote) | `a.java:2017-2024`, `DLinkNotifyL.java:1992-2003` |
| 0 | `DISCONNECT_REQ` | — | **Callback vacío** (`DLinkNotifyL.l()`); QDLink nunca responde `DISCONNECT_RSP` | `a.java:2040-2047`, `DLinkNotifyL.java:1597-1599` |
| 0 | `HEARTBEAT` (y cualquier CMD desconocido) | — | Ignorado; solo refresca el reloj del watchdog | `a.java:2070-2071` |
| 2 | (táctil) | binario (§6) | Inyección táctil | `a.java:2074-2158` |
| 12 | (voz) | bytes | Callback vacío | `a.java:2167-2175` |
| 13 | `Music` / `PlayControl` | — | Alterna reproducir/pausa del **reproductor interno** de QDLink | `a.java:2275-2281`, `DLinkNotifyL.java:1275-1287` |
| 13 | `Music` / `PlayControlPlay` | — | Reproducir/reanudar | `DLinkNotifyL.java:1667-1677` |
| 13 | `Music` / `PlayControlPause` | — | Pausa | `DLinkNotifyL.java:1521-1527` |
| 13 | `Music` / `Prev` | — | Pista anterior | `DLinkNotifyL.java:1757-1761` |
| 13 | `Music` / `Next` | — | Pista siguiente | `DLinkNotifyL.java:1788-1792` |
| 13 | `Music` / `MuteControl` | — | Alterna el silencio de `STREAM_MUSIC` (`adjustStreamVolume(3, ±100)`) | `DLinkNotifyL.java:1601-1613` |
| 13 | `AudioSource` / `AudioSourceState` | `Para:{AudioSourceState:int}` | Callback vacío | `a.java:2197-2210` |
| 13 | `Global` / `DarkModeOn` | `Para:{DarkModeOn:int}` | Callback vacío | `a.java:2212-2226` |
| 99 | (personalizado) | bytes; subtipo en `reservedOne` | Callback vacío | `a.java:2159-2166` |

Constantes definidas pero **sin manejar** al recibir: `LOCK_SCREEN_REQ`, `UPDATE_PKG_REQ` (`h0/c.java:65,116`).

### 4.2 Teléfono → coche

| msgType | CMD / AppID·FunctionID | PARA | Cuándo | Ref. |
|---|---|---|---|---|
| (legado) | `!BIN` AppStatus, 512 B | dataType 0, action 2, cmd 1, value 1, SDK, 2 | Una vez al arrancar la sesión, **también en 5A5A** | `a.java:1344-1352`, `message/b.java` |
| 0 | `HEARTBEAT` | — (literal) | Cada 3 s (el primero a 1 s) | `a.java:1366-1451`, `1742-1764` |
| 0 | `PHONE_INFO` | `PhoneWidth`, `PhoneHeight`, `MirrorWidth`, `MirrorHeight`, `PhoneWidthInApp`, `PhoneHeightInApp`, `MirrorWidthInApp`, `MirrorHeightInApp`, `PhoneFeature:{PassistMobileNum}`, `PhoneUUID`, `PhoneName`, `Version`, `PhoneBrand`, `PhoneModel`, `Platform`=0, `PlatformVersion`=SDK, `MirrorTypeSupport`, `PhoneSystemTime`=0 | En respuesta a `CAR_INFO` | `a.java:914-959` |
| 0 | `UPDATE_NOTIFY` | `{UpdateStatus:5}` | Justo después de `PHONE_INFO` | `a.java:1530-1543` |
| 0 | `VIDEO_SUP_RSP` | `{VideoFormat:3, VideoSupport:1\|0}` | Tras `VIDEO_SUP_REQ`, cuando el usuario concede (1) o deniega (0) la captura. Con 0, QDLink **cierra la conexión** | `a.java:2390-2427` |
| 0 | `SPEECH_ARGS` | `{EncodingType:1, SampleRate:16000, ChannelConfig:1, AudioFormat:16}` | En respuesta a `VIDEO_ARGS` | `a.java:2472-2488` |
| 0 | `LAND_MODE_RSP` | `{Orientation, Authority:1\|2, StatusArg:0\|1\|2}` | En respuesta a `LAND_MODE_REQ` | `a.java:2937-2981` |
| 0 | `BT_RESULT` | `{Result:int}` (0/1/2/3, ver §8) | Resultado de la conexión BT pedida en `BT_ADDR` | `a.java:3077-3094` |
| 0 | `LOCK_SCREEN_STATUS` | `{LockScreenStatus:1\|2\|3}` | 1 = pantalla apagada, 2 = encendida, 3 = desbloqueada (`USER_PRESENT`) | `a.java:3096-3118`, `MainActivity.java:467-518` |
| 0 | `CAR_APP_BACKGROUND` | — (solo `{"CMD":…}`) | El usuario pulsa un botón de la página principal de QDLink (volver al sistema del coche) | `a.java:3037-3055`, `MainPageView.java:286-295,363-375` |
| 0 | `PHONE_INFO_CHANGE` | `PhoneWidth`, `PhoneHeight`, `MirrorWidth`, `MirrorHeight`, `*InApp` | Cuando cambia `smallestScreenWidthDp` (plegables) | `a.java:1484-1504`, `MyApplication.java:142-160` |
| 0 | `CAR_APP_FOREGROUND` | — | **Nunca se invoca** | `a.java:3057-3075` |
| 0 | `SPEECH_CTRL` | `{SpeechStatus:1\|0}` | **Nunca se invoca** | `a.java:2983-3035` |
| 0 | `DISCONNECT_RSP` | `{CanDisconnect:int}` | **Nunca se invoca** | `a.java:3120-3138` |
| 1 | vídeo | cabecera ext. + H.264 | Tras `VIDEO_CTRL{1}` | `a.java:1453-1481` |
| 13 | `Mirror` / `WhitelistAppOn` | `Para:{WhitelistAppOn:1\|0}` | **Cada segundo** si `CarFeature.legal_app_watch==1`: 1 si la app en primer plano está en la lista blanca | `a.java:2877-2895`, `DLinkNotifyL.java:486-535` |
| 13 | `Music` / `PlayState` | `Para:{PlayState:1\|0}` | Al reproducir (1) o pausar (0) en el reproductor interno de QDLink | `a.java:2897-2915`, `MusicPlayView.java:140-180` |
| 99 | (personalizado) | bytes | API `h0()` sin llamadas | `a.java:2685-2701` |

### 4.3 Ejemplos

Heartbeat (35 bytes):

```
35 41 35 41  00 00 00 23  00 00  00 00 00  01  00  00   {"CMD":"HEARTBEAT"}
"5A5A"       total=35     ext=0  type=0    fmt=1 rsv pad  (19 bytes)
```

`PHONE_INFO`. Valores ilustrativos: teléfono de 2400×1080 y `CarWidth/CarHeight` hipotéticos de 1920×1080. Ver §5.4.

```json
{"CMD":"PHONE_INFO","PARA":{
  "PhoneWidth":2400,"PhoneHeight":1080,
  "MirrorWidth":1920,"MirrorHeight":864,
  "PhoneWidthInApp":1920,"PhoneHeightInApp":1080,"MirrorWidthInApp":1920,"MirrorHeightInApp":1080,
  "PhoneFeature":{"PassistMobileNum":""},"PhoneUUID":"","PhoneName":"",
  "Version":"1.9.7","PhoneBrand":"samsung","PhoneModel":"SM-S911B",
  "Platform":0,"PlatformVersion":"34","MirrorTypeSupport":<eco de MirrorTypeReq>,"PhoneSystemTime":0}}
```

`PhoneUUID`, `PhoneName` y `PassistMobileNum` salen **vacíos**: se rellenan con `interconnection.b.s(...)`, que nunca se
llama (`interconnection/b.java:40-49`, `utils/e.java:83-89`). `PlatformVersion` es un **String** (`"34"`).

Comandos de música (msgType 13, coche → tel):

```json
{"AppID":"Music","FunctionID":"Next"}
{"AppID":"Music","FunctionID":"PlayState","Para":{"PlayState":1}}           // tel → coche
{"AppID":"Mirror","FunctionID":"WhitelistAppOn","Para":{"WhitelistAppOn":0}} // tel → coche
```

---

## 5. Handshake: del socket conectado al vídeo en pantalla

### 5.1 Secuencia (Wi-Fi, protocolo 5A5A)

```
Coche (HU)                                           Teléfono (QDLink)
   |  (UDP AddDevice con MirrorPort; ver doc. de transporte)  |
   |-------------------- TCP connect -------------------------> accept()            wificonnection/d.java:83
   |                                                          K0(): lector + timers  a.java:1690
   |<---------- "!BIN" AppStatus (512 B, legado) -------------|                     a.java:1698
   |<---------- 5A5A {"CMD":"HEARTBEAT"}  (t=1 s, cada 3 s) --|                     a.java:1761
   |-- 5A5A CAR_INFO ---------------------------------------->|  W := "5A5A"
   |                                                          |  LegalApp, screenMode, tamaños
   |<---------- PHONE_INFO -----------------------------------|                     a.java:954
   |<---------- UPDATE_NOTIFY {UpdateStatus:5} ---------------|                     a.java:1542
   |-- VIDEO_SUP_REQ {VideoFormat} -------------------------->|  ¿hay encoder? -> diálogo MediaProjection
   |                                                          |  (el usuario pulsa "Empezar ahora")
   |<---------- VIDEO_SUP_RSP {VideoFormat:3,VideoSupport:1} -|                     a.java:2402-2410
   |-- VIDEO_ARGS {Width,Height,EncodingType,FrameRate,..} -->|
   |<---------- SPEECH_ARGS {1,16000,1,16} -------------------|                     a.java:2487
   |-- VIDEO_CTRL {PlayStatus:1} ---------------------------->|  MirrorActivity.q() -> ScreenCaptureService
   |<========== msgType 1: SPS/PPS + frames H.264 ============|                     a.java:1453
   |-- KEY_FRAME_REQ (opcional) ----------------------------->|  reenvía SPS/PPS
   |-- LAND_MODE_REQ / BT_ADDR / GO_IN_LINK_APP (opcionales)->|  -> LAND_MODE_RSP / BT_RESULT
   |-- msgType 2 (táctil), PHONE_KEYS, msgType 13 Music ----->|
   |<---------- LOCK_SCREEN_STATUS, WhitelistAppOn, PlayState |
```

**[INFERENCIA]** El orden de los mensajes del coche se ha deducido de las dependencias del código. QDLink no lo impone,
salvo en dos casos: `VIDEO_CTRL` necesita un `VIDEO_ARGS` previo (si no, el NPE se captura y no pasa nada), y
`PHONE_INFO` necesita el binder de captura enlazado.

### 5.2 Precondiciones en el teléfono (QDLink)

1. `MirrorActivity` debe haber enlazado `ScreenCaptureService` (`MirrorActivity.java:56-76`). Sin él no arranca la
   sesión (`a.java:1004-1010`), y `PHONE_INFO` saldría sin tamaños.
2. `VIDEO_SUP_REQ` → `q0()` (`a.java:1507-1519`): si `ScreenCaptureService.N()` no consigue configurar un codificador
   `video/avc` de 800×480 (`ScreenCaptureService.java:722-745`), responde `VideoSupport:0` y cierra. Si lo consigue, pide
   la captura (`MirrorActivity.d()` → `y()` → `ScreenCaptureService.e.B()`, `ScreenCaptureService.java:187-203`), y
   `VIDEO_SUP_RSP` **solo** se envía tras el resultado del diálogo del sistema (`MirrorActivity.java:437-473`).
3. Antes de `VIDEO_SUP_RSP` se procesa la lista blanca (`Z()` llama a `r(LegalApp)`, `a.java:2391-2394`).

### 5.3 Qué necesita el coche y qué le da el teléfono

- **Resolución**: el coche impone su área con `CarWidth/CarHeight` en `CAR_INFO`; si vale 0×0 se usa 800×480
  (`a.java:919`). El teléfono devuelve en `PHONE_INFO` su resolución física (`PhoneWidth`/`PhoneHeight` = lado largo y
  corto **reales**, sin importar la orientación actual), el área del espejo de pantalla completa en horizontal
  (`MirrorWidth`/`MirrorHeight`) y el área "in-app" (= tamaño del coche).
- **Versión**: `PHONE_INFO.Version` = `versionName` de la app (`"1.9.7"`, `a.java:940`, `utils/a.java:107-114`). No se
  sabe si el coche la valida (§12).
- **Tipo de espejo**: `MirrorTypeSupport` es un **eco** de `CAR_INFO.MirrorTypeReq` (`a.java:945`).
- **Paquete / MD5 / firma**: en 5A5A **no se envía** ni nombre de paquete ni MD5. El MD5 solo aparece en la función
  legada de empujar un paquete al coche (`SUBAPP_PKGMD5`, `DLinkNotifyL.java:1438-1441`).
- **Activación en línea**: `POST https://api.qdrive.cc:8087/activation/international/verifyActivation` con
  `projectId`/`uuid` solo se hace con el sabor `BEIJINGLink` (`a.java:1953-1955`, `744-759`). Este APK es `QDriveLink`
  (`utils/e.java:18-21`; `E` no se reasigna nunca), así que **no hay activación en línea**.

### 5.4 Cálculo de tamaños (`ScreenCaptureService.H`, `ScreenCaptureService.java:466-661`)

Con un coche horizontal (W > H), sabor QDriveLink, `rp = lado largo / lado corto` del teléfono, `rc = W/H` del coche y
W y H redondeados a par:

| Magnitud | Fórmula | Va a |
|---|---|---|
| `inCarWidth × inCarHeight` | W × H (pares) | `*InApp` en `PHONE_INFO`; VirtualDisplay in-app |
| `outHor` (espejo horizontal) | si `rc > rp`: `(H·rp) × H`; si no: `W × (W/rp)` (pares) | `MirrorWidth/MirrorHeight` |
| `outVer` (espejo vertical) | `(H/rp) × H` | VirtualDisplay out-of-app en vertical |
| `tempScreen` | `lado largo del teléfono × (lado largo/rc)` | Escala de la UI in-app |

Ejemplos (teléfono de 2400×1080, `rp≈2.22`): coche de 1920×1080 → `Mirror` ≈ 1920×864, `outVer` ≈ 486×1080; coche de
2560×1440 → `Mirror` ≈ 2560×1152, `outVer` ≈ 648×1440.

> Nota: en el cálculo de `tempScreenLong` se usa `E.f()` antes de asignarlo (vale 0; se asigna en la línea 652), así que
> `tempScreenLong` es **siempre el lado largo del teléfono** (`ScreenCaptureService.java:542,548`).

### 5.5 Temporizadores

| Timer | Valor | Ref. |
|---|---|---|
| Heartbeat saliente | retardo 1 s, periodo 3 s (`K0 = 3000`) | `a.java:52, 1761` |
| Watchdog de lectura | cada 5 s: si pasan > 5 s sin leer nada **y** `W=="5A5A"`, desconecta | `a.java:53, 683-704, 1678` |
| Espera de `accept()` Wi-Fi | 20 s | `wificonnection/d.java:65` |
| Arranque de sesión | inmediato en Wi-Fi (`endTimer=7000 ≥ 6000`) | `a.java:1693-1701` |

---

## 6. Entrada táctil desde el coche

### 6.1 Formato (msgType 2, payloadFormat 0)

Parseo en `h0/e.java:204-225`. Todo big-endian; los floats se leen con `ByteBuffer.getFloat()` (BE por defecto,
`utils/i.java:54-56`).

| Off | Tam | Campo | Notas |
|---|---|---|---|
| 0 | 4 | `action` int32 | Acción global del gesto (ver §6.2) |
| 4 | 1 | `fingerCount` u8 | N |
| 5 + 10·k | 1 | `fingerId` | QDLink lo usa como índice 0…9 (`MouseAccessibilityService.java:188`) |
| 6 + 10·k | 1 | `fingerAction` | 1 = down, 2 = up, 3 = move |
| 7 + 10·k | 4 | `x` float32 | coordenada (§6.4) |
| 11 + 10·k | 4 | `y` float32 | |

Tamaño: `totalSize = 16 + 5 + 10·N`.

> *Fragmento del código decompilado de QDLink omitido en la versión pública (referencia: `h0/e.java:204-224`).*

Ejemplo (pulsación de un dedo en 960,540, DOWN):

```
35 41 35 41 00 00 00 1F 00 00 02 00 00 00 00 00 | 00 00 00 00 | 01 | 00 01 44 70 00 00 44 07 00 00
cabecera (total=31, type=2, fmt=0)                action=0      N=1  id=0 dn x=960.0     y=540.0
```

### 6.2 Códigos de acción

- **Acción por dedo** (`fingerAction`), explícita en `a.java:2093-2103`: `1→ACTION_DOWN(0)`, `2→ACTION_UP(1)`,
  `3→ACTION_MOVE(2)`, cualquier otro valor → `ACTION_DOWN`. Constantes equivalentes en
  `MouseAccessibilityService.java:229-235`.
- **Acción global** (`action` int32). **[INFERENCIA fuerte]** Usa los códigos de `MotionEvent` de Android:
  - `MouseAccessibilityService.k()` trata `0` como inicio del gesto y `1` como fin (`MouseAccessibilityService.java:177-185`);
  - la ruta multitáctil (sin uso) pasa el valor tal cual a `MotionEvent.obtain(…, eVar.a(), …)` (`managers/d.java:164`).

  Por tanto: 0 = DOWN, 1 = UP, 2 = MOVE, y probablemente 5/6 = POINTER_DOWN/UP, con el índice del puntero en los bits
  8-15. Falta confirmarlo con captura.

### 6.3 Rutas de inyección

La ruta depende del sabor, del enlace y del modo de proyección (`a.java:2074-2158`). `this.E` toma el valor de
`iAppCapture` del último frame de vídeo (`a.java:2452`): 1 = in-app (valor inicial), 2 = pantalla completa.

| Condición | Ruta | Multitáctil |
|---|---|---|
| USB (cualquier modo) | `ScreenCaptureService.e.l(MotionEvent)` → `managers.d.d()` → `ViewGroup.dispatchTouchEvent` sobre la `Presentation` (solo la UI de QDLink) | No: solo el dedo con `id==0` |
| Wi-Fi + in-app (`E==1`) | Igual que USB | No |
| Wi-Fi + pantalla completa (`E==2`) | `g0.a.b()` → `DLinkNotifyL.b()` → `MouseAccessibilityService.k()` → `dispatchGesture()` (API ≥ 26) | Sí, rudimentario (hasta 10 ids) |
| Sabor BEIJINGLink | Según el flag `isControl` (`utils/e.J`); no aplica | — |

Ruta in-app (un dedo):

> *Fragmento del código decompilado de QDLink omitido en la versión pública (referencia: `a.java:2142-2155`).*

Ruta pantalla completa (accesibilidad):

> *Fragmento del código decompilado de QDLink omitido en la versión pública (referencia: `DLinkNotifyL.java:409-421`).*

`StrokeDescription(path, 0, max(1, |distancia|), willContinue=false)` está en `MouseAccessibilityService.java:113-126`.
`GestureDescription` combina un trazo por cada id activo (`MouseAccessibilityService.java:81-97`).

Requisito: el servicio de accesibilidad debe declarar `canPerformGestures="true"`. QDLink lo hace en
`resources/res/xml-v22/mouse_accessibility_service_config.xml`; la variante `res/xml/` sin `-v22` no lo incluye. El
manifiesto declara `MouseAccessibilityService` con `BIND_ACCESSIBILITY_SERVICE` (`AndroidManifest.xml:173-185`), y el
usuario tiene que activarlo a mano.

### 6.4 Sistema de coordenadas y escalado

- **Pantalla completa (accesibilidad)**: `x,y` se usan **tal cual** como píxeles físicos de la pantalla del teléfono
  (`Path.moveTo(x,y)`, `MouseAccessibilityService.java:114-118`). `DLinkNotifyL.b()` ignora los factores `e.K` y `e.L`
  que le pasan (`DLinkNotifyL.java:1460-1466`). Por tanto **el coche debe enviar ya coordenadas del teléfono**.
- **In-app**: `managers/d.java:142-147` multiplica por `tempScreenLong/inCarWidth` (y `tempShort/inCarHeight`) con
  **división entera**. Eso indica que en este modo el coche envía coordenadas en el **espacio del VirtualDisplay** (=
  `CarWidth×CarHeight` en pares), y que QDLink intenta llevarlas al espacio sin escalar de su `ViewGroup`.
  - **Bug de QDLink**: como `tempScreenLong` es el lado largo del teléfono (§5.4), el factor da 1 si
    `carW ≤ teléfono < 2·carW` (el toque queda descolocado), y **0** si el coche es más ancho que el teléfono (todos los
    toques caen en x=0).
- **[INFERENCIA]** Para eso sirven los campos de `PHONE_INFO`: `PhoneWidth/PhoneHeight` (píxeles reales),
  `MirrorWidth/MirrorHeight` (área del espejo horizontal) y `*InApp` (tamaño del coche). Junto con
  `iAppCapture/dataWidth/dataHeight/ángulo/orientación` de cada frame (§3.4), el coche convierte un toque en su pantalla
  a (a) coordenadas del VirtualDisplay en modo in-app, o (b) coordenadas físicas del teléfono en modo pantalla completa.
  Así, para el modo pantalla completa en horizontal: `x_tel = x_vídeo · PhoneWidth/MirrorWidth`. **Hay que verificarlo
  con captura**, también en vertical y con el teléfono girado.
- Un `fingerId ≥ 10` provocaría un `ArrayIndexOutOfBoundsException` en `MouseAccessibilityService`
  (`new b[10]`, línea 33).

### 6.5 Limitaciones de la implementación de QDLink

1. **Sin pulsación larga** en pantalla completa: un dedo quieto se despacha como un trazo de 1 ms (la duración es la
   distancia en px).
2. **Arrastres troceados**: cada ~200 ms se despacha un gesto independiente (con el dedo "levantado" entre trozos) que
   va en línea recta del inicio al punto actual. Los puntos intermedios se pierden. Además, un trozo cuya duración (=
   píxeles) supere ~200 ms puede ser **cancelado** por el siguiente `dispatchGesture`. Desplazar listas o mover mapas
   resulta brusco.
3. **Latencia** (pantalla completa): un toque simple solo se inyecta al recibir el UP. Un arrastre añade hasta 200 ms
   más la duración sintética (1 ms por píxel). En la ruta in-app cada evento se inyecta al llegar.
4. **Multitáctil**: in-app, solo el dedo 0. En pantalla completa, los dedos se combinan en un único
   `GestureDescription` de trazos rectos (pellizco aproximado). La ruta multitáctil in-app
   (`ScreenCaptureService.e.m`, `managers/d.e`) existe pero **no se llama nunca**.
5. Funciona con la pantalla del teléfono encendida y requiere API ≥ 26 para la ruta de accesibilidad
   (`DLinkNotifyL.java:418`).
6. **USB**: siempre usa la ruta in-app, así que en pantalla completa por USB el táctil no llega a otras apps.

### 6.6 Recomendaciones para el cliente Kotlin

- Parsear el táctil con `readFully` y validar `totalSize == 16 + 5 + 10·N`.
- Para controlar todo el teléfono: `AccessibilityService` con `canPerformGestures`. Usar trazos **continuos**
  (`StrokeDescription(path, 0, dt, willContinue=true)` + `continueStroke()`, API 26) que se despachen cada ~16-33 ms.
  Así se soportan arrastre, pulsación larga (mantener el trazo vivo) y desplazamiento suave. Se puede aplicar nuestro
  propio escalado coche→teléfono si la captura confirma que el coche envía coordenadas del vídeo.
- Para controlar solo nuestra UI: `Presentation` en un `VirtualDisplay` + `dispatchTouchEvent`, con escalado en `float`
  y `MotionEvent.obtain(..., pointerCount, PointerProperties[], PointerCoords[], ...)` para el multitáctil real.

---

## 7. Teclas y mandos

### 7.1 `PHONE_KEYS` (msgType 0)

| `PhoneKeys` | Callback | Acción Android | Ref. |
|---|---|---|---|
| 1 | `g0.a.e(1)` → `DLinkNotifyL.e()` | `performGlobalAction(GLOBAL_ACTION_HOME=2)` | `a.java:2060-2061`, `DLinkNotifyL.java:1497-1504`, `MouseAccessibilityService.java:146-152` |
| 2 | `g0.a.c(2)` → `DLinkNotifyL.c()` | `performGlobalAction(GLOBAL_ACTION_BACK=1)` | `a.java:2062-2063`, `DLinkNotifyL.java:1468-1475`, `MouseAccessibilityService.java:138-144` |
| 3 | `g0.a.d(3)` → `DLinkNotifyL.d()` | `performGlobalAction(GLOBAL_ACTION_RECENTS=3)` | `a.java:2064-2065`, `DLinkNotifyL.java:1488-1495`, `MouseAccessibilityService.java:166-172` |

`MouseAccessibilityService` también tiene "Power" (`GLOBAL_ACTION_POWER_DIALOG=6`) y "Lock" (sin implementar), pero
ningún mensaje los activa (`MouseAccessibilityService.java:154-164`).

### 7.2 Mandos multimedia (msgType 13, `AppID:"Music"`)

| `FunctionID` | Acción en QDLink | Ref. |
|---|---|---|
| `PlayControl` | Alternar: si no empezó, `start`; si suena, `pause`; si está en pausa, `resume` | `DLinkNotifyL.java:1275-1287` |
| `PlayControlPlay` | `start`/`resume` | `DLinkNotifyL.java:1667-1677` |
| `PlayControlPause` | `pause` | `DLinkNotifyL.java:1521-1527` |
| `Prev` | Pista anterior (`MusicPlayService.e.k()`) | `DLinkNotifyL.java:1757-1761` |
| `Next` | Pista siguiente (`MusicPlayService.e.c()`) | `DLinkNotifyL.java:1788-1792` |
| `MuteControl` | Alterna silencio de `STREAM_MUSIC` | `DLinkNotifyL.java:1601-1613` |

Todos actúan **solo sobre el reproductor de música interno de QDLink**, no sobre Spotify ni otras apps. Para nuestro
cliente: traducirlos a `AudioManager.dispatchMediaKeyEvent(KEYCODE_MEDIA_PLAY_PAUSE / PLAY / PAUSE / PREVIOUS / NEXT)`
y `adjustStreamVolume(..., ADJUST_TOGGLE_MUTE, ...)`.

### 7.3 Beans `KeyEvent` / `KeyEventPara`: código muerto

`KeyEvent {ACTION, FINGER_COUNT, FINGERS:[KeyEventPara{FingerAction, x, y}]}` (`newmessage/bean/KeyEvent.java`,
`KeyEventPara.java`) y su parser `h0.c.h()` (`h0/c.java:221-242`) son un **modelo JSON del táctil que nadie usa**. El
táctil real es el binario de §6, que en los logs aparece como "DataType.KeyEvent:2". **No contienen códigos de teclas**:
no hay next/prev/volumen/voz en ellos.

### 7.4 Botones del volante por Bluetooth (fuera del protocolo)

`MyMediaButtonReceiver` (`music/musicreceiver/MyMediaButtonReceiver.java:30-81`) recibe `MEDIA_BUTTON` de Android, es
decir, AVRCP por BT: 126 = play, 127 = pause, 87 = next, 88 = previous. Solo controla el reproductor interno. Las teclas
de volumen y de llamada del coche van por BT (A2DP/HFP), **no por el socket QDLink**.

---

## 8. Otros canales

| Canal | Estado en 1.9.7 | Detalle / Ref. |
|---|---|---|
| Metadatos de música (título, artista, carátula, posición) | **No existe** | Solo `Music/PlayState {0,1}` (`a.java:2897-2915`) |
| Estado de llamada | **No existe** | Las llamadas van por BT HFP del coche. El protocolo solo ayuda a conectar BT (`BT_ADDR` → `BT_RESULT`) |
| Voz | **Esbozado, sin uso** | `SPEECH_ARGS` anuncia PCM a 16 kHz, mono, 16 bit, `EncodingType:1` (`a.java:2472-2488`). `SPEECH_CTRL {SpeechStatus 1/0}` existe pero nadie lo llama (`a.java:2983-3035`). La voz entrante (msgType 12) va a un callback vacío (`DLinkNotifyL.java:1737-1739`) |
| Notificaciones del teléfono | **No existe** | `NotificationService` es solo la notificación del servicio en primer plano (`NotificationService.java:39-54`) |
| Navegación (giro a giro) | **No existe** | El mapa (`MapNaviView`) es UI de QDLink que se ve por el espejo |
| Pantalla bloqueada | Sí | `LOCK_SCREEN_STATUS` 1 = `SCREEN_OFF`, 2 = `SCREEN_ON`, 3 = `USER_PRESENT` (`MainActivity.java:467-518`, `receiver/a.java:34-46`) |
| Lista blanca / apps legales | Sí | Si `CAR_INFO.CarFeature.legal_app_watch==1`, cada 1 s se compara la app en primer plano (UsageStats) con `legalapplist` de la BD + QDLink y se envía `Mirror/WhitelistAppOn {1\|0}` sin deduplicar. **[INFERENCIA]** El coche oculta el espejo con 0 al circular. Lista del C10: Google Maps, Waze, HERE, Last.fm, Amap, Baidu Map (`linkmanager.db3`, ids 167/168) |
| Modo horizontal | Sí | `LAND_MODE_REQ {Orientation}` (1 = fuerza horizontal con una superposición `screenOrientation=LANDSCAPE`; 2 = "ScreenOn", libera), en `RotateScreenService.java:104-131`. Respuesta `LAND_MODE_RSP {Orientation, Authority: 1 = hay permiso de superposición / 2 = no, StatusArg: 0 normal / 1 la app en primer plano es un launcher / 2 falta el permiso de uso}` (`a.java:1557-1606, 2937-2981`) |
| Modo oscuro | Recibido, ignorado | `Global/DarkModeOn {DarkModeOn}` |
| Fuente de audio | Recibido, ignorado | `AudioSource/AudioSourceState {AudioSourceState}` |
| BT | Sí | `BT_ADDR {BluetoothAddr, BluetoothStatus, NeedAutoConnect}`. Si `NeedAutoConnect==0` solo comprueba si A2DP ya está conectado a esa MAC; si no, enciende BT, enlaza y conecta A2DP por reflexión. `BT_RESULT.Result`: **[INFERENCIA]** 2 = conectado a la MAC del coche, 0 = no conectado/timeout de 3 s, 1 = sin A2DP o fallo de enlace, 3 = desconectado (`DLinkNotifyL.java:150-356, 663-814, 1529-1541`) |
| Volver al sistema del coche | Sí | `CAR_APP_BACKGROUND` desde la página principal de QDLink |
| Teléfono plegable | Sí | `PHONE_INFO_CHANGE` |
| Datos personalizados | API sin uso | msgType 99 en ambos sentidos |

---

## 9. `CarInfoPara`: qué nos dice el coche

Parseo en `h0/c.java:166-200`. Las claves se leen **en este orden** con `getString`/`getInt`. Si falta una clave
obligatoria, salta una `JSONException` y los campos siguientes quedan a 0/null (salvo `ProjectID`/`CarUUID`, que pasan a
`""`).

| Clave JSON | Tipo | Obligatoria | Uso en QDLink | C10 (BD) |
|---|---|---|---|---|
| `Version` | String | sí | Solo se guarda | ? |
| `CarType` | String | sí | Modo de pantalla (`H0`: lista `28B,297,298,299,29A,29B,2C7` → `screenType=1`) y búsqueda en BD (`typeid`) | `"2D4"` (C10_i_L) / `"2D5"` (C10_i_R) |
| `Platform` | int | sí | No se usa | ? |
| `PlatformVersion` | String | sí | No se usa | ? |
| `CarWidth` | int | sí | Tamaños de vídeo/espejo/in-app (§5.4) | ? (captura) |
| `CarHeight` | int | sí | Ídem | ? (captura) |
| `CarFactory` | String | sí | Búsqueda en BD (`factoryid`) | `"018"` (LEAP) |
| `HUFactory` | String | sí | Búsqueda en BD (`huid`) | `"119"` |
| `MirrorTypeReq` | int | sí | Eco en `PHONE_INFO.MirrorTypeSupport` | ? |
| `ProjectID` | String | no | Activación (solo sabor BEIJINGLink) | ? |
| `CarUUID` | String | no | Ídem | ? |
| `CarFeature` | objeto JSON | no | `legal_app_watch` (1 = vigilar la lista blanca) (`a.java:1044-1067`) | ? |

- **No hay** DPI, tamaño físico ni nombre de modelo legible: solo códigos de fabricante, tipo y HU. El "Car_DPI" que
  aparece en los logs de captura es un valor por defecto (1), no algo que envíe el coche
  (`ScreenCaptureService.java:756-797`, `c0/a.java`).
- La BD (`resources/res/raw/linkmanager.db3`, consulta `factoryid=? AND typeid=? AND huid=?` en
  `QD_DBUtil.java:120`) tiene para LEAP: `T03_i (2B6)`, `C11_i (2C0)`, `C10_i_L (2D4)`, `C10_i_R (2D5)`,
  `T03_i_L (2D6)` y `T03_i_R (2D7)`, todos con `huid 119`. El C10 no aparece en ninguna lista especial de
  `qdrivelink/c.java:286-288` ni en la lista de `screenType=1`, así que QDLink lo trata como un coche "genérico".

---

## 10. Esqueleto Kotlin (referencia)

```kotlin
import java.io.DataInputStream
import java.nio.ByteBuffer
import org.json.JSONObject

object Qd {
    const val T_CMD = 0; const val T_VIDEO = 1; const val T_TOUCH = 2
    const val T_SPEECH = 12; const val T_APP = 13; const val T_CUSTOM = 99
    const val PF_BIN = 0; const val PF_JSON = 1; const val PF_VIDEO = 2
}

data class QdHeader(val totalSize: Int, val extLen: Int, val msgType: Int,
                    val payloadFormat: Int, val reserved: Int = 0) {
    fun encode(): ByteArray = ByteBuffer.allocate(16).apply {          // big-endian por defecto
        put(byteArrayOf(0x35, 0x41, 0x35, 0x41))                        // "5A5A"
        putInt(totalSize); putShort(extLen.toShort())
        put(msgType.toByte()); put(0); put(0)                           // bytes 11-12 desconocidos (0)
        put(payloadFormat.toByte()); put(reserved.toByte()); put(0)
    }.array()
    companion object {
        fun decode(h: ByteArray): QdHeader {
            require(h[0] == 0x35.toByte() && h[1] == 0x41.toByte() && h[2] == 0x35.toByte() && h[3] == 0x41.toByte())
            val bb = ByteBuffer.wrap(h)
            return QdHeader(bb.getInt(4), bb.getShort(8).toInt() and 0xFFFF,
                            h[10].toInt() and 0xFF, h[13].toInt() and 0xFF, h[14].toInt() and 0xFF)
        }
    }
}

fun cmdMsg(cmd: String, para: JSONObject? = null): ByteArray {
    val o = JSONObject().put("CMD", cmd); if (para != null) o.put("PARA", para)       // "PARA" en mayúsculas
    val p = o.toString().toByteArray(Charsets.UTF_8)
    return QdHeader(16 + p.size, 0, Qd.T_CMD, Qd.PF_JSON).encode() + p
}

fun appMsg(appId: String, fn: String, para: JSONObject?): ByteArray {
    val o = JSONObject().put("AppID", appId).put("FunctionID", fn); if (para != null) o.put("Para", para) // "Para"
    val p = o.toString().toByteArray(Charsets.UTF_8)
    return QdHeader(16 + p.size, 0, Qd.T_APP, Qd.PF_JSON).encode() + p
}

data class Finger(val id: Int, val action: Int, val x: Float, val y: Float)   // action: 1 down, 2 up, 3 move
data class Touch(val action: Int, val fingers: List<Finger>)                   // action: ¿MotionEvent? (0/1/2…)

fun parseTouch(p: ByteArray): Touch {
    val bb = ByteBuffer.wrap(p)
    val action = bb.int
    val n = bb.get().toInt() and 0xFF
    return Touch(action, List(n) { Finger(bb.get().toInt() and 0xFF, bb.get().toInt() and 0xFF, bb.float, bb.float) })
}

/** Bucle lector robusto (QDLink usa un único read(); aquí readFully). */
fun readLoop(input: DataInputStream, onMsg: (QdHeader, ByteArray) -> Unit, onLegacy: (ByteArray) -> Unit) {
    val hdr = ByteArray(16)
    while (true) {
        input.readFully(hdr)
        when (String(hdr, 0, 4, Charsets.US_ASCII)) {
            "5A5A" -> {
                val h = QdHeader.decode(hdr)
                val body = ByteArray(h.totalSize - 16).also { input.readFully(it) }
                onMsg(h, body.copyOfRange(h.extLen, body.size))
            }
            "!BIN" -> {
                val blk = ByteArray(512); hdr.copyInto(blk)
                input.readFully(blk, 16, 496); onLegacy(blk)   // ojo: los mensajes legados > 512 traen más bloques
            }
            else -> error("desincronizado")
        }
    }
}

// Respuestas mínimas (valores como QDLink):
val updateNotify = cmdMsg("UPDATE_NOTIFY", JSONObject().put("UpdateStatus", 5))
val videoSupRsp  = cmdMsg("VIDEO_SUP_RSP", JSONObject().put("VideoFormat", 3).put("VideoSupport", 1))
val speechArgs   = cmdMsg("SPEECH_ARGS", JSONObject().put("EncodingType", 1).put("SampleRate", 16000)
                                                   .put("ChannelConfig", 1).put("AudioFormat", 16))
val heartbeat    = cmdMsg("HEARTBEAT")   // QDLink: cada 3 s
```

---

## 11. Cómo capturar en vivo (para cerrar las incógnitas)

1. **Logs internos de QDLink** (lo más rápido, sin root):
   1. En QDLink, abrir *Ajustes* y tocar **6 veces en menos de 2,2 s** la zona invisible de 60×60 dp de la esquina
      **inferior izquierda** (`R.id.log_view`; `resources/res/layout/layout_setting.xml:296-304`, `SettingView.java:576-586,648-649,678`).
      Se abre `UploadActivity`.
   2. Activar el interruptor "log开关" (`key_save_log_switch_inter`; `UploadActivity.java:49-68`). Así se habilitan
      `utils.g`, `LogUtils` y los logs de captura (`MainActivity.java:941-949`).
   3. `adb logcat` mostrará, por ejemplo:
      - `parsingNewData parsingNewData msgType:… strData:{…}` (cada mensaje entrante con su JSON, `a.java:1942`);
      - `sendVersionNewProtocol PhoneInfo strData:` (`a.java:947`);
      - `parsingNewData keyEvent x:…;y…:action:…,fingerCount:…` (`a.java:2082`);
      - `printTouchEvent …` con id y acción de cada dedo (`DLinkNotifyL.java:895-917`);
      - `calcScreenFromCar …` con todos los tamaños (`ScreenCaptureService.java:659`).
   4. El interruptor "h264" (`key_save_h264_data_inter`) vuelca el vídeo; interesa al documento de vídeo.
2. **Tráfico TCP** en el puerto `MirrorPort`: PCAPdroid en el teléfono, o `tcpdump` con root o en un AP intermedio. Con
   las tablas de §3 y §6 los paquetes se decodifican directamente; el JSON va en claro.
3. **Pruebas de táctil**: tocar las cuatro esquinas y el centro del área proyectada en modo in-app y en pantalla
   completa (vertical y horizontal), probar arrastre, pulsación larga y pellizco, y anotar `action`, `fingerId`,
   `fingerAction`, x e y.

---

## 12. Incógnitas (requieren captura en vivo)

1. **Protocolo del C10**: confirmar que el coche usa `5A5A` y no `!BIN`, y si tolera o necesita el **AppStatus `!BIN`
   de 512 B** que QDLink envía siempre al arrancar.
2. **Valores reales de `CAR_INFO`** del C10: `CarWidth/CarHeight` (¿2560×1440, 1920×1080 o un área recortada?),
   `CarType` (`2D4`/`2D5`), `CarFactory` (`018`), `HUFactory` (`119`), `Version`, `Platform`, `PlatformVersion`,
   `MirrorTypeReq` (significado de sus valores), `CarFeature` (¿`legal_app_watch`?), `ProjectID` y `CarUUID`.
3. **Orden y momento** de los mensajes del coche (`CAR_INFO`, `VIDEO_SUP_REQ`, `VIDEO_ARGS`, `VIDEO_CTRL`, `BT_ADDR`,
   `LAND_MODE_REQ`, `GO_IN_LINK_APP`), y tiempos de espera del coche (¿cuánto espera `PHONE_INFO` o `VIDEO_SUP_RSP`
   mientras el usuario acepta el permiso de captura?).
4. **Sistema de coordenadas del táctil**: ¿píxeles del teléfono (como supone la ruta de accesibilidad) o píxeles del
   vídeo/VirtualDisplay (como supone la ruta in-app)? ¿Depende de `iAppCapture`, de la orientación o del ángulo? ¿Cómo
   gestiona el coche las franjas negras?
5. **Acción global** del táctil: confirmar los códigos `MotionEvent` (0/1/2, ¿5/6 con índice de puntero?), el rango de
   `fingerId`, la frecuencia de envío de MOVE y si el coche admite multitáctil real.
6. **Bytes 11, 12 y 15** de la cabecera 5A5A (QDLink los deja a 0 e ignora): ¿versión, flags, secuencia? ¿El coche
   envía valores distintos de 0?
7. **Heartbeat del coche**: formato (¿`{"CMD":"HEARTBEAT"}`?) e intervalo. ¿El coche corta si no recibe el nuestro cada
   3 s?
8. Significado de `UPDATE_NOTIFY.UpdateStatus=5` y si el coche lo exige.
9. Valores de `VIDEO_SUP_REQ.VideoFormat` y de `VIDEO_CTRL.PlayStatus` distintos de 1 (¿0 = pausa o parada? QDLink los
   ignora), y uso real de `VIDEO_ARGS.Width/Height` frente a `CAR_INFO`.
10. `LAND_MODE_REQ.Orientation`: confirmar 1 = horizontal y 2 = liberar; qué hace el coche con `Authority` y
    `StatusArg`.
11. Semántica exacta de `BT_RESULT.Result` (0/1/2/3).
12. `DISCONNECT_REQ`: ¿espera el coche `DISCONNECT_RSP {CanDisconnect}`? QDLink no responde nunca.
13. ¿Envía el coche `LOCK_SCREEN_REQ`, `UPDATE_PKG_REQ` u otros `CMD` no contemplados? QDLink los ignora sin error;
    aparecen en el log `parsingNewData cmd:`.
14. **Voz**: formato de los datos msgType 12 (¿PCM de 16 kHz, mono, 16 bit según `SPEECH_ARGS`?), cuándo los envía el
    coche y para qué sirve `SPEECH_CTRL`.
15. Valores de `AudioSourceState` y `DarkModeOn`; contenido y subtipos de msgType 99.
16. ¿Hay `PhoneKeys` además de 1, 2 y 3 (volumen, voz, power)?
17. Efecto de `WhitelistAppOn=0` en el coche (¿pantalla negra o aviso?) y si depende de la velocidad.
18. ¿El coche valida `PHONE_INFO.Version` (por ejemplo, una versión mínima) u otros campos (`Platform`, `PhoneBrand`)?
