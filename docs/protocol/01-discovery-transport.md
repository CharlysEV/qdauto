# 01 · Descubrimiento, transporte y ciclo de vida de la conexión (QDLink 1.9.7)

Paquete analizado: `com.neusoft.qdrivelink` v1.9.7 (versionCode 107), decompilado con jadx.
Ámbito de este documento: cómo se ponen teléfono y coche en la misma red, cómo se descubre el coche, qué sockets se abren, el ciclo de vida completo (handshake, heartbeats, timeouts, desconexión y reconexión), la autenticación, el modo USB (resumen) y los requisitos de pantalla encendida.
El formato de los mensajes de control y el pipeline de vídeo se documentan aparte. Aquí solo se tratan en la medida en que afectan al ciclo de vida.

## Convenciones de citas

Todas las rutas son relativas a `decomp\qdlink\`. Prefijos:

| Prefijo | Ruta real |
|---|---|
| `IC/` | `sources/com/neusoft/interconnection/` |
| `QL/` | `sources/com/neusoft/qdrivelink/` |
| `WD/` | `sources/com/neusoft/qdrive/wifidirect/` |
| `SC/` | `sources/com/neu/ssp/mirror/screencap/` |
| `h0/`, `f0/`, `g0/` | `sources/h0/`, `sources/f0/`, `sources/g0/` |
| `RES/` | `resources/` |

`archivo:N` es una línea y `archivo:N-M` un rango. Cuando algo es una **inferencia** (no se ve literalmente en el código) se indica explícitamente.

---

## 0. Resumen

* **No hay IPs ni SSIDs fijos.** QDLink no crea ni configura redes Wi-Fi. Hay dos modos Wi-Fi:
  1. **"热点连接" / Hotspot Connection**: el usuario activa el hotspot del teléfono y conecta el Wi-Fi del coche a ese hotspot.
  2. **"无感连接" / Wi-Fi Direct**: P2P de Android. El teléfono se une con `groupOwnerIntent=0`, así que el coche queda como Group Owner.
* **Descubrimiento por UDP, pasivo en el teléfono.** El teléfono escucha en `0.0.0.0:18463` las tramas `Connect_Broadcast` que emite el coche. Cuando el usuario elige un coche, responde **una sola vez** con una trama `Broadcast_ACK` en unicast a `IP_coche:18464` (puerto origen 18463). Esa trama lleva en JSON el `MirrorPort`, un puerto TCP aleatorio entre 10001 y 65535.
* Formato de trama UDP, todo en ASCII: `"QDrive_SSPLink_UDP_MSG"` + longitud total (4 dígitos hex) + longitud del tipo (2 dígitos hex) + tipo + longitud del JSON (4 dígitos hex) + JSON.
* **Un único socket TCP para todo.** El teléfono es el **servidor** (`ServerSocket(MirrorPort)`) y el coche es el cliente que conecta. Por ese socket va todo: control JSON, vídeo H.264, toques, voz y heartbeats. `ControlPort` y `AudioPort` se anuncian como 0. El audio multimedia va por Bluetooth A2DP, no por Wi-Fi.
* **Ciclo de vida:**
  1. Accept (el teléfono espera 20 s como máximo).
  2. El teléfono envía `AppStatus` en formato antiguo `"!BIN"` (bloque de 512 B).
  3. El teléfono envía un heartbeat JSON `{"CMD":"HEARTBEAT"}` con cabecera `"5A5A"` a 1 s y luego **cada 3 s**.
  4. Watchdog de recepción: **5 s** sin datos cortan la sesión (solo si el coche habla `5A5A`).
  5. Intercambio de control: `CAR_INFO` → `PHONE_INFO`; `VIDEO_SUP_REQ` → (diálogo de MediaProjection) → `VIDEO_SUP_RSP`; `VIDEO_ARGS` → `SPEECH_ARGS`; `VIDEO_CTRL{PlayStatus:1}` → empieza el vídeo.
* **No hay reconexión automática, ni autenticación, ni cifrado.** La comprobación contra el servidor de activación existe pero está desactivada en este build.

---

## 1. Mapa de clases ofuscadas (rol → archivo)

| Rol (nombre deducido) | Archivo | Evidencia |
|---|---|---|
| Hilo UDP de descubrimiento ("UdpReceiveDataThread") + constructor del ACK + puente P2P | `IC/wificonnection/a.java` | tag `L = "UdpReceiveDataThread"` (`:29`) |
| Callback de la UI (lista de dispositivos, éxito/fallo) | `IC/wificonnection/b.java:7-15` | |
| Fachada singleton del gestor Wi-Fi | `IC/wificonnection/c.java` | |
| Hilo servidor TCP ("wifiserversocketthread") | `IC/wificonnection/d.java` | log en `:75` |
| Gestor Wi-Fi Direct genérico (librería) | `WD/c.java`, callback `WD/a.java` | |
| API pública de la librería (InterConnection) | `IC/d.java`, `IC/b.java` | |
| LinkManager (singleton que posee el motor) | `f0/b.java` | log "LinkManger getInstance" (`:72`) |
| ConnectionManager: motor de protocolo (lectura, escritura, heartbeats, parser) | `IC/linkconnection/a.java` | logs "ConnectionManager …" (`:987`, `:1158`) |
| Conector USB AOA | `IC/linkconnection/c.java` | |
| LinkConfig (estado global: modo, sockets, puertos, nombres) | `IC/utils/e.java` | log "LinkConfig.getInstance().getLinkMode()" en `IC/linkconnection/a.java:693` |
| ConnConstant (streams, firmas `!BIN`/`5A5A`) | `IC/utils/a.java` | |
| Utilidades de bytes/hex | `IC/utils/i.java` | |
| Cabecera del protocolo nuevo `"5A5A"` | `h0/a.java` | |
| Helper de comandos JSON (`CMD`/`PARA`) | `h0/c.java` | |
| Diálogo con la lista de coches | `QL/wifilink/LinkDialog.java` | |
| Actividad que arranca el enlace | `QL/interconnection/ConnectActivity.java` (hereda de `IC/MirrorActivity.java`) | |
| Servicio que implementa los callbacks de negocio (`f0.a`) | `QL/interconnection/DLinkNotifyL.java` | |
| Servicio de captura (MediaProjection + encoder) | `SC/service/ScreenCaptureService.java` | |

Variables de estado de `IC/utils/e.java` (LinkConfig):

* `h()`/`w()` (`f9735m`, `:101`, `:179`, `:235`): **transporte elegido**. 0 = USB y 1 = Wi-Fi. `IC/linkconnection/a.java:883-896` crea el conector USB si vale 0 y el servidor TCP si vale 1.
* `b()`/`q()` (`f9734l`, `:98`, `:159`, `:215`): **estado de conexión**. -1 = desconectado, 0 = USB conectado (`IC/linkconnection/c.java:147`), 1 = Wi-Fi conectado (`IC/wificonnection/d.java:102`).
* `i()`/`x()` (`f9736n`, `:104`, `:183`, `:239`): MirrorPort (puerto TCP).
* `d()`, `e()`, `j()`: DeviceName, DeviceUUID y PassistMobileNum del teléfono. Valen `""` por defecto (`:83-89`) y su setter `IC/b.java:40-49` no tiene ningún llamador en todo el código. Por eso **el teléfono anuncia nombre y UUID vacíos**.
* `m()`/`C()` (`f9730h`, `:92`, `:147`, `:199`): "uuidName". Es el nombre del peer P2P que se está conectando y se usa para emparejar la trama UDP.

---

## 2. Cómo se ponen teléfono y coche en la misma red

QDLink **no** usa ninguna API para crear o configurar redes: no aparecen `WifiConfiguration`, `WifiNetworkSpecifier`, `LocalOnlyHotspot`, `setWifiApEnabled` ni `SoftApConfiguration`. Tampoco hay IPs LAN codificadas. La única IP privada del código es `10.10.94.46:8081`, un servidor interno de actualizaciones (`QL/c.java:235`, `QL/upgrade/update/UpdateUtils.java:88,155`), que no tiene nada que ver con el enlace. La IP del coche se obtiene **de la dirección origen del datagrama UDP** que emite el coche (`IC/wificonnection/a.java:499`).

### 2.1 Modo "Hotspot Connection" ("热点连接", modo `WIFI_LINK = 0`)

* **Entrada en la UI:**
  * El elemento `cl_wifi` (texto `@string/link_wifi_connected` = "Hotspot Connection", `RES/res/layout/layout_setting.xml:27`; `RES/res/values/strings.xml:69`) lleva a `QL/mine/setting/SettingView.java:437-445` y de ahí a `R()` (`:528-532`).
  * `R()` guarda `WUGAN_LINK=false` (`QL/c.java:178`) y publica `ShowLinkBean`.
  * `MainPageView.showLinkDialog` (`QL/mainpage/MainPageView.java:663-670`) llama a `LinkDialog.startSearch()`.
* **Instrucciones al usuario:**
  * `strings.xml:61`: "1、打开手机热点 2、中控屏Wi-Fi连接手机热点" (*1. activa el hotspot del móvil; 2. conecta el Wi-Fi de la pantalla central al hotspot del móvil*).
  * `strings.xml:60`: recomienda la banda de **5 GHz** "para mayor estabilidad; solo algunos modelos".
  * Pasos por marca para poner el AP en 5 GHz: `QL/wifilink/PhoneHostWifiHelpView.java:54,61,69` y siguientes. Ayuda general: `QL/wifilink/LinkHelpView.java:168-173`.
* **Roles de red.** El **teléfono es el AP** y el **coche es un cliente Wi-Fi**. El SSID y la contraseña son los que el usuario tenga configurados en su hotspot. La app no los conoce ni los toca.
* **No hace falta Wi-Fi cliente activo.** Si el Wi-Fi del móvil está apagado y el modo no es "wugan", se sigue con `link(false)` (`QL/wifilink/LinkDialog.java:201-210`).
* **Activación del modo.** `link(false)` (`LinkDialog.java:195-199`) llama a `IC/d.java:232-237` (`k(0)`), luego a `IC/wificonnection/c.java:133-138` y finalmente a `a.D(0)` (`IC/wificonnection/a.java:293-302`), que fija `wifiModeType = 0`. Desde ese momento la lista de dispositivos se llena con las tramas UDP recibidas (sección 3).
* **Direcciones IP.** Las asigna el DHCP del hotspot de Android. Por conocimiento general de Android, no del código: el AP suele ser `192.168.43.1/24` en Android ≤ 10, y a partir de Android 11 la subred se aleatoriza.

### 2.2 Modo "Wi-Fi Direct" ("无感连接" = "conexión sin intervención", `WUGAN_LINK = 1`)

* **Entrada en la UI:**
  * El elemento `cl_direct` ("Wi-Fi Direct", `layout_setting.xml:52`; `strings.xml:70`) lleva a `SettingView.java:447-455` y de ahí a `S()` (`:552-557`).
  * `S()` guarda `WUGAN_LINK=true`, llama a `IC/d.java:334-339` (`v()`), luego a `IC/wificonnection/c.java:147-152` y a `a.E()` (`IC/wificonnection/a.java:304-309`).
  * `a.E()` acaba en `WD/c.java:1114-1140` (`f0()`, `discoverPeers`).
  * Después `LinkDialog` llama a `link(true)`, lo que fija `wifiModeType = 1`.
* **Instrucciones al usuario** (`strings.xml:72`): "1、打开手机Wi-Fi 2、打开中控屏Wi-Fi 3、在搜索列表中选择中控设备名称" (*activa el Wi-Fi del móvil y el del coche, y elige el nombre de la pantalla central en la lista*). Ayuda: `LinkHelpView.java:180-185`.
* **Inicialización P2P.**
  * Al arrancar el hilo UDP se llama a `a.w()` (`IC/wificonnection/a.java:266-271`, invocado en `:480-482`). Esta llama a `WD/c.T(ctx, cb)` (`WD/c.java:1106-1108`), que a su vez llama a `R(ctx, canal=-1, nombre=null, rol=0, modo=0)` (`WD/c.java:1071-1100`).
  * **Rol 0** significa intent de GO = 0. **Modo 0** significa descubrimiento simple de peers, sin filtro de servicio.
  * Al habilitarse P2P, la app cancela cualquier conexión y borra cualquier grupo previo: `cancelConnect` + `removeGroup` (`WD/c.java:837-848`).
* **Lista de peers.**
  * La lista incluye *todos* los peers P2P cercanos, cada uno como `SearchDevice` de tipo 2 (`IC/wificonnection/a.java:369-397`), con:
    * nombre = `WifiP2pDevice.deviceName`;
    * `c()` = dirección MAC;
    * estado, traducido así (`WD/c.java:735-755`): CONNECTED pasa a 2, INVITED a 1, AVAILABLE a 0, y los demás se descartan.
  * Un hilo vuelve a lanzar `discoverPeers` cada **3 s** mientras no hay grupo (`WD/c.java:542-582`, `sleep(3000)` en `:571`).
* **Conexión.**
  1. Al pulsar un peer, `a.p()` hace `LinkConfig.C(deviceName)` y llama a `WD/c.E(mac)` (`IC/wificonnection/a.java:454-460`).
  2. `E()` crea un `WifiP2pConfig` con **`groupOwnerIntent = 0`** (`WD/c.java:988-998`). El teléfono quiere ser cliente, así que **el coche será el Group Owner**.
  3. Si el grupo no se forma en **60 s** se hace `cancelConnect` (`WD/c.java:902-917` y `:334-367`).
* **Grupo formado.**
  * Se leen la IP del GO, la MAC del GO, el SSID `DIRECT-…` y la passphrase (`WD/c.java:785-799`).
  * El callback solo los **registra en el log** (`IC/wificonnection/a.java:424-426`) y no los usa.
  * La IP del coche se obtiene igualmente del datagrama `Connect_Broadcast` (sección 3.5).
  * Por conocimiento general de Android, no del código: un GO Android usa `192.168.49.1/24`.
* **Capacidades de la librería que QDLink no usa** (dan pistas sobre el lado del coche):
  * DNS-SD con instancia `"QDLink_DnsSd_Instance"` y tipo `"_ssplink._tcp"` (`WD/c.java:98`, `:935-947`).
  * UPnP `"QDLink_UPnP_Device"` (`WD/c.java:101`, `:158-189`, `:950-954`).
  * `createGroup` para rol 2 (`WD/c.java:690-695`, `:849-853`).
  * `setDeviceName` y `setWifiP2pChannels` por reflexión (`WD/c.java:879-900`).
  * Una reinicialización con canal operativo **48** (5 GHz), `IC/wificonnection/a.java:596-607`. Solo se alcanza desde `IC/d.java:163-184` (`f()`), que no tiene llamadores, así que es código muerto.

### 2.3 USB (AOA)

Resumen en la sección 6.

---

## 3. Descubrimiento del coche (UDP)

### 3.1 Socket de escucha

> *Fragmento del código decompilado de QDLink omitido en la versión pública (referencia: `IC/wificonnection/a.java:486-496`).*

* Constantes (`IC/wificonnection/a.java:30-34`):
  * `M = 18463` es el puerto local de escucha.
  * `N = 18464` es el puerto destino en el coche.
  * `O = "QDrive_SSPLink_UDP_MSG"` es la firma.
  * `P = "Connect_Broadcast"` y `Q = "Broadcast_ACK"` son los tipos.
* El buffer es de **1024 bytes** (`:490`). Las tramas más largas se truncan.
* **Cuándo arranca.**
  1. `MainActivity.onCreate` (`QL/MainActivity.java:931`) llama a `J()` (`:797-802`).
  2. `J()` llama a `IC/d.java:371-376` (`y(ctx,true)`, solo si el estado de conexión no es 1).
  3. Esa llamada acaba en `IC/wificonnection/c.java:161-166`, que crea y arranca el hilo.
* **Duración.** Vive mientras viva la app. Una desconexión no lo para (`L()` y `A()` no lo tocan, ver 5.7). Solo `IC/d.java:117-138` (`B()`, al salir de la app) baja su flag con `IC/wificonnection/c.java:191-198`, y aun así **el socket nunca se cierra explícitamente**.
* No se adquiere `MulticastLock` (no hay ninguna referencia en el código).
* **El teléfono nunca emite descubrimiento.** El único `send()` UDP de toda la app está en `IC/wificonnection/d.java:80` y envía el ACK.

### 3.2 Formato de trama UDP

Se deduce de los constructores (`IC/wificonnection/a.java:134-137`, `:187-190`), de los helpers `i.r()`/`i.s()` (`IC/utils/i.java:179-193`) y de los offsets fijos del parser (`IC/wificonnection/a.java:501`, `:563`). Todo es texto ASCII:

| Offset | Long. | Campo | Nota |
|---|---|---|---|
| 0 | 22 | `QDrive_SSPLink_UDP_MSG` | firma |
| 22 | 4 | longitud **total** de la trama | hex ASCII en mayúsculas, con ceros a la izquierda (`i.r`) |
| 26 | 2 | longitud N del tipo | hex ASCII (`i.s`): `0D` para el ACK y `11` para `Connect_Broadcast` |
| 28 | N | tipo | `Connect_Broadcast` (17) o `Broadcast_ACK` (13) |
| 28+N | 4 | longitud M del JSON | hex ASCII (`i.r`) |
| 32+N | M | JSON | |

* La longitud total es 32 + N + M. Para el ACK queda `45 + json.length()` (`IC/wificonnection/a.java:134`, `:187`).
* En `Connect_Broadcast` el JSON empieza en el offset **49** = 22+4+2+17+4. El parser lo usa como `substring(49)` (`:501`) y como `substring(O.length()+P.length()+10)` (`:563`).
* El teléfono **no valida** los campos de longitud: solo usa `contains()` y el offset fijo.
* Las longitudes se calculan en caracteres pero se envía `getBytes()`. Si los textos no son ASCII habría desajuste. Hoy no ocurre porque el nombre está vacío.

### 3.3 `Connect_Broadcast` (coche → teléfono, destino puerto 18463)

* Del JSON el teléfono solo lee `DeviceUUID` y `DeviceName` (`IC/wificonnection/a.java:502-503`, `:564-565`). Si falta alguno salta una excepción y **el estado de conexión pasa a -1** (`:526-528`).
* El resto del contenido, la periodicidad y la dirección destino (broadcast limitado o dirigido) no se pueden ver desde el teléfono (ver Incógnitas).
* Ejemplo **hipotético**, construido con el mismo formato:

```
QDrive_SSPLink_UDP_MSG005811Connect_Broadcast0027{"DeviceUUID":"abc","DeviceName":"C10"}
```

### 3.4 `Broadcast_ACK` (teléfono → coche, a `IP_coche:18464`)

JSON del ACK (`IC/wificonnection/a.java:171-181` en el camino hotspot y `:119-128` en el camino P2P):

> *Fragmento del código decompilado de QDLink omitido en la versión pública.*

Trama resultante exacta, de 174 bytes, con `MirrorPort` = 34567 como ejemplo. El `org.json` de Android conserva el orden de inserción y genera el JSON compacto (conocimiento general de Android):

```
QDrive_SSPLink_UDP_MSG00AE0DBroadcast_ACK0081{"ControlPort":0,"MirrorPort":34567,"AudioPort":0,"OS":0,"DeviceName":"","DeviceUUID":"","DeviceFeature":{"PassistMobileNum":""}}
```

Detalles de envío:

* El `DatagramPacket` se **prepara** en uno de dos momentos y se guarda en LinkConfig (`IC/utils/e.java:247-249`):
  * en modo hotspot, cuando el usuario pulsa el coche (`:190`);
  * en modo P2P, cuando llega el broadcast cuyo nombre coincide (`:137`).
* Se **envía una sola vez**, sin reintentos, desde el socket ligado a 18463 (`IC/wificonnection/d.java:80`: `e.f().c().send(e.f().k())`). Por tanto el **puerto origen es 18463**.
* El envío ocurre **justo después** de abrir el `ServerSocket(MirrorPort)` (`IC/wificonnection/d.java:78-80`), de modo que el puerto ya escucha cuando el coche recibe el ACK.

### 3.5 Lógica de recepción (pseudocódigo fiel a `IC/wificonnection/a.java:491-531`)

```
loop:
  pkt = receive()                                   # 1024 B
  str = String(pkt); carIp = pkt.sourceAddress       # :498-499
  json = JSON(str[49:]); uuid = json.DeviceUUID; name = json.DeviceName   # :501-503
  if LinkConfig.uuidName.contains(name):            # :505  (camino Wi-Fi Direct)
      if estadoConexion != 1:                        # :507
          p2p.stopPeerDiscovery()                   # :508-511
          port = puertoAleatorio(); LinkConfig.mirrorPort = port   # :512-513
          prepararACK(port, carIp) -> callback éxito # :514, :99-152
          estadoConexion = 1                         # :515
  else:
      x(): si str contiene "QDrive_SSPLink_UDP_MSG" y "Connect_Broadcast":   # :519, :560-594
           añadir SearchDevice{uuid, name, ip=carIp, tipo=1} sin duplicados (uuid+ip)
