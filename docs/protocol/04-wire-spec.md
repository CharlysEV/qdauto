# 04 · Especificación de cable (lado teléfono, Wi-Fi) — QDLink 1.9.7

Este documento es la referencia contra la que se programa la implementación Kotlin (`:core`, `:carsim`, `:app`).
Describe **todo lo que viaja por la red** cuando nuestro programa sustituye a QDLink en el papel de **teléfono**:
datagramas UDP, framing TCP, cada mensaje de control, vídeo y táctil, con su temporización.

- Cada dato lleva cita `archivo:línea` al código decompilado. Las afirmaciones aritméticas se han comprobado además
  sobre el **bytecode** (`dexdump`), ver §0.3 y el Anexo A.
- **[INFERENCIA]** marca lo que se deduce pero no se lee literalmente en el código.
- **Recomendación** marca decisiones para nuestro cliente.
- Los documentos 00–03 fueron el punto de partida; donde este documento los contradice, manda este (ver Anexo C).

---

## 0. Convenciones

### 0.1 Rutas

Todas relativas a `decomp\qdlink\`.

| Prefijo | Ruta real |
|---|---|
| `LC/` | `sources/com/neusoft/interconnection/linkconnection/` (`LC/a.java` = motor del protocolo) |
| `WF/` | `sources/com/neusoft/interconnection/wificonnection/` |
| `IU/` | `sources/com/neusoft/interconnection/utils/` |
| `IC/` | `sources/com/neusoft/interconnection/` (resto) |
| `QL/` | `sources/com/neusoft/qdrivelink/` |
| `SC/` | `sources/com/neu/ssp/mirror/screencap/` |
| `h0/`, `f0/`, `g0/`, `b0/`, `c0/`, `d0/` | `sources/h0/` … (paquetes de primer nivel) |
| `RES/` | `resources/` |
| `dex:` | `dexdump -d` (build-tools 36.0.0) sobre `classes.dex` de `apk/com.neusoft.qdrivelink/base.apk`; se cita método y offset en unidades de 16 bits |

### 0.2 Representación

- **Enteros: big-endian** siempre (`IU/i.java:58-68` lee int32, `:91-93` lee int16, `:213-219` escribe int32, `:221-225`
  escribe int16).
- **Floats: IEEE-754 de 32 bits big-endian** (`ByteBuffer.wrap(...).getFloat()`, orden por defecto BE; `IU/i.java:54-56`).
- **Texto**: el charset por defecto de Android (UTF-8) en `new String(byte[])` y `String.getBytes()`. Todas las cadenas del
  protocolo son ASCII.
- Volcados hex: `offset: bytes |ASCII|`.

### 0.3 Aviso sobre la decompilación (importante)

El código "restructure" de jadx **pierde conversiones `int→float`** en varios sitios y mete algún `break` falso:

| Lo que muestra jadx | Lo que hace el bytecode | Prueba |
|---|---|---|
| `float f5 = i3 / i4;` (`SC/service/ScreenCaptureService.java:531`) → parecería división entera | `int-to-float v14,v14; int-to-float v15,v15; div-float/2addr v14,v15` → **división en coma flotante** | `dex: ScreenCaptureService.H(II) @0119-011b` |
| `this.f8946v / this.f8942o` en el táctil in-app (`SC/managers/d.java:86-87, 144`) | `int-to-float` + `div-float` | `dex: managers.d.<init> @002f-003f`, `managers.d.d @0010-0024` |
| `case "CAR_INFO": … carType = getCarType(); break; } catch … H0(); B0(); z0();` (`LC/a.java:1957-1965`) → parecería que nunca se responde | `getCarType()` y luego **siempre** `H0`, `B0` (PHONE_INFO) y `z0` (UPDATE_NOTIFY) | jadx `-m fallback` de `LC/a.U` |

Por tanto **no existe el "bug de división entera"** que describían 02 y 03 (§8.6 y §9.3 dan los valores reales).

---

## 1. Descubrimiento UDP

### 1.1 Sockets y puertos

| Concepto | Valor | Referencia |
|---|---|---|
| Socket de escucha (teléfono) | `new DatagramSocket(null)` → `setReuseAddress(true)` → `bind(new InetSocketAddress(18463))` (comodín 0.0.0.0) | `WF/a.java:486-489`, `:30` |
| Buffer de recepción | 1024 B; un datagrama mayor se trunca | `WF/a.java:490` |
| `SO_BROADCAST`, `MulticastLock` | no se tocan (no hay ninguna referencia a `MulticastLock`) | `WF/a.java:486-490` |
| Socket de envío del ACK | **el mismo** socket ligado a 18463 (guardado en LinkConfig) → **puerto origen 18463** | `WF/a.java:487`; `IU/e.java:163-165, 219-221`; `WF/d.java:80` |
| Destino del ACK | IP origen del `Connect_Broadcast` recibido, **puerto 18464** | `WF/a.java:499`, `:31`, `:190` (`:137` en P2P) |
| Ciclo de vida | se abre en `MainActivity.onCreate` y no se cierra nunca | ver 01 §3.1 |

El teléfono **nunca emite** descubrimiento: el único `send()` UDP es el del ACK (`WF/d.java:80`).

### 1.2 Sobre común de los datagramas

Todo el datagrama es texto ASCII. N = longitud del tipo, M = longitud del JSON.

| Offset | Long. | Campo | Codificación |
|---|---|---|---|
| 0 | 22 | magic `QDrive_SSPLink_UDP_MSG` | ASCII (`WF/a.java:32`) |
| 22 | 4 | longitud **total del datagrama** = 32 + N + M (cuenta el magic y este mismo campo) | hex ASCII en **MAYÚSCULAS**, relleno con `0` a la izquierda hasta 4 dígitos (`IU/i.java:179-185`) |
| 26 | 2 | N | hex ASCII mayúsculas, 2 dígitos (`IU/i.java:187-193`) |
| 28 | N | tipo: `Connect_Broadcast` (N=17=`11`) o `Broadcast_ACK` (N=13=`0D`) | ASCII (`WF/a.java:33-34`) |
| 28+N | 4 | M | hex ASCII mayúsculas, 4 dígitos (`IU/i.java:179-185`) |
| 32+N | M | JSON compacto | UTF-8 |

- Sin terminador, sin CRC.
- Las longitudes se calculan con `String.length()` (caracteres UTF-16) y se envía `getBytes()` (`WF/a.java:187-189`).
  Coinciden porque todo es ASCII.

### 1.3 `Connect_Broadcast` (coche → teléfono, destino :18463)

Qué hace QDLink con cada datagrama (`WF/a.java:491-531`):

1. `str = new String(data, 0, len)` (UTF-8) y `carIp = packet.getAddress().getHostAddress()` (`:498-499`).
2. **Sin comprobar antes el magic**: `new JSONObject(str.substring(49))` (`:501`).
   - 49 = 22 + 4 + 2 + 17 + 4. Es decir, QDLink **exige** campos de longitud de anchura fija (4/2/4) y el tipo
     exacto `Connect_Broadcast`. **[INFERENCIA]** Como QDLink funciona con el C10, el coche usa este mismo sobre.
   - El `JSONObject(String)` de Android ignora lo que haya detrás del objeto (conocimiento de `org.json` de Android), así
     que se toleran bytes de relleno al final.
3. `getString("DeviceUUID")` y `getString("DeviceName")`, ambos **obligatorios** (`:502-503`). `getString` acepta
   también números (los convierte a texto).
4. Cualquier excepción (datagrama ajeno, JSON roto, clave ausente) pone el estado global de conexión a **-1**, incluso
   en mitad de una sesión TCP (`:526-528`).
5. Si `LinkConfig.uuidName` (vacío salvo en Wi-Fi Direct) **contiene** `DeviceName` → camino automático: elige puerto,
   prepara el ACK y pone el estado a 1 sin que el usuario pulse nada (`:505-516`). **[INFERENCIA]** Con un `DeviceName`
   vacío esto se dispara también en modo hotspot (`"".contains("")`).
6. Si no, `x()` (`:560-594`): exige que `str` contenga el magic **y** `Connect_Broadcast`, vuelve a leer el JSON en
   `substring(22+17+10 = 49)` y añade a la lista `{uuid, name, ip, tipo 1}`, sin duplicados por (`uuid`, `ip`).
7. Un timer publica la lista a la UI cada 2 s (primera vez a los 2 s) y **la vacía** (`WF/a.java:80-97`, `:335`).
   **[INFERENCIA]** El coche emite cada ≤ 2 s.

QDLink **no lee ni valida**: los tres campos de longitud, el uso de mayúsculas/minúsculas en el hex, ni ninguna otra
clave del JSON.

Ejemplo **hipotético** construido con el mismo sobre (101 bytes; el JSON real del C10 es una incógnita):

```
QDrive_SSPLink_UDP_MSG006511Connect_Broadcast0034{"DeviceUUID":"0123456789abcdef","DeviceName":"C10"}