on excepción: estadoConexion = -1                    # :526-528
```

* **Camino hotspot.**
  * El usuario pulsa el coche en `LinkDialog` (`QL/wifilink/LinkDialog.java:270-277`). Eso llama a `IC/d.java:239-245`, que **no hace nada si hay un accesorio USB conectado** (`e.f().p()`, activado en `IC/linkconnection/c.java:149`).
  * Después pasa por `IC/wificonnection/c.java:84-90` y llega a `a.p()`, que lanza el hilo `c` para el tipo 1 (`IC/wificonnection/a.java:447-452`, `:154-204`).
* **Camino Wi-Fi Direct.**
  * Tras formarse el grupo, el teléfono espera un `Connect_Broadcast` cuyo `DeviceName` esté **contenido en el nombre P2P** que pulsó el usuario.
  * Ese nombre se guardó en `a.java:458`. Si coincide, el teléfono envía el ACK automáticamente.
  * `uuidName` se borra en `a.java:148` y en `IC/wificonnection/d.java:86`.
* **Efecto colateral.** Si un coche emite con `DeviceName` vacío, `"".contains("")` es verdadero y se dispara el camino automático aunque no se esté en P2P. Es una inferencia directa del código.

### 3.6 Temporización del descubrimiento

| Qué | Valor | Referencia |
|---|---|---|
| Publicación de la lista de coches UDP a la UI | cada 2 s (primera a los 2 s); la lista se **vacía** tras publicarse | `IC/wificonnection/a.java:80-97`, `:331-335` |
| Mensajes "buscando" / "no encontrado" de la UI | a los 3 s, o a los 20 s si es la primera vez (no afecta a la red) | `LinkDialog.java:247-255` |
| Re-descubrimiento P2P | cada 3 s | `WD/c.java:571` |
| Timeout de formación del grupo P2P | 60 s | `WD/c.java:916` |
| Caducidad de entradas de servicio P2P (modos no usados) | 30 s | `WD/c.java:1030` |
| Espera del TCP tras el ACK | 20 s | `IC/wificonnection/d.java:49-69` |

**Inferencia:** como la lista se vacía cada 2 s, el coche debe emitir `Connect_Broadcast` con un periodo de 2 s o menos para seguir visible.

---

## 4. Sockets (lado teléfono) y qué transporta cada uno

| # | Proto | Extremo local (teléfono) | Extremo remoto | Quién inicia | Contenido | Referencias |
|---|---|---|---|---|---|---|
| 1 | UDP | `0.0.0.0:18463` (SO_REUSEADDR) | coche, puerto origen desconocido | el coche emite | Entrada: `Connect_Broadcast` | `IC/wificonnection/a.java:486-496` |
| 2 | UDP | el mismo socket `:18463` | `IP_coche:18464` | el teléfono, una vez | Salida: `Broadcast_ACK` con `MirrorPort` | `a.java:31`, `:137`, `:190`; `d.java:80` |
| 3 | **TCP** | `0.0.0.0:MirrorPort` (aleatorio de 10001 a 65535), **servidor**, un solo `accept()` | coche como **cliente** | **el coche conecta** | **Todo el enlace**: control JSON (`5A5A`, msgType 0), vídeo teléfono→coche (msgType 1), toques/teclas del coche (msgType 2), voz del micro del coche (msgType 12), datos de app JSON (msgType 13), datos personalizados del HU (msgType 99), heartbeats y mensajes antiguos `!BIN` de 512 B | `IC/wificonnection/d.java:72-120`; dispatcher en `IC/linkconnection/a.java:1920-2322` y `:1101-1290`; cabecera de vídeo en `:1471-1476` |
| 4 | UDP (fuga) | `0.0.0.0:MirrorPort` | — | — | Ningún tráfico. Es el socket de "prueba de puerto libre", que nunca se cierra | `IC/wificonnection/a.java:249-264` (`new DatagramSocket(iNextInt)` en `:255`) |
| 5 | Bluetooth A2DP | — | MAC del coche, que llega en `BT_ADDR` | el teléfono | Audio multimedia (no va por Wi-Fi; por eso `AudioPort:0`) | `IC/linkconnection/a.java:2028-2039`; `QL/interconnection/DLinkNotifyL.java:999`, `:1025` |
| 6 | USB AOA | — | — | el coche (host USB) | El mismo protocolo que el #3, con relleno a bloques de 512 B | sección 6 |
| — | HTTPS | — | `api.qdrive.cc:8087` | el teléfono | Verificación de activación. **Solo en el flavor BEIJINGLink, inactivo aquí** | `IC/linkconnection/a.java:744-759`, `:1953-1955`; `IC/utils/e.java:18-21` |

Notas sobre el socket TCP (#3):

* **Rango del puerto.** `new Random().nextInt(55535) + 10001` da 10001-65535 (`IC/wificonnection/a.java:253`). La "comprobación de puerto libre" se hace con un `DatagramSocket` UDP, no con TCP (`:255`).
* **Opciones tras `accept()`** (`IC/wificonnection/d.java:88-94`): `setTcpNoDelay(true)`, `setSendBufferSize(4194304)`, `setReceiveBufferSize(6291456)` y `setKeepAlive(true)`. No se usa `setSoTimeout` en ningún sitio, así que las lecturas bloquean indefinidamente.
* El servidor TCP escucha en **todas las interfaces** (`new ServerSocket(port)` sin dirección). El teléfono nunca llama a `connect()`.
* **Inferencia:** el coche debe sacar la IP del teléfono de la dirección origen del ACK, porque el ACK no contiene la IP.
* El único otro socket que aparece en todo el código decompilado es un helper de depuración de androidx (`127.0.0.1:5327`, `sources/androidx/constraintlayout/core/motion/utils/e0.java:65`). No es relevante.

---

## 5. Ciclo de vida completo

### 5.1 Secuencia (Wi-Fi, modo hotspot)

```
Teléfono (QDLink)                                              Coche (HU)
[onCreate MainActivity] bind UDP *:18463 ......................(escucha)
                               <── UDP → :18463  "…Connect_Broadcast…{DeviceUUID,DeviceName,…}"  (periódico, inferido)
[cada 2 s la lista va a LinkDialog; el usuario pulsa el coche]
puerto P = rand(10001..65535); se prepara el ACK
→ replyConnectSuccess → EventBus → MainActivity.R() → ConnectActivity
→ bindService(ScreenCaptureService) → LinkManager → ConnectionManager (modo Wi-Fi)
ServerSocket *:P ; UDP :18463 ── "…Broadcast_ACK…{"MirrorPort":P,…}" ──> IP_coche:18464
(se arma un timer de 20 s)
                               <── TCP connect → IP_teléfono:P
accept(); NODELAY/KEEPALIVE/buffers; estado=1
arranca el hilo lector (prioridad 10)
"!BIN" AppStatus 512 B ─────────────────────────────────────────>
t+1 s y luego cada 3 s: 5A5A {"CMD":"HEARTBEAT"} ──────────────>
watchdog RX: cada 5 s, corta si pasan >5 s sin leer (solo protocolo 5A5A)
                               <── 5A5A CAR_INFO  (el orden lo marca el coche; el teléfono solo reacciona)
PHONE_INFO ─────────────────────────────────────────────────────>
UPDATE_NOTIFY {UpdateStatus:5} ─────────────────────────────────>
                               <── 5A5A VIDEO_SUP_REQ
[diálogo del sistema "iniciar grabación/emisión" (MediaProjection)]
VIDEO_SUP_RSP {VideoFormat:3, VideoSupport:1} ──────────────────>
                               <── 5A5A VIDEO_ARGS {Width,Height,EncodingType,FrameRate,BitRate,FrameInterval}