0000: 51 44 72 69 76 65 5f 53  53 50 4c 69 6e 6b 5f 55  |QDrive_SSPLink_U|
0010: 44 50 5f 4d 53 47 30 30  36 35 31 31 43 6f 6e 6e  |DP_MSG006511Conn|
0020: 65 63 74 5f 42 72 6f 61  64 63 61 73 74 30 30 33  |ect_Broadcast003|
0030: 34 7b 22 44 65 76 69 63  65 55 55 49 44 22 3a 22  |4{"DeviceUUID":"|
0040: 30 31 32 33 34 35 36 37  38 39 61 62 63 64 65 66  |0123456789abcdef|
0050: 22 2c 22 44 65 76 69 63  65 4e 61 6d 65 22 3a 22  |","DeviceName":"|
0060: 43 31 30 22 7d                                    |C10"}|
```

### 1.4 `Broadcast_ACK` (teléfono → `IP_coche:18464`, origen :18463)

Construcción exacta (`WF/a.java:171-190`, camino hotspot; idéntica en P2P `:118-137`):

```
json  = new JSONObject() con put() en este orden (org.json conserva el orden de inserción):
        ControlPort  (int)    0
        MirrorPort   (int)    P             ← puerto TCP elegido
        AudioPort    (int)    0
        OS           (int)    0             ← 0 = Android
        DeviceName   (String) ""            ← LinkConfig.d(), nunca se asigna (IU/e.java:86,167; IC/b.java:40-49 sin llamadas)
        DeviceUUID   (String) ""            ← LinkConfig.e()  (IU/e.java:83,171)
        DeviceFeature (objeto) {"PassistMobileNum": ""}   ← LinkConfig.j() (IU/e.java:89,187)
total = 45 + json.length()                                         (:187)
bytes = ("QDrive_SSPLink_UDP_MSG" + hex4(total) + "0D" + "Broadcast_ACK" + hex4(json.length()) + json).getBytes()
```

`P` siempre tiene 5 cifras (rango 10001-65535, §2.1), así que **el ACK de QDLink mide siempre 174 bytes**
(`00AE`) con un JSON de 129 (`0081`). Ejemplo con `P = 34567`:

```
QDrive_SSPLink_UDP_MSG00AE0DBroadcast_ACK0081{"ControlPort":0,"MirrorPort":34567,"AudioPort":0,"OS":0,"DeviceName":"","DeviceUUID":"","DeviceFeature":{"PassistMobileNum":""}}

0000: 51 44 72 69 76 65 5f 53  53 50 4c 69 6e 6b 5f 55  |QDrive_SSPLink_U|
0010: 44 50 5f 4d 53 47 30 30  41 45 30 44 42 72 6f 61  |DP_MSG00AE0DBroa|
0020: 64 63 61 73 74 5f 41 43  4b 30 30 38 31 7b 22 43  |dcast_ACK0081{"C|
0030: 6f 6e 74 72 6f 6c 50 6f  72 74 22 3a 30 2c 22 4d  |ontrolPort":0,"M|
0040: 69 72 72 6f 72 50 6f 72  74 22 3a 33 34 35 36 37  |irrorPort":34567|
0050: 2c 22 41 75 64 69 6f 50  6f 72 74 22 3a 30 2c 22  |,"AudioPort":0,"|
0060: 4f 53 22 3a 30 2c 22 44  65 76 69 63 65 4e 61 6d  |OS":0,"DeviceNam|
0070: 65 22 3a 22 22 2c 22 44  65 76 69 63 65 55 55 49  |e":"","DeviceUUI|
0080: 44 22 3a 22 22 2c 22 44  65 76 69 63 65 46 65 61  |D":"","DeviceFea|
0090: 74 75 72 65 22 3a 7b 22  50 61 73 73 69 73 74 4d  |ture":{"PassistM|
00a0: 6f 62 69 6c 65 4e 75 6d  22 3a 22 22 7d 7d        |obileNum":""}}|
```

Cuándo y cuántas veces:

| Paso | Detalle | Ref. |
|---|---|---|
| Preparación | Al pulsar el usuario un coche (hotspot) se elige el puerto y se **guarda** el `DatagramPacket`; en P2P, al llegar un broadcast cuyo nombre coincide | `WF/a.java:154-204`, `:99-152`; `IU/e.java:247-249` |
| Envío | **Una sola vez**, en el hilo servidor TCP, **justo después** de `new ServerSocket(P)` y antes de `accept()` | `WF/d.java:78-83` |
| Reintentos | Ninguno. Si se pierde, salta el timeout de 20 s y el usuario tiene que volver a pulsar (nuevo puerto) | `WF/d.java:49-69` |

**Recomendación**:
- Enviar el ACK desde el mismo socket ligado a 18463. No sabemos si el coche comprueba el puerto origen.
- Abrir el `ServerSocket` **antes** de enviar el ACK.
- Reenvío opcional y configurable: si no hay `accept()` en 3 s, repetir el **mismo** ACK cada 2 s hasta 20 s
  (QDLink no lo hace). Registrar cualquier conexión TCP extra que llegue.
- Parser tolerante del broadcast: localizar el magic, leer las longitudes en hex sin distinguir mayúsculas y, si no
  cuadran, usar el offset 49 y después el primer `{`. Guardar siempre el datagrama crudo en el log.

---

## 2. TCP

### 2.1 Roles, puerto y espera

| Concepto | Valor | Ref. |
|---|---|---|
| Quién escucha | **El teléfono** (`new ServerSocket(P)`, todas las interfaces, backlog por defecto). El coche es el cliente | `WF/d.java:78`, `:83` |
| Puerto `P` | `new Random().nextInt(55535) + 10001` → 10001-65535. Se "comprueba" abriendo un **UDP** en ese puerto (que se queda abierto) | `WF/a.java:249-264` |
| Espera de `accept()` | Timer de **20 s**. Si vence: estado -1 y teardown del motor (`LC/a.d()`), pero el `ServerSocket` sigue abierto y el hilo bloqueado en `accept()`. Si el coche conecta tarde, la conexión se acepta (opciones de §2.2 y `onConnected`), pero `d()` ya borró el flag del binder (`f9305q`), así que `K0` no se ejecuta: ni AppStatus ni heartbeats, sesión colgada | `WF/d.java:28-43`, `:65`, `:83-105`; `LC/a.java:1004-1010`, `:2513-2546` |
| Conexiones aceptadas | **una** (`f9774f = false` tras el primer `accept`). El `ServerSocket` **no se cierra** después: sigue escuchando (backlog 50) toda la sesión y solo se cierra en `L()` o en `IC/d.A()`. Otro `connect` del coche a `P` completa el saludo TCP en el kernel, pero nadie lo acepta, lee ni escribe (ni RST ni datos) | `WF/d.java:76-119`; `LC/a.java:1725-1731`; `IC/d.java:101-107` |
| IP a la que conecta el coche | **[INFERENCIA]** la IP origen del ACK; el ACK no lleva IP | — |

### 2.2 Opciones del socket aceptado

| Opción | Valor | Ref. |
|---|---|---|
| `TCP_NODELAY` | `true` | `WF/d.java:88` |
| `SO_SNDBUF` | 4 194 304 (petición; el kernel la limita) | `WF/d.java:90` |
| `SO_RCVBUF` | 6 291 456 (puesto **después** de conectar) | `WF/d.java:92` |
| `SO_KEEPALIVE` | `true` (timers del SO) | `WF/d.java:94` |
| `SO_TIMEOUT` | **no se usa**: lecturas bloqueantes sin límite | (no aparece en `WF/`, `LC/`) |
| Streams | `getInputStream()`/`getOutputStream()` directos, sin buffering | `WF/d.java:96-98` |

### 2.3 Arranque de la sesión tras `accept()`

1. Estado = 1 y callback `onConnected` (`WF/d.java:102-104` → `LC/a.java:2429-2439`).
2. Cuando también está enlazado el servicio de captura, la cadena `LC/a.H()` → `f0/b.y()` → `f0/b.C()` →
   `DLinkNotifyL.y()` → `IC/b.o()` → `f0/b.Y()` acaba en **`LC/a.K0(true, {"28B","297","298","299","29A","29B","2C7"})`**
   (`LC/a.java:1004-1010`; `f0/b.java:521-530`, `:53-66`; `QL/interconnection/DLinkNotifyL.java:1985-1990`;
   `IC/b.java:138-143`; `f0/b.java:236-241`).
3. En Wi-Fi `K0` arranca **en el acto** (usa 7000 ms ≥ umbral 6000; `LC/a.java:1693-1696`; `IU/e.java:41`) y, en este
   orden (`LC/a.java:1697-1700`):
   1. `f()`: hilo lector, prioridad 10 (`:2594-2603`);
   2. `f0()`: **AppStatus `!BIN`** de 512 B desde un hilo nuevo (`:1344-1352`, §4.2);
   3. `M0()`: heartbeat, primero a 1 s y luego cada 3 s (`:1742-1764`, §5);
   4. `J0()`: watchdog de recepción, primero a 1 s y luego cada 5 s (`:1659-1681`, §5).

El teléfono **no espera** a que el coche hable para mandar AppStatus y heartbeats.

### 2.4 Cómo decide QDLink qué protocolo habla el coche

No hay negociación. **Cada** mensaje entrante se clasifica por sus 4 primeros bytes (`LC/a.java:328-363`, `:2499-2511`):

| 4 primeros bytes | Acción | Ref. |
|---|---|---|
| `!BIN` (`21 42 49 4E`) | lee 496 bytes más (bloque de 512), `W = "!BIN"`, `W()` (legado) | `LC/a.java:342-356` |
| `5A5A` (`35 41 35 41`) | `W = "5A5A"`, lee `totalSize-16` bytes, `U()` | `LC/a.java:357-360`, `:2376-2388` |
| cualquier otra cosa | los 16 bytes leídos **se descartan sin aviso**; `W` no cambia | (no hay rama `else`) |

`W` arranca en `"DEFULT"` (`LC/a.java:187`; `IU/a.java:36`) y vale el formato del **último** mensaje recibido. Las
constantes `"JSON"` y `"!XML"` (`IU/a.java:27, 30`) no se usan nunca.

Efectos de `W`:

| Depende de `W` | Siempre `5A5A` (sea cual sea `W`) | Siempre `!BIN` |
|---|---|---|
| `VIDEO_SUP_RSP` (si no, `MirrorSupport` legado) `LC/a.java:2396-2419` | `HEARTBEAT` (`:1366-1451`) | AppStatus (`:1344-1352`) |
| `LAND_MODE_RSP` (si no, LandMode legado) `:2939-2949` | `PHONE_INFO`, `UPDATE_NOTIFY`, `SPEECH_ARGS` (solo los dispara un mensaje 5A5A) | mensajes HU "A6A6" (`:2860-2875`) |
| `SPEECH_CTRL`, `CAR_APP_BACKGROUND/FOREGROUND` (si no, legado) `:2983-3075` | `PHONE_INFO_CHANGE` (`:1484-1504`) | |
| `BT_RESULT`, `LOCK_SCREEN_STATUS`, `DISCONNECT_RSP`: **solo** si `W=="5A5A"`; si no, no se envían `:3077-3138` | `WhitelistAppOn`, `PlayState` (`:2877-2915`) | |
| Vídeo: `l0()` (5A5A) si `W=="5A5A"`; si no, cabecera legada de 512 B `D()` (`:2455-2462`) | | |
| Watchdog: solo actúa si `W=="5A5A"` (`:690`) | | |

**[INFERENCIA fuerte]** El C10 habla `5A5A` por Wi-Fi: con `!BIN`, QDLink copia una cabecera de 512 B en un buffer
que solo tiene 48 B reservados (`LC/a.java:961-983` frente a `SC/managers/a.java:815-816`) y el vídeo no funcionaría.
Además, cualquier `!BIN` que mande el coche cambia `W` y estropea la sesión; por tanto el C10 no los manda.

---

## 3. Trama "5A5A"

### 3.1 Cabecera común de 16 bytes

Serialización `h0/a.java:35-56`, parseo `h0/a.java:114-122`. Nombres de campo sacados de los logs (`LC/a.java:1933`,
`:1942`).

| Byte | Tipo | Campo | Al enviar (QDLink) | Al recibir (QDLink) |
|---|---|---|---|---|
| 0-3 | ASCII | magic `"5A5A"` = `35 41 35 41` (texto, **no** `5A 5A`) | fijo (`h0/a.java:33`) | se compara como `String` (`LC/a.java:2499-2511`) |
| 4-7 | u32 BE | `totalSize` = 16 + longitud de la cabecera extendida + payload | ver cada mensaje | `i.c()` (`h0/a.java:115`) |
| 8-9 | u16 BE | `extendHeaderTotalSize` | 0 en control/app; **32** en vídeo | int16 con signo (`h0/a.java:116`) |
| 10 | u8 | `msgType` | §3.3 | `h0/a.java:117` |
| 11 | u8 | sin nombre (setter `n()`) | siempre 0 | se lee y se ignora |
| 12 | u8 | sin nombre (setter `i()`) | siempre 0 | se lee y se ignora |
| 13 | u8 | `payLoadFormat` | 1 = JSON, 2 = vídeo | 0 = binario (táctil), 1 = JSON |
| 14 | u8 | `reservedOne` | 0 (subtipo solo en msgType 99, sin uso) | solo se pasa al callback de msgType 99 |
| 15 | u8 | relleno | 0 | no se lee |

### 3.2 Cabecera extendida

- Ocupa `extendHeaderTotalSize` bytes justo detrás de los 16 de la cabecera común. Al recibir, QDLink la **salta**
  sin interpretarla: `payload = body[extLen:]` (`LC/a.java:1935-1941`).
- Solo el vídeo la usa (32 bytes, §8.1). Control, táctil y app llevan `extLen = 0`.

### 3.3 `msgType` y `payLoadFormat` en cada sentido

| msgType | Nombre en logs | Sentido | payLoadFormat | Contenido | Ref. |
|---|---|---|---|---|---|
| 0 | comando | ambos | 1 (si el coche manda otro valor, **se ignora**) | JSON `{"CMD":…,"PARA":{…}}` | `LC/a.java:1944-1947` |
| 1 | vídeo | tel → coche | 2 | ext 32 B + H.264 Annex-B | `LC/a.java:1468-1478` |
| 2 | `KeyEvent` (táctil) | coche → tel | 0 (si no, **se ignora**) | binario, §9 | `LC/a.java:2074-2078` |
| 12 | `speechStatus` | coche → tel | cualquiera | bytes de voz → callback vacío | `LC/a.java:2167-2175`; `QL/interconnection/DLinkNotifyL.java:1737-1739` |
| 13 | `appData` | ambos | 1 (exigido al recibir) | JSON `{"AppID":…,"FunctionID":…,"Para":{…}}` | `LC/a.java:2176-2318` |
| 99 | `customStatus` | ambos | 1 al enviar; cualquiera al recibir | opaco; `reservedOne` = subtipo. Entrada → callback vacío; salida `h0()` sin llamadas | `LC/a.java:2159-2166`, `:2685-2701`; `DLinkNotifyL.java:1513-1515` |

### 3.4 Cómo lee QDLink el flujo TCP (Wi-Fi)

Bucle del hilo lector (`LC/a.java:314-442`, rama Wi-Fi `:328-363`):

1. Cabecera: **una sola** llamada `InputStream.read(buf16, 0, 16)` sobre un buffer de 16 B que se **reutiliza**
   (`:325`, `:330`; `S0()` en `:1858-1902`). Si devuelve menos de 16 bytes, el código sigue igualmente, con bytes viejos
   en el resto del buffer.
2. Fallos de lectura:
   - `read` devuelve -1 (EOF): `S = 0`, espera 50 ms y vuelve a intentarlo (`:332-338`). QDLink **no cierra** el socket
     por un EOF: sigue mandando heartbeats a la conexión medio cerrada hasta que falla una escritura (`IOException` →
     `L()`) o corta el watchdog (§5.3).
   - `IOException` al leer la cabecera (`S0`): pone a `null` **los dos** streams globales (`ConnConstant.wifiInputStream`
     y `wifiOutputStream`) y devuelve -1, **sin** llamar a `L()` (`:1876-1883`; `dex: a.S0 @0055-0058`). Desde ese
     momento todas las escrituras del teléfono se saltan sin aviso (heartbeat `:1424-1425`, `h()` `:2659-2660`, `j()`
     `:2837-2838`): el teléfono se queda **mudo sin cerrar**. El socket solo lo cierra el watchdog, y solo si
     `W == "5A5A"`; si no, no se cierra nunca.
   - `IOException` al leer el cuerpo (`e()`): `L()` en el acto (`:2565-2570`).
3. Cuerpo 5A5A: `new byte[totalSize-16]` y **una sola** `read()` (`Y()`, `:2376-2388`; `e()`, `:2548-2592`). Si llega
   menos, se procesa el array a medio llenar (el resto son ceros) y el flujo queda **desincronizado**: los bytes que
   faltaban se leen después como "cabeceras" y se descartan de 16 en 16 salvo que casualmente empiecen por un magic.
4. No hay tamaño máximo. Con `totalSize < 16` salta `NegativeArraySizeException` en `Y()` (`:2381`), que no se
   captura en el hilo lector. Con `extLen > totalSize-16` salta `NegativeArraySizeException` al reservar el payload
   (`:1938`); esa sí se captura en `U()` (`LC/a.java:2319-2321`) y el mensaje se descarta.
5. Los errores de JSON **no** llegan al `catch` de `U()`. Cada parser de `h0/c` captura su propia `JSONException` y
   devuelve un bean con valores por defecto o a medio rellenar. Además, `h0/c.f()`/`j()` devuelven `""` si el JSON no se
   puede parsear o falta `CMD`/`AppID`/`FunctionID` (`h0/c.java:142-317`). Así que un mensaje de control mal formado o
   incompleto **no se descarta**: QDLink actúa y **responde** con valores por defecto:
   - `CAR_INFO` sin `PARA` o sin alguna clave: envía igualmente `PHONE_INFO` (con `CarWidth`/`CarHeight` a 0, los
     tamaños salen de 800×480 y los `*InApp` a 0) y `UPDATE_NOTIFY` (`h0/c.java:194-198`; `LC/a.java:1951-1966`);
   - `VIDEO_ARGS` incompleto: guarda `Y` con lo que haya podido leer y el resto a 0, y responde `SPEECH_ARGS`
     (`h0/c.java:283-297`; `LC/a.java:1967-1971`);
   - `LAND_MODE_REQ` sin `Orientation`: responde `LAND_MODE_RSP{Orientation:0}` (`h0/c.java:244-252`);
   - `BT_ADDR` sin `NeedAutoConnect`: `btauto = 1` (`h0/c.java:152-164`);
   - `VIDEO_SUP_REQ` con un `PARA` incorrecto: se procesa igual (`VideoFormat` solo va al log);
   - JSON imposible de parsear o sin `CMD`: `CMD = ""` → `default` del `switch`, se ignora sin excepción.

   Solo las excepciones que no son de JSON llegan a `LC/a.java:2319-2321` y descartan el mensaje:
   - NPE en `VIDEO_CTRL{1}` sin `VIDEO_ARGS` previo (`:2012`);
   - NPE en `BT_ADDR` sin `BluetoothAddr` (`toUpperCase()` sobre `null`, `:2033`): no hay `BT_RESULT`;
   - táctil con N = 0 o payload corto (`:2081-2082`, §9.1);
   - `extLen > totalSize-16` (punto 4).
6. Cada `read()` que vuelve, aunque sea parcial, actualiza la marca `S` del watchdog (`:1872`, `:2564`). Tras un EOF o
   un fallo de lectura, `S` queda a 0 (`:333`, `:346`, `:1862`, `:2556`).

**[INFERENCIA]** Como el lector de QDLink no tolera mensajes partidos y aun así funciona, el coche escribe cada mensaje
de una vez y los mensajes son pequeños (control y táctil).

### 3.5 Cómo escribe QDLink

- Cada mensaje se construye entero (cabecera + payload) y se escribe con **un solo** `OutputStream.write(byte[])` +
  `flush()` (`LC/a.java:2666-2667`, `:2844-2845`, `:1432-1433`).
- En Wi-Fi **no hay relleno** (`d0()` `LC/a.java:1299-1304`). El relleno a múltiplos de 512 B es solo de USB
  (`:1306-1315`).
- Un único `ReentrantLock` (`f9275b`) serializa vídeo, heartbeat y control (`:2454`, `:1368`, `:2656`). El heartbeat
  espera a que termine el `write()` de vídeo en curso.
- Casi todos los JSON salen desde un `new Thread` propio, así que el orden entre dos mensajes enviados casi a la vez
  **no está garantizado** (por ejemplo `PHONE_INFO` frente a `UPDATE_NOTIFY`, `LC/a.java:954`, `:1542`).
  `SPEECH_ARGS`, `BT_RESULT` y `LOCK_SCREEN_STATUS` se envían de forma síncrona (`d0()`).

### 3.6 Reglas para nuestro lector y escritor

**Recomendación**:
- Lector: `readFully` de 16 bytes y validar el magic.
  - Si es `5A5A`: comprobar `16 ≤ totalSize ≤ 8 MiB` y `0 ≤ extLen ≤ totalSize-16`, y después `readFully(totalSize-16)`.
  - Si es `!BIN`: `readFully(496)`. Después, igual que QDLink, leer datos extra solo en dos casos (QDLink los lee en
    bucle hasta completarlos, a diferencia del cuerpo 5A5A):
    - `dataType` 3 (HU "A6A6") **y** `action` (u32 en `[28]`) == 1: leer `totalsize − headersize` (u32 en `[8]` menos
      u32 en `[12]`). QDLink se queda con los primeros `dataSize` (u32 en `[64]`) (`LC/a.java:1214-1242`). Con
      `action` 2 no lee nada más.
    - `dataType` 12 (voz): leer siempre `totalsize − 512`, sea cual sea `action`. `dataSize` está en `[288]`
      (`LC/a.java:1263-1288`; `LC/message/m.java:102-119`).
    - Ningún otro `dataType` (0, 1, 10…) lee más allá del bloque de 512 B, aunque `totalsize` sea mayor.
  - Si el magic es desconocido: registrar y **resincronizar** buscando `5A5A`/`!BIN` byte a byte.
- Parser de control tolerante, como QDLink (§3.4 punto 5): una clave ausente o de tipo incorrecto vale 0/`""`/`null` y
  se responde igualmente. Registrar siempre el JSON crudo.
- Escritor: un mensaje = un `write()` desde **un solo** hilo escritor con cola. Prioridad al control y descarte de vídeo
  si la cola crece.

---

## 4. Bloques legados `!BIN`

### 4.1 Cabecera común de 512 bytes

Nombres sacados del `toString()` de `LC/message/f.java:96-98`. Todos los campos son u32 BE.

| Off | Campo | Notas |
|---|---|---|
| 0 | format `"!BIN"` | `21 42 49 4E` |
| 4 | dataType | 0 comando, 1 pantalla, 3 HU "A6A6", 10 táctil, 12 voz, 99 personalizado (`LC/message/e.java:7-31`) |
| 8 | totalsize | 512 en los bloques sin datos extra (todos los que manda QDLink en una sesión 5A5A). Al recibir no se valida: solo se usa para leer los datos extra de `dataType` 3 (con `action` 1) y 12 (§3.6) |
| 12 | headersize | 512 |
| 16 | commonHeaderSize | 64 |
| 20 | requestHeaderSize | depende del tipo |
| 24 | responseHeaderSize | depende del tipo |
| 28 | action | 1 = petición del coche, 2 = mensaje del teléfono |
| 32-63 | mark | bytes `0x20…0x3F` |
| 64 | TimeStamp / dataSize | |
| 68 | cmd | (`LC/message/c.java:7-64`: 1…20) |
| 72 | value | |
| 192 | ret | cabecera de respuesta |

### 4.2 AppStatus (teléfono → coche, una vez al empezar la sesión)

Construido en `LC/message/b.java:56-83` (orden de campos comprobado en `dex: message.b.a() @0029-005e`) y enviado en
crudo, 512 B, con `h()` (`LC/a.java:1344-1352`; `IU/b.java:31-35`):

| Off | Valor | Campo |
|---|---|---|
| 0 | `!BIN` | format |
| 4 | 0 | dataType (comando) |
| 8 | 512 | totalsize |
| 12 | 512 | headersize |
| 16 | 64 | commonHeaderSize |
| 20 | 128 | requestHeaderSize |
| 24 | 128 | responseHeaderSize |
| 28 | 2 | action (teléfono → coche) |
| 32-63 | `20 21 … 3F` | mark |
| 64 | 0 | TimeStamp |
| 68 | 1 | cmd = AppStatus |
| 72 | 1 | value |
| 76 | `Build.VERSION.SDK_INT` | versionAndroid (36 = `0x24` en Android 16) |
| 80 | 2 | integrator_server (`IU/b.java:15`; el setter `f()` no tiene llamadas) |
| 192 | 0 | ret |
| resto | 0 | |

Volcado completo con `SDK_INT = 36` (de `0x060` a `0x1FF` todo son ceros):

```
0000: 21 42 49 4e 00 00 00 00  00 00 02 00 00 00 02 00  |!BIN............|
0010: 00 00 00 40 00 00 00 80  00 00 00 80 00 00 00 02  |...@............|
0020: 20 21 22 23 24 25 26 27  28 29 2a 2b 2c 2d 2e 2f  | !"#$%&'()*+,-./|
0030: 30 31 32 33 34 35 36 37  38 39 3a 3b 3c 3d 3e 3f  |0123456789:;<=>?|
0040: 00 00 00 00 00 00 00 01  00 00 00 01 00 00 00 24  |...............$|
0050: 00 00 00 02 00 00 00 00  00 00 00 00 00 00 00 00  |................|
0060: 00 … 00                                            (hasta 0x1FF)
```

### 4.3 Otros `!BIN` posibles en una sesión 5A5A

| Caso | Qué hace QDLink | Ref. |
|---|---|---|
| El coche manda un heartbeat legado (dataType 0, action 1, cmd 10, value 1) | Lo **devuelve**: mismos campos `[4..72]` y mark (`action` sigue siendo 1), `ret`=1 en `[192]` y el resto a 0 | `LC/a.java:1122-1129`; `LC/message/f.java:56-94` |
| El coche manda otros `!BIN` (cmd 3 versión, 5 LandMode, 12 PlayStatus, 16 MirrorSupport, 17 keyframe, 20 sspHome; dataType 1, 3, 10, 12) | Flujo legado completo (`W()`) | `LC/a.java:1101-1290` |
| Mensajes HU "A6A6" (dataType 3, action 2) | Solo como respuesta a mensajes HU del coche o en flujos legados (`phoneready`, `LEGAL_APP_ON`, `BT_AUTO_CONNECTED`…). En una sesión 5A5A pura **no se envían** | `LC/a.java:2860-2875`; `DLinkNotifyL.java:1303-1441`, `:1742-1755`, `:1794-1957` |

En una sesión 5A5A el **único** `!BIN` que manda QDLink es el AppStatus.

---

## 5. Heartbeat y vigilancia

### 5.1 Teléfono → coche

- Literal `{"CMD":"HEARTBEAT"}` (`LC/a.java:208`), con `totalSize = 16 + 19 = 35` (`:1371-1378`).
- Se envía **siempre en 5A5A**, sin mirar `W` (`:1418-1435`).

```
0000: 35 41 35 41 00 00 00 23  00 00 00 00 00 01 00 00  |5A5A...#........|
0010: 7b 22 43 4d 44 22 3a 22  48 45 41 52 54 42 45 41  |{"CMD":"HEARTBEA|
0020: 54 22 7d                                          |T"}|
```

- Temporización: `java.util.Timer.schedule(task, 1000, 3000)`, es decir, primero a 1 s del arranque de la sesión y
  después cada 3 s de **retardo fijo** (`LC/a.java:52`, `:1761`, tarea `:672-681`). Tiene que tomar el lock de
  escritura, así que puede retrasarse por un frame de vídeo.
- Si la escritura falla (`IOException`), se desmonta la sesión Wi-Fi con `L()` (`:1436-1443`).

### 5.2 Coche → teléfono

- No hay `case "HEARTBEAT"`: cae en `default` y **no se responde** (`LC/a.java:2070-2071`). Su único efecto es
  refrescar `S`.

### 5.3 Watchdog de recepción de QDLink

- `Timer.schedule(r, 1000, 5000)` (`LC/a.java:1678`).
- Regla (`:689-703`, confirmada en `dex: a$r.run @000a-001e`): si `ahora − S > 5000 ms` **y** `W.equals("5A5A")`,
  ejecuta `L()` (corta).
- `S` es la hora de la última `read()` que volvió (`:1872`, `:2564`). Un EOF o un fallo de lectura la ponen a 0
  (`:333`, `:346`, `:1862`, `:2556`).
- Por tanto el coche tiene que mandar **algo** como mucho cada 5 s. Cuándo corta:
  - enlace mudo (la `read()` se queda bloqueada): entre 5 y 10 s después del último dato;
  - tras un EOF o un fallo de lectura (`S = 0`): en el siguiente tic, entre 0 y 5 s después.
- Mientras `W` no sea `"5A5A"` (p. ej. `"DEFULT"`: el coche aún no ha hablado), el watchdog **no actúa**. Si en ese
  estado falla la lectura de una cabecera (§3.4 punto 2), el socket no se cierra nunca.

**[INFERENCIA]** El coche manda heartbeats periódicos; si no, QDLink cortaría a los 5-10 s. No se sabe su formato
(probablemente el mismo literal), ni su periodo, ni el timeout del lado del coche.

**Recomendación**:
- Heartbeat cada 3 s a partir de 1 s, como QDLink.
- Watchdog configurable: por defecto, aviso a los 5 s y corte a los 15 s. Registrar los intervalos reales entre
  mensajes del coche.

---

## 6. Mensajes de control

### 6.1 Formato JSON y serializador de beans

Escritura con `h0.c.q(cmd, bean)` (msgType 0, `h0/c.java:348-373`) y `h0.c.p(appId, functionId, bean)` (msgType 13,
`h0/c.java:319-346`):

1. `bean.getClass().getDeclaredFields()` → **todas** las variables declaradas. Ninguno de los beans enviados tiene
   campos estáticos ni renombrados por jadx.
2. Para cada campo, clave = **nombre exacto del campo** (mayúsculas incluidas). Valor = resultado de invocar por
   reflexión `"get" + mayúscula(primera letra) + resto` (`h0/c.java:127-133`). Si no existe el getter o lanza, el valor
   es `null`.
3. Esos pares van a un `HashMap` (PARA); luego `HashMap{"CMD": cmd, "PARA": mapa}` (o `{"AppID","FunctionID","Para"}`),
   y se serializa con `new JSONObject(map).toString()` de Android:
   - `int`/`long` → número JSON sin decimales (`PhoneSystemTime` es `long` → `0`).
   - `String` → cadena; `""` se queda en `""`; **`null` → `null`** JSON.
   - Campo `Object` que contiene un `JSONObject` (`PhoneFeature`) → objeto anidado con su orden de inserción.
   - JSON compacto, sin espacios. `"` `\` y **`/`** se escapan (`\/`), igual que los caracteres de control.
4. `bean == null` → solo `{"CMD":"…"}`, sin `PARA` (`h0/c.java:349-353`). En msgType 13 → `{"FunctionID":…,"AppID":…}`.
5. Si algo lanza dentro del `try`, se devuelve `""` y se enviaría un mensaje con payload vacío (`h0/c.java:370-372`).
6. Nombre del objeto de parámetros: **`PARA`** en msgType 0 y **`Para`** en msgType 13 (`h0/c.java:50-53`).

**Orden de las claves.** No es el de inserción: es el orden de iteración de `java.util.HashMap`, que es
**determinista** para un runtime dado. En Android ≥ 7 (HashMap de OpenJDK) y con `getDeclaredFields()` en orden de
dex (alfabético), sale lo siguiente (reproducido con el mismo `HashMap`; las colisiones de cubeta se resuelven por orden
de inserción):

| Mensaje | Orden real de claves en el JSON |
|---|---|
| exterior msgType 0 | **`PARA` primero, luego `CMD`**: `{"PARA":{…},"CMD":"…"}` |
| exterior msgType 13 | `FunctionID`, `AppID`, `Para` |
| `PHONE_INFO` | `PhoneName`, `PlatformVersion`, `PhoneModel`, `Platform`, `PhoneSystemTime`, `PhoneHeightInApp`, `MirrorWidthInApp`, `PhoneWidthInApp`, `MirrorHeightInApp`, `MirrorTypeSupport`, `PhoneFeature`, `PhoneUUID`, `Version`, `MirrorHeight`, `MirrorWidth`, `PhoneBrand`, `PhoneHeight`, `PhoneWidth` |
| `PHONE_INFO_CHANGE` | `MirrorHeight`, `MirrorWidth`, `PhoneHeightInApp`, `PhoneHeight`, `MirrorWidthInApp`, `PhoneWidth`, `PhoneWidthInApp`, `MirrorHeightInApp` |
| `VIDEO_SUP_RSP` | `VideoFormat`, `VideoSupport` |
| `SPEECH_ARGS` | `SampleRate`, `ChannelConfig`, `EncodingType`, `AudioFormat` |
| `LAND_MODE_RSP` | `Authority`, `StatusArg`, `Orientation` |

**[INFERENCIA fuerte]** El coche **no puede depender** del orden: el `HashMap` de Android 5-6 (minSdk de QDLink = 21)
itera en otro orden. **Recomendación**: generar el mismo orden que QDLink en Android ≥ 7 (tabla anterior) para ser
idénticos byte a byte, pero sin darle importancia.

Lectura de lo que manda el coche: `new JSONObject(str)`, que distingue mayúsculas. `getString("CMD")`
(`h0/c.java:202-209`) y después `getJSONObject("PARA")` con `getInt`/`getString` por clave. En msgType 13:
`getString("AppID")`, `getString("FunctionID")` y `getJSONObject("Para")` (`h0/c.java:254-261`, `:142-150`, `:211-219`).
`getInt` también acepta cadenas numéricas y `getString` también acepta números. Cada parser captura su propia
`JSONException`: lo que falte queda a 0/`""`/`null` y QDLink responde igualmente (§3.4 punto 5).

### 6.2 Teléfono → coche (todos los mensajes que QDLink puede generar)

Cabecera de todos: `35 41 35 41 | totalSize | 00 00 | msgType | 00 00 | 01 | 00 00`. JSON tal y como sale de QDLink
(valores de ejemplo cuando dependen del entorno):

| msgType | CMD / AppID·FunctionID | JSON exacto (bytes) → `totalSize` | Cuándo | Ref. |
|---|---|---|---|---|
| — (`!BIN`) | AppStatus | 512 B, §4.2 | una vez, al arrancar la sesión | `LC/a.java:1344-1352` |
| 0 | `HEARTBEAT` | `{"CMD":"HEARTBEAT"}` (19) → 35 | 1 s y luego cada 3 s | `LC/a.java:1366-1451` |
| 0 | `PHONE_INFO` | §6.4 (412 en el ejemplo) → 428 | al recibir `CAR_INFO` (cada vez) | `LC/a.java:914-959` |
| 0 | `UPDATE_NOTIFY` | `{"PARA":{"UpdateStatus":5},"CMD":"UPDATE_NOTIFY"}` (49) → 65 | justo después de `PHONE_INFO`, en otro hilo | `LC/a.java:1530-1543` |
| 0 | `VIDEO_SUP_RSP` | `{"PARA":{"VideoFormat":3,"VideoSupport":1},"CMD":"VIDEO_SUP_RSP"}` (65) → 81. `VideoSupport:0` si no hay encoder o el usuario rechaza la captura, y después **teardown**. Si la rechaza, el socket se cierra casi a la vez; si no hay encoder, se queda abierto (§7.5) | tras `VIDEO_SUP_REQ`, cuando el usuario responde al diálogo de MediaProjection | `LC/a.java:2390-2427`, `:302-311`; `IC/MirrorActivity.java:437-473` |
| 0 | `SPEECH_ARGS` | `{"PARA":{"SampleRate":16000,"ChannelConfig":1,"EncodingType":1,"AudioFormat":16},"CMD":"SPEECH_ARGS"}` (101) → 117 | al recibir `VIDEO_ARGS` (cada vez), síncrono | `LC/a.java:2472-2488` |
| 0 | `LAND_MODE_RSP` | `{"PARA":{"Authority":1,"StatusArg":0,"Orientation":1},"CMD":"LAND_MODE_RSP"}` (76) → 92 | al recibir `LAND_MODE_REQ` (§6.7) | `LC/a.java:2937-2981` |
| 0 | `BT_RESULT` | `{"PARA":{"Result":2},"CMD":"BT_RESULT"}` (39) → 55 | resultado de la conexión A2DP pedida en `BT_ADDR` (§6.8). Puede haber varios por `BT_ADDR`, y después llegan no solicitados (3 cada vez que se desconecta un A2DP). Solo si `W=="5A5A"` | `LC/a.java:3077-3094` |
| 0 | `LOCK_SCREEN_STATUS` | `{"PARA":{"LockScreenStatus":1},"CMD":"LOCK_SCREEN_STATUS"}` (58) → 74 | `SCREEN_OFF`→1, `SCREEN_ON`→2, `USER_PRESENT`→3; solo si `W=="5A5A"` | `LC/a.java:3096-3118`; `QL/MainActivity.java:467-519`; `QL/receiver/a.java:30-48` |
| 0 | `CAR_APP_BACKGROUND` | `{"CMD":"CAR_APP_BACKGROUND"}` (28) → 44 | el usuario toca el icono "salir" (`iv_exit`) de la UI de QDLink proyectada | `LC/a.java:3037-3055`; `QL/mainpage/MainPageView.java:363-384`, `:558`, `:570` |
| 0 | `PHONE_INFO_CHANGE` | `{"PARA":{"MirrorHeight":886,"MirrorWidth":1920,"PhoneHeightInApp":1080,"PhoneHeight":1080,"MirrorWidthInApp":1920,"PhoneWidth":2340,"PhoneWidthInApp":1920,"MirrorHeightInApp":1080},"CMD":"PHONE_INFO_CHANGE"}` (207) → 223 | cambia `smallestScreenWidthDp` (plegables o cambio de resolución) | `LC/a.java:1484-1504`; `QL/MyApplication.java:142-159` |
| 0 | `CAR_APP_FOREGROUND` | `{"CMD":"CAR_APP_FOREGROUND"}` → 44 | **nunca** (`IC/b.f()` sin llamadas) | `LC/a.java:3057-3075` |
| 0 | `SPEECH_CTRL` | `{"PARA":{"SpeechStatus":1},"CMD":"SPEECH_CTRL"}` (`r0()`, vía `IC/b.k()`) o `{"PARA":{"SpeechStatus":0},"CMD":"SPEECH_CTRL"}` (`s0()`, vía `IC/b.l()`), 47 B cada uno → 63. Los dos salen desde un hilo nuevo; con `W≠"5A5A"` se envía en su lugar el `!BIN` legado (`message.n`) | **nunca** (`IC/b.k()`/`l()` sin llamadas) | `LC/a.java:2983-3035`; `IC/b.java:110-122`; `f0/b.java:180-192` |
| 0 | `DISCONNECT_RSP` | `{"PARA":{"CanDisconnect":1},"CMD":"DISCONNECT_RSP"}` (51) → 67 | **nunca** (`IC/b.n()` sin llamadas) | `LC/a.java:3120-3138` |
| 1 | vídeo | §8 | desde `VIDEO_CTRL{PlayStatus:1}` | `LC/a.java:1453-1481` |
| 13 | `Mirror` / `WhitelistAppOn` | `{"FunctionID":"WhitelistAppOn","AppID":"Mirror","Para":{"WhitelistAppOn":1}}` (76) → 92 | cada 1 s si `CarFeature.legal_app_watch == 1` (§6.6) | `LC/a.java:2877-2895` |
| 13 | `Music` / `PlayState` | `{"FunctionID":"PlayState","AppID":"Music","Para":{"PlayState":1}}` (65) → 81 | solo con clics en la UI de música **interna** de QDLink (`MusicPlayView`): 1 al reproducir o reanudar, y también al pulsar siguiente/anterior, elegir pista o usar la barra de progreso; 0 solo al pausar con el botón. **No** se envía como respuesta a los `Music/PlayControl*` del coche (`DLinkNotifyL.C/p/i/t/v` no llaman a `IC/b.j()`). Cada envío sale en un hilo nuevo, sin mirar `W` y sin deduplicar | `LC/a.java:2897-2915`; `QL/music/onlinemusic/MusicPlayView.java:140-218`, `:345-377`, `:503-518` |
| 99 | personalizado | `reservedOne` = subtipo, `payLoadFormat` 1 | **nunca** (`IC/b.g()` sin llamadas) | `LC/a.java:2685-2701` |

`h0/c` declara además el `FunctionID` `MuteState` y los beans `CarAppBackgroundPara{int PhoneMirrOff}` y
`UpdatePkgPara{int UpdateStatus}`, pero QDLink **no los usa nunca**: `CAR_APP_BACKGROUND` sale sin `PARA`
(`q(cmd, null)`, `LC/a.java:3046`) y `Music/MuteState` no se envía (`h0/c.java:98-101`;
`LC/newmessage/bean/CarAppBackgroundPara.java:4-13`, `UpdatePkgPara.java:4-13`).

Volcados de dos ejemplos:

```
VIDEO_SUP_RSP (81 B)
0000: 35 41 35 41 00 00 00 51  00 00 00 00 00 01 00 00  |5A5A...Q........|
0010: 7b 22 50 41 52 41 22 3a  7b 22 56 69 64 65 6f 46  |{"PARA":{"VideoF|
0020: 6f 72 6d 61 74 22 3a 33  2c 22 56 69 64 65 6f 53  |ormat":3,"VideoS|
0030: 75 70 70 6f 72 74 22 3a  31 7d 2c 22 43 4d 44 22  |upport":1},"CMD"|
0040: 3a 22 56 49 44 45 4f 5f  53 55 50 5f 52 53 50 22  |:"VIDEO_SUP_RSP"|
0050: 7d                                                |}|

WhitelistAppOn (msgType 13 = 0x0d, 92 B)
0000: 35 41 35 41 00 00 00 5c  00 00 0d 00 00 01 00 00  |5A5A...\........|
0010: 7b 22 46 75 6e 63 74 69  6f 6e 49 44 22 3a 22 57  |{"FunctionID":"W|
0020: 68 69 74 65 6c 69 73 74  41 70 70 4f 6e 22 2c 22  |hitelistAppOn","|
0030: 41 70 70 49 44 22 3a 22  4d 69 72 72 6f 72 22 2c  |AppID":"Mirror",|
0040: 22 50 61 72 61 22 3a 7b  22 57 68 69 74 65 6c 69  |"Para":{"Whiteli|
0050: 73 74 41 70 70 4f 6e 22  3a 31 7d 7d              |stAppOn":1}}|
```

### 6.3 Coche → teléfono (todo lo que QDLink reconoce)

`CMD` con `getString("CMD")`. El `switch` de `CMD` tiene exactamente 10 casos en el bytecode, los de la tabla
(`dex: linkconnection.a.U @051f`, sparse-switch con 10 claves en `@0812`). Los `CMD` desconocidos se ignoran sin error
ni respuesta (`LC/a.java:2070-2071`), incluidos `LOCK_SCREEN_REQ` y `UPDATE_PKG_REQ`, que `h0/c` declara
(`h0/c.java:65`, `:116`) pero que no tienen `case`.

| msgType | CMD / AppID·FunctionID | Claves leídas (tipo) | Qué hace QDLink | ¿Responde? | Ref. |
|---|---|---|---|---|---|
| 0 | `CAR_INFO` | §6.5 | Calcula los tamaños, fija `screenType` y prepara `LegalApp` | `PHONE_INFO` + `UPDATE_NOTIFY{5}` | `LC/a.java:1951-1966`, `:1044-1067`, `:1626-1645` |
| 0 | `VIDEO_SUP_REQ` | `PARA.VideoFormat` (int; solo para el log, opcional) | Prueba un encoder; si no hay → `VideoSupport:0` y teardown, con el socket abierto (§7.5). Si lo hay, abre el diálogo de MediaProjection (en el sabor `QDriveLink` lo hace con **cada** `VIDEO_SUP_REQ`, no solo con el primero) | `VIDEO_SUP_RSP` tras el diálogo | `LC/a.java:1972-2002`, `:1507-1519`; jadx fallback |
| 0 | `VIDEO_ARGS` | `PARA.Width`, `Height`, `EncodingType`, `FrameRate`, `BitRate`, `FrameInterval` (todas `getInt`; si falta una, las siguientes quedan a 0) | Sustituye `Y` (§6.9) sin tocar la captura. Con el vídeo en marcha solo cambian los campos de eco de la cabecera ext | `SPEECH_ARGS` (cada vez) | `LC/a.java:1967-1971`; `h0/c.java:283-297` |
| 0 | `VIDEO_CTRL` | `PARA.PlayStatus` (int) | `== 1` → arranca la captura con los `VIDEO_ARGS` (NPE silencioso si no hubo `VIDEO_ARGS`; tampoco arranca si el usuario aún no aceptó la MediaProjection, §7.1); cualquier otro valor **no hace nada**. Repetirlo es inocuo | — (empieza el vídeo) | `LC/a.java:2003-2016`; `IC/MirrorActivity.java:226-274` |
| 0 | `KEY_FRAME_REQ` | — | `resetSpsFlag`: vuelve a mandar SPS/PPS antes del siguiente buffer. **No** pide un IDR | — | `LC/a.java:2048-2054`; `SC/managers/a.java:627-631` |
| 0 | `LAND_MODE_REQ` | `PARA.Orientation` (int) | 1 → fuerza horizontal con overlay; 2 → libera (§6.7) | `LAND_MODE_RSP` | `LC/a.java:2025-2027`, `:1557-1606`, `:2917-2935` |
| 0 | `BT_ADDR` | `PARA.BluetoothAddr` (String, **obligatoria** en la práctica), `BluetoothStatus` (int), `NeedAutoConnect` (int), leídas en ese orden. Ante cualquier `JSONException`, `NeedAutoConnect = 1` y lo que falte se queda por defecto: sin `BluetoothStatus` → `BluetoothStatus` 0 y `NeedAutoConnect` 1; sin `NeedAutoConnect` → 1. Sin `BluetoothAddr` salta una NPE en `toUpperCase()` (`:2033`): el mensaje se descarta y **no** hay `BT_RESULT` | Comprueba o fuerza A2DP con esa MAC (§6.8) | `BT_RESULT` (asíncrono; puede haber varios, §6.8) | `LC/a.java:2028-2039`, `:2319-2321`; `h0/c.java:152-164` |
| 0 | `PHONE_KEYS` | `PARA.PhoneKeys` (int) | 1 = Home, 2 = Atrás, 3 = Recientes (`performGlobalAction` 2/1/3); otros valores se ignoran | — | `LC/a.java:2055-2069`; `QL/interconnection/MouseAccessibilityService.java:138-172` |
| 0 | `GO_IN_LINK_APP` | — | Trae al frente la `MainActivity` de QDLink (antirrebote de 1 s). Efecto en el cable: `onResume` → `IC/d.h()` → modo in-app. Si estaba en espejo con la captura en marcha, encoder nuevo → SPS/PPS y frames in-app (§7.4) | — (cambia el vídeo) | `LC/a.java:2017-2024`; `DLinkNotifyL.java:1992-2003`; `QL/MainActivity.java:1056-1062`, `:138-151`; `IC/d.java:193-204` |
| 0 | `DISCONNECT_REQ` | — | Callback **vacío**: no responde ni corta | — | `LC/a.java:2040-2047`; `DLinkNotifyL.java:1597-1599` |
| 0 | `HEARTBEAT`, `LOCK_SCREEN_REQ`, `UPDATE_PKG_REQ` y desconocidos | — | Nada (refresca el watchdog). Nuestro cliente: registrar el JSON crudo | — | `LC/a.java:2070-2071`; `h0/c.java:65`, `:116` |
| 2 | táctil | binario (§9) | Inyección táctil | — | `LC/a.java:2074-2158` |
| 12 | voz | bytes | Callback vacío | — | `LC/a.java:2167-2175` |
| 13 | `Music` / `PlayControl` | — | Alterna reproducir/pausa del reproductor **interno** | — | `LC/a.java:2266-2270`, `:2275-2281`; `DLinkNotifyL.java:1275-1287` |
| 13 | `Music` / `PlayControlPlay` | — | Reproducir/reanudar | — | `LC/a.java:2252-2258`, `:2283-2289`; `DLinkNotifyL.java:1667-1677` |
| 13 | `Music` / `PlayControlPause` | — | Pausa | — | `LC/a.java:2259-2265`, `:2291-2297`; `DLinkNotifyL.java:1521-1527` |
| 13 | `Music` / `Prev` | — | Pista anterior | — | `LC/a.java:2238-2244`, `:2299-2305`; `DLinkNotifyL.java:1757-1761` |
| 13 | `Music` / `Next` | — | Pista siguiente | — | `LC/a.java:2231-2237`, `:2314-2317`; `DLinkNotifyL.java:1788-1792` |
| 13 | `Music` / `MuteControl` | — | Alterna el silencio de `STREAM_MUSIC` | — | `LC/a.java:2245-2251`, `:2308-2311`; `DLinkNotifyL.java:1601-1613` |
| 13 | `AudioSource` / `AudioSourceState` | `Para.AudioSourceState` (int) | Callback vacío | — | `LC/a.java:2197-2210`; `DLinkNotifyL.java:1636-1638` |
| 13 | `Global` / `DarkModeOn` | `Para.DarkModeOn` (int) | Callback vacío | — | `LC/a.java:2212-2226`; `DLinkNotifyL.java:1981-1983` |
| 13 | `AppID`/`FunctionID` no listados (p. ej. `Mirror/*`, `Music/PlayState`, `Music/MuteState`) | — | Nada. Nuestro cliente: registrar el JSON crudo | — | `LC/a.java:2176-2318` |
| 99 | personalizado | bytes, `reservedOne` | Callback vacío | — | `LC/a.java:2159-2166` |

La correspondencia de los `hashCode` del `switch` de msgType 13 se ha comprobado (`"Music"`=74710533,
`"AudioSource"`=2105663345, `"Global"`=2135814083, `"Next"`=2424595, `"Prev"`=2496083, `"MuteControl"`=891516132,
`"PlayControlPlay"`=1575615037, `"PlayControlPause"`=1599117325, `"PlayControl"`=2042615657).

### 6.4 `PHONE_INFO` al detalle

Construcción en `LC/a.java:914-959`, bean `LC/newmessage/bean/PhoneInfoPara.java:5-22`:

| Clave | Tipo JSON | Valor que pone QDLink | Origen | Recomendación |
|---|---|---|---|---|
| `PhoneWidth` | número | **lado largo** físico en px, sin importar la rotación | `b0.a.m()` ← `getRealSize` (`SC/service/ScreenCaptureService.java:516-523`) | real |
| `PhoneHeight` | número | lado corto físico | `b0.a.k()` | real |
| `MirrorWidth` | número | `outHorW` (§8.6) | `b0.a.b()` | misma fórmula |
| `MirrorHeight` | número | `outHorH` (§8.6) | `b0.a.a()` | misma fórmula |
| `PhoneWidthInApp` | número | `CAR_INFO.CarWidth` **en crudo** (sin redondear y aunque sea 0) | `LC/a.java:924` | eco |
| `PhoneHeightInApp` | número | `CAR_INFO.CarHeight` en crudo | `:925` | eco |
| `MirrorWidthInApp` | número | `CAR_INFO.CarWidth` en crudo | `:926` | eco |
| `MirrorHeightInApp` | número | `CAR_INFO.CarHeight` en crudo | `:927` | eco |
| `PhoneFeature` | objeto | `{"PassistMobileNum":""}` | `:931-937` | igual |
| `PhoneUUID` | cadena | `""` | `:938` (setter sin llamadas) | `""` (configurable) |
| `PhoneName` | cadena | `""` | `:939` | `""` (configurable) |
| `Version` | cadena | `versionName` = `"1.9.7"` | `:940`; `IU/a.java:107-114` | **`"1.9.7"`** por defecto (configurable); el coche podría exigir una versión mínima |
| `PhoneBrand` | cadena | `Build.MANUFACTURER` (`"samsung"`) | `:941` | real |
| `PhoneModel` | cadena | `Build.MODEL` | `:942` | real |
| `Platform` | número | `0` | `:943` | 0 |
| `PlatformVersion` | **cadena** | `String.valueOf(SDK_INT)`, p. ej. `"36"` | `:944` | igual |
| `MirrorTypeSupport` | número | eco de `CAR_INFO.MirrorTypeReq` | `:945` | eco |
| `PhoneSystemTime` | número | `0` (es `long` y nunca se asigna) | bean `:16` | 0 |

- Si `CarWidth == 0 && CarHeight == 0`, los tamaños se calculan con 800×480, pero los cuatro `*InApp` salen a 0
  (`LC/a.java:919`, `:924-927`).
- Ejemplo exacto con un S25 Ultra en FHD+ (1080×2340) y un `CAR_INFO` **hipotético** de 1920×1080 con
  `MirrorTypeReq` 0 (412 bytes de JSON, `totalSize` 428 = `0x1AC`):

```
{"PARA":{"PhoneName":"","PlatformVersion":"36","PhoneModel":"SM-S938B","Platform":0,"PhoneSystemTime":0,"PhoneHeightInApp":1080,"MirrorWidthInApp":1920,"PhoneWidthInApp":1920,"MirrorHeightInApp":1080,"MirrorTypeSupport":0,"PhoneFeature":{"PassistMobileNum":""},"PhoneUUID":"","Version":"1.9.7","MirrorHeight":886,"MirrorWidth":1920,"PhoneBrand":"samsung","PhoneHeight":1080,"PhoneWidth":2340},"CMD":"PHONE_INFO"}

0000: 35 41 35 41 00 00 01 ac  00 00 00 00 00 01 00 00  |5A5A............|
0010: 7b 22 50 41 52 41 22 3a  7b 22 50 68 6f 6e 65 4e  |{"PARA":{"PhoneN|
…
```

### 6.5 `CAR_INFO` al detalle (`h0/c.java:166-200`)

Las claves se leen **en este orden**. La primera que falte o tenga un tipo incompatible lanza una excepción: las
siguientes quedan a 0/null y `ProjectID`/`CarUUID` pasan a `""` (`:194-198`). La excepción se captura dentro del
parser, así que QDLink responde igualmente con `PHONE_INFO` y `UPDATE_NOTIFY` (§3.4 punto 5). "Obligatoria" significa
aquí que, si falta, las claves siguientes no se leen.

| # | Clave | Lectura | Obligatoria | Uso en QDLink | C10 según la BD (`RES/res/raw/linkmanager.db3`, filas 167/168) |
|---|---|---|---|---|---|
| 1 | `Version` | `getString` | sí | ninguno | ? |
| 2 | `CarType` | `getString` | sí | `screenType` = 1 si está en {`28B`,`297`,`298`,`299`,`29A`,`29B`,`2C7`} (`QL/interconnection/DLinkNotifyL.java:1989`; `LC/a.java:1626-1645`); búsqueda en la BD | `2D4` (C10_i_L) / `2D5` (C10_i_R) → screenType 0 |
| 3 | `Platform` | `getInt` | sí | ninguno | ? |
| 4 | `PlatformVersion` | `getString` | sí | ninguno | ? |
| 5 | `CarWidth` | `getInt` | sí | tamaños (§8.6) y `*InApp` | ? |
| 6 | `CarHeight` | `getInt` | sí | ídem | ? |
| 7 | `CarFactory` | `getString` | sí | BD | `018` |
| 8 | `HUFactory` | `getString` | sí | BD | `119` |
| 9 | `MirrorTypeReq` | `getInt` | sí | eco en `MirrorTypeSupport` | ? |
| 10 | `ProjectID` | `getString` si `!isNull` | no | activación (solo sabor BEIJINGLink, inactivo) | ? |
| 11 | `CarUUID` | `getString` si `!isNull` | no | ídem | ? |
| 12 | `CarFeature` | `getJSONObject` si `!isNull` | no | `legal_app_watch` (`getInt`) | ? |

`Version` se lee con la constante `QL/c.java:34` (`"Version"`).

### 6.6 Lista blanca (`WhitelistAppOn`)

- Se arma en `CAR_INFO`: `LegalApp{legal_app_watch, CarFactory, CarType, HUFactory}` (`LC/a.java:1044-1067`).
- Se activa al enviar `VIDEO_SUP_RSP` (`Z()` → `DLinkNotifyL.k()`, `LC/a.java:2391-2394`;
  `DLinkNotifyL.java:1543-1595`). Solo si `legal_app_watch == 1`:
  - `C = 1`;
  - lista = `legalapplist` de la fila de la BD (`DLinkNotifyL.java:873-892`) más el paquete de QDLink (`:1574-1577`).
    Para el C10: `com.google.android.apps.maps`, `com.waze`, `com.here.app.maps`, `fm.last.android`,
    `com.autonavi.minimap` y `com.baidu.BaiduMap`. **No incluye Android Auto.**
- Timer `schedule(1000, 1000)` (`DLinkNotifyL.java:948-961`). Cada segundo: si la app en primer plano está en la lista
  → `WhitelistAppOn` = 1; si no, 0 (`:500-523`, `:1316-1318` → `IC/b.java:89-94` → `LC/a.java:2877-2895`).
- Sin deduplicar, en 5A5A sin mirar `W`, hasta el teardown (`DLinkNotifyL.java:1777`).
- Requisito: que exista `Z` (el `LegalApp` de `CAR_INFO`). Si no llegó `CAR_INFO` antes de producirse `VIDEO_SUP_RSP`,
  `Z` es `null`, no se llama a `k()` y no hay `WhitelistAppOn` (`LC/a.java:1956`, `:2390-2394`).
- El timer `a1()` también arranca si el `type` de la fila de la BD está en `QL/c.java:286` (`f10009d0`), pero entonces
  no envía `WhitelistAppOn`. Solo vigila si la app en primer plano es un lanzador (flag que usa `LAND_MODE_RSP`, §6.7) y
  libera la horizontal forzada (`IC/b.x()` → `LC/a.I0()`) (`DLinkNotifyL.java:1581-1594`, `:524-533`). No afecta al C10.

**[INFERENCIA]** Es una función de seguridad: el coche puede ocultar la imagen si al circular la app proyectada no es
"legal". **Recomendación**: si `legal_app_watch == 1`, enviar `WhitelistAppOn` cada 1 s con el valor configurado.
Por defecto 1 mientras proyectemos nuestra propia UI (patrón de prueba o Android Auto, que es una interfaz diseñada para
conducir), igual que QDLink cuando proyecta su UI. No usarlo para mostrar contenido que no sea apto para conducir.
Probar también con 0 para ver qué hace el coche.

### 6.7 `LAND_MODE_REQ` / `LAND_MODE_RSP`

`F(o)` (`LC/a.java:1557-1606`) y `p0()` (`:2937-2981`):

- `Orientation` de la respuesta = eco del valor recibido.
- Si hay permiso de superposición, QDLink lanza `RotateScreenService`: 1 → `"Landscape"`, que se traduce en
  `screenOrientation` 0; 2 → `"ScreenOn"`, que es -1 (libre) (`LC/a.java:2917-2935`;
  `IC/service/RotateScreenService.java:54-61`, `:116-131`). Otros valores no hacen nada.
- `Authority`: 1 = permiso de superposición concedido (o API < 23), 2 = no concedido.
- `StatusArg`: 0 normal; 1 = la app en primer plano es un lanzador (flag `f9274a0`, `LC/a.D0`); 2 = modo "usage"
  activo y sin permiso de estadísticas de uso (`LC/a.java:2958-2972`). Para el C10 vale **siempre 0**:
  - El modo "usage" (`f9276b0`, `LC/a.E0`) lo decide `DLinkNotifyL.k()`. No compara `CarType`: compara el campo **`type`
    de la fila de la BD** (`getDBInfo(CarFactory, CarType, HUFactory)`) con `QL/c.java:287` (`SS11R1`, `SX11RC`,
    `VX11_R1`, `X70MC2`). El C10 es `C10_i_L`/`C10_i_R` (`RES/res/raw/linkmanager.db3` filas 167-168), así que queda
    apagado. `k()` solo se ejecuta al producir `VIDEO_SUP_RSP` (antes el flag vale `false`) y pone el flag a `true` un
    instante (`DLinkNotifyL.java:1554`) antes de fijarlo (`:1569`).
  - El flag de lanzador solo lo activa el timer de `DLinkNotifyL` cuando `type` está en `QL/c.java:286` (`GE13-J2`,
    `G836` y los cuatro anteriores) (`DLinkNotifyL.java:524-533`, `:1581-1594`). Para el C10 no ocurre nunca.
- Sin permiso de superposición cambia `Authority` (a 2), no `StatusArg`.

**Recomendación**: responder `{"Authority":1,"StatusArg":0,"Orientation":<eco>}` sin girar nada; Android Auto no
necesita girar el teléfono.

### 6.8 `BT_ADDR` / `BT_RESULT`

`BT_ADDR` → `BTInfo{btmac = BluetoothAddr.toUpperCase(), btstate = BluetoothStatus, btauto = NeedAutoConnect}`
(`LC/a.java:2028-2039`) → `DLinkNotifyL.j()` (`:1529-1541`), que guarda `V = BTInfo`, elige respuestas 5A5A
(`f10022y = 0`) y actúa como sigue. Todas las MAC se comparan sin distinguir mayúsculas.

- `NeedAutoConnect == 0` (la única rama en la que QDLink no empareja): solo mira el perfil A2DP (listener `j`,
  `:1237-1246`, `:537-634`).
  - Sin proxy A2DP o sin ningún dispositivo A2DP conectado → `Result` 1.
  - Esa MAC conectada por A2DP → 2.
  - Solo otros dispositivos conectados → no envía nada.
- `NeedAutoConnect ≠ 0` (`A0()`, `:816-836`):
  - Bluetooth apagado: solo llama a `enable()` y vuelve. El resto se ejecuta al llegar `ACTION_STATE_CHANGED` =
    `STATE_ON`, y solo si aún no ha habido coincidencia en la sesión (`!f10012g0`) (`:715-724`).
  - `BluetoothStatus == 0` y sin coincidencia previa en la sesión: comprueba los perfiles (`o0()`, `:1062-1082`).
    - Si ni A2DP, ni HFP, ni HDP están conectados o conectándose → `Result` 0 a los 3 s, si para entonces sigue sin
      haber coincidencia (`Y0()`, `:931-946`, `:326-356`).
    - Si alguno lo está, pide un proxy con el listener `c` (`:255-324`). Ojo: pasa el **estado** (1/2) como id de
      perfil. Lista de conectados vacía → no envía nada; la MAC está → 2 y `f10012g0 = true`; no está → 0
      **inmediatamente**.
  - En otro caso (`BluetoothStatus ≠ 0`, o ya hubo coincidencia en la sesión aunque `BluetoothStatus` sea 0): empareja
    (`createBond`) y conecta A2DP por reflexión → 2 nada más invocar `connect` (`:977-1018`). Si el emparejamiento
    acaba en `BOND_NONE` con más de 2 reintentos → 1 (`:779-811`).
- **`BT_RESULT` no solicitados.** Tras cualquier `BT_ADDR`, el receptor registrado en `onStartCommand` (`:1659-1665`
  → `:838-851`) reacciona a cada `CONNECTION_STATE_CHANGED` de A2DP (`:729-778`). jadx reconstruye mal este `switch`;
  lo que sigue sale del bytecode (`dex: DLinkNotifyL$m.onReceive @0106-01e7`):
  - `STATE_CONNECTED` de la MAC pedida, con `V` aún sin consumir → 2, `f10012g0 = true` y `V = null` (una sola vez);
  - `STATE_DISCONNECTED` de **cualquier** dispositivo A2DP → 3 (o 2 si ya hubo 11 o más intentos de `connect`);
  - `CONNECTING`, `DISCONNECTING` o `CONNECTED` de otro dispositivo → nada.
- Así que un solo `BT_ADDR` puede producir varios `BT_RESULT` (p. ej. un 2 al invocar `connect` y otro 2 al conectarse),
  y además llega un 3 cada vez que se desconecta un dispositivo A2DP.

**Recomendación** (sin emparejar nunca por nuestra cuenta), con los mismos códigos que QDLink en cada rama:
- `NeedAutoConnect == 0`: 1 si no hay ningún dispositivo A2DP conectado, 2 si esa MAC está conectada por A2DP y ninguna
  respuesta si solo hay otros.
- `NeedAutoConnect ≠ 0` y `BluetoothStatus == 0`: 2 si la MAC está conectada en algún perfil (A2DP/HFP); si no, 0.
- `NeedAutoConnect ≠ 0` y `BluetoothStatus ≠ 0`: QDLink emparejaría y mandaría 2 nada más invocar `connect`. Nosotros:
  2 si ya está conectada; si no, **desviación consciente**: no emparejar y responder 0 (configurable: 0, 1, 2 o nada).
- `BT_RESULT` no solicitados (2 al conectarse esa MAC, 3 al desconectarse un A2DP): opcionales y configurables.
- Comparar la MAC sin distinguir mayúsculas y registrar los tres valores recibidos.

### 6.9 `VIDEO_ARGS`: unidades y efecto

| Clave | Uso en QDLink | Unidad | Si vale 0 | Ref. |
|---|---|---|---|---|
| `Width`, `Height` | Llegan a `c0.a` (redondeados a par), pero el encoder **no** los usa | px (ignorado) | — | `IC/MirrorActivity.java:238-239`; `SC/service/ScreenCaptureService.java:715` |
| `EncodingType` | 1 → tipo de captura 983042 → **no se crea VirtualDisplay** (no hay vídeo); cualquier otro valor → H.264 (983041). Eco en el byte 15 de la cabecera extendida | 3 = H.264 (`VIDEO_SUP_RSP.VideoFormat` 3) | H.264 | `IC/MirrorActivity.java:233-237`; `SC/managers/a.java:693-703`; `LC/a.java:1463` |
| `FrameRate` | `KEY_FRAME_RATE` y timer GL de `1000/fps` ms (división entera, `dex: glec.a.q @000d`). Eco en ext[16] | fps | 24 | `SC/service/ScreenCaptureService.java:782-784`; `SC/glec/a.java:237-245` |
| `BitRate` | `KEY_BIT_RATE` tal cual. Eco en ext[20] | **bps** (semántica de Android; el valor por defecto 2 764 800 = 1280·720·3) | 2 764 800 | `SC/managers/a.java:567`; `c0/a.java:12` |
| `FrameInterval` | `KEY_I_FRAME_INTERVAL` tal cual. Eco en ext[24] | **segundos** para Android; no se sabe qué quiere decir el coche | 4 | `SC/managers/a.java:584`; `c0/a.java:27` |

`VIDEO_ARGS` con el vídeo en marcha:
- Cada `VIDEO_ARGS` sustituye `Y` (`LC/a.java:1968`) y vuelve a provocar `SPEECH_ARGS`, sin tocar la captura.
- Como `l0()` lee `Y` en cada frame (`LC/a.java:1463-1466`), desde el siguiente frame cambian ext[15] (encodingType),
  ext[16..19] (fps), ext[20..23] (bitrate) y ext[24..27] (frameInterval). El encoder sigue con la configuración del
  `VIDEO_CTRL` que lo arrancó.
- Un `VIDEO_CTRL{1}` posterior solo guarda parámetros (`eVar.t`) y llama a `glec.a.w()`, que es idempotente: no recrea
  el encoder (`IC/MirrorActivity.java:249-274`; `SC/service/ScreenCaptureService.java:433-435`, `:756-798`;
  `SC/glec/a.java:342-355`).
- Si el nuevo `VIDEO_ARGS` trae `EncodingType` 1, la cabecera pasa a decir 1 aunque se siga enviando H.264.

---

## 7. Handshake y ciclo de vida

### 7.1 Secuencia (Wi-Fi, 5A5A)

```
COCHE                                            TELÉFONO (QDLink)
UDP Connect_Broadcast → :18463  (periódico)
                                                 [usuario pulsa el coche] P = rand(10001..65535)
                                                 ServerSocket(P)                                   WF/d.java:78
                              ←  UDP Broadcast_ACK {MirrorPort:P} (desde :18463, 1 vez)            WF/d.java:80
TCP connect → IP_tel:P                           accept (límite 20 s)                              WF/d.java:83
                                                 t0: lector; AppStatus !BIN 512 B (hilo)           LC/a.java:1697-1698
                              ←  !BIN AppStatus
                                                 t0+1 s y luego cada 3 s: HEARTBEAT                LC/a.java:1761
                              ←  5A5A {"CMD":"HEARTBEAT"} …
CAR_INFO                      →                  W="5A5A"; H0, LegalApp, tamaños
                              ←  PHONE_INFO      (hilo)                                            LC/a.java:954
                              ←  UPDATE_NOTIFY {UpdateStatus:5}  (hilo)                            LC/a.java:1542
VIDEO_SUP_REQ                 →                  prueba de encoder + diálogo MediaProjection
                                                 (usuario: "Empezar ahora") → lista blanca (si procede)
                              ←  VIDEO_SUP_RSP {VideoFormat:3, VideoSupport:1}                    LC/a.java:2398-2410
                              ←  [cada 1 s, si legal_app_watch==1] WhitelistAppOn
VIDEO_ARGS                    →                  guarda Y
                              ←  SPEECH_ARGS {EncodingType:1,SampleRate:16000,ChannelConfig:1,AudioFormat:16}
VIDEO_CTRL {PlayStatus:1}     →                  MirrorActivity → ScreenCaptureService → encoder
                              ←  vídeo: [SPS+PPS] [IDR] [P] … (≈ fps mensajes/s)
KEY_FRAME_REQ / LAND_MODE_REQ / BT_ADDR / PHONE_KEYS / táctil / Music …  (en cualquier momento)
```

- El orden de los mensajes del **coche** no lo impone QDLink: cada uno se atiende cuando llega. **[INFERENCIA]** El
  coche manda `VIDEO_ARGS` después de recibir `VIDEO_SUP_RSP` y `VIDEO_CTRL` después de `SPEECH_ARGS`.
- Si llega `VIDEO_CTRL{1}` antes que `VIDEO_ARGS`, QDLink no hace nada (NPE capturado, `LC/a.java:2012`). Si el coche
  no vuelve a mandarlo, no hay vídeo.
- `VIDEO_CTRL{1}` también se pierde si llega **antes de que el usuario acepte la MediaProjection**, es decir, antes de
  que QDLink mande `VIDEO_SUP_RSP{1}`. `MirrorActivity.r()` solo guarda `resultCode`/`Intent` y manda `VIDEO_SUP_RSP`
  con `L(true)`: no arranca nada. La captura solo arranca en `MirrorActivity.s()`, al recibir `VIDEO_CTRL{1}`, y solo si
  ese `Intent` ya existe. Si no existe, `s()` guarda los parámetros y `ScreenCaptureService.I()` sale con "The Media
  Projection is not init.": no hay vídeo hasta otro `VIDEO_CTRL{1}` (`IC/MirrorActivity.java:226-274`, `:437-473`;
  `SC/service/ScreenCaptureService.java:664-692`, `:897-933`). Esto refuerza la **[INFERENCIA]** anterior: con QDLink,
  el coche tiene que mandar `VIDEO_CTRL{1}` después de `VIDEO_SUP_RSP{1}`. **Recomendación**: arrancar el vídeo con
  cualquier `VIDEO_CTRL{1}` que llegue después de `VIDEO_ARGS`.

### 7.2 Qué respuestas son imprescindibles

Desde el código del teléfono no se ve qué exige el coche. Las respuestas del handshake son baratas, así que se
replican **todas**:

| Coche | Respuesta | Motivo |
|---|---|---|
| (conexión) | AppStatus `!BIN` + heartbeats | QDLink lo hace siempre, antes de que hable el coche |
| `CAR_INFO` | `PHONE_INFO` y luego `UPDATE_NOTIFY{5}` | El coche necesita los tamaños **[INFERENCIA]** |
| `VIDEO_SUP_REQ` | `VIDEO_SUP_RSP{3,1}` | Con `VideoSupport:0` QDLink desmonta la sesión (§7.5). **[INFERENCIA]** El coche espera esta respuesta para mandar `VIDEO_ARGS` |
| `VIDEO_ARGS` | `SPEECH_ARGS` | **[INFERENCIA]** puede ser requisito para `VIDEO_CTRL` |
| `VIDEO_CTRL{1}` | vídeo (SPS/PPS + frames) | |

### 7.3 Mensajes periódicos de QDLink

| Mensaje | Desde | Periodo | Condición |
|---|---|---|---|
| `HEARTBEAT` | conexión + 1 s | 3 s (retardo fijo) | siempre |
| `WhitelistAppOn` | `VIDEO_SUP_RSP` + 1 s | 1 s | `legal_app_watch == 1` |
| vídeo | `VIDEO_CTRL{1}` | `1000/fps` ms (el timer GL redibuja aunque la imagen no cambie) | siempre |

No hay ningún otro mensaje periódico.

### 7.4 Pantalla apagada o bloqueada y app en segundo plano

| Evento en el teléfono | Qué envía QDLink | Qué cambia en el vídeo | Ref. |
|---|---|---|---|
| `SCREEN_OFF` | `LOCK_SCREEN_STATUS {1}` (solo si `W=="5A5A"`) | Pasa a modo UI propia (in-app, appType 1). Si estaba en espejo, se recrea el encoder: SPS/PPS nuevos, W×H = inCar, orientación 1 / ángulo 90. La `Presentation` usa `SHOW_WHEN_LOCKED\|DISMISS_KEYGUARD` y sigue emitiendo | `QL/MainActivity.java:508-512`; `SC/service/ScreenCaptureService.java:110-127`, `:414-426`; `SC/managers/a.java:981-1014`; `SC/managers/d.java:90-93` |
| `SCREEN_ON` | `LOCK_SCREEN_STATUS {2}` | — | `QL/MainActivity.java:502-506` |
| `USER_PRESENT` (desbloqueo) | `LOCK_SCREEN_STATUS {3}` | — | `QL/MainActivity.java:514-518` |
| La app pasa a segundo plano | **nada** | El espejo sigue capturando lo que haya en pantalla | (no hay llamadas en `onPause`/`onStop`) |
| El usuario toca "salir" en la UI de QDLink proyectada | `CAR_APP_BACKGROUND` | — | `QL/mainpage/MainPageView.java:363-384` |
| Abre una app de terceros desde QDLink | `LOCK_SCREEN_STATUS {2}` si la pantalla estaba apagada (la despierta) | Pasa a espejo (appType 2) 700 ms después | `QL/mainpage/MainPageView.java:642-651`, `:276-284` |
| El usuario pulsa el botón de voz de Google en la UI de QDLink proyectada | `LOCK_SCREEN_STATUS {2}` si la pantalla estaba apagada (la despierta) | Pasa a espejo (appType 2) 700 ms después | `QL/mainpage/MainPageView.java:489-514`, `:298-306` |
| QDLink vuelve a primer plano (`onResume` de `MainActivity`, también por `GO_IN_LINK_APP`) | — | Pasa a modo in-app. Si estaba en espejo con la captura en marcha, encoder nuevo: SPS/PPS y frames in-app (appType 1, 1/90, W×H = inCar) | `QL/MainActivity.java:1056-1062`, `:138-151`; `IC/d.java:193-204`; `SC/service/ScreenCaptureService.java:414-426`; `SC/managers/a.java:981-1014` |

Gestión de la pantalla en QDLink:
- La pantalla se queda **encendida** y con brillo toda la sesión. El constructor del motor adquiere un
  `FULL_WAKE_LOCK` (`newWakeLock(26)`, `setReferenceCounted(false)`, `acquire()` sin tiempo límite) que solo se libera
  en `J()`, dentro del teardown `d()` (`LC/a.java:897-902`, `:1027-1035`, `:2539`). Por eso, con QDLink,
  `SCREEN_OFF` → `LOCK_SCREEN_STATUS{1}` casi solo ocurre si el usuario pulsa el botón de encendido.
- Al lanzar una app o la voz desde la UI proyectada, `MainPageView.L()` despierta la pantalla si estaba apagada
  (`ACQUIRE_CAUSES_WAKEUP | SCREEN_BRIGHT_WAKE_LOCK` = 268435466), lo que provoca `SCREEN_ON` →
  `LOCK_SCREEN_STATUS{2}` (`QL/mainpage/MainPageView.java:447-455`, `:489-514`, `:642-651`). Ya estaba en 01 §8 y
  02 §9.1.

**[INFERENCIA]** QDLink pasa a propósito a la `Presentation` (visible con el teléfono bloqueado) al apagarse la
pantalla. Eso indica que el coche **sigue mostrando vídeo** después de `LOCK_SCREEN_STATUS {1}`.

**Recomendación** para mantener vídeo con la pantalla apagada:
- seguir enviando heartbeats y frames a ritmo constante (servicio en primer plano + `WakeLock` parcial + `WifiLock` de
  baja latencia);
- **no** enviar `LOCK_SCREEN_STATUS` por defecto, con una opción para emular a QDLink y comprobar si el coche
  reacciona. Es una **decisión**, no un hecho del código: qué hace el coche con este mensaje es una incógnita (§11.11).
  **[INFERENCIA]** Con QDLink la pantalla casi nunca se apaga, así que en una sesión normal el coche apenas recibe
  `{1}`. Con nuestra app la pantalla sí se apagará, y emular el mensaje mandaría `{1}` mucho más a menudo de lo que el
  coche está acostumbrado a ver. Pero no enviarlo tampoco es idéntico a QDLink, que manda `{2}`/`{3}` al encender o
  desbloquear.

### 7.5 Fin de sesión

- QDLink no envía nunca despedida. Normalmente cierra el socket en `L()` (`LC/a.java:1710-1736`) o en `IC/d.A()`
  (`IC/d.java:90-115`), pero no siempre:
  - **El usuario rechaza o cancela la captura.** `MirrorActivity.r()` llama primero a `f0.b.M()` →
    `DLinkNotifyL.q()` → mensaje 3 al `Handler` de `MainActivity` → `IC/d.A()`, que cierra `Socket` y `ServerSocket`.
    Después llama a `L(false)` → `VIDEO_SUP_RSP{0}` desde un hilo nuevo, más `E()` y `d()`. El cierre compite con el
    envío del `{0}`: normalmente sale antes el `{0}`, pero no está garantizado (`IC/MirrorActivity.java:437-458`;
    `f0/b.java:152-157`; `DLinkNotifyL.java:1679-1685`; `QL/MainActivity.java:582-584`).
  - **No hay encoder o no está enlazado el servicio de captura** (`G == null`). `q0()` → `Z(false)` →
    `VIDEO_SUP_RSP{0}`, y luego `E()` y `d()`. `d()` para lector, heartbeat, watchdog, captura y lista blanca, y pone
    `W = "DEFULT"` y el estado a -1, pero **no cierra** el socket TCP: el coche recibe el `{0}` y después silencio con
    la conexión abierta (`LC/a.java:1507-1519`, `:2390-2427`, `:292-312`, `:2513-2546`).
  - **Falla la lectura de una cabecera con `W ≠ "5A5A"`** (§3.4 punto 2): el teléfono enmudece y el socket se queda
    abierto.
- `DISCONNECT_REQ` se ignora (§6.3). `DISCONNECT_RSP` existe pero no se usa.
- No hay reconexión automática (ver 01 §5.8).

---

## 8. Vídeo

### 8.1 Mensaje de vídeo: 16 + 32 bytes de cabecera

Rellenado en `l0()` (`LC/a.java:1453-1481`; comprobado en `dex: a.l0`), serialización de la cabecera extendida en
`h0/d.java:71-88` (orden comprobado en `dex: h0.d.h() @000c-0073`):

| Byte msg | Byte ext | Tipo | Campo (setter) | Valor |
|---|---|---|---|---|
| 0-3 | — | ASCII | magic | `5A5A` |
| 4-7 | — | u32 | totalSize (`A.o(i4)`) | **48 + longitud del payload** |
| 8-9 | — | u16 | extLen (`A.j(B.a())`) | 32 |
| 10 | — | u8 | msgType | 1 |
| 11-12 | — | u8 | — | 0, 0 |
| 13 | — | u8 | payLoadFormat | 2 |
| 14-15 | — | u8 | — | 0, 0 |
| 16-17 | 0-1 | u16 | longitud de la ext (`h0/b.a()`, `"32"` en `h0/d.java:9`, `:51`) | 32 |
| 18 | 2 | u8 | `h0/b.e(1)` (¿tipo o versión?) | 1 |
| 19 | 3 | u8 | `h0/b.f()`, nunca se asigna | 0 |
| 20-23 | 4-7 | u32 | ancho (`C`, "dataWidth") | según el modo (§8.7) |
| 24-27 | 8-11 | u32 | alto (`w`, "dataHeight") | según el modo |
| 28-29 | 12-13 | i16 | ángulo (`z`, "ang") | in-app 90; espejo = último ángulo sondeado (0/90/180/270). Vale 0 al crear el gestor y **no** se reinicia al cambiar de modo |
| 30 | 14 | i8 | orientación (`y`, "orls") | in-app 1; espejo 0 si la rotación es 0, 1 si es 90/180/270, **-1 (`FF`)** desde que se crea el gestor o cambia el modo hasta el siguiente sondeo (≤ 1 s) |
| 31 | 15 | u8 | encodingType (`t`) | eco de `VIDEO_ARGS.EncodingType` |
| 32-35 | 16-19 | u32 | frameRate (`v`) | eco de `VIDEO_ARGS.FrameRate` (el valor crudo, aunque sea 0) |
| 36-39 | 20-23 | u32 | bitRate (`s`) | eco de `VIDEO_ARGS.BitRate` |
| 40-43 | 24-27 | u32 | frameInterval (`u`) | eco de `VIDEO_ARGS.FrameInterval` |
| 44 | 28 | u8 | appType (`x`, "iAppCapture") | **1 = UI de QDLink (in-app), 2 = espejo** |
| 45-47 | 29-31 | — | 0 | |
| 48… | — | — | payload | H.264 Annex-B |

- `h0/d` tiene además un `int` (setter `A`) y un `long` (setter `B`) que nadie asigna y que `h()` no serializa
  (`h0/d.java:12-15`, `:55-61`). **No hay timestamp, ni número de secuencia, ni CRC, ni marca de keyframe.**
- El valor de `appType` del último frame enviado es el que decide la ruta del táctil (`LC/a.java:2452`, §9.3).

Rotación (`SC/managers/a.java:164-211`): un `Runnable` en el hilo principal consulta cada 1 s
`DisplayManager.getDisplay(0).getRotation()`. Con `ROTATION_0`: ángulo 0, orientación 0. Con 1/2/3: ángulo 90/180/270
y orientación **1** (también con 180°). Se arranca 1 s después de crear el gestor (`:504-506`). La orientación empieza
en -1 (`:454`) y vuelve a -1 en cada cambio de modo (`:1107-1110`). El ángulo empieza en 0 (`:455`) y conserva el
último valor sondeado: `Z()` solo reinicia la orientación.

Con screenType 0 (el C10) el sondeo **solo** actualiza ángulo y orientación. Únicamente pide un encoder nuevo (`Q()`)
si `H == 983058 && X == 1` (`:192`, `:201`). El listener del sensor (`:1145-1190`) tiene la misma condición y nunca se
registra (`D()` no tiene llamadas). Por tanto, una rotación no reenvía SPS/PPS (§8.2).

### 8.2 SPS/PPS

- Con `INFO_OUTPUT_FORMAT_CHANGED` (-2) QDLink guarda `csd-0` (SPS) y `csd-1` (PPS) con
  `getOutputFormat().getByteBuffer("csd-N").array()` y baja `bSendSps` (`SC/managers/a.java:669-672`, `:618-625`,
  `:762-780`). Los buffers incluyen el start code que pone el encoder (`00 00 00 01`).
- El buffer de salida con `BUFFER_FLAG_CODEC_CONFIG` **se descarta**: `size = 0`, no se envía como frame (`:783-789`).
- Antes del siguiente buffer, si es el primero del gestor o se ha bajado `bSendSps`, se envía **un mensaje de vídeo
  propio** cuyo payload es `csd-0 ‖ csd-1` (SPS y luego PPS, cada uno con su start code). Lleva las mismas cabeceras que
  un frame y `totalSize = 48 + len(SPS) + len(PPS)` (`:795-809`).
- Con screenType 0 (el C10) se vuelven a enviar:
  - tras `KEY_FRAME_REQ` (`N()` estático, `:627-631`);
  - tras un cambio de modo in-app ↔ espejo (`ScreenCaptureService.e.r` → `managers.a.Z` + `P()`, encoder nuevo;
    `:981-1014`; `SC/service/ScreenCaptureService.java:414-426`);
  - tras un cambio de tamaño de pantalla, solo en espejo (`ScreenCaptureService.e.p` → `managers.a.L(b0.a)` → `Q()`,
    `:960-968`);
  - tras un cambio de idioma del sistema (`IC/d.g` → `ScreenCaptureService.e.b` → `managers.a.u` → `P()`;
    `QL/MyApplication.java:172`, `:437`);
  - tras un error de render EGL (`C0111a.a` → `P(H)`, `:150-154`);
  - tras un nuevo `INFO_OUTPUT_FORMAT_CHANGED` (`:669-672`).
- Una **rotación no** recrea el encoder ni reenvía SPS/PPS: tras girar en modo espejo, los frames siguen saliendo del
  mismo encoder con el mismo SPS y solo cambian los bytes 12-14 de la cabecera ext (ángulo y orientación)
  (`:164-211`, §8.1).
- **No se fuerza ningún IDR**: no hay llamada a `setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` en el APK. Así que
  tras un `KEY_FRAME_REQ` al SPS/PPS le sigue normalmente un frame P.

### 8.3 Frames

- Cada buffer de salida de `MediaCodec` = **un mensaje**: `payload = bytes[offset, offset+size)` tal cual, en
  Annex-B (`SC/managers/a.java:810-823`).
- El drenado se hace en el hilo del timer GL, antes del `eglSwapBuffers`: `dequeueOutputBuffer(info, 200 µs)` en
  bucle (`:663-684`; `SC/glec/a.java:86-111`).
- Envío: `LC/a.b()` con el lock de escritura → `l0()` → `j()` → `write(buf)` + `flush()`, es decir, un solo `write`
  por mensaje (`LC/a.java:2450-2470`, `:2811-2858`).
- Orden típico de la sesión: `[SPS+PPS]`, `[IDR]`, `[P]`, `[P]`…

### 8.4 `totalSize`

`totalSize = 16 + 32 + N`, con N = longitud del payload (SPS+PPS o el buffer del encoder). En Wi-Fi el buffer mide
exactamente eso y no hay relleno (`SC/managers/a.java:797`, `:815`). En USB el buffer se rellena a múltiplos de 512, pero
`totalSize` sigue siendo 48 + N (`:884-919`).

### 8.5 Encoder de QDLink

`SC/managers/a.java:537-608`:

| Clave | Valor | Línea |
|---|---|---|
| MIME | `video/avc` | `:39`, `:560` |
| tamaño | `inCarW × inCarH` cuando screenType es 0 (el caso del C10) | `:559-561` |
| `KEY_COLOR_FORMAT` | `0x7F000789` (`COLOR_FormatSurface`) | `:566` |
| `KEY_BIT_RATE` | `VIDEO_ARGS.BitRate` (o 2 764 800) | `:567` |
| `KEY_FRAME_RATE` | `VIDEO_ARGS.FrameRate` (o 24) | `:568-573` |
| `"intra-refresh-period"` | `setString(…, "intra-refresh")`, una cadena en una clave entera: **[INFERENCIA]** no tiene efecto (API ≥ 24) | `:575-577` |
| `KEY_PROFILE` | 1 = `AVCProfileBaseline` (API ≥ 23) | `:579` |
| `KEY_LEVEL` | 512 = `AVCLevel31` (API ≥ 23). **[INFERENCIA]** a 1080p el encoder sube el nivel por su cuenta | `:580` |
| `KEY_BITRATE_MODE` | 1 = VBR | `:582` |
| `KEY_COMPLEXITY` | 1 | `:583` |
| `KEY_I_FRAME_INTERVAL` | `VIDEO_ARGS.FrameInterval` (o 4), en **segundos** | `:584` |
| Ausentes | `KEY_LATENCY`, `KEY_LOW_LATENCY`, `KEY_PRIORITY`, `KEY_OPERATING_RATE`, `KEY_MAX_B_FRAMES`, `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` | — |

- Entrada por `createInputSurface()`. Se pinta con GL a `1000/fps` ms: el último frame del VirtualDisplay se repite aunque
  no cambie. El PTS es sintético, `n·1e9/fps` (`SC/glec/a.java:86-111`, `:237-245`, `:342-355`).
- Prueba previa en `VIDEO_SUP_REQ` (`SC/service/ScreenCaptureService.java:722-745`): 800×480, 1 500 000 bps, 24 fps,
  CBR (2), complexity 2, I-frame cada 1 s, Baseline/3.1. Solo `configure`; si lanza, `VideoSupport:0`.

### 8.6 Resolución calculada a partir de `CAR_INFO` (fórmula exacta)

`ScreenCaptureService.H(carW, carH)` (`SC/service/ScreenCaptureService.java:466-661`), rama de coche horizontal
(`carW > carH`). **Todas las divisiones son en coma flotante** (`dex: ScreenCaptureService.H @0119-0193`):

```
pL, pS   = lado largo y corto de getRealSize()          (:499-525)
inCarW   = carW redondeado a par (+1 si es impar)       (:528)
inCarH   = carH redondeado a par                        (:529)
rc       = (float)carW / (float)carH                     (valores crudos; @0119-011b)
rp       = (float)pL / (float)pS                         (@011c-0124)
si rc > rp:  outHorW = (int)(inCarH · rp);  outHorH = inCarH          (@0129-012c)   coche más panorámico que el teléfono
si no:       outHorW = inCarW;              outHorH = (int)(inCarW / rp)   (@0164-0166)
outHorW, outHorH → redondeo a par
outVerW  = par((int)(inCarH / rp));  outVerH = inCarH  (@0191-0193)
tempLong = pL                     (compara con E.f() antes de asignarlo, que vale 0; :542, :548)
tempShort= (int)(tempLong / rc)   (@0148-014a / @0182-0184)
```

- La única "alineación" es el redondeo a par. No se alinea a 16.
- `MirrorWidth/Height` = `outHor`. El encoder y el SPS usan `inCar` en los dos modos (con screenType 0).
- Con `CarWidth = CarHeight = 0` se calcula con 800×480. Un coche vertical (`carW ≤ carH`) va por otra rama
  (`:566-651`), que no aplica al C10.

Valores (reproducción exacta en Java de la aritmética float de arriba):

| Teléfono | CAR_INFO | inCar (encoder/SPS, in-app) | **Mirror = outHor** | outVer | tempLong×tempShort | rc / rp |
|---|---|---|---|---|---|---|
| 1080×2340 (S25U FHD+) | 800×480 | 800×480 | **800×370** | 222×480 | 2340×1404 | 1,667 / 2,167 |
| 1080×2340 | 1280×720 | 1280×720 | **1280×590** | 332×720 | 2340×1316 | 1,778 / 2,167 |
| 1080×2340 | 1920×720 | 1920×720 | **1560×720** | 332×720 | 2340×877 | 2,667 / 2,167 |
| 1080×2340 | 1920×1080 | 1920×1080 | **1920×886** | 498×1080 | 2340×1316 | 1,778 / 2,167 |
| 1080×2340 | 1920×1200 | 1920×1200 | **1920×886** | 554×1200 | 2340×1462 | 1,600 / 2,167 |
| 1080×2340 | 2560×1440 | 2560×1440 | **2560×1182** | 664×1440 | 2340×1316 | 1,778 / 2,167 |
| 1080×2340 | 1919×1079 | 1920×1080 | **1920×886** | 498×1080 | 2340×1315 | 1,778 / 2,167 |
| 1440×3120 (S25U WQHD+) | 1920×1080 | 1920×1080 | **1920×886** | 498×1080 | 3120×1755 | 1,778 / 2,167 |
| 1440×3120 | 2560×1440 | 2560×1440 | **2560×1182** | 664×1440 | 3120×1755 | 1,778 / 2,167 |
| 1080×2400 | 1920×720 | 1920×720 | **1600×720** | 324×720 | 2400×900 | 2,667 / 2,222 |
| 1080×2400 | 1920×1080 | 1920×1080 | **1920×864** | 486×1080 | 2400×1350 | 1,778 / 2,222 |

### 8.7 Modo UI de QDLink (Presentation) frente a espejo, en el cable

`U()` de `SC/managers/a.java:633-660` (comprobado en dex). Con screenType X = 0, que es el caso del C10:

| | In-app (UI de QDLink, 983057) | Espejo (out-of-app, 983058) |
|---|---|---|
| Qué se captura | `ViewGroup` de QDLink en una `Presentation` sobre un VirtualDisplay **privado** de inCar | la pantalla del teléfono (MediaProjection, VD **público** de outHor) |
| Tamaño codificado (SPS) | inCar | inCar (el GL **estira** outHor hasta inCar) |
| W×H en la cabecera ext | inCar | **outHor** (= `MirrorWidth×MirrorHeight`) |
| orientación / ángulo | **1 / 90 fijos** | reales (0/0 en vertical; 1/90 o 1/270 en horizontal; 1/180 boca abajo). Al entrar en espejo, hasta el siguiente sondeo (≤ 1 s): `FF` + el último ángulo sondeado (p. ej. `FF`/90 con el teléfono en horizontal). `FF`/0 solo si el gestor se creó directamente en espejo o el teléfono está en vertical |
| appType | **1** | **2** |
| Ruta del táctil | `Presentation.dispatchTouchEvent`, solo el dedo 0 (§9.3) | `AccessibilityService.dispatchGesture` |
| Cuándo | al empezar (`ScreenCaptureService.C` = 983057, `SC/service/ScreenCaptureService.java:70`); cuando QDLink vuelve a primer plano (también por `GO_IN_LINK_APP`); al apagarse la pantalla | al abrir otra app o la voz de Google desde el lanzador de QDLink (700 ms después) |

Al cambiar de modo se recrean encoder, EGL y VD. Llega un SPS/PPS nuevo y, después, frames con la otra cabecera
(`SC/service/ScreenCaptureService.java:414-426`; `SC/managers/a.java:981-1014`).

**[INFERENCIA]** El coche presenta el frame con la relación de aspecto `W×H` de la cabecera, sea cual sea el tamaño del
SPS; si no, el espejo saldría deformado. **Recomendación** para nuestro vídeo: imitar el modo in-app. Es decir:
appType 1, orientación 1, ángulo 90, W×H de la cabecera = tamaño del SPS = `par(CarWidth) × par(CarHeight)`, y eco de
`VIDEO_ARGS` en encodingType/fps/bitrate/GOP. Dejar appType, orientación, ángulo y W×H configurables para probar el
modo 2.

### 8.8 Ejemplos hex (primeros 48 bytes y comienzo del payload)

Mensaje SPS+PPS en modo espejo: Mirror 1920×886, orientación 1, ángulo 90, `VIDEO_ARGS` = {enc 3, 30 fps,
4 000 000, GOP 1}, appType 2. SPS y PPS de 8 bytes cada uno, **ilustrativos**:

```
0000: 35 41 35 41 00 00 00 40  00 20 01 00 00 02 00 00  |5A5A...@. ......|   total=64 ext=32 type=1 fmt=2
0010: 00 20 01 00 00 00 07 80  00 00 03 76 00 5a 01 03  |. .........v.Z..|   32,1,0 W=1920 H=886 ang=90 or=1 enc=3
0020: 00 00 00 1e 00 3d 09 00  00 00 00 01 02 00 00 00  |.....=..........|   fps=30 br=4000000 gop=1 app=2
0030: 00 00 00 01 67 42 c0 29  00 00 00 01 68 ce 3c 80  |....gB.)....h.<.|   SPS ‖ PPS (Annex-B)
```

Frame IDR en modo in-app: 1920×1080, 1/90, mismos `VIDEO_ARGS`, appType 1, payload de 12 345 bytes
(`totalSize` = 12 393 = `0x3069`):

```
0000: 35 41 35 41 00 00 30 69  00 20 01 00 00 02 00 00  |5A5A..0i. ......|
0010: 00 20 01 00 00 00 07 80  00 00 04 38 00 5a 01 03  |. .........8.Z..|
0020: 00 00 00 1e 00 3d 09 00  00 00 00 01 01 00 00 00  |.....=..........|
0030: 00 00 00 01 65 …                                   slice IDR
```

Cabecera ext de un frame de espejo justo después de entrar en espejo con el teléfono en vertical (orientación `FF`,
ángulo 0). Con el teléfono en horizontal, los bytes 12-13 serían `00 5a` (ángulo 90):

```
00 20 01 00 00 00 07 80 00 00 03 76 00 00 ff 03 00 00 00 1e 00 3d 09 00 00 00 00 01 02 00 00 00
```

---

## 9. Táctil y otras entradas

### 9.1 Formato (msgType 2, `payLoadFormat` 0, coche → teléfono)

Parser en `h0/e.java:43-64`; bean del dedo en `h0/f.java:18-46`:

| Off. en payload | Tipo | Campo |
|---|---|---|
| 0 | i32 BE | `action` (acción global) |
| 4 | u8 (se lee como byte **con signo**) | `fingerCount` = N |
| 5 + 10k | u8 | `fingerId` |
| 6 + 10k | u8 | `fingerAction` |
| 7 + 10k | f32 BE | `x` |
| 11 + 10k | f32 BE | `y` |

- `totalSize = 16 + 5 + 10·N`, con `extLen` 0.
- Con N = 0, el log de `LC/a.java:2082` accede a `get(0)`, lanza una excepción y el mensaje se **descarta**: QDLink
  necesita al menos un dedo.
- El bucle de lectura no comprueba la longitud. Bytes de sobra se ignoran; si faltan, salta una excepción y el mensaje
  se descarta.

### 9.2 Códigos

| Campo | Valores conocidos | Ref. |
|---|---|---|
| `fingerAction` | **1 = down, 2 = up, 3 = move**. QDLink in-app los convierte a `ACTION_DOWN`/`UP`/`MOVE`; cualquier otro valor se trata como `DOWN` | `LC/a.java:2144-2153`; `QL/interconnection/MouseAccessibilityService.java:229-235` |
| `action` | QDLink solo distingue **0 = empieza el gesto** y **1 = termina el gesto** (ruta de accesibilidad); el resto son intermedios. **[INFERENCIA]** Son códigos de `MotionEvent` (0 DOWN, 1 UP, 2 MOVE, quizá 5/6 POINTER_DOWN/UP con el índice en los bits 8-15): la ruta multitáctil, que no se usa, pasa `action` directamente a `MotionEvent.obtain` | `QL/interconnection/MouseAccessibilityService.java:174-210`; `SC/managers/d.java:164` |
| `fingerId` | Se lee como byte **con signo** (`h0/e.java:56`) y se usa tal cual como índice. In-app: se inyectan **todas** las entradas con id 0 (si varios dedos llevan id 0, todos) y ninguna otra. Accesibilidad: índice de un array de 10. Cualquier id fuera de 0-9 (10-127, y también `0x80-0xFF`, que son negativos) lanza `ArrayIndexOutOfBoundsException` en un `Handler` del hilo principal y **QDLink se cae**. Rango seguro: 0-9 | `LC/a.java:2142-2155`; `h0/e.java:52-62`; `QL/interconnection/DLinkNotifyL.java:416`, `:1460-1466`, `:1651`; `QL/interconnection/MouseAccessibilityService.java:33`, `:188` |

### 9.3 Espacio de coordenadas por modo

La ruta depende del appType del **último frame enviado** (`E`, que vale 1 antes del primer frame; `LC/a.java:173`,
`:2452`, `:2129-2157`):

| appType | Ruta | Coordenadas que espera QDLink | Ref. |
|---|---|---|---|
| 1 (in-app) | `MotionEvent.obtain(t, t, acción del dedo 0, x, y, 0)` → `Presentation.d()` → `x·(tempLong/inCarW)`, `y·(tempShort/inCarH)` en **float** → `ViewGroup.dispatchTouchEvent` | **px del frame de vídeo (inCar)**: `[0, par(CarWidth)) × [0, par(CarHeight))`. La conversión es la inversa exacta del escalado con que se pinta la vista (`SC/managers/d.java:110-128`), así que **funciona bien** (no hay bug de división entera) | `LC/a.java:2138-2156`; `SC/service/ScreenCaptureService.java:366-368`; `SC/managers/d.java:142-147`; `dex: managers.d.d @0010-0024` |
| 2 (espejo) | `DLinkNotifyL.b()` → `MouseAccessibilityService.k(action, dedos)` → `Path` con `(int)x, (int)y` → `dispatchGesture` | **px de la pantalla del teléfono** en su rotación actual. Los factores `e.K`/`e.L` se pasan pero **no se usan** | `LC/a.java:2129-2135`, `:1904-1909`; `DLinkNotifyL.java:1460-1466`, `:398-423`; `QL/interconnection/MouseAccessibilityService.java:112-126`, `:174-210` |

- **[INFERENCIA]** En modo espejo el coche convierte él mismo sus coordenadas a las del teléfono, usando `PHONE_INFO` y la
  orientación/ángulo de la cabecera de cada frame. Si no lo hiciera, el táctil del espejo de QDLink caería fuera de
  sitio, y el usuario solo se queja de que es torpe.
- Comportamiento de la ruta de accesibilidad: trazos rectos que se despachan al recibir `action` 1 o cada ≥ 200 ms; la
  duración del trazo es la distancia en px, en ms.

**Recomendación**: declarar appType 1 y tratar `x,y` como px del frame (inCar). La test app debe registrar los valores
crudos y pintar en el teléfono las dos hipótesis.

### 9.4 Ejemplos hex

Un dedo, `action` 0, id 0, down, (960.0, 540.0) → 31 bytes:

```
0000: 35 41 35 41 00 00 00 1f  00 00 02 00 00 00 00 00  |5A5A............|
0010: 00 00 00 00 01 00 01 44  70 00 00 44 07 00 00     |.......Dp..D...|
      action=0    N=1 id=0 dn x=960.0      y=540.0
```

Dos dedos, `action` 2 (hipotético), move, (100.5, 200.25) y (300.0, 400.0) → 41 bytes:

```
0000: 35 41 35 41 00 00 00 29  00 00 02 00 00 00 00 00  |5A5A...)........|
0010: 00 00 00 02 02 00 03 42  c9 00 00 43 48 40 00 01  |.......B...CH@..|
0020: 03 43 96 00 00 43 c8 00  00                       |.C...C...|
```

### 9.5 Otras entradas

| Entrada | Por dónde | Códigos | Ref. |
|---|---|---|---|
| Teclas de sistema | msgType 0 `PHONE_KEYS` | 1 Home, 2 Atrás, 3 Recientes | §6.3 |
| Multimedia | msgType 13 `Music` | `PlayControl`, `PlayControlPlay`, `PlayControlPause`, `Prev`, `Next`, `MuteControl` | §6.3 |
| Táctil legado | `!BIN` dataType 10 | int16 en `[66]` = acción (`0x8000` DOWN, `0x8001` MOVE, otro valor UP), `[68]` x y `[70]` y en int16; un solo dedo | `LC/a.java:1243-1262`; `LC/message/g.java:115-117` |
| Mandos del volante | **fuera del protocolo**: AVRCP por Bluetooth (`MEDIA_BUTTON`) | — | `QL/music/musicreceiver/MyMediaButtonReceiver.java:30-81` |
| Rueda o encoder rotativo, volumen, voz | no existen en el protocolo | — | (no hay constantes) |

El modelo JSON del táctil (`KeyEvent` con `ACTION`/`FINGER_COUNT`/`FINGERS[{FingerAction,x,y}]`, `h0/c.java:221-242`)
no tiene llamadas: es código muerto.

---

## 10. Lo que debe hacer un cliente mínimo

### 10.1 Obligatorio, en orden

1. **UDP**:
   - `bind(0.0.0.0:18463)` con `reuseAddress` y `MulticastLock` (QDLink no la usa; es un seguro).
   - Parsear `Connect_Broadcast` con tolerancia (recomendación de §1.4) y registrar el datagrama crudo, la IP origen y
     el puerto origen.
2. **Elegir coche** (automático con el primero o por config) y abrir el `ServerSocket` en el puerto `P`: 10001-65535
   aleatorio o fijo por config, en todas las interfaces.
3. **Enviar `Broadcast_ACK`** (§1.4) **desde el socket de 18463** a `IP_coche:18464`, con reenvío opcional.
4. **`accept()`** con 20 s de límite. Después: `TCP_NODELAY`, `SO_KEEPALIVE` y un `SO_SNDBUF` moderado (QDLink pide
   4 MiB; nosotros, menos para no esconder la congestión).
5. **Nada más aceptar**: AppStatus `!BIN` de 512 B (§4.2) y programar `HEARTBEAT` a 1 s + cada 3 s (§5.1).
6. **Lector robusto** (§3.6) y **watchdog** configurable.
7. `CAR_INFO` → calcular tamaños (§8.6) → `PHONE_INFO` (§6.4) **y después** `UPDATE_NOTIFY {UpdateStatus:5}`, en ese
   orden y desde el hilo escritor.
8. `VIDEO_SUP_REQ` → `VIDEO_SUP_RSP {VideoFormat:3, VideoSupport:1}` **inmediatamente**: no hay MediaProjection que
   esperar.
9. `VIDEO_ARGS` → guardar y responder `SPEECH_ARGS {EncodingType:1, SampleRate:16000, ChannelConfig:1, AudioFormat:16}`.
10. `VIDEO_CTRL {PlayStatus:1}` → arrancar el vídeo (§8):
    - primer mensaje con SPS‖PPS en Annex-B;
    - después un mensaje por access unit;
    - cabecera ext según §8.7;
    - ritmo constante: repetir el último frame si la fuente se para, como hace el timer GL de QDLink.
11. `KEY_FRAME_REQ` → reenviar SPS/PPS **y** pedir un IDR (`PARAMETER_KEY_REQUEST_SYNC_FRAME`). Esto mejora lo que
    hace QDLink.
12. **Táctil (msgType 2)** → parsear, registrar y mostrar (§9).

### 10.2 Decisión para cada mensaje opcional

| Mensaje | Decisión | Por qué (código) |
|---|---|---|
| `LAND_MODE_REQ` | **Responder** `LAND_MODE_RSP {Authority:1, StatusArg:0, Orientation:eco}` sin girar nada | QDLink responde siempre en 5A5A (`LC/a.java:2950-2980`) |
| `BT_ADDR` | **Responder** `BT_RESULT` con los códigos de QDLink en cada rama (§6.8, Recomendación): `NeedAutoConnect==0` → 1, 2 o nada; `≠0` → 2 si la MAC está conectada y 0 si no. No emparejar nunca (desviación consciente) | §6.8 |
| `VIDEO_CTRL {PlayStatus≠1}` | Registrar y **seguir emitiendo**, como QDLink. Opción configurable: pausar con 0 y reanudar con 1 | QDLink no hace nada ni responde (`LC/a.java:2003-2016`). La rama de parar/pausar de `MirrorActivity.q()` solo se alcanza con un `str` distinto de `"play"`, y la ruta 5A5A siempre pasa `"play"` (`IC/MirrorActivity.java:226-232`) |
| `PHONE_KEYS` | Registrar (fase 0). Más adelante, traducir a teclas de Android Auto | `LC/a.java:2055-2069` |
| `Music/*` | Registrar (fase 0). Más adelante, teclas multimedia | §6.3 |
| `AudioSource/AudioSourceState`, `Global/DarkModeOn` | Registrar e ignorar | callbacks vacíos en QDLink |
| `GO_IN_LINK_APP` | Registrar; opcionalmente traer nuestra app al frente | `DLinkNotifyL.java:1992-2003` |
| `DISCONNECT_REQ` | Registrar y **no responder** por defecto, como QDLink. Opción: `DISCONNECT_RSP {CanDisconnect:1}` y cerrar | QDLink nunca responde (`DLinkNotifyL.java:1597-1599`) |
| `HEARTBEAT` del coche | No responder; refrescar el watchdog y medir el periodo | `LC/a.java:2070-2071` |
| msgType 12 / 99 | Registrar tamaño y cadencia; ignorar | callbacks vacíos |
| `!BIN` heartbeat (cmd 10) | Devolverlo con `ret=1`, como QDLink | `LC/a.java:1122-1129` |
| `LOCK_SCREEN_STATUS` | **No enviar** por defecto (decisión, no hecho); opción "emular QDLink" (1/2/3 con los eventos de pantalla) | §7.4. Qué hace el coche con él es una incógnita (§11.11) |
| `WhitelistAppOn` | Si `legal_app_watch==1`: cada 1 s con el valor configurado (por defecto 1, §6.6). Si no, no enviar | `DLinkNotifyL.java:948-961`, `:486-535` |
| `CAR_APP_BACKGROUND` | No enviar automáticamente. Botón de depuración "volver al sistema del coche" | solo lo dispara un botón de la UI de QDLink |
| `PlayState` | No enviar (solo existe para el reproductor interno de QDLink) | `LC/a.java:2897-2915` |
| `PHONE_INFO_CHANGE` | No enviar (solo plegables o cambio de resolución) | `QL/MyApplication.java:142-159` |
| `SPEECH_CTRL`, `CAR_APP_FOREGROUND`, `DISCONNECT_RSP` espontáneo, msgType 99 | No enviar | QDLink no los envía nunca |

### 10.3 Parámetros configurables para la prueba en el coche

- **Red**: puerto TCP (aleatorio o fijo), reenvío del ACK (sí/no, intervalo), selección de coche (automática o por
  UUID/IP).
- **Sesión**: enviar AppStatus (sí/no); periodo del heartbeat; umbrales del watchdog.
- **`PHONE_INFO`**: `Version`, `PhoneUUID`/`PhoneName` y forzar `Phone*`, `Mirror*` y `*InApp`.
- **Vídeo**: tamaño del encoder (auto = `par(CarW)×par(CarH)` o forzado), W×H de la cabecera, appType, orientación,
  ángulo, perfil/nivel H.264, fps, bitrate, GOP, ritmo de repetición del último frame.
- **Opcionales**: política de `WhitelistAppOn`, emular `LOCK_SCREEN_STATUS`, respuesta a `DISCONNECT_REQ`, respuesta a
  `LAND_MODE`/`BT_ADDR`.

---

## 11. Incógnitas (no se pueden resolver leyendo el código)

1. **`Connect_Broadcast` real**:
   - JSON completo (claves además de `DeviceUUID`/`DeviceName`) y valores;
   - hex en mayúsculas o minúsculas;
   - periodo, dirección destino (broadcast limitado o de subred) y puerto origen;
   - si sigue emitiendo durante una sesión.
2. **Validación del ACK en el coche**:
   - ¿exige puerto origen 18463?
   - ¿usa la IP origen para el TCP?
   - ¿qué hace con ACKs repetidos o perdidos y cuánto espera?
   - ¿acepta `DeviceName`/`DeviceUUID` vacíos?
3. **Protocolo TCP del C10**:
   - ¿5A5A (casi seguro, §2.4)?
   - ¿necesita el AppStatus `!BIN`?
   - ¿envía algo antes de recibirlo?
   - orden y temporización reales de `CAR_INFO`, `VIDEO_SUP_REQ`, `VIDEO_ARGS` y `VIDEO_CTRL`, y cuánto espera cada
     respuesta.
4. **Heartbeat del coche**: formato, periodo y **timeout propio**. ¿Corta si no recibe heartbeats o vídeo?
5. **Valores de `CAR_INFO`**:
   - `CarWidth`/`CarHeight`;
   - `CarType`/`CarFactory`/`HUFactory` (la BD dice `2D4`/`2D5`, `018`, `119`);
   - `Version`, `Platform`, `PlatformVersion`;
   - **significado de `MirrorTypeReq`**;
   - `CarFeature` (¿`legal_app_watch`?), `ProjectID`, `CarUUID`.
6. **`VIDEO_ARGS` reales** y la unidad que quiere el coche para `FrameInterval` (¿segundos o frames?); confirmar que
   `BitRate` va en bps. ¿`Width/Height` coinciden con `CarWidth/CarHeight`?
7. **Qué usa el coche de la cabecera de vídeo**:
   - ¿W×H de la cabecera o tamaño del SPS?
   - ¿orientación/ángulo?
   - ¿qué cambia entre appType 1 y 2?
   - ¿tolera orientación `FF`?
8. **Decodificador**: perfiles (Main/High), niveles, 60 fps, bitrate máximo, cambios de resolución a mitad de stream y
   si el SPS/PPS tiene que ir en un mensaje aparte. ¿Necesita frames continuos o tolera huecos (contenido estático de
   Android Auto)?
9. **Táctil**: espacio de coordenadas en appType 1 y 2, códigos de `action` distintos de 0/1, multitáctil real, rango
   de `fingerId`, frecuencia de los MOVE.
10. **Semántica** de `UpdateStatus=5`, `VideoFormat` en `VIDEO_SUP_REQ`, `PlayStatus ≠ 1` y `KEY_FRAME_REQ` (¿cuándo lo
    pide?).
11. **Reacción del coche** a `LOCK_SCREEN_STATUS` (1/2/3), a `WhitelistAppOn` = 0 o ausente (¿depende de la
    velocidad?), a `LAND_MODE_RSP` (`Authority`/`StatusArg`) y a `BT_RESULT`.
12. **`DISCONNECT_REQ`**: ¿cuándo lo manda el coche?, ¿espera `DISCONNECT_RSP`?, ¿cierra él?
13. **Bytes 11, 12, 14 y 15** de la cabecera 5A5A cuando escribe el coche; contenido de msgType 12 (¿PCM de 16 kHz,
    mono, 16 bit?) y de msgType 99.
14. **¿Valida el coche** `PHONE_INFO.Version`, `Platform` o `PhoneBrand`?

**Cómo cerrarlas en el viaje** (sin root):
- **Logs de QDLink**: en Ajustes, 6 toques en menos de 2,2 s en la esquina inferior izquierda
  (`QL/mine/setting/SettingView.java:46`, `:576-587`, `:648-650`) → interruptor de logs → `adb logcat`.
  - Líneas útiles: `parsingNewData … strData:` (cada JSON del coche, `LC/a.java:1942`), `sendVersionNewProtocol PhoneInfo
    strData:` (`:947`), `parsingNewData keyEvent x:` (`:2082`), `sendMirrorDataBytesH264NewProtocol111,dataWidth:` por
    frame (`:1480`) y `calcScreenFromCar` (`SC/service/ScreenCaptureService.java:659`).
  - La etiqueta del log es `Clase.método(L:n)` (`IU/g.java:122-129`).
- **Nuestra test app**: registrar cada byte recibido y enviado con marca de tiempo.

---

## Anexo A · Comprobaciones sobre el bytecode

`dexdump -d classes.dex` (build-tools 36.0.0) y `jadx -m fallback`:

| Qué | Método | Instrucciones | Resultado |
|---|---|---|---|
| `rc` de la resolución | `ScreenCaptureService.H(II)Lb0/a;` | `@0119 int-to-float v14,v14` · `@011a int-to-float v15,v15` · `@011b div-float/2addr v14,v15` | división **float** |
| `rp`, `outHorW`, `outHorH`, `outVerW`, `tempShort` | ídem | `@011e-0120`, `@0129-012c`, `@0164-0166`, `@0191-0193`, `@0148-014a` | todas float, después `float-to-int` |
| Rama vertical (`f10`) | ídem | `@01e4-01e6 int-to-float; div-float` | float (jadx muestra `i15 / i17`) |
| Escala del táctil in-app | `managers.d.<init>`, `managers.d.d`, `managers.d.e` | `@002f-003f`, `@0010-0024`, `@0042-0094` (`int-to-float` + `div-float`) | float |
| Intervalo del timer GL | `glec.a.q(I)` | `@000d div-int/2addr` | entero (`1000/fps`) |
| Orden de la cabecera ext | `h0.d.h()` | `@000c-0073` | §8.1 |
| Setters de `l0` | `linkconnection.a.l0` | `@0013-008e` | §8.1 |
| Argumentos del sumidero de vídeo | `managers.a.U([BIIII)V` | `@0005-0078` | §8.7 |
| Orden del AppStatus | `message.b.a()` | `@0029-005e` | §4.2 |
| Watchdog | `linkconnection.a$r.run()` | `@000a-001e` (`> 5000` y `"5A5A".equals(W)`) | §5.3 |
| `CAR_INFO` | `linkconnection.a.U` (jadx fallback) | `getCarType` → `H0` → `B0` → `z0` | el `break` de jadx es falso |
| `VIDEO_SUP_REQ` en el sabor QDriveLink | ídem | `r7 = 1` → `q0()` en cada petición | §6.3 |
| `switch` de `CMD` | `linkconnection.a.U` | `@051f sparse-switch` → `@0812` (10 claves) y `@0588 packed-switch` (10 destinos) | §6.3 |
| Fallo al leer la cabecera | `linkconnection.a.S0([BII)I` | `@0055-0058` (`sput-object null` a `utils.a.v` y `utils.a.w`), sin llamada a `L()` | §3.4 |
| Receptor Bluetooth | `DLinkNotifyL$m.onReceive` | `@0106-01e7` (rama `CONNECTION_STATE_CHANGED`) | §6.8 (jadx reconstruye mal el `switch`) |

## Anexo B · Constantes

| Constante | Valor | Ref. |
|---|---|---|
| UDP escucha / destino | 18463 / 18464 | `WF/a.java:30-31` |
| Magic UDP / tipos | `QDrive_SSPLink_UDP_MSG` / `Connect_Broadcast` / `Broadcast_ACK` | `WF/a.java:32-34` |
| Offset del JSON en el broadcast | 49 | `WF/a.java:501`, `:563` |
| Rango de MirrorPort | 10001-65535 | `WF/a.java:253` |
| Timeout de accept | 20 000 ms | `WF/d.java:65` |
| Magic TCP | `5A5A` (`35 41 35 41`) / `!BIN` (`21 42 49 4E`) | `h0/a.java:33`; `IU/a.java:24`, `:33` |
| Heartbeat | `{"CMD":"HEARTBEAT"}`, 1000 ms + 3000 ms | `LC/a.java:208`, `:52`, `:1761` |
| Watchdog | comprueba cada 5000 ms (primera a 1000), umbral > 5000 ms, solo en 5A5A | `LC/a.java:53`, `:690`, `:1678` |
| msgType | 0 control, 1 vídeo, 2 táctil, 12 voz, 13 app, 99 personalizado | `LC/a.java:1944-2318` |
| payLoadFormat | 0 binario, 1 JSON, 2 vídeo | `LC/a.java:953`, `:1476`, `:2076` |
| Cabecera ext de vídeo | 32 B | `h0/d.java:9`, `:51` |
| appType | 1 in-app, 2 espejo | `SC/managers/a.java:638-657` |
| Tipos de captura | 983041 H.264, 983042 (EncodingType 1, sin vídeo), 983057 in-app, 983058 espejo | `SC/utils/e.java:5-8`, `:94` |
| Encoder | `video/avc`, Baseline (1), nivel 3.1 (512), VBR (1), complexity 1, Surface | `SC/managers/a.java:560-584` |
| Valores por defecto de captura | 2 764 800 bps, 24 fps, GOP 4, DPI 1, 1280×720 | `c0/a.java:12-30` |
| Fallback de tamaño de coche | 800×480 | `LC/a.java:919` |
| `VIDEO_SUP_RSP` | `VideoFormat` 3 | `LC/a.java:2400` |
| `SPEECH_ARGS` | 1 / 16000 / 1 / 16 | `LC/a.java:2475-2478` |
| `UPDATE_NOTIFY` | 5 | `LC/a.java:1533` |
| C10 en la BD | `018` / `2D4` (L) o `2D5` (R) / `119` | `RES/res/raw/linkmanager.db3` filas 167-168 |

## Anexo C · Diferencias con 00-03

1. **No hay división entera** ni en `rc` ni en la escala del táctil in-app (§0.3, Anexo A):
   - en 02 §3.3 sobran el pseudocódigo "¡DIVISIÓN ENTERA!", la columna "rc (div. entera)" y la fila 1920×720 → 1920×864
     ("> alto del coche, por el bug"); el valor real es 1600×720 con un teléfono de 1080×2400 y 1560×720 con el S25U;
   - en 03 §6.4 sobra el "bug" de factores 1/0: el táctil in-app escala bien;
   - `tempScreenShort` es `ladoLargo/(W/H)` con W/H en float (p. ej. 1316 para un coche 16:9 en el S25U), no
     `ladoLargo/⌊W/H⌋`.
2. **Orden del JSON**: no es "no determinista". En Android ≥ 7 es `{"PARA":{…},"CMD":"…"}` (PARA primero) y
   `{"FunctionID","AppID","Para"}`, con el orden interno de §6.1. Los ejemplos de 03 §4.3 (`CMD` primero) no
   coinciden con lo que se envía. Lo que sí es cierto es que el coche no puede depender del orden (cambia en Android 5-6).
3. **Cita del parser táctil**: `h0/e.java:43-64`, no `:204-225` (03 §6.1).
4. El `break` de `LC/a.java:1960` es un artefacto de jadx: `PHONE_INFO`/`UPDATE_NOTIFY` se envían siempre tras
   `CAR_INFO`.
5. **Orientación de la cabecera de vídeo**: vale 1 para **cualquier** rotación distinta de 0, también 180° (02 §3.5
   dice "1 horizontal").
6. **`EncodingType`**: solo el valor 1 apaga el vídeo. 0, 2 o cualquier otro valor distinto de 1 producen H.264, igual
   que 3 (02 §2 solo mencionaba 1 y 3).
7. **Watchdog**: el corte efectivo llega entre 5 y 10 s sin datos con el enlace mudo (entre 0 y 5 s tras un EOF o un
   error de lectura), y solo si el coche ya ha hablado 5A5A (00/03 dicen "> 5 s").
8. **`VIDEO_SUP_REQ`**: en el sabor QDriveLink **cada** petición vuelve a abrir el diálogo de MediaProjection.
9. **ACK UDP**: siempre mide 174 B (`00AE`/`0081`), porque el puerto siempre tiene 5 cifras. Se llama
   `Broadcast_ACK` ("AddDevice" en 03 §2 es solo el texto de un log).
10. **`Connect_Broadcast`**: QDLink parsea el JSON en el offset 49 **antes** de comprobar el magic; cualquier datagrama
    ajeno en 18463 pone el estado global a -1. 01 §3.2 decía que "solo usa `contains()` y el offset fijo".

## Registro de verificación

Dos verificadores independientes contrastaron este documento con el código decompilado. Cada punto se volvió a
comprobar en el código (y en el bytecode donde se indica) antes de aplicarlo o rechazarlo.

| # | Sección | Resultado | Motivo |
|---|---|---|---|
| 1 | §3.4 puntos 4 y 5, §3.6, §6.1, §6.5 | aplicado | Los parsers de `h0/c` capturan su `JSONException`: un JSON incompleto no se descarta y QDLink responde con valores por defecto |
| 2 | §8.1, §8.2 | aplicado | Con screenType 0 una rotación no recrea el encoder ni reenvía SPS/PPS (`:192`, `:201`); se añade además el cambio de idioma como disparador |
| 3 | §3.6, §4.1 | aplicado | `dataType` 3 solo lee extra con `action` 1 (`totalsize − headersize`); `dataType` 12 lee siempre `totalsize − 512` |
| 4 | §8.1, §8.7, §8.8 | aplicado | `Z()` solo reinicia la orientación: al entrar en espejo sale `FF` con el último ángulo sondeado, no siempre `FF`/0 |
| 5 | §3.4 puntos 2 y 6, §5.3, Anexo C.7 | aplicado | Un fallo de lectura de cabecera anula los dos streams sin `L()` (comprobado en `dex: a.S0`); tras un EOF el corte llega en 0-5 s |
| 6 | §2.1 | aplicado | El `ServerSocket` sigue escuchando tras el `accept` y tras el timeout de 20 s |
| 7 | §9.2 | aplicado | `fingerId` es un byte con signo: los ids `0x80-0xFF` también tumban QDLink; in-app se inyectan todas las entradas con id 0 |
| 8 | §8.6 | aplicado | 1919/1079 = 1,778499… → 1,778 (recalculado en float32); el resto de la fila y de la tabla era correcto |
| 9 | §6.2, §6.3 | aplicado | `LOCK_SCREEN_REQ`, `UPDATE_PKG_REQ` y `MuteState` están declarados pero sin uso; el `switch` de `CMD` tiene 10 casos (comprobado en dex) |
| 10 | §7.1, §6.3 (`VIDEO_CTRL`) | aplicado | La captura solo arranca con un `VIDEO_CTRL{1}` posterior a aceptar la MediaProjection |
| 11 | §6.3 (`BT_ADDR`) | aplicado | Sin `BluetoothAddr` salta una NPE en `:2033` y no hay `BT_RESULT` |
| 12 | §6.8, §6.2 (`BT_RESULT`), Anexo A | aplicado con corrección | Confirmado salvo el receptor A2DP: según el bytecode, `CONNECTED` de otro dispositivo no envía nada; solo `DISCONNECTED` (de cualquiera) envía 3 |
| 13 | §6.8 (Recomendación), §10.2 | aplicado | Con `NeedAutoConnect == 0` QDLink usa 1 para "no conectado", no 0; la recomendación ahora replica los códigos de cada rama |
| 14 | §6.3 (`GO_IN_LINK_APP`), §7.4, §8.7 | aplicado | Volver a QDLink pasa a in-app (SPS/PPS nuevos); el botón de voz pasa a espejo a los 700 ms |
| 15 | §7.4 | aplicado | `FULL_WAKE_LOCK` toda la sesión y `MainPageView.L()` despierta la pantalla |
| 16 | §6.2 (`SPEECH_CTRL`) | aplicado | Existen las variantes `SpeechStatus` 1 (`r0`) y 0 (`s0`), ambas sin llamadas |
| 17 | §6.3 (`VIDEO_ARGS`), §6.9 | aplicado | Un `VIDEO_ARGS` con el vídeo en marcha solo cambia los ecos de la cabecera ext; el encoder no se recrea |
| 18 | §10.2 | aplicado | Fila nueva: `VIDEO_CTRL{PlayStatus≠1}` se registra y no detiene el vídeo, como en QDLink |
| 19 | §7.4 (Recomendación), §10.2 | aplicado en parte | Se marca como decisión e [INFERENCIA]; se mantiene "no enviar por defecto", porque con QDLink la pantalla casi nunca se apaga y el coche apenas ve `{1}` |
| 20 | §6.7 | aplicado | "usage" y "lanzador" dependen del `type` de la BD (`C10_i_L`/`C10_i_R`), no de `CarType`; la conclusión (`StatusArg` = 0) se mantiene |
| 21 | §6.6 | aplicado | El timer también arranca por `f10009d0` sin enviar `WhitelistAppOn`; sin `CAR_INFO` previo (`Z == null`) no hay lista blanca |
| 22 | §6.2 (`VIDEO_SUP_RSP`), §6.3, §7.2, §7.5 | aplicado | Sin encoder, `d()` desmonta la sesión sin cerrar el socket; al rechazar la captura, el cierre compite con el `{0}` |
| 23 | §6.2 (`PlayState`) | aplicado | Solo lo disparan clics en `MusicPlayView`, nunca los `Music/*` del coche |

Total: 23 aplicados (uno con corrección y otro en parte), 0 rechazados.