SPEECH_ARGS {EncodingType:1,SampleRate:16000,ChannelConfig:1,AudioFormat:16} ─>
                               <── 5A5A VIDEO_CTRL {PlayStatus:1}
startForegroundService(ScreenCaptureService) → VirtualDisplay → encoder H.264
5A5A msgType=1 (vídeo) ─────────────────────────────────────────> (continuo)
                               <── toques (msgType 2), KEY_FRAME_REQ, LAND_MODE_REQ, HEARTBEAT…
```

### 5.2 Paso a paso con referencias

1. **Arranque de la app.**
   * `MyApplication.onCreate` solo hace inicializaciones generales (`QL/MyApplication.java:454-467`).
   * `MainActivity.onCreate` → `J()` (`QL/MainActivity.java:797-802`) hace tres cosas:
     * `IC/d.java:317-321`: configura la orientación.
     * `IC/d.java:341-362`: `utils.a.A = true` (`:345`) y `bindService(ScreenCaptureService)` (`:356`).
     * `IC/d.java:371-376`: arranca el hilo UDP 18463 con Wi-Fi Direct inicializado (`IC/wificonnection/a.java:474-537`).
   * El hilo arranca a su vez el timer de lista de 2 s (`:478`).
2. **Elección del modo y apertura del diálogo.** Ver 2.1 y 2.2.
3. **El usuario elige un coche.**
   * Se ejecuta `LinkDialog.onItemClick` (`LinkDialog.java:270-277`), que guarda el historial en las preferencias `LINK_HISTORY` (`QL/c.java:187`) y llama a `IC/d.java:239-245`.
   * El hilo `c` (`IC/wificonnection/a.java:154-204`):
     * elige el puerto (`:168`) y lo guarda en `LinkConfig.mirrorPort` (`:169`);
     * prepara el ACK (`:185-190`);
     * llama al callback `c()` (`:191-193`).
   * Desde ahí: `IC/wificonnection/c.java:77-82` → `IC/d.java:309-315`, donde se fija **`linkMode = 1`** (`:312`).
   * Después: `LinkDialog.replyConnectSuccess` (`LinkDialog.java:316-328`, publica `SearchDevice` por EventBus en `:326`) → `MainActivity.startUsbConnect` (`QL/MainActivity.java:1073-1076`) → `R()` (`:877-883`, vuelve a fijar `w(1)` y abre `ConnectActivity`).
4. **ConnectActivity.**
   * `ConnectActivity.onCreate` (`QL/interconnection/ConnectActivity.java:168-187`) hace `MyApplication.f9901v = true` (`:173`), pide los permisos de ejecución (`:139-152`) y arranca el servicio `DLinkNotifyL` (`:182-184`).
   * `DLinkNotifyL.onStartCommand` (`DLinkNotifyL.java:1659-1665`) llama a `IC/b.java:180-190`, que obtiene el LinkManager (`f0/b.java:68-80`) y registra el callback (`f0/b.java:273-283`).
   * En paralelo, `MirrorActivity.onCreate` → `o()` (`IC/MirrorActivity.java:387-406`) hace `bindService(ScreenCaptureService)`. En `onServiceConnected` (`:60-76`) obtiene el LinkManager y le pasa el binder (`f0/b.java:332-340` → `IC/linkconnection/a.java:1608-1620`, `receiveMyBinder = true`).
5. **Creación del motor.**
   * La primera llamada a `f0.b.D()` construye `IC/linkconnection/a` (`f0/b.java:82-91`).
   * Su constructor, con `linkMode == 1`, arranca el hilo servidor TCP (`IC/linkconnection/a.java:890-896`) y **adquiere un `FULL_WAKE_LOCK`** (`:897-902`).
6. **Hilo servidor TCP** (`IC/wificonnection/d.java:72-120`):
   1. `ServerSocket(port)` (`:78`).
   2. Envío del ACK por UDP (`:80`).
   3. Timer de 20 s (`:82`, `:49-69`).
   4. `accept()` (`:83`).
   5. Opciones del socket (`:88-94`).
   6. Streams globales `ConnConstant.wifiInputStream/OutputStream` (`:96-98`; `IC/utils/a.java:84-87`).
   7. Estado = 1 (`:102`) y callback `a()` (`:104`).
7. **Puesta en marcha del protocolo.**
   * `IC/linkconnection/a.java:2429-2439` (`onConnected`) llama a `H()` (`:1004-1010`).
   * Cuando ya existen el binder de captura y la conexión, `f0/b.java:521-530` → `C()` (`:53-66`) → `DLinkNotifyL.y()` (`DLinkNotifyL.java:1985-1990`) → `IC/b.java:138-143` → `f0/b.java:236-241` → **`IC/linkconnection/a.java:1690-1708` (`K0`)**.
   * `K0` es inmediato en Wi-Fi: el estado vale 1, así que se usan 7000 ms, por encima del umbral de 6000 (`:1693`, `:1696`). Ejecuta en orden:
     * `f()`: hilo lector (`:2594-2603`).
     * `f0()`: **AppStatus `!BIN`** (`:1344-1352`; bytes en 5.3).
     * `M0()`: heartbeat TX (`:1742-1764`).
     * `J0()`: watchdog RX (`:1659-1681`).
8. **Lectura y detección del protocolo** (`IC/linkconnection/a.java:314-442`, rama Wi-Fi `:328-363`):
   * Lee 16 bytes (`:330`) y mira los 4 primeros (`:2499-2511`).
   * **`"!BIN"`**: lee 496 bytes más, hasta completar el bloque de 512 (`:342-356`), y lo despacha con `W()` (`:1101-1290`).
   * **`"5A5A"`**: lee `total-16` bytes según la cabecera (`:357-360`, `Y()` en `:2376-2388`) y lo despacha con `U()` (`:1920-2322`).
   * La variable de protocolo `W` empieza en `"DEFULT"` (`:187`) y se fija con el primer mensaje del coche.
9. **Control (5A5A, msgType 0, JSON).** Las reacciones del teléfono son:
   * `CAR_INFO` (`:1951-1966`): responde **`PHONE_INFO`** (`:914-959`) y **`UPDATE_NOTIFY{UpdateStatus:5}`** (`:1530-1543`). También construye `LegalApp` (`:1044-1067`).
   * `VIDEO_SUP_REQ` (`:1972-2002`, rama `"QDriveLink"`): llama a `q0()` (`:1507-1519`).
     * Si el encoder H.264 no está soportado (`SC/service/ScreenCaptureService.java:722` y siguientes), responde `VideoSupport=0`.
     * Si lo está, pide la MediaProjection: `IC/MirrorActivity.java:381-385` → `:316-325` → `SC/service/ScreenCaptureService.java:187-203`.
     * Con el resultado (`IC/MirrorActivity.java:437-473`) se ejecuta `Z(true/false)` (`IC/linkconnection/a.java:2390-2427`), que envía **`VIDEO_SUP_RSP {VideoFormat:3, VideoSupport:1|0}`** (`:2399-2410`).
   * `VIDEO_ARGS` (`:1967-1971`): guarda los parámetros y envía **`SPEECH_ARGS`** (`:2472-2488`).
   * `VIDEO_CTRL{PlayStatus:1}` (`:2003-2016`) pasa por `f0.c.a("play", …)`, luego por `IC/MirrorActivity.java:356-362`, `q()` (`:226-247`) y `s()` (`:249-274`), y termina en `startForegroundService(ScreenCaptureService, code, data)` (`:260-271`), que **arranca el vídeo**.
   * `KEY_FRAME_REQ` (`:2048-2054`) fuerza un fotograma IDR.
10. **Vídeo.** Lo envía `IC/linkconnection/a.java:2450-2470` → `l0()` (`:1453-1481`) con cabecera msgType 1 y payloadFormat 2, por el mismo socket. El detalle está en el documento de vídeo.
11. **Tras arrancar el mirror** (`IC/MirrorActivity.java:364-374` → `DLinkNotifyL.java:1259-1273`): en Wi-Fi se pide activar el **servicio de accesibilidad** para inyectar toques (`QL/MainActivity.java:586-588`; `QL/wifilink/CustomAccessDialog.java`).

### 5.3 Primeros bytes que envía el teléfono

**AppStatus (`!BIN`, 512 B)**, de `IC/linkconnection/message/b.java:21-83`. Todos los enteros son de 32 bits big-endian (`IC/utils/i.java:213-219`):

| Offset | Valor | Campo (según `toString` de la clase hermana `message/f.java:96-98`) |
|---|---|---|
| 0 | `21 42 49 4E` ("!BIN") | format |
| 4 | 0 | dataType |
| 8 | 512 | totalsize |
| 12 | 512 | headersize |
| 16 | 64 | commonHeaderSize |
| 20 | 128 | requestHeaderSize |
| 24 | 128 | responseHeaderSize |
| 28 | 2 | action |
| 32..63 | `0x20…0x3F` | mark |
| 64 | 0 | TimeStamp |
| 68 | 1 | cmd |
| 72 | 1 | value |
| 76 | `Build.VERSION.SDK_INT` | versionAndroid |
| 80 | 2 | integrator_server (`IC/utils/b.java:15`, `:31-35`) |
| 192 | 0 | ret |
| resto | 0 | |

**Heartbeat (protocolo nuevo).**

* El JSON es una constante: `"{\"CMD\":\"HEARTBEAT\"}"` (`IC/linkconnection/a.java:208`).
* La cabecera se construye en `:1370-1378` y se serializa con `h0/a.java:35-56`.
* En Wi-Fi se envía **sin relleno** (`:1418-1435`). En USB se rellena a múltiplos de 512 (`:1380-1389`).
* Son 35 bytes:

```
35 41 35 41  00 00 00 23  00 00  00  00  00  01  00  00   7B 22 43 4D 44 22 3A 22 48 45 41 52 54 42 45 41 54 22 7D
"5A5A"       total=35     ext=0  mt=0 b11 b12 pf=1 res 0   {"CMD":"HEARTBEAT"}
```

Cabecera `5A5A` (`h0/a.java:33-56`, parseo en `:114-122`):

| Bytes | Contenido |
|---|---|
| 0-3 | `"5A5A"` |
| 4-7 | longitud total, BE, incluye los 16 bytes de cabecera |
| 8-9 | longitud de la cabecera extendida, BE |
| 10 | msgType |
| 11 | campo `n()`, sin nombre |
| 12 | campo `i()`, sin nombre |
| 13 | payloadFormat (1 = JSON) |
| 14 | reservedOne |
| 15 | 0 |

### 5.4 Heartbeats y keepalive

* **Teléfono → coche, protocolo nuevo.**
  * `{"CMD":"HEARTBEAT"}` a los **1000 ms** y después **cada 3000 ms** (`K0 = 3000`, `IC/linkconnection/a.java:52`; `schedule(qVar, 1000L, K0)` en `:1761`; la tarea está en `:672-681` y la función en `:1366-1451`).
  * Se envía **siempre**, también si el coche habla el protocolo antiguo en Wi-Fi. Solo en USB con `!BIN` se paran los heartbeats (`:426-430`).
* **Coche → teléfono, protocolo nuevo.** El teléfono **no responde** a un `HEARTBEAT` recibido: no hay `case` para él en `:1950-2072`. Cualquier dato recibido simplemente actualiza la marca de última lectura `S` (`:1872`, `:2564`).
* **Watchdog RX.**
  * Primera comprobación a los 1000 ms y después **cada 5000 ms** (`L0 = 5000`, `:53`; `:1678`; tarea en `:683-704`).
  * Si `ahora - S > 5000` **y** el protocolo es `"5A5A"`, se ejecuta `L()` en Wi-Fi o `K()` en USB.
  * Con `!BIN` el watchdog no actúa (`:690`).
  * Un EOF (`read() == -1`) pone `S = 0` (`:332-333`), así que el watchdog corta como mucho unos 5 s después.
* **Protocolo antiguo (`!BIN`).** El coche envía dataType 0, action 1, `cmd=10` (offset 68) y `value=1` (offset 72). El teléfono **devuelve el mismo bloque con `ret=1`** en el offset 192 (`IC/linkconnection/a.java:1122-1129`; `IC/linkconnection/message/f.java:56-94`).
* **TCP keepalive** del sistema operativo activado (`IC/wificonnection/d.java:94`). Usa los timers por defecto del SO; la app no ajusta nada.

### 5.5 Timeouts y temporizadores relevantes

| Temporizador | Valor | Efecto | Ref. |
|---|---|---|---|
| Espera de `accept()` tras el ACK | 20 s | estado -1 y `onDisconnected()`; **el ServerSocket queda abierto y el hilo sigue bloqueado** | `IC/wificonnection/d.java:28-43`, `:65` |
| Heartbeat TX | 1 s inicial, 3 s de periodo | | `IC/linkconnection/a.java:1761` |
| Watchdog RX | comprueba cada 5 s y corta con >5 s sin datos | `L()` | `:1678`, `:690` |
| Guarda de reconexión USB | 6 s (`f9715s`) | retrasa el arranque del protocolo si la última desconexión fue hace menos de 6 s; en Wi-Fi no aplica | `IC/utils/e.java:41`; `IC/linkconnection/a.java:1693-1707`; `f9714r` se fija en `:2520` |
| Formación del grupo P2P | 60 s | `cancelConnect` | `WD/c.java:916` |
| Sondeos de la UI de mirror | 500 ms | | `IC/MirrorActivity.java:302`, `:350` |

### 5.6 Mensajes de desconexión

* **Coche → teléfono `DISCONNECT_REQ`** (`IC/linkconnection/a.java:2040-2047`):
  * Llega a `f0/b.java:426-431` y de ahí a `DLinkNotifyL.l()`, que **está vacío** (`DLinkNotifyL.java:1597-1599`). El mensaje **se ignora**.
  * El constructor de la respuesta `DISCONNECT_RSP {"CMD":"DISCONNECT_RSP","PARA":{"CanDisconnect":n}}` existe (`IC/linkconnection/a.java:3120-3138`; nombre en `h0/c.java:95`). Su única ruta de entrada (`IC/b.java:131-136` → `f0/b.java:222-227`) **no tiene llamadores**.
* **Teléfono → coche.** **No hay ningún mensaje de despedida.** El teléfono simplemente cierra el socket.
* **Otros mensajes de estado que el teléfono sí envía:**
  * `LOCK_SCREEN_STATUS`: 2 = pantalla encendida, 1 = apagada/bloqueada, 3 = desbloqueada (`QL/receiver/a.java:34-48`; `QL/MainActivity.java:467-519`; `IC/linkconnection/a.java:3096-3118`).
  * `CAR_APP_BACKGROUND` / `CAR_APP_FOREGROUND` (`IC/linkconnection/a.java:3037-3075`).

### 5.7 Desconexión: causas y acciones

| Causa | Qué hace | Ref. |
|---|---|---|
| `IOException` al leer por Wi-Fi | `L()` | `IC/linkconnection/a.java:2565-2570` |
| `IOException` al escribir | `L()` | `:1436-1443`, `:2670-2676`, `:2737-2747`, `:2848-2855` |
| Watchdog RX | `L()` | `:683-704` |
| El usuario rechaza la MediaProjection, o no hay encoder | `VIDEO_SUP_RSP{VideoSupport:0}` y `d()` (teardown). El rechazo además dispara `f0/b.java:152-157` → `DLinkNotifyL.java:1679-1685` → `QL/MainActivity.java:582-585` → `IC/d.java:90-115` (`A()`, **cierra los sockets**) | `IC/linkconnection/a.java:302-311`, `:2390-2427`; `IC/MirrorActivity.java:440-458` |
| "Desconectar" en Ajustes | `IC/d.java:90-115` (`A()`) | `QL/mine/setting/SettingView.java:326-351`, `:385-410`, `:542-548` |
| Salir de la app | `IC/d.java:117-138` (`B()`) y `System.exit(0)` | `QL/MainActivity.java:965-991`, `:1020-1034` |
| Timeout de `accept()` | `d()`, sin cerrar ningún socket | `IC/wificonnection/d.java:28-43` |
| `IOException` del ServerSocket (p. ej. puerto ocupado) | estado -1 y `c()` → `notifyConnectFail` | `IC/wificonnection/d.java:109-117`; `IC/linkconnection/a.java:2490-2497`; `DLinkNotifyL.java:1615-1619` |
| Paquete UDP mal formado en :18463 | estado de conexión -1, aunque haya sesión TCP activa | `IC/wificonnection/a.java:526-528` |

Acciones de `L()` ("closeWifiButton", `IC/linkconnection/a.java:1710-1736`):

1. En modo P2P hace `removeGroup` (`:1712-1714` → `IC/wificonnection/a.java:539-545`).
2. Ejecuta `d()` (`:2513-2546`). Esta función:
   * para los timers;
   * detiene la captura (`binder.k()`);
   * pone el estado a -1 y el protocolo a `"DEFULT"`;
   * notifica a la UI (`f0/b.java:398-407` → `DLinkNotifyL.java:1763-1786`) y cierra `ConnectActivity`;
   * detiene el lector;
   * **libera el wake lock** (`:1027-1035`).
3. Pone `linkMode = 0` (`:1717`).
4. **Cierra el socket y el ServerSocket** (`:1718-1731`).
5. `IC/wificonnection/c.java:121-127` → `a.z()` (`IC/wificonnection/a.java:609-625`): limpia las listas y reinicia el descubrimiento P2P.

`d()` por sí sola **no cierra el socket TCP** en Wi-Fi. Solo lo cierran `L()` y `A()`.

### 5.8 Reconexión

* **No hay reconexión automática.**
  * El auto-enlace con el último coche guardado (`LinkDialog.java:293-301`) solo se activa con `isFirst = true`, y el constructor que lo permite (`LinkDialog.java:366-376`) no se llama en ningún sitio. La UI usa siempre `new LinkDialog(ctx)` (`QL/mainpage/MainPageView.java:667`).
  * La preferencia `AUTO_LINK` (`QL/c.java:169`) nunca se escribe.
* El hilo UDP sigue escuchando tras desconectar, pero el usuario tiene que volver a pulsar el coche.
* **Cada sesión nueva** implica:
  * un **MirrorPort aleatorio nuevo**;
  * un `ServerSocket` nuevo;
  * un motor nuevo (`f0/b.java:100-109` pone los singletons a null);
  * un **diálogo de MediaProjection nuevo**.

---

## 6. Autenticación, emparejamiento, cifrado y listas blancas

* **No hay cifrado.** UDP y TCP van en claro. No aparecen TLS, `Cipher`, `Mac`, `SecretKey` ni firmas en el código del enlace (búsqueda en `com/neusoft` y `com/neu`). Las únicas "firmas" son los magic `QDrive_SSPLink_UDP_MSG`, `5A5A` y `!BIN` (`IC/utils/a.java:24`, `:33`).
* **No hay token ni PIN de emparejamiento.** El ACK lleva `DeviceName` y `DeviceUUID` vacíos (sección 1). `PHONE_INFO` lleva `PhoneUUID`/`PhoneName` vacíos, más marca, modelo, `Version` (= versionName 1.9.7), `Platform=0` y la versión de SDK (`IC/linkconnection/a.java:914-959`).
* **Activación contra servidor.**
  * `POST https://api.qdrive.cc:8087/activation/international/verifyActivation` con `projectId` y `uuid` (los `ProjectID` y `CarUUID` de `CAR_INFO`) (`IC/linkconnection/a.java:706-760`).
  * Si `errCode` no es 0 ni 1, el teléfono desconecta (`:727-733`).
  * **Solo se ejecuta si el flavor es `"BEIJINGLink"`** (`:1953-1955`). En este APK el flavor vale `"QDriveLink"` (`IC/utils/e.java:18`) y no cambia en ningún sitio, así que **está desactivada**.
* **Lista blanca de apps (seguridad al volante).** No es autenticación:
  * Si `CAR_INFO.CarFeature.legal_app_watch == 1` (`IC/linkconnection/a.java:1044-1067`), el teléfono comprueba cada 1 s la app en primer plano contra `legalapplist`.
  * Esa lista se busca en la BD local `RES/res/raw/linkmanager.db3`, por `CarFactory`/`CarType`/`HUFactory` (`DLinkNotifyL.java:873-892`, `:486-535`, `:948-961`, `:1543-1595`).
  * El resultado se envía al coche como `WhitelistAppOn` (msgType 13; `IC/linkconnection/a.java:2877-2895`).
  * **La BD contiene el C10:**
    * fila 167: `factoryid "018"`, `typeid "2D4"`, `huid "119"`, `factory "LEAP"`, `type "C10_i_L"`;
    * fila 168: `"2D5"`, `"C10_i_R"`;
    * ambas con `android=1`, `port=0` y `legalapplist` = Google Maps, Waze, HERE, Last.fm, Amap y Baidu Map.
    * Esto **sugiere** (inferencia, a confirmar) que el C10 envía `CarFactory="018"`, `CarType="2D4"` (volante a la izquierda) o `"2D5"` y `HUFactory="119"` en `CAR_INFO`.
* **MD5.** Solo sirve para comprobar paquetes de la app del HU descargados para actualizar el coche (`QL/MainActivity.java:359-366`; `DLinkNotifyL.java:463`). No forma parte del handshake.
* **Identificación del coche en USB.** Filtro AOA `manufacturer="Neusoft" model="QDriveLink" version="1"` (`RES/res/xml/usb_accessory_filter.xml`). En Wi-Fi no hay equivalente.
* **Qué comprueba el coche** (paquete, certificado, versión mínima): **no se puede ver** desde el código del teléfono (ver Incógnitas).
* **Seguridad de la red Wi-Fi:** la del hotspot (WPA del usuario) o la WPA2 que gestiona el framework P2P de Android.

---

## 7. Modo USB (resumen)

* **Arranque.** `ConnectActivity` se lanza con `USB_ACCESSORY_ATTACHED` (`RES/AndroidManifest.xml:127-142`) y el filtro Neusoft/QDriveLink/1. Con `linkMode` a 0 el motor crea `IC/linkconnection/c.java` (`IC/linkconnection/a.java:884-889`).
* **Apertura del accesorio.**
  * `getAccessoryList()`, hasta 5 intentos con 50 ms entre ellos; exige permiso (`IC/linkconnection/c.java:83-123`).
  * `openAccessory`, que da un `FileInputStream`/`FileOutputStream` (`:125-151`).
  * Estado 0 y flag "USB conectado", que bloquea el Wi-Fi (`:147-149`; `IC/d.java:241`).
* **Cierre.** Se cierra con `USB_ACCESSORY_DETACHED` **o con `ACTION_POWER_DISCONNECTED`** (`IC/linkconnection/c.java:51-75`, `:153-198`).
* **Mismo motor de protocolo, con dos diferencias:**
  * los mensajes JSON (`5A5A`) y el heartbeat se rellenan con ceros hasta un múltiplo de 512 bytes (`IC/linkconnection/a.java:1306-1315`, `:1380-1389`). En Wi-Fi no se rellenan (`:1299-1303`, `:1418-1435`);
  * las lecturas son bloques de 512 (`:388-405`).
* **`UsbBroadcastReceiver`** (`QL/receiver/UsbBroadcastReceiver.java:13-27`): no está en el manifest ni se registra en ningún sitio. Es código muerto. Solo cerraría un diálogo de actualización.
* **`AdbCopyUtils`** (`QL/interconnection/utils/AdbCopyUtils.java:16-211`): copia assets y escribe logs, y no tiene llamadores.
* **Binarios en `RES/assets`** (`ooomserver14..29`, `oms_monitor14..29`, `hclink_md5_*`, `findpids_*`, `getsdkver_*`, `omsm_check_*`): ningún código Java los referencia. La columna `port` de la BD (9986-9998, solo en modelos antiguos; 0 en Leapmotor) se lee (`QL/interconnection/utils/QD_DBUtil.java:127`) pero `getPort()` no tiene llamadores. **Inferencia:** son restos de un modo antiguo de control por ADB y no afectan al Wi-Fi.
* **`libsspLib.so`** (`sources/com/neusoft/ssp/protocol/SSPProtocol.java:19`) solo serializa mensajes del HU. No abre sockets. El `.so` está en el split APK de ABI y no se incluye en este decompilado.

---

## 8. Requisitos que obligan a mantener la pantalla encendida

1. **`FULL_WAKE_LOCK`.** `newWakeLock(26, …)`, donde 26 = `FULL_WAKE_LOCK`. Se adquiere al crear el motor (`IC/linkconnection/a.java:897-902`) y se libera en el teardown (`:1027-1035`). La pantalla se mantiene encendida y con brillo durante toda la sesión.
2. **MediaProjection.**
   * Pide consentimiento **en cada sesión**, cuando llega `VIDEO_SUP_REQ`: `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())` en API ≥ 34 y `startActivityForResult(…, 123)` (`SC/service/ScreenCaptureService.java:187-203`; `requestCode = 123` en `SC/utils/e.java:91`).
   * El usuario tiene que interactuar con el teléfono, que por tanto debe estar encendido y desbloqueado.
   * Corre como servicio en primer plano de tipo `mediaProjection` (`RES/AndroidManifest.xml:46`, `:157-165`; `startForeground(…, 32)` en `SC/service/ScreenCaptureService.java:897-927`).
   * Un rechazo provoca desconexión (5.7).
3. **Dos modos de proyección** (`SC/managers/a.java:717-752`):
   * "dentro de app": `DisplayManager.createVirtualDisplay` con flag PRESENTATION, que renderiza una UI propia para el coche;
   * "fuera de app": `MediaProjection.createVirtualDisplay`, que espeja la pantalla del móvil, por lo que esta tiene que estar encendida.
   * Ambos exigen tener la MediaProjection (`:693-703`).
   * La ventana Presentation usa `FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD` (`SC/managers/d.java:89-93`).
4. **Despertar la pantalla.** `ACQUIRE_CAUSES_WAKEUP | SCREEN_BRIGHT` al lanzar apps o inyectar acciones (`QL/interconnection/MouseAccessibilityService.java:128-136`; `QL/mainpage/MainPageView.java:452`; `QL/mapnavi/MapNaviView.java:158`; `QL/otherapp/OtherAppView.java:146`).
5. **Otros permisos de soporte:**
   * accesibilidad para inyectar toques en Wi-Fi (`DLinkNotifyL.java:1259-1273`);
   * superposición, para forzar la rotación (`IC/linkconnection/a.java:1087-1098`, `:1557-1606`);
   * `NEARBY_WIFI_DEVICES`, localización y `CHANGE_WIFI_STATE` para P2P (`RES/AndroidManifest.xml:52-55`; lista de permisos en tiempo de ejecución en `QL/c.java:289-290`).

---

## 9. Peculiaridades del código que conviene conocer al reimplementar

1. **Sin `readFully`.** En Wi-Fi la cabecera de 16 bytes y el payload se leen con **una sola llamada `read()`** (`IC/linkconnection/a.java:330`, `:2382`, `:2563`). Las lecturas parciales no se tratan. Nuestro cliente debe usar lectura completa.
2. **ACK sin reintentos.** Si se pierde el datagrama, salta el timeout de 20 s (`IC/wificonnection/d.java:80`, `:65`).
3. **Timeout de accept incompleto.** No cierra el `ServerSocket` ni interrumpe el hilo (`IC/wificonnection/d.java:28-43`).
4. **Teardown sin cierre.** `d()` no cierra el TCP (`IC/linkconnection/a.java:2513-2546`). El coche detectará la caída por su propio timeout.
5. **Sockets que nunca se cierran:**
   * el UDP de "prueba de puerto" (`IC/wificonnection/a.java:255`);
   * el UDP 18463 (`IC/wificonnection/c.java:191-198`; `IC/wificonnection/a.java:273-276`).
6. **Fragilidad del UDP.** Cualquier datagrama mal formado en 18463 pone el estado a -1 (`IC/wificonnection/a.java:526-528`).
7. **Orden del JSON de control.** Se construye con `new JSONObject(HashMap)` (`h0/c.java:348-373`), así que el orden de las claves de `PHONE_INFO` y similares no es el de inserción. El coche tiene que parsear JSON de verdad. El ACK UDP sí conserva el orden.
8. **Botón de notificación roto.** Apunta al componente literal `"DTLinkNotificationReceiver.class.getName()"` (`QL/interconnection/ConnectActivity.java:176`; `QL/MyApplication.java:464`; `SC/utils/b.java:68-72`), que no existe. No sirve para desconectar.

---

## 10. Recomendaciones para el cliente Kotlin (rol "teléfono")

Este flujo replica exactamente lo observado en el código:

1. **Escucha UDP.** Ligar un UDP a `0.0.0.0:18463` con `reuseAddress = true`. En Android conviene además una `MulticastLock` como seguro, aunque QDLink no la usa. Parsear las tramas y agrupar los coches por `DeviceUUID` + IP origen.
2. **Al elegir un coche.**
   * Abrir un `ServerSocket` en un puerto entre 10001 y 65535, en todas las interfaces.
   * **Después**, enviar el `Broadcast_ACK` **desde el mismo socket 18463** a `IP_coche:18464`.
   * Mejora sobre QDLink: reenviar el ACK cada 1-2 s mientras no llegue el `accept()`, con un límite de 20 s. *Ojo: esto no se ha observado; validarlo con captura.*
3. **Tras `accept()`.**
   * Configurar `tcpNoDelay`, `keepAlive` y buffers grandes.
   * Arrancar un lector con framing robusto: mirar 4 bytes; si es `5A5A`, leer el total BE del offset 4; si es `!BIN`, leer un bloque de 512.
   * Enviar AppStatus `!BIN` para imitar a QDLink.
   * Programar un `HEARTBEAT` cada 3 s y un watchdog de 5 s.
4. **Responder a los mensajes de control.** `CAR_INFO` → `PHONE_INFO` + `UPDATE_NOTIFY{5}`; `VIDEO_SUP_REQ` → `VIDEO_SUP_RSP{3,1}`; `VIDEO_ARGS` → `SPEECH_ARGS`; `VIDEO_CTRL{1}` → empezar el vídeo. Detalles en los documentos de control y vídeo.
5. **Pantalla.** Mantenerla encendida con `FLAG_KEEP_SCREEN_ON` o un wake lock, y pedir la MediaProjection en cada sesión.

Esqueleto de las tramas, derivado del código citado:

```kotlin
const val UDP_LISTEN_PORT = 18463               // IC/wificonnection/a.java:30
const val UDP_CAR_PORT    = 18464               // IC/wificonnection/a.java:31
const val MAGIC = "QDrive_SSPLink_UDP_MSG"      // IC/wificonnection/a.java:32

private fun hex4(n: Int) = n.toString(16).uppercase().padStart(4, '0') // IC/utils/i.java:179-185
private fun hex2(n: Int) = n.toString(16).uppercase().padStart(2, '0') // IC/utils/i.java:187-193

fun buildUdpFrame(type: String, json: String): ByteArray {   // usar solo ASCII (las longitudes van en caracteres)
    val total = MAGIC.length + 4 + 2 + type.length + 4 + json.length
    return (MAGIC + hex4(total) + hex2(type.length) + type + hex4(json.length) + json).toByteArray()
}

fun buildAck(mirrorPort: Int): ByteArray = buildUdpFrame("Broadcast_ACK",
    """{"ControlPort":0,"MirrorPort":$mirrorPort,"AudioPort":0,"OS":0,"DeviceName":"","DeviceUUID":"","DeviceFeature":{"PassistMobileNum":""}}""")

data class UdpFrame(val type: String, val json: String)
fun parseUdpFrame(buf: ByteArray, len: Int): UdpFrame? {
    val s = String(buf, 0, len, Charsets.UTF_8)
    if (!s.startsWith(MAGIC) || s.length < MAGIC.length + 10) return null
    var p = MAGIC.length + 4                        // saltar la longitud total
    val tl = s.substring(p, p + 2).toInt(16); p += 2
    val type = s.substring(p, p + tl); p += tl
    val jl = s.substring(p, p + 4).toInt(16); p += 4
    return UdpFrame(type, s.substring(p, minOf(s.length, p + jl)))
}

fun header5A5A(total: Int, msgType: Int, payloadFormat: Int, extHdr: Int = 0, reserved: Int = 0): ByteArray {
    val h = ByteArray(16)                            // h0/a.java:35-56 (big-endian)
    "5A5A".toByteArray().copyInto(h, 0)
    java.nio.ByteBuffer.wrap(h, 4, 4).putInt(total)
    java.nio.ByteBuffer.wrap(h, 8, 2).putShort(extHdr.toShort())
    h[10] = msgType.toByte(); h[13] = payloadFormat.toByte(); h[14] = reserved.toByte()
    return h
}
val HEARTBEAT: ByteArray = """{"CMD":"HEARTBEAT"}""".toByteArray().let { header5A5A(16 + it.size, 0, 1) + it }
```

---

## 11. Incógnitas (solo se pueden confirmar con una captura en vivo)

**Método de captura sugerido.** El teléfono escucha en comodín, tanto el UDP (`IC/wificonnection/a.java:489`) como el TCP (`IC/wificonnection/d.java:78`), y no comprueba la interfaz. Por eso es **probable** (inferencia) que el enlace también funcione si teléfono y coche son clientes de un tercer AP, por ejemplo un portátil con Wireshark. Si no funciona, capturar en el propio teléfono con root (`tcpdump -i any`).

1. **Modo del C10.** ¿Usa hotspot del teléfono, Wi-Fi Direct o ambos? ¿Admite el Wi-Fi o solo USB AOA? Los textos dicen "solo algunos modelos" (`strings.xml:60`, `:71`).
2. **`Connect_Broadcast`:**
   * dirección destino (`255.255.255.255`, broadcast dirigido de la subred o unicast) y puerto origen;
   * periodicidad (inferida ≤ 2 s);
   * si se sigue emitiendo durante una sesión activa;
   * si también se emite sobre la interfaz P2P tras formarse el grupo.
3. **JSON completo de `Connect_Broadcast`.** El teléfono solo lee `DeviceUUID` y `DeviceName`. ¿Incluye `ControlPort`, `MirrorPort`, `AudioPort`, `OS`, versión, `DeviceFeature`? ¿El `DeviceName` coincide con el nombre P2P del coche?
4. **Validación del ACK en el coche:**
   * ¿acepta `DeviceName`/`DeviceUUID` vacíos y `OS:0`?
   * ¿comprueba los campos de longitud hex?
   * ¿necesita que el puerto origen sea 18463?
   * ¿usa la IP origen del ACK para conectar el TCP (inferido)?
   * ¿abriría sockets extra si `ControlPort`/`AudioPort` fueran distintos de 0?
5. **Comportamiento del coche si se pierde el ACK.** ¿Reintenta, vuelve a emitir o cuánto espera antes del `connect()`?
6. **Protocolo TCP que habla el C10:**
   * `5A5A` (JSON) o `!BIN`;
   * ¿envía algo antes de recibir el AppStatus?
   * orden real de `CAR_INFO`, `VIDEO_SUP_REQ`, `VIDEO_ARGS` y `VIDEO_CTRL`;
   * ¿necesita el `!BIN` AppStatus o le basta con `5A5A`?
7. **Heartbeat del coche:** formato (`{"CMD":"HEARTBEAT"}`?), periodo y **timeout propio**. ¿Qué pasa si el teléfono deja de enviar heartbeats?
8. **Desconexión:**
   * ¿cuándo envía el coche `DISCONNECT_REQ` y qué espera como respuesta?
   * ¿cierra él el socket si no recibe `DISCONNECT_RSP`?
   * ¿qué hace al cerrarse el TCP sin aviso?
9. **Wi-Fi Direct:**
   * ¿el coche es GO autónomo (`createGroup`) o negocia?
   * IP del GO (¿192.168.49.1?), canal, aceptación WPS/PBC;
   * ¿anuncia DNS-SD `"_ssplink._tcp"` / `"QDLink_DnsSd_Instance"` o UPnP `"QDLink_UPnP_Device"` (`WD/c.java:98`, `:101`)?
10. **Hotspot:**
    * ¿el coche exige 5 GHz?
    * ¿tolera las subredes aleatorias de Android 11+?
    * ¿hay aislamiento de clientes o requisitos de DHCP?
11. **Valores reales de `CAR_INFO` del C10:** `CarFactory`/`CarType`/`HUFactory` (¿`018`/`2D4`/`119` según la BD?), `ProjectID`, `CarUUID`, `CarFeature` (¿`legal_app_watch`?), `MirrorTypeReq`, `CarWidth`/`CarHeight`.
12. **Comprobaciones de identidad en el coche:** ¿verifica `PHONE_INFO` (versión mínima, `Platform`, marca)? ¿Recuerda teléfonos por `PhoneUUID`, que QDLink envía vacío?
13. **Significado de los bytes 11, 12 y 14 de la cabecera `5A5A`** cuando los envía el coche. El teléfono siempre escribe 0 y solo lee el 14 como `reservedOne` para msgType 99.
14. **Política de la lista blanca.** ¿El coche oscurece el vídeo si `WhitelistAppOn = 0`, o si nunca lo recibe?

---

## Anexo A · Índice rápido de constantes

| Constante | Valor | Referencia |
|---|---|---|
| Puerto UDP de escucha (teléfono) | 18463 | `IC/wificonnection/a.java:30` |
| Puerto UDP destino (coche) | 18464 | `IC/wificonnection/a.java:31` |
| Firma UDP | `QDrive_SSPLink_UDP_MSG` | `IC/wificonnection/a.java:32` |
| Tipos UDP | `Connect_Broadcast`, `Broadcast_ACK` | `IC/wificonnection/a.java:33-34` |
| Rango de MirrorPort | 10001-65535 | `IC/wificonnection/a.java:253` |
| Timeout de accept | 20000 ms | `IC/wificonnection/d.java:65` |
| Periodo del heartbeat TX | 3000 ms (con 1000 ms iniciales) | `IC/linkconnection/a.java:52`, `:1761` |
| Watchdog RX | 5000 ms | `IC/linkconnection/a.java:53`, `:1678`, `:690` |
| Heartbeat JSON | `{"CMD":"HEARTBEAT"}` | `IC/linkconnection/a.java:208` |
| Magic nuevo / antiguo | `5A5A` / `!BIN` | `h0/a.java:33`; `IC/utils/a.java:24`, `:33` |
| Buffers TCP | SND 4 MiB / RCV 6 MiB | `IC/wificonnection/d.java:90-92` |
| Wake lock | 26 (`FULL_WAKE_LOCK`) | `IC/linkconnection/a.java:899` |
| Intent de GO (P2P) | 0 | `WD/c.java:993-996` |
| Servicios P2P de la librería (sin uso) | `QDLink_DnsSd_Instance` / `_ssplink._tcp` / `QDLink_UPnP_Device` | `WD/c.java:98`, `:101`, `:937` |
| Filtro AOA | Neusoft / QDriveLink / 1 | `RES/res/xml/usb_accessory_filter.xml` |
| URL de activación (inactiva) | `https://api.qdrive.cc:8087/activation/international/verifyActivation` | `IC/linkconnection/a.java:758` |
