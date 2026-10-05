# 05 · Wi-Fi Direct (lado teléfono): QDLink 1.9.7

Especificación, lista para implementar, de cómo QDLink monta la red **Wi-Fi Direct (P2P)** con el coche y de cómo
esa red enlaza con el descubrimiento UDP y la sesión TCP de `04-wire-spec.md`.

- Una vez formado el grupo P2P, por UDP y TCP viaja **exactamente lo mismo que en el modo zona Wi-Fi** (04). Aquí solo
  se describen la capa P2P y el traspaso a UDP/TCP.
- Cada dato lleva cita `archivo:línea`. Los flujos dudosos de jadx se han comprobado con `dexdump` (Anexo B).
- Marcas:
  - **[INFERENCIA]**: se deduce del código, pero no se lee literalmente en él.
  - **[PLATAFORMA]**: comportamiento del framework de Android (AOSP), por conocimiento general. No se puede verificar
    en este repositorio. Las firmas y los permisos del SDK sí están comprobados (prefijo `SDK:`).
  - **Recomendación**: decisión para nuestra app.

---

## 0. Convenciones

Rutas relativas a `decomp\qdlink\`:

| Prefijo | Ruta real |
|---|---|
| `WD/` | `sources/com/neusoft/qdrive/wifidirect/` (`WD/c.java` = gestor P2P genérico, `WD/a.java` = su callback) |
| `WF/` | `sources/com/neusoft/interconnection/wificonnection/` (`WF/a.java` = hilo UDP `UdpReceiveDataThread`) |
| `IC/` | `sources/com/neusoft/interconnection/` (`IC/d.java` = API pública `InterConnection`) |
| `IU/` | `sources/com/neusoft/interconnection/utils/` (`IU/e.java` = `LinkConfig`) |
| `LC/` | `sources/com/neusoft/interconnection/linkconnection/` |
| `QL/` | `sources/com/neusoft/qdrivelink/` (UI) |
| `RES/` | `resources/` |
| `ES:` | cadenas en español: `aapt dump --values resources` de `apk/com.neusoft.qdrivelink/split_config.es.apk` (se cita el id) |
| `dex:` | `dexdump -d` (build-tools 36.0.0) sobre `classes.dex` de `apk/com.neusoft.qdrivelink/base.apk`; método y offset en unidades de 16 bits |
| `SDK:` | `D:\Android\sdk\platforms\android-36`: `android.jar` (`javap`), `android-stubs-src.jar` (javadoc de la API pública), `data/api-versions.xml`, `data/broadcast_actions.txt`, `data/annotations.zip` → `android/net/wifi/p2p/annotations.xml` |

En el texto, a las variables ofuscadas de `WD/c` se les da un nombre (§2.3). Por ejemplo, `E` es el estado del grupo,
que QDLink llama `mGroupFormedStatus` en sus logs (`WD/c.java:242`, `:968`).

---

## 1. Resumen

1. **Entrada.** El usuario va a Ajustes y pulsa **«Wi-Fi Direct»**. QDLink lanza `discoverPeers` y abre «Lista de
   dispositivos», donde salen **todos** los dispositivos P2P cercanos (solo se descartan los que están en estado
   `FAILED` o `UNAVAILABLE`, §3.2), sin ningún otro filtro. El usuario reconoce el coche por su nombre P2P y lo pulsa.
2. **Conexión.** `connect()` con un `WifiP2pConfig` creado con el constructor simple:
   - `deviceAddress` = MAC P2P del coche;
   - **`groupOwnerIntent = 0`**;
   - WPS y grupo persistente con los valores por defecto (PBC y persistente [PLATAFORMA]).

   El teléfono **nunca** hace `createGroup`, **nunca** llama a `setDeviceName` (el código existe, pero no se alcanza) y
   **nunca** anuncia ni busca servicios DNS-SD/UPnP (también es código que no se alcanza).
3. **Roles e IPs.** Con intent 0, el dueño del grupo (GO) es el **coche** (salvo empate de intents, o un grupo
   persistente de una vez anterior en la que el móvil quedó como GO, §2.7). Las IPs las
   reparte el DHCP del coche: con un GO Android, `192.168.49.1/24` [PLATAFORMA]. QDLink lee la IP del GO, pero **solo la
   escribe en el log**.
4. **Traspaso a UDP/TCP.** Al formarse el grupo, QDLink no hace nada más. Espera en `0.0.0.0:18463` un
   `Connect_Broadcast` cuyo **`DeviceName` esté contenido en el nombre P2P** del coche pulsado (la regla no comprueba
   si hay grupo ni por qué red llega, §2.7). Entonces, **sin que el usuario haga nada**:
   - para la búsqueda P2P;
   - elige el puerto `P`;
   - prepara el mismo ACK de 174 B, dirigido a `IP_origen:18464`;
   - abre el `ServerSocket(P)`, envía el ACK desde `:18463` y espera el TCP 20 s.

   **El coche tiene que emitir `Connect_Broadcast` también por la interfaz P2P**: es el único disparador del ACK.
5. **Temporizadores.**
   - `discoverPeers` se relanza cada **3 s** si la búsqueda se ha parado, siempre que no haya unión en curso ni grupo
     formado (`E==0`) y ningún peer esté `INVITED`.
   - La unión al grupo tiene un plazo de **60 s**; si vence, `cancelConnect`.
   - No hay reintentos.
   - Con el grupo formado, la espera del broadcast **no tiene límite**.
6. **Cierre.** Al terminar la sesión: `removeGroup`, se cierran los sockets y se vuelve a buscar. No hay reconexión
   automática. `LINK_HISTORY` solo sirve para marcar «Emparejado» en la lista. La cadena «Conexión automática»
   (`link_wugan`) no se usa en ninguna pantalla.
7. **Permisos de QDLink.** En Android 13+ pide `NEARBY_WIFI_DEVICES` y la ubicación precisa y aproximada. No declara
   `neverForLocation`.

   **Recomendación** para nuestra app:
   - `NEARBY_WIFI_DEVICES` con `usesPermissionFlags="neverForLocation"`;
   - `ACCESS_FINE_LOCATION` y `ACCESS_COARSE_LOCATION` con `maxSdkVersion="32"`;
   - `CHANGE_WIFI_STATE`, que ahora falta en el manifiesto (§7.2).

---

## 2. Entrada, hilos y máquina de estados

### 2.1 Cómo entra el usuario en el modo Wi-Fi Direct

| # | Qué pasa | Referencia |
|---|---|---|
| 1 | **Al arrancar la app, sea cual sea el modo:** `MainActivity.onCreate` → `J()` → `IC/d.y(ctx, true)` → `WF/c.q(true)` crea y arranca el hilo `UdpReceiveDataThread` con `isStartDirect=true` | `QL/MainActivity.java:931`, `:797-802`; `IC/d.java:371-376`; `WF/c.java:161-166`; `WF/a.java:627-648` |
| 2 | El hilo UDP inicializa P2P **antes** de abrir el UDP 18463: `w()` → `WD/c.O().T(ctx, this)` → `R(ctx, canal=-1, nombre=null, rol=0, modo=0)` | `WF/a.java:480-482`, `:266-271`; `WD/c.java:1106-1108`, `:1071-1100` |
| 3 | `R()`: `getSystemService("wifip2p")`, `initialize(ctx, mainLooper, null)` (callbacks en el hilo principal y sin `ChannelListener`), `registerReceiver(receiver, filtro)` y arranque del `DiscoveryThread` | `WD/c.java:1084-1099`; filtro en `:654-666` |
| 4 | Llega `WIFI_P2P_STATE_CHANGED = ENABLED`: **`cancelConnect` + `removeGroup`**, para limpiar cualquier conexión o grupo previos | `WD/c.java:837-848`, `:956-965`, `:1002-1007` |
| 5 | **Ajustes → «Wi-Fi Direct»** (`cl_direct`): `SettingView.S()`. Si no hay sesión (`MyApplication.f9901v == false`): guarda `WUGAN_LINK=true`, llama a `IC/d.v()` → `WF/c.o()` → `WF/a.E()` → **`WD/c.f0()`** (`discoverPeers`) y publica `ShowLinkBean`. Si hay sesión, ofrece desconectar | `RES/res/layout/layout_setting.xml:40-52`; `QL/mine/setting/SettingView.java:447-455`, `:674`, `:552-574`; `IC/d.java:334-339`; `WF/c.java:147-152`; `WF/a.java:304-309`; `WD/c.java:1114-1140` |
| 6 | `MainPageView.showLinkDialog` → `new LinkDialog(ctx)` → `startSearch()` → `startFindWifi()`: `type=1` y título «Lista de dispositivos» | `QL/mainpage/MainPageView.java:663-670`; `QL/wifilink/LinkDialog.java:344-348`, `:226-256` |
| 7 | `linkWifi()`: si la Wi-Fi está activada o activándose (`getWifiState()` 3 o 2) → `link(true)` → `IC/d.k(1)` → `WF/c.m(1)` → `WF/a.D(1)` (`wifiModeType=1`; a partir de aquí la lista muestra peers P2P, no coches UDP). Si la Wi-Fi está apagada, solo muestra «Asegúrese de que el Wi-Fi de su teléfono y del coche esté activado.». Ojo: a los 3 s, si la lista sigue vacía, ese mismo texto sustituye a «Buscando...» y se oculta la rueda del título **aunque la Wi-Fi esté activada**; la búsqueda sigue en segundo plano | `LinkDialog.java:201-219`, `:195-199`, `:247-249`, `:76-85`; `IC/d.java:232-237`; `WF/a.java:293-302`; `ES:` `link_sure_open` 0x7f0f003f |
| 8 | «Actualizar» vuelve a llamar a `IC/d.v()` (`discoverPeers`). «Cancelar» hace `cancelConnect` y `stopPeerDiscovery` | `LinkDialog.java:162-168`, `:149-161`; `IC/d.java:156-161`, `:378-383` |

Textos:
- Ajustes, opción: «Wi-Fi Direct» (`ES:` `link_wifi_direct_connected` 0x7f0f0043; `RES/res/values/strings.xml:70`).
- Ayuda (`QL/wifilink/LinkHelpView.java:180-185`): título «无感连接». Instrucciones: «1、打开手机Wi-Fi 2、打开中控屏Wi-Fi手机
  3、在搜索列表中选择中控设备名称» (*1. activa la Wi-Fi del móvil; 2. activa la Wi-Fi de la pantalla central; 3. en la
  lista de búsqueda, elige el nombre de la pantalla central*), sin traducir al español
  (`RES/res/values/strings.xml:72`). Nota: «本功能仅支持部分车型» (*solo algunos modelos*, `:71`).

### 2.2 Hilos y clases

| Contexto | Clase | Papel | Ref. |
|---|---|---|---|
| Hilo principal (Looper del canal) | `WD/c`, receiver `WD/c$y`, todos los `ActionListener` | Llamadas a `WifiP2pManager` y broadcasts P2P | `WD/c.java:1087-1088`, `:584-652` |
| `DiscoveryThread` (Thread propio, bucle de 3 s) | `WD/c$x` | Relanza la búsqueda | `WD/c.java:542-582`, `:665`, `:1089-1099` |
| `java.util.Timer` | `WD/c$m` | Plazo de 60 s para unirse | `WD/c.java:334-367`, `:902-917` |
| `UdpReceiveDataThread` | `WF/a` | UDP 18463, regla de nombre, disparo del ACK | `WF/a.java:474-537` |
| Hilo efímero | `WF/a$b` | Construye el ACK P2P y avisa a la UI | `WF/a.java:99-152`, `:463-465` |
| UI (hilo principal) | `LinkDialog`, `SettingView`, `MainPageView`, EventBus | Lista y pulsación | §2.1 |
| `wifiserversocketthread` | `WF/d` | `ServerSocket`, envío del ACK, `accept` (igual que en 04) | `WF/d.java:71-120` |

### 2.3 Estado interno de `WD/c`

| Nombre aquí | Campo jadx | Valores | Quién lo cambia |
|---|---|---|---|
| `B` inicializado | `B` (`:123`) | true tras `R()` | `R()` `:1072-1075`; `i0()` `:1165-1166` |
| `C` P2P habilitado | `C` (`:124`) | según `WIFI_P2P_STATE_CHANGED` | `b0()` `:836-877`; `i0()` `:1175` |
| `A` búsqueda pedida | `A` (`:39`) | true tras `f0()` | `f0()` `:1135-1138`; `h0()` `:1144-1146` |
| `D` estado de la búsqueda | `D` (`:125`) | 0 parada, 1 pedida, 2 en marcha | `H()` `:699-700`; `Y()` `:806-816`; fallo de `discoverPeers` `:412`; P2P deshabilitado `:865`; `i0()` `:1177` |
| `E` estado del grupo («mGroupFormedStatus») | `E` (`:126`) | 0 sin grupo, 1 uniéndose, 2 en grupo | `E()` `:981-982`; `X()` `:758-802`; timeout `:357-360`; fallo `:244-245`; `B()` (`cancelConnect`, 1→0) `:960-962`; P2P deshabilitado `:864`; `i0()` `:1176` |
| Datos del grupo | `F`, `G`, `H`, `I`, `J` (`:40-44`) | isGroupOwner, MAC del GO, IP del GO, SSID `DIRECT-…`, passphrase | `X()` `:787-793` |
| Configuración | `w`, `x`, `y`, `z` (`:113-122`) | canal (-1), nombre (null), **rol (0)**, **modo (0)** | `R()` `:1079-1082` |

### 2.4 Roles y modos de la librería, y lo que usa QDLink

`WD/c` es una librería genérica con tres **roles** (`y`) y tres **modos** de descubrimiento (`z`). Constantes públicas:
`K/L/M = 0/1/2` y `N/O/P = 0/1/2` (`WD/c.java:32-37`).

| Comportamiento | Condición | Ref. (dex en el Anexo B) |
|---|---|---|
| `discoverPeers` en `f0()` | `z==0 && y!=2` | `WD/c.java:1117-1120` |
| Pide servicios (DNS-SD o UPnP) | `z==1 && y==0` → `y()`; `z==2 && y==0` → `A()` | `:1121-1134` |
| **Anuncia** servicios | `z==1 && y!=0` → `x()`; `z==2 && y!=0` → `z()` | `:1121-1134` |
| **`createGroup`** al habilitarse P2P | `(z==0 && y==2) \|\| (z!=0 && y!=0)` | `:849-853` |
| `groupOwnerIntent` en `connect` | solo si `z==0`: `y==1` → **15**; `y==0` → **0**; en otro caso no se toca | `:990-997` |
| Marca `A` (búsqueda activa) | `(z==0 && y!=2) \|\| y==0` | `:1135-1138` |
| Bucle de 3 s | `z==0` → `requestPeers`→`discoverPeers`; `z!=0` → `discoverServices` | `:563-569` |

Interpretación de los roles:

| Rol | Significado | Qué hace |
|---|---|---|
| 0 | cliente | busca y se une con intent 0 |
| 1 | GO por negociación | busca y se une con intent 15 |
| 2 | GO autónomo | `createGroup`; no busca |

Esa tabla vale para el modo 0. En los modos de servicio (`z` = 1 o 2), los roles 1 y 2 hacen lo mismo: `createGroup` al
habilitarse P2P y **anuncio** del servicio en `f0()`. No llaman nunca a `discoverPeers` ni a `discoverServices`, porque
`A` no llega a ponerse a true (`(z==0 && y!=2) || y==0` es falso). El rol 0 **pide** el servicio, repite
`discoverServices` cada 3 s y se une sin tocar el intent (queda `GROUP_OWNER_INTENT_AUTO`, porque el intent solo se fija
con `z==0`) (`WD/c.java:1121-1138`, `:849-853`, `:567-569`, `:990-997`).

**QDLink en el teléfono usa siempre rol 0 y modo 0.** El único inicializador alcanzable es `T()`
(`WF/a.java:266-271` → `WD/c.java:1106-1108`). `P(ctx, 48, 0, 0)` también usa rol 0 y modo 0, y solo lo llama
`WF/a.y()` (`WF/a.java:596-607`). Ese método solo se alcanza desde `IC/d.f()` (`IC/d.java:163-184`), que no tiene
llamadores: es código muerto. `S()`, el único que pasa un nombre, y `Q()` no tienen llamadores.

**[INFERENCIA]** Los roles 1 y 2 y los modos de servicio sirven para el otro extremo, es decir, el coche, o para otros
productos de Neusoft. Las cadenas de §3.3 indican qué podría anunciar un coche que use esta librería.

### 2.5 Secuencia completa

```
Teléfono (QDLink)                                 Android / aire                          Coche
onCreate: initialize, receiver, DiscoveryThread
STATE_CHANGED=ENABLED → cancelConnect + removeGroup
Ajustes ▸ Wi-Fi Direct → discoverPeers ──────────> búsqueda P2P (sondeos) ──────────────> (visible)
cada 3 s: si la búsqueda paró → requestPeers → discoverPeers
PEERS_CHANGED → «Lista de dispositivos» (todos) → el usuario pulsa «<nombre P2P>»
uuidName = <nombre P2P>; connect{MAC, intent 0}; plazo de 60 s
                                                  negociación GO o unión, WPS PBC ──────> (¿pide aceptar?)
CONNECTION_CHANGED groupFormed=true → solo log (isGO, IP del GO, SSID); E=2
                                                  DHCP: móvil 192.168.49.x  <──  GO 192.168.49.1 [PLATAFORMA]
            <── UDP "Connect_Broadcast" → :18463, desde la IP P2P del coche ───────────────────
DeviceName ⊂ uuidName y estado≠1 → stopPeerDiscovery; P=aleatorio; estado=1; ACK preparado; uuidName=""
replyConnectSuccess → ConnectActivity → ServerSocket(P) → ACK desde :18463 ──────────────> IP_coche:18464
            <── TCP connect → IP_P2P_móvil:P ──────────────────────────────────────────────────────────
sesión de 04-wire-spec (AppStatus, HEARTBEAT, CAR_INFO, vídeo, táctil…)
fin de sesión (L()): removeGroup → sockets cerrados → uuidName="" → stop + start de la búsqueda
```

### 2.6 Máquina de estados

| Estado | Entrada | Evento | Acción de QDLink | Siguiente |
|---|---|---|---|---|
| **P0 Inactivo** | App arrancada; `E=0`, `A=false` | `STATE_CHANGED`=2 | `C=true`, `cancelConnect`, `removeGroup` (`WD/c.java:839-848`) | P0 |
| P0 | | `STATE_CHANGED`=1 (Wi-Fi apagada o P2P desactivado) | `C=false`, `A=false`, todo a cero, listas vacías (`:856-875`). Al volver a habilitarse **no** se relanza la búsqueda: hay que reabrir la lista | P0 |
| P0 | | Ajustes ▸ Wi-Fi Direct o «Actualizar» | `f0()`: `discoverPeers`, `A=true` | **P1** |
| **P1 Buscando** | `A=true`, `E=0` | Cada 3 s con `C && A && D==0 && E==0` | `requestPeers` → si ningún peer está `INVITED`, `discoverPeers` (`D=1`) (`:563-566`, `:550-552`, `:721-733`) | P1 |
| P1 | | `PEERS_CHANGED` | Lista nueva para la UI (`:829-834`, `:818-827`) | P1 |
| P1 | | Zona Wi-Fi activándose o activada (`WIFI_AP_STATE` 12/13) | Vacía la lista de peers y refresca la UI (dex) | P1 |
| P1 | | El usuario pulsa un peer | `uuidName=nombre` (`WF/a.java:458`); `E()`: `E=1`, `connect`, plazo de 60 s (`WD/c.java:967-1000`) | **P2** |
| P1 | | «Cancelar» | `cancelConnect` (con `E` 1→0 si había unión en curso) + `stopPeerDiscovery` (`A=false`) | P0 |
| **P2 Uniéndose** | `E=1` | `connect` → `onFailure(r)` | Para el plazo, `E=0`, `replyConnectFail(r)`, que la UI **ignora** (`WD/c.java:240-253`; `LinkDialog.java:312-314`) | P1 |
| P2 | | Vencen los 60 s | `E=0` y `cancelConnect` (`WD/c.java:354-366`) | P1 |
| P2 | | `CONNECTION_CHANGED` con `groupFormed=false` | Se trata como fallo: para el plazo y `E=0` (`:761-770`) | P1 |
| P2 | | `CONNECTION_CHANGED` con `groupFormed=true` | Para el plazo, guarda los datos del grupo, `E=2`, callback que solo escribe en el log (`:785-800`; `WF/a.java:423-426`) | **P3** |
| P2 | | `Connect_Broadcast` que cumple la regla (solo puede llegar por otra red) | Lo mismo que en P3: la regla no mira `E` (§2.7). El plazo de 60 s y la unión siguen en marcha | P4 |
| **P3 En grupo** | `E=2`; `uuidName` puesto | `Connect_Broadcast` con `uuidName.contains(DeviceName)` y estado≠1 | §2.7: ACK automático y paso a TCP | **P4** |
| P3 | | Otro `Connect_Broadcast` | Va a la lista UDP, que en este modo no se muestra (`WF/a.java:517-520`, `:89-92`) | P3 |
| P3 | | El usuario pulsa un peer | **`removeGroup` y vuelve sin conectar** (`WD/c.java:976-980`): hay que pulsar otra vez | P1, tras el grupo |
| P3 | | `CONNECTION_CHANGED` con `groupFormed=false` | `E=0` y callback que solo escribe en el log (`:771-777`). Si `A` sigue a true, se vuelve a buscar | P1 |
| **P4 Sesión** | ACK enviado y TCP aceptado | TCP de 04 | — | P4 |
| P4 | | `accept` agota sus 20 s | Estado -1 y teardown del motor, **sin `removeGroup`**. La búsqueda queda parada (`A=false` desde el broadcast) y `uuidName=""`. QDLink se queda colgado: hay que volver a Ajustes ▸ Wi-Fi Direct y pulsar dos veces (la primera solo quita el grupo) | `WF/d.java:28-43`; `WF/a.java:508-511`, `:148` |
| P4 | | Fin de sesión (`L()`: IOException o watchdog) | `removeGroup` (con `wifiModeType==1`), cierra sockets, `uuidName=""`, `h0()` + `f0()` (vuelve a buscar) | `LC/a.java:1710-1736`; `WF/a.java:539-545`, `:609-625` |
| P4 | | «Desconectar» en Ajustes, o el usuario rechaza la captura de pantalla (04 §7.5) | `IC/d.A()`: `removeGroup` (sin mirar `wifiModeType`), cierra sockets y vuelve a buscar | `IC/d.java:90-115`, `:247-252`; `WF/a.java:547-552`; `QL/MainActivity.java:582-585` |
| cualquiera | | Salir de la app | `i0()`: stop de la búsqueda, `cancelConnect`, `removeGroup`, `unregisterReceiver`, `channel.close()` (API 27+) | `IC/d.java:117-138`; `WF/c.java:191-199`; `WF/a.java:240-247`; `WD/c.java:1164-1193` |

### 2.7 Traspaso a UDP 18463 / TCP

**Lo que hace QDLink al formarse el grupo.** Solo escribe en el log `isGroupOwner`, la MAC del GO, la IP del GO, el SSID
y la passphrase (`WD/c.java:785-800` → `WF/a.java:423-426`). No usa la IP del GO, no la compara con nada y no abre
ningún socket especial.

**El disparador es el UDP** (`WF/a.java:498-516`; dex en el Anexo B):

1. Llega cualquier datagrama a `0.0.0.0:18463` (el mismo socket de 04 §1.1, que no se ata a ninguna interfaz).
   `carIp = IP origen`. Se leen `DeviceUUID` y `DeviceName` del JSON en el offset 49.
2. Condición: **`LinkConfig.uuidName.contains(DeviceName)`**. Hay que fijarse en el sentido de la comparación: el nombre
   P2P pulsado tiene que **contener** el `DeviceName` del broadcast (`dex: wificonnection.a.run @00f4-00fc`). Además,
   el estado de conexión tiene que ser ≠1 (`WF/a.java:505-507`).
   - La regla **no mira `E`** (el estado del grupo) **ni la IP o la interfaz de llegada**. Desde la pulsación
     (`uuidName` puesto), un broadcast que la cumpla dispara el ACK aunque el grupo aún no se haya formado y aunque
     llegue por otra red (`WF/a.java:505-516`).
   - Con un `DeviceName` vacío se cumple siempre (`"…".contains("")` es true), incluso sin pulsación (04 §1.3, punto 5).
3. Si se cumple:
   - `WD/c.h0()`: `stopPeerDiscovery` y `A=false` (`:508-511`);
   - `P = u()` (aleatorio entre 10001 y 65535), guardado en `LinkConfig` (`:512-513`);
   - `q(P, carIp)` (`:514`);
   - **estado = 1 en el acto** (`:515`), para que ningún broadcast posterior lo vuelva a disparar.
4. `WF/a$b` (`WF/a.java:113-151`):
   - construye el **mismo** ACK que en el modo zona Wi-Fi: los mismos campos en el mismo orden, 174 B (04 §1.4);
   - lo guarda como `DatagramPacket` dirigido a `carIp:18464` (`:136-137`);
   - avisa con `c()` → `IC/d.replyConnectSuccess()`, que fija `linkMode=1` (`IC/d.java:309-315`) →
     `LinkDialog.replyConnectSuccess()` → EventBus → `MainActivity.startUsbConnect()` → `R()` → `ConnectActivity` → motor
     → `WF/d` (`LinkDialog.java:316-328`; `QL/MainActivity.java:1073-1076`, `:877-883`);
   - en el `finally`: `uuidName = ""` (`WF/a.java:147-149`; `dex: a$b.run @0129-012d`).
5. `WF/d.run()` funciona igual que en 04 §2: `ServerSocket(P)`, envío del ACK desde el socket de 18463, 20 s de espera
   y `accept` (`WF/d.java:78-83`).

Respuestas a las preguntas clave:

| Pregunta | Respuesta | Base |
|---|---|---|
| ¿El coche sigue emitiendo `Connect_Broadcast` por la interfaz P2P? | **Tiene que hacerlo.** Sin broadcast no hay ACK, ni `ServerSocket`, ni TCP: no existe otro camino en Wi-Fi Direct | `WF/a.java:505-516`; el callback del grupo no hace nada (`:423-426`) |
| ¿Qué `DeviceName`? | Una subcadena del nombre P2P del coche (lo normal: el mismo nombre). Si el coche usa otro nombre, QDLink **no conecta nunca** en este modo (salvo con `DeviceName` vacío, que encaja siempre) | `:505` |
| ¿A qué dirección lo envía el coche? | **Incógnita.** El teléfono escucha en `0.0.0.0:18463` y acepta broadcast limitado (`255.255.255.255`), dirigido (`192.168.49.255`) o unicast a su IP P2P | `WF/a.java:486-489` |
| ¿A dónde va el ACK? | A `IP_origen_del_broadcast:18464`, desde `:18463`. Lo normal es que esa IP sea la del GO (el coche) | `WF/a.java:499`, `:137`; `WF/d.java:80` |
| ¿Adónde conecta el TCP? | El coche, a la IP P2P del móvil (la IP origen del ACK) y al puerto `P` [INFERENCIA, igual que en 04 §2.1] | `WF/d.java:78` (todas las interfaces) |
| Enrutado del ACK por la interfaz P2P | Automático: la subred P2P está en la tabla `local_network` de Android, que se consulta antes que la red por defecto en los sockets no vinculados [PLATAFORMA]. **No funciona si el proceso está vinculado a una red** (`bindProcessToNetwork`) | — |

Quién es GO y qué IPs resultan:

| Caso | Cómo se llega [PLATAFORMA] | GO | IPs | Efecto en QDLink |
|---|---|---|---|---|
| El coche ya es GO autónomo (`isGroupOwner()` del peer = true en la lista) | `connect` hace una **unión** (provision discovery + WPS PBC); el intent no se usa | coche | DHCP del coche (GO Android: GO `192.168.49.1/24`, clientes `.2-.254`) | flujo normal |
| Negociación, con intent del coche > 0 | gana el intent mayor | coche | igual | flujo normal |
| Negociación, con intent del coche = 0 | empate → bit de desempate | cualquiera | si el móvil es GO: móvil `192.168.49.1` y coche con lease | solo se registra (`isGroupOwner`); el flujo UDP/TCP no depende del rol |
| Coche con intent 15 + móvil con intent 0 | gana el coche | coche | DHCP del coche | flujo normal |
| Ya hay un grupo persistente guardado con ese coche (§5.7) | se **reinvoca** el grupo guardado (*invitation*); el intent no se usa | el mismo que la primera vez: si el móvil quedó como GO por empate, vuelve a serlo | las de ese grupo | solo se registra; el flujo UDP/TCP no depende del rol |

---

## 3. Descubrimiento de peers y de servicios

### 3.1 Peers: `discoverPeers`

| Llamada | Cuándo | Resultado | Ref. |
|---|---|---|---|
| `f0()` → `discoverPeers` (listener `n`) | Ajustes ▸ Wi-Fi Direct; «Actualizar»; tras cada fin de sesión (`z()`) | Solo se escribe en el log | `WD/c.java:1114-1120`, `:369-382`; `WF/a.java:609-616` |
| `H()` → `discoverPeers` (listener `p`, `D=1`) | Desde el bucle de 3 s, si `C && A && D==0 && E==0` y `requestPeers` no devuelve ningún peer `INVITED` | Si falla: avisa a la UI **dos veces** (código duplicado) con `replayDiscoverFail`, que está vacío, y pone `D=0` | `WD/c.java:697-704`, `:399-419`, `:542-566`; `LinkDialog.java:308-310` |
| `stopPeerDiscovery` (`h0()`, solo con `D≠0`) | Llega el broadcast bueno; «Cancelar»; salir de la app; fin de sesión (seguido de `f0()`) | Solo se escribe en el log | `WD/c.java:1142-1162`, `:384-397` |

- Por qué se relanza: la búsqueda de Android **para sola** al cabo de ~120 s [PLATAFORMA], y también al empezar una
  conexión o al formarse el grupo (`SDK:` javadoc de `discoverPeers` y de `WIFI_P2P_DISCOVERY_CHANGED_ACTION`). QDLink
  la vuelve a lanzar como mucho 3 s después de recibir `DISCOVERY_STATE_CHANGE = STOPPED` (`WD/c.java:804-816`).
- No relanza la búsqueda mientras se une a un grupo o está en él (`E≠0`: 1 uniéndose, 2 en grupo) ni mientras algún
  peer está `INVITED` (`U()`, `:721-733`).
- Al activarse la zona Wi-Fi del móvil (estados 12 y 13), vacía la lista de peers y refresca la UI (`WD/c.java:605-640`;
  el `break` de jadx es falso, `dex: c$y.onReceive @007a-00a2`, `@00c6-00ed`). [INFERENCIA] Neusoft vio que con la zona
  Wi-Fi encendida desaparece el P2P (§7.3).

### 3.2 Qué se muestra en la lista

1. `PEERS_CHANGED` → `a0()` → `Z(extra "wifiP2pDeviceList")`: la lista completa del framework (`WD/c.java:829-834`,
   `:818-827`).
2. `J()` + `W()` traducen el estado del peer (`WD/c.java:1009-1024`, `:735-755`):
   - `CONNECTED(0)` → 2;
   - `INVITED(1)` → 1;
   - `AVAILABLE(3)` → 0;
   - **`FAILED(2)` y `UNAVAILABLE(4)` se descartan**.

   De cada peer guarda `deviceName`, `deviceAddress`, el estado e `isGroupOwner()`.
3. `WF/a.a()` crea un `SearchDevice` de tipo 2 con `c()`=MAC, `b()`=nombre, `e()`=estado y `f()`=isGO. Solo los publica
   si `wifiModeType==1` (`WF/a.java:369-397`; campos en `IC/bean/SearchDevice.java:24-70`).
4. `LinkAdapter` muestra **solo el nombre**:
   - una rueda de carga si el estado es `INVITED`;
   - la etiqueta «Emparejado» si nombre, MAC y tipo coinciden con `LINK_HISTORY`

   (`QL/wifilink/LinkAdapter.java:22-39`; `RES/res/layout/item_wifi_link.xml`; `ES:` `connect_pair` 0x7f0f0027).

**No hay ningún filtro**: ni por nombre, ni por tipo de dispositivo (`primaryDeviceType` no aparece en el código), ni
por servicio, ni por OUI. También salen televisores, impresoras y otros móviles. El usuario elige el coche por su nombre.

### 3.3 Servicios DNS-SD y UPnP (el código existe, pero QDLink no lo usa)

Solo se ejecutaría con el modo `z` = 1 o 2, y QDLink usa 0 (§2.4). Cadenas y llamadas exactas:

| Qué | API | Valores exactos | Ref. |
|---|---|---|---|
| **Anuncio** DNS-SD (rol≠0, modo 1) | `addLocalService(WifiP2pDnsSdServiceInfo.newInstance(instancia, tipo, txt))` | instancia **`"QDLink_DnsSd_Instance"`**, tipo **`"_ssplink._tcp"`**, TXT **`null`** (sin claves) | `WD/c.java:934-939`, `:98` |
| **Petición** DNS-SD (rol 0, modo 1) | `setDnsSdResponseListeners(t, u)` + `addServiceRequest(WifiP2pDnsSdServiceRequest.newInstance())` (pide **todos** los DNS-SD) | — | `:941-947` |
| Filtro DNS-SD | `onDnsSdServiceAvailable(instanceName, registrationType, device)` | `instanceName.contains("QDLink_DnsSd_Instance")`. No mira ni el tipo ni el TXT (el listener del TXT está vacío) | `:465-499` |
| **Anuncio** UPnP (rol≠0, modo 2) | `addLocalService(WifiP2pUpnpServiceInfo.newInstance(uuid, device, services))` | uuid `UUID.randomUUID()`, device **`"QDLink_UPnP_Device"`**, services `null` | `:949-954`, `:101` |
| **Petición** UPnP (rol 0, modo 2) | `setUpnpServiceResponseListener` + `addServiceRequest(WifiP2pUpnpServiceRequest.newInstance())` (todos) | — | `:668-674` |
| Filtro UPnP | `onUpnpServiceAvailable(List<String>, device)` | algún elemento `contains("QDLink_UPnP_Device")` | `:158-189` |
| Lista de servicios | sin duplicados por MAC; caducan a los **30 s** | — | `:170-176`, `:474-480`, `:1026-1031` |
| Repetición | `discoverServices` cada 3 s si `C && A && E≠1`. En la práctica solo lo hace el que pide (rol 0): con rol≠0 `A` nunca es true (§2.4) | — | `:567-569`, `:706-712`, `:1135-1138` |
| Limpieza | rol 0: `clearServiceRequests`; rol≠0: `clearLocalServices` (en `h0()`) | — | `:1147-1156`, `:676-688` |

[PLATAFORMA] `WifiP2pUpnpServiceInfo.newInstance(uuid, "QDLink_UPnP_Device", null)` anuncia `uuid:<uuid>`,
`uuid:<uuid>::upnp:rootdevice` y `uuid:<uuid>::QDLink_UPnP_Device`. En DNS-SD, el `registrationType` recibido llega con
el sufijo `.local.` (`_ssplink._tcp.local.`).

### 3.4 Cómo decide QDLink que un peer es un coche QDLink

- **En la capa P2P, no lo decide**: lo decide el usuario al pulsar un nombre.
- La única comprobación «QDLink» llega después, en UDP:
  1. el datagrama tiene que tener el JSON en el offset 49 con `DeviceUUID` y `DeviceName` (04 §1.3);
  2. el `DeviceName` tiene que estar contenido en el nombre P2P pulsado (§2.7).
- No se comprueban `isGroupOwner`, `primaryDeviceType`, WFD, elementos de fabricante ni el prefijo de la MAC.

---

## 4. `setDeviceName` y `setWifiP2pChannels` por reflexión

| Qué | Código | ¿Se ejecuta en QDLink? | Si falla |
|---|---|---|---|
| `setDeviceName(channel, nombre, listener)` por reflexión | `WD/c.java:879-888`, al habilitarse P2P (`:847`) | **No**: solo se ejecuta con un nombre ≠ null, y todos los inicializadores alcanzables pasan `null` (`T()` `:1107`, `P()` `:1064`); `S()`, el único que pasa nombre (`:1102-1104`), no tiene llamadores | `catch (Exception)` → `Log.e("setDeviceName: throws exception: …")`, o bien `onFailure` → `Log.e` (`:885-887`, `:294-307`). No hay nada más que dependa de ello |
| `setWifiP2pChannels(channel, lc=0, oc=canal, listener)` | `WD/c.java:890-900` | **No**: hace falta un canal ≥0 y solo lo pasa `WF/a.y()` (canal **48**, código muerto, §2.4) | Igual: solo se escribe en el log |

- **Qué nombre pone QDLink:** ninguno. El coche ve el nombre P2P por defecto del móvil, que es el nombre del dispositivo
  de Android (en Samsung: Ajustes ▸ Acerca del teléfono ▸ Editar) [PLATAFORMA].
- **[INFERENCIA]** Por tanto, **el coche no puede depender del nombre P2P del teléfono**: QDLink funciona con
  cualquier móvil sin renombrarlo. Tampoco recibe el nombre por UDP, porque el ACK lleva `DeviceName:""` (04 §1.4).
- **Plataforma:**
  - `setDeviceName` y `setWifiP2pChannels` **no forman parte del SDK público** (no están en `SDK: android.jar` ni en
    `api-versions.xml`).
  - Desde Android 9 la reflexión sobre API ocultas está restringida.
  - Desde Android 11 son `@SystemApi` y el servicio exige `NETWORK_SETTINGS`, `NETWORK_STACK` u `OVERRIDE_WIFI_CONFIG`
    [PLATAFORMA].
  - En una app normal, o la reflexión lanza una excepción, o la llamada termina en `onFailure(ERROR)`.
- **Recomendación: no llamarlas.** No hacen falta.

---

## 5. `createGroup` frente a `connect`

### 5.1 `connect`: configuración exacta

`WD/c.E(mac)` (`WD/c.java:967-1000`; `dex: c.E @004f-0071`):

| Campo de `WifiP2pConfig` | Valor en QDLink | Ref. |
|---|---|---|
| Construcción | `new WifiP2pConfig()`, constructor simple (sin `Builder`) | `:988` |
| `deviceAddress` | MAC P2P del peer pulsado (`SearchDevice.c()` = `WifiP2pDevice.deviceAddress`) | `:989`; `WF/a.java:459`, `:382` |
| `groupOwnerIntent` | **0** (rol 0, modo 0). Sería 15 con rol 1 y modo 0; en otros casos no se toca y queda el valor por defecto `GROUP_OWNER_INTENT_AUTO` = -1 | `:990-997`; `dex: c.E @0056-0066`; `SDK: WifiP2pConfig` (javadoc: «By default this field is set to GROUP_OWNER_INTENT_AUTO») |
| `wps.setup` / `wps.pin` | no se tocan → **PBC** y sin PIN (valores del constructor) [PLATAFORMA] | — |
| `netId` | no se toca → `NETWORK_ID_PERSISTENT` (-2): **grupo persistente** [PLATAFORMA] | `SDK: WifiP2pGroup.NETWORK_ID_PERSISTENT = -2` |
| Nombre de red, passphrase, banda, versión P2P | no (no se usa el `Builder`) | — |

Condiciones y efectos:
- No hace nada si P2P no está habilitado, si ya hay una unión en curso (`E==1`) o si la MAC está vacía (`:969-971`).
- **Si ya hay grupo (`E==2`)**: hace `removeGroup` y **vuelve sin conectar** (`:976-980`).
- Si `E==0`: pone `E=1`, `connect` y el plazo de 60 s (`:981-999`).
- Listener `g` (`:236-262`):
  - `onSuccess` solo marca una bandera (`WF/a.java:408-411`);
  - `onFailure(r)` para el plazo, pone `E=0` y llama a `replyConnectFail(r)`, que **la UI ignora** (`LinkDialog.java:312-314`).

### 5.2 `createGroup`

`F()` (`WD/c.java:690-695`) solo se llama desde `b0()` cuando `(z==0 && y==2) || (z!=0 && y!=0)`
(`dex: c.b0 @0023-0031`). En QDLink (`y=0`, `z=0`) **nunca** se llama: **el teléfono nunca crea el grupo**.

### 5.3 `cancelConnect` y `removeGroup`

| Llamada | Dónde | Cuándo |
|---|---|---|
| `cancelConnect` | `WD/c.B()` `:956-965` | Al habilitarse P2P (`:845`); «Cancelar» de la lista (`IC/d.java:156-161`); salir de la app (`:1169`) |
| `cancelConnect` | `WD/c$m` `:354-366` | Vencen los 60 s de unión |
| `removeGroup` | `WD/c.G()` `:1002-1007` | Al habilitarse P2P (`:846`); pulsar un peer con el grupo formado (`:976-980`); fin de sesión con `wifiModeType==1` (`LC/a.java:1712-1714` → `WF/a.java:539-545`); «Desconectar» o rechazo de la captura de pantalla, los dos por `IC/d.A()` (`IC/d.java:91` → `WF/a.java:547-552`; 04 §7.5); salir de la app (`:1170`) |

### 5.4 Reintentos y temporizadores

| Qué | Valor | Ref. |
|---|---|---|
| Relanzar la búsqueda | cada 3 s si está parada, sin unión en curso ni grupo formado (`E==0`) y sin peers `INVITED` | `WD/c.java:571`, `:563-566`, `:550-552` |
| Plazo de unión al grupo | **60 s** → `cancelConnect` (se para al formarse el grupo o al fallar) | `:916`, `:919-932` |
| Reintentos de `connect` | **ninguno** (el usuario tiene que volver a pulsar) | `:240-253` |
| Espera del `Connect_Broadcast` con el grupo formado | **sin límite** | — |
| Espera del TCP tras el ACK | 20 s (04 §2.1) | `WF/d.java:65` |
| Caducidad de los servicios (modos no usados) | 30 s | `WD/c.java:1030` |

### 5.5 Reacción a los broadcasts

Filtro registrado (`WD/c.java:656-662`): `STATE_CHANGED`, `DISCOVERY_STATE_CHANGE`, `PEERS_CHANGED`,
`CONNECTION_STATE_CHANGE` y `WIFI_AP_STATE_CHANGED`. **No registra `THIS_DEVICE_CHANGED`.**

| Broadcast | Extras que lee | Acción | Ref. |
|---|---|---|---|
| `android.net.wifi.p2p.STATE_CHANGED` | `wifi_p2p_state` (1 desactivado, 2 activado) | 2: inicializa (§2.6, P0). 1: lo pone todo a cero y `A=false` | `:836-877` |
| `android.net.wifi.p2p.DISCOVERY_STATE_CHANGE` | `discoveryState` (1 parada, 2 en marcha) | `D=2` o `D=0` | `:804-816` |
| `android.net.wifi.p2p.PEERS_CHANGED` | `wifiP2pDeviceList` | Lista nueva para la UI (solo con `C` y modo 0) | `:829-834` |
| `android.net.wifi.p2p.CONNECTION_STATE_CHANGE` | `wifiP2pInfo`, `p2pGroupInfo` | `groupFormed`: datos del grupo, `E=2` y solo log. Si no: `E` 1→0 (fallo) o 2→0 (salida), solo log | `:757-802`; `dex: c.X @0010-00ad` |
| `android.net.wifi.WIFI_AP_STATE_CHANGED` (no es pública en el SDK) | `wifi_state` | 12/13: vacía la lista de peers y refresca la UI; 10/11: solo log | `:605-640`; `SDK: data/broadcast_actions.txt` no la incluye |

### 5.6 `requestConnectionInfo` y `requestGroupInfo`

QDLink **no llama** a ninguno de los dos, ni a `requestDeviceInfo` o `requestNetworkInfo` (`dex:` no hay ninguna
llamada en `classes.dex` ni en `classes2.dex`). Todo lo saca de los extras de `CONNECTION_STATE_CHANGE`. Si faltara
`p2pGroupInfo`, `getOwner()` lanzaría un NPE dentro del receiver (`WD/c.java:789-790`); la javadoc de
`WIFI_P2P_CONNECTION_CHANGED_ACTION` avisa de que ese extra «puede ser null» (`SDK:`). Un `groupOwnerAddress` nulo daría
otro NPE (`:788`).

### 5.7 Qué hace Android con esta configuración [PLATAFORMA]

- **Unión o negociación.** Si el peer anuncia que ya es GO, el framework hace una **unión** al grupo existente
  (provision discovery + WPS) y el intent no se usa. Si no, negocia el GO con el intent indicado.
- **Grupo persistente.** Con `netId = PERSISTENT`, el framework guarda las credenciales del grupo. En la siguiente
  conexión con el mismo coche intenta **reinvocar** el grupo, o unirse directamente sin WPS. La reinvocación conserva
  los papeles de la primera vez (si el móvil quedó como GO, vuelve a serlo) y no usa el intent. Si el coche ya no lo
  recuerda, la invitación falla y el framework vuelve a negociar.
- **Cuándo llega `groupFormed=true`.** Si el móvil es cliente, Android lo emite después del DHCP, así que la IP P2P ya
  está puesta. Si el móvil es GO, lo emite cuando se une el primer cliente.
- **PBC.** El móvil que inicia la conexión no muestra ningún diálogo. El coche, si es un GO Android sin adaptar, mostraría
  «¿Aceptar conexión?». **[INFERENCIA]** Como las instrucciones de QDLink no mencionan nada en el coche, el coche
  acepta solo.
- **Plazo interno.** El framework tiene su propio plazo de creación del grupo, de unos 120 s. QDLink corta antes, a los
  60 s.

---

## 6. Reconexión y «Conexión automática»

- **«Conexión automática» no existe como función.**
  - La cadena `link_wugan` («Conexión automática»; `ES:` 0x7f0f0046; en inglés «No sensory connection», 无感连接) solo
    aparece en `QL/R.java:5380`: ninguna pantalla ni línea de código la usa.
  - En el código, «wugan» es el modo Wi-Fi Direct: la preferencia `WUGAN_LINK` (`QL/c.java:178`) la escribe
    `SettingView` (`:530`, `:554`) y la leen `LinkDialog` (`:204`, `:207`, `:229`).
- **Preferencias** (archivo `Q_DRIVE_SHARE_DATE`, `sources/com/neusoft/qdrivezeusbase/utils/SharedPreferencesUtils.java:8`):

| Clave | Contenido | Escritura | Lectura y uso |
|---|---|---|---|
| `LINK_HISTORY` (`QL/c.java:187`) | JSON de Gson del `SearchDevice` pulsado; las claves son los nombres ofuscados: `a` tipo (2 = P2P), `b` MAC, `c` nombre P2P, `d` IP (nula en P2P), `e` estado, `f` isGO | al pulsar (`LinkDialog.java:270-277`, `:330-338`) y al arrancar el espejo (`QL/mainpage/MainPageView.java:632-639`) | etiqueta «Emparejado» si nombre, **MAC** y tipo coinciden (`LinkAdapter.java:31-38`); autoconexión muerta (fila siguiente) |
| `LINK_HISTORY_CURRENT` (`:196`) | igual | igual | nombre en «¿Seguro que deseas desconectar la interconexión con X?» (`SettingView.java:543-545`, `:568-570`) |
| `AUTO_LINK` (`:169`) | booleano | **nunca** se escribe | `LinkDialog.java:190-192`, `:251` (siempre false) |

- **Autoconexión al coche recordado: código muerto.** Exige `isFirst=true` (`LinkDialog.java:293-302`), que solo pone el
  constructor `LinkDialog(Context, boolean)` (`:366-376`), y ese constructor no lo usa nadie. La única instancia es
  `new LinkDialog(this.mContext)` (`QL/mainpage/MainPageView.java:667`), y no hay ninguna en los layouts.
- **Tras una sesión**: `removeGroup` y vuelta a buscar (§2.6, P4). Para reconectar, el usuario tiene que abrir la lista y
  pulsar el coche.
- **Lo único que se «recuerda» es cosa de Android:** el grupo persistente (§5.7) puede hacer más rápida la siguiente
  conexión con el mismo coche [PLATAFORMA].
- **[PLATAFORMA]** Si el coche cambia su MAC P2P en cada arranque, la etiqueta «Emparejado» deja de coincidir.

---

## 7. Permisos y versiones de Android

### 7.1 Qué hace QDLink

- Manifiesto: `targetSdkVersion 35`, `minSdkVersion 21` (`RES/AndroidManifest.xml:12-14`). Permisos relacionados:
  - `ACCESS_WIFI_STATE` (`:19`);
  - **`NEARBY_WIFI_DEVICES` sin `usesPermissionFlags`** (`:52`);
  - `CHANGE_WIFI_STATE` (`:53`);
  - `ACCESS_COARSE_LOCATION` y `ACCESS_FINE_LOCATION` sin `maxSdkVersion` (`:54-55`).
- Pide los permisos en tiempo de ejecución al arrancar (`QL/SplashActivity.java:152-166`) y otra vez en
  `ConnectActivity` (`QL/interconnection/ConnectActivity.java:139-152`). La lista depende del nivel de API
  (`QL/c.java:289-292`):
  - de 23 a 30: localización precisa y aproximada (más otros permisos sin relación con P2P);
  - 31-32: además Bluetooth;
  - 33 o más: **`NEARBY_WIFI_DEVICES` + localización precisa y aproximada**.
- No comprueba si la ubicación del sistema está activada. El código de QDLink no llama a `isLocationEnabled` ni a
  `isProviderEnabled`, y no abre `ACTION_LOCATION_SOURCE_SETTINGS`. En el APK esas llamadas solo están dentro de
  AndroidX (`androidx.core.location.x`, `androidx.appcompat.app.j0`), y nada de `com.neusoft` las alcanza (`dex:`).
- Como no declara `neverForLocation`, en Android 13+ las llamadas P2P le exigen además `ACCESS_FINE_LOCATION` (fila
  siguiente). Por eso la pide.

### 7.2 Qué necesita nuestra app (targetSdk 36, minSdk 29)

Permisos que exige cada llamada, según `SDK: annotations.xml`: `@RequiresPermission(allOf = {NEARBY_WIFI_DEVICES,
ACCESS_FINE_LOCATION}, conditional = true)`. La anotación no dice la condición; la explica la javadoc de cada método
(`SDK: android-stubs-src.jar`): *«si la app apunta a Android 13+ (targetSdk 33+), `NEARBY_WIFI_DEVICES` con
`neverForLocation`; si no declara `neverForLocation`, también `ACCESS_FINE_LOCATION`; si apunta a una versión anterior,
`ACCESS_FINE_LOCATION`»*. Con nuestro targetSdk 36 eso equivale a `NEARBY_WIFI_DEVICES` en móviles con Android 13+ y a
`ACCESS_FINE_LOCATION` en Android 10-12, donde `NEARBY_WIFI_DEVICES` no existe:

| Llamada a `WifiP2pManager` | Permiso en tiempo de ejecución | Ref. `SDK:` |
|---|---|---|
| `discoverPeers`, `requestPeers`, `connect`, `createGroup`, `discoverServices`, `addLocalService`, `requestGroupInfo`, `requestDeviceInfo` | `NEARBY_WIFI_DEVICES` (33+, con `neverForLocation`); `ACCESS_FINE_LOCATION` (29-32) | `annotations.xml:78-125`, `:149-154`, `:161-172` |
| `registerWifiP2pListener` (API 35+) | `NEARBY_WIFI_DEVICES` + `ACCESS_WIFI_STATE` (anotación `allOf`, `conditional = true`) | `:138-143`; `api-versions.xml:44748` |
| `initialize`, `cancelConnect`, `removeGroup`, `stopPeerDiscovery`, `requestConnectionInfo`, `addServiceRequest`, `clearServiceRequests`, `requestP2pState`, `requestDiscoveryState`, `requestNetworkInfo` | ninguno en tiempo de ejecución (`initialize` usa `ACCESS_WIFI_STATE` y `CHANGE_WIFI_STATE`, que se conceden al instalar [PLATAFORMA]) | sin anotación |

**Recomendación**: añadir al manifiesto (de lo que necesita P2P, hoy solo está `ACCESS_WIFI_STATE`;
`CHANGE_WIFI_MULTICAST_STATE` y `CHANGE_NETWORK_STATE` no sustituyen a ninguno de estos):

```xml
<!-- Wi-Fi Direct: WifiP2pManager.initialize() exige ACCESS_WIFI_STATE + CHANGE_WIFI_STATE. -->
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<!-- Android 13+: búsqueda y conexión P2P. neverForLocation: sin permiso de ubicación y con la ubicación apagada. -->
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="33" />
<!-- Android 10-12: las mismas llamadas exigen ubicación precisa (y aproximada en la misma petición en 12). -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="32" />
<uses-feature android:name="android.hardware.wifi.direct" android:required="false" />
```

En tiempo de ejecución:
- **API 33+** (incluido el S25 con API 36): pedir `NEARBY_WIFI_DEVICES` (diálogo «Dispositivos cercanos»). Con
  `neverForLocation` no hace falta permiso de ubicación (`SDK:` javadoc) ni tener la ubicación encendida [PLATAFORMA].
- **API 29-32**: pedir `ACCESS_FINE_LOCATION` (en 31-32, junto con `ACCESS_COARSE_LOCATION`). Además, comprobar
  `LocationManager.isLocationEnabled()`: con la ubicación apagada, la búsqueda P2P falla [PLATAFORMA]. Si está apagada,
  ofrecer abrir `ACTION_LOCATION_SOURCE_SETTINGS`.
- Sin el permiso, la llamada termina en `onFailure`: `ERROR (0)` o, en API 36, `NO_PERMISSION (4)` [PLATAFORMA]
  (la constante es de API 36: `SDK: api-versions.xml:44800`). Registrar el código.
- Broadcasts:
  - `PEERS_CHANGED`, `CONNECTION_CHANGED` y `THIS_DEVICE_CHANGED` solo llegan a apps con `ACCESS_WIFI_STATE` y, además,
    `ACCESS_FINE_LOCATION` o `NEARBY_WIFI_DEVICES` (`SDK:` javadoc de las tres acciones);
  - `STATE_CHANGED` es *sticky* y lo recibe cualquiera [PLATAFORMA; su javadoc no pide permisos];
  - desde Android 10, `CONNECTION_CHANGED` y `THIS_DEVICE_CHANGED` ya no son *sticky*: al empezar hay que preguntar con
    `requestConnectionInfo`, `requestGroupInfo` y `requestDeviceInfo` [PLATAFORMA].

### 7.3 Particularidades de plataforma y de Samsung que afectan aquí

| # | Particularidad | Consecuencia | Fuente |
|---|---|---|---|
| 1 | Con la **zona Wi-Fi del móvil encendida**, Wi-Fi Direct no está disponible: Samsung lo dice en su pantalla de Wi-Fi Direct y QDLink vacía la lista en ese caso (§3.1) | Los dos modos son excluyentes: en modo Wi-Fi Direct, la zona Wi-Fi tiene que estar **apagada** | [PLATAFORMA/Samsung, comprobar en casa] |
| 2 | P2P necesita la **Wi-Fi activada**: sin ella llega `STATE_CHANGED`=1 | Comprobarlo antes de buscar; QDLink hace lo mismo (`LinkDialog.java:201-219`) | [PLATAFORMA] |
| 3 | La interfaz P2P se crea **bajo demanda** al abrir un canal: `STATE_CHANGED` puede llegar a 1 y, poco después, a 2 | No buscar hasta tener `ENABLED` (por broadcast o `requestP2pState`, API 29) | [PLATAFORMA] |
| 4 | La búsqueda para sola a los ~120 s, al empezar una conexión y al formarse el grupo; relanzarla durante la conexión «puede fallar» | Relanzarla como QDLink (§3.1), pero nunca durante la unión (si no, `BUSY (2)`) ni con el grupo formado | [PLATAFORMA] (120 s, `BUSY`); `SDK:` javadoc de `discoverPeers` y `WIFI_P2P_DISCOVERY_CHANGED_ACTION` |
| 5 | La MAC P2P propia puede ser aleatoria (Android 10+, si el fabricante activa la aleatorización P2P). `THIS_DEVICE_CHANGED` la da anonimizada y `requestDeviceInfo` devuelve `02:00:00:00:00:00` salvo con `LOCAL_MAC_ADDRESS`, que una app normal no tiene | No sirve como identificador; el coche puede ver una MAC distinta en cada sesión | [PLATAFORMA] (aleatorización); `SDK:` javadoc de `requestDeviceInfo` y `WIFI_P2P_THIS_DEVICE_CHANGED_ACTION` |
| 6 | El proceso vinculado a una red (nuestro ajuste `bind_wifi`, que usa `bindProcessToNetwork`) saca el tráfico por esa red y no llega a la subred P2P | En modo Wi-Fi Direct, **`bind_wifi` siempre desactivado** (su ayuda ya lo dice: `SettingsSchema.kt:114-118`) | [PLATAFORMA] |
| 7 | Wi-Fi cliente y P2P a la vez: si la Wi-Fi cliente está en otro canal, el chip reparte el tiempo entre canales (MCC) | En el coche, mejor que el móvil no esté conectado a otra Wi-Fi. Registrar la frecuencia del grupo y la de la Wi-Fi cliente | [PLATAFORMA] |
| 8 | En Android 15+ (API 35), `registerWifiP2pListener` da `onGroupCreationFailed(motivo)`. Motivos: `TIMED_OUT`, `USER_REJECTED`, `PROVISION_DISCOVERY_FAILED`, `INVITATION_FAILED`, `GROUP_REMOVED`, `CONNECTION_CANCELLED`. También `onGroupNegotiationRejectedByUser` | En el S25 (API 36) es la mejor pista de por qué falla la unión (por ejemplo, el coche pide aceptar y nadie acepta) | `SDK: api-versions.xml:44748` (`registerWifiP2pListener`, API 35), `:44794-44799` (los seis motivos, API 35), `:44890-44905` (`WifiP2pListener`, API 35); `javap WifiP2pManager$WifiP2pListener` |
| 9 | Grupos persistentes guardados en los dos extremos (§5.7) | Si el primer intento falla de forma rara, se pueden borrar los grupos recordados desde los ajustes de Wi-Fi Direct del sistema | [PLATAFORMA] |
| 10 | El firewall de Android limita la red a las apps en segundo plano sin servicio en primer plano | Nuestro `LinkService` (`connectedDevice`) ya evita el problema: con la pantalla apagada, el modo zona Wi-Fi ya se ha verificado | — |

---

## 8. Cómo debe hacerlo nuestra app

### 8.1 Principios

1. **La capa P2P solo forma la red.** `PhoneLink` (UDP 18463 → ACK → TCP → `PhoneSession`) **no cambia**: el broadcast
   llega al mismo socket `0.0.0.0:18463`, porque el UDP no está atado a ninguna interfaz (`DiscoveryConfig.bindAddress = null`).
2. **Ante el coche, lo mismo que QDLink:**
   - las mismas llamadas, con el mismo `WifiP2pConfig` (constructor simple, intent 0, sin tocar WPS ni `netId`);
   - nada de `createGroup`, `setDeviceName` ni anuncio de servicios.
3. **No tocar el modo actual.** Wi-Fi Direct es un modo nuevo y explícito: «Modo de conexión: Zona Wi-Fi del móvil
   (actual) / Wi-Fi Direct».
4. **Diagnóstico primero.** Solo hay un viaje y no hay portátil: todo lo que pase en P2P va al log de sesión y se puede
   exportar.

### 8.2 Secuencia exacta

**A. Preparación** (al arrancar el servicio en modo Wi-Fi Direct)

1. Comprobaciones previas. Si alguna falla, se muestra en la UI y no se sigue:
   - `PackageManager.FEATURE_WIFI_DIRECT`;
   - Wi-Fi activada (`WifiManager.isWifiEnabled`);
   - **zona Wi-Fi apagada**: ninguna interfaz `swlan*`, `ap*` o `softap*`, que `NetworkWatcher` clasifica como
     «zona Wi-Fi»;
   - permisos de §7.2 y, en 29-32, la ubicación activada;
   - `bind_wifi` desactivado (si está activado, se ignora y se avisa).
2. `initialize(appContext, looper, channelListener)`:
   - looper del hilo principal, como QDLink, o un `HandlerThread` propio;
   - `channelListener.onChannelDisconnected`: registrar y reinicializar **una vez**.
3. `registerReceiver` con: `STATE_CHANGED`, `DISCOVERY_CHANGED`, `PEERS_CHANGED`, `CONNECTION_CHANGED`,
   `THIS_DEVICE_CHANGED` y, solo para el log, `android.net.wifi.WIFI_AP_STATE_CHANGED`. En API 33+, con
   `RECEIVER_NOT_EXPORTED` (son broadcasts del sistema).
   - Usar `Context.registerReceiver(…, Context.RECEIVER_NOT_EXPORTED)` solo en 33+, y no
     `ContextCompat.registerReceiver`: por debajo de 33, `ContextCompat` exige el permiso
     `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, que nuestro manifiesto quita, y lanzaría una excepción [PLATAFORMA].
4. API 35+: `registerWifiP2pListener(executor, listener)`, solo para diagnóstico (§7.3 #8). Si lanza
   `SecurityException`, registrarlo y seguir.
5. Con P2P `ENABLED` (broadcast o `requestP2pState`), igual que QDLink:
   - **`cancelConnect`** y **`removeGroup`**, registrando el resultado de cada uno;
   - después, `requestDeviceInfo` → registrar el **nombre P2P del móvil**.

**B. Búsqueda**

6. `discoverPeers`. Cada 3 s, si `DISCOVERY_STOPPED`, no hay unión en curso ni grupo formado y ningún peer está
   `INVITED`: `requestPeers` y, si procede, `discoverPeers` otra vez (§3.1). QDLink tampoco busca con el grupo formado
   (`E==0`). Buscar en ese momento haría que el chip saliera del canal del grupo para escanear, y eso podría afectar al
   vídeo [PLATAFORMA].
7. Lista en la UI:
   - peers `CONNECTED`, `INVITED` y `AVAILABLE`, como QDLink; en el log van todos;
   - de cada uno, nombre y MAC, más el estado y la marca «GO» si `isGroupOwner()`;
   - el último coche usado va primero (se guarda su nombre y su MAC, como `LINK_HISTORY`).
8. **Diagnóstico de servicios** (ajuste, activado por defecto; QDLink no lo hace):
   - una sola vez por búsqueda, y nunca durante la unión:
     - `setDnsSdResponseListeners` + `addServiceRequest(WifiP2pDnsSdServiceRequest.newInstance())`;
     - `setUpnpServiceResponseListener` + `addServiceRequest(WifiP2pUpnpServiceRequest.newInstance())`;
     - `discoverServices`;
   - registrar cada respuesta;
   - marcar en la lista los peers con `QDLink_DnsSd_Instance` o `QDLink_UPnP_Device`;
   - nunca anunciar nada.
   - Motivo: identifica el coche sin depender del nombre, y las peticiones las contesta el `wpa_supplicant` del coche
     sin cambiar la formación del grupo.
   - Al conectar o al cerrar: `clearServiceRequests`.

**C. Conexión** (al pulsar un peer)

9. Si ya hay un grupo **con ese mismo peer**, se reutiliza y se pasa al punto 12 (es una mejora: QDLink quitaría el
   grupo). Si el grupo es con otro, `removeGroup` y se espera `groupFormed=false` antes de seguir.
10. `connect(WifiP2pConfig())` con `deviceAddress` = MAC del peer y `groupOwnerIntent` = **0**. No tocar `wps` ni
    `netId`, ni usar el `Builder`. Plazo de **60 s**: si vence, `cancelConnect`. Mientras tanto no se busca.
11. Fallo:
    - por `onFailure(motivo)`, `onGroupCreationFailed(motivo)` o plazo vencido;
    - se muestra el motivo en español y se vuelve a la búsqueda;
    - no hay reintento automático: QDLink no reintenta y el usuario puede volver a pulsar.

**D. Grupo formado** (`CONNECTION_CHANGED` con `groupFormed=true`)

12. Registrar:
    - `WifiP2pInfo` y `WifiP2pGroup` (interfaz, frecuencia, `networkName`, dueño, `isGroupOwner`, clientes, `netId`);
    - el resultado de `requestConnectionInfo` y `requestGroupInfo` (para cruzarlos con el broadcast);
    - la IPv4/prefijo de la interfaz P2P del móvil (`NetworkWatcher` ya clasifica `p2p*` como «Wi-Fi Direct»).

    Si `isGroupOwner=true`, avisar: «El móvil ha quedado como dueño del grupo (QDLink espera lo contrario)». No se
    corta.
13. Esperar el `Connect_Broadcast` **sin límite**, como QDLink, con avisos en la UI y en el log a los 10 s y a los 30 s:
    «No llega el anuncio del coche por Wi-Fi Direct».
    - Ojo: al aparecer la interfaz `p2p*`, `LinkEngine.onNetworkChanged` reabre los sockets si está en `SEARCHING`
      (`LinkEngine.kt:216-223`, `:204-214`).
    - Es inocuo, porque el siguiente broadcast llega en ≤2 s.
    - **Recomendación**: en modo Wi-Fi Direct, no reabrir por la aparición de `p2p*`; el UDP ya está en 0.0.0.0.

**E. Traspaso** (lo hace `PhoneLink`; en modo Wi-Fi Direct solo cambia el filtro `carFilter`/`autoGate`)

14. Se acepta el **primer** `Connect_Broadcast` cuya **IP origen esté en la subred de la interfaz P2P** o coincida con
    `groupOwnerAddress`. Con eso basta.
    - La regla de QDLink (`nombre P2P pulsado` contiene `DeviceName`) se evalúa y se **registra** («regla QDLink: sí/no»).
    - Solo se exige con el ajuste «Exigir nombre como QDLink» (desactivado por defecto).
    - El ACK es automático tras la pulsación, aunque la conexión automática esté desactivada (QDLink hace lo mismo).
15. Al aceptar ese broadcast, `stopPeerDiscovery` (QDLink lo hace). Después, `PhoneLink` sigue igual: `ServerSocket(P)`,
    el ACK desde :18463 a `IP_origen:18464` y `accept` con la espera configurada (20 s).
16. Si el TCP no llega (vencen los 20 s): **se conserva el grupo** y se responde al siguiente broadcast. Es una mejora
    sobre P4 de QDLink, que se queda colgado. El reintento ya lo hace `PhoneLink` (`reconnect`, `retryDelayMs`).

**F. Cierre**

17. Fin de sesión: `removeGroup` (como QDLink) y vuelta a B. Con el ajuste «Mantener el grupo tras la sesión»
    (desactivado), se espera otro broadcast sin `removeGroup`.
18. «Desconectar», o parar el servicio:
    - `stopPeerDiscovery`, `cancelConnect`, `removeGroup` y `clearServiceRequests`;
    - `unregisterReceiver`, `unregisterWifiP2pListener` y `channel.close()`.

### 8.3 Parámetros

| Parámetro | QDLink | Nuestra app (por defecto) | ¿Ajustable? |
|---|---|---|---|
| Rol del móvil | cliente (rol 0) | cliente | no |
| `groupOwnerIntent` | 0 | **0** | sí, solo para pruebas: 0 / 15 / automático (−1) |
| WPS | PBC (por defecto) | PBC (sin tocar `wps`) | no |
| Grupo persistente | sí (`netId` por defecto) | sí (sin tocar) | no |
| `createGroup`, `setDeviceName`, `setWifiP2pChannels`, anuncio de servicios | no | no | no |
| Búsqueda de servicios | no | sí, una vez por búsqueda, solo diagnóstico | sí |
| Relanzar `discoverPeers` | 3 s si paró, sin unión ni grupo formado | 3 s, con las mismas condiciones | no |
| Plazo de unión | 60 s → `cancelConnect` | 60 s | sí (30-120) |
| Reintentos de `connect` | 0 | 0 | no |
| Espera del broadcast con el grupo formado | sin límite | sin límite (avisos a 10 y 30 s) | no |
| Criterio del broadcast | `DeviceName` ⊂ nombre P2P | IP origen en la subred P2P o IP del GO; la regla QDLink solo se registra | sí («Exigir nombre como QDLink») |
| `stopPeerDiscovery` al aceptar el broadcast | sí | sí | no |
| Al entrar en el modo: `cancelConnect` + `removeGroup` | sí | sí | no |
| `removeGroup` al terminar la sesión | sí | sí | sí («Mantener el grupo») |
| Tras vencer el TCP | colgado | conservar el grupo y esperar el siguiente broadcast | no |
| ACK, TCP y sesión | 04 | igual (ajustes actuales) | sí, los de siempre |
| `bind_wifi` | n/a | forzado a desactivado | no |

### 8.4 Qué registrar (etiqueta `p2p`, con hora en ms)

1. **Capacidades** al arrancar el modo:
   - `SDK_INT`, fabricante y modelo;
   - `FEATURE_WIFI_DIRECT` e `isWiFiDirectR2Supported()` (API 36);
   - Wi-Fi activada, zona Wi-Fi e interfaces;
   - permisos concedidos, ubicación activada (29-32) y `bind_wifi`.
2. **Cada llamada P2P y su resultado**, con el código traducido: `0 ERROR`, `1 P2P_UNSUPPORTED`, `2 BUSY`,
   `3 NO_SERVICE_REQUESTS`, `4 NO_PERMISSION`. Por ejemplo: `discoverPeers → FALLO 2 (BUSY)`.
3. **Cada broadcast**:
   - `STATE` y `DISCOVERY` (valor numérico);
   - `PEERS`, solo si cambia: cada peer con `toString()` (nombre, MAC, tipos primario y secundario, métodos WPS,
     capacidades de grupo y dispositivo, estado, WFD) más `isGroupOwner()`;
   - `CONNECTION`: `WifiP2pInfo`, `WifiP2pGroup.toString()` y el `NetworkInfo` detallado;
   - `THIS_DEVICE`;
   - estado de la zona Wi-Fi.
4. **`WifiP2pListener`** (API 35+): todos sus callbacks, con el nombre del motivo.
5. **Servicios**: en DNS-SD, `instanceName`, `registrationType`, el dispositivo y el mapa TXT; en UPnP, la lista de
   USN y el dispositivo.
6. **Grupo**:
   - interfaz, IPv4/prefijo del móvil, IP del GO, SSID y frecuencia;
   - `netId`, `isGroupOwner`, dueño (nombre y MAC) y clientes.

   No hace falta registrar la passphrase (QDLink sí lo hace).
7. **Cada datagrama UDP** (además del volcado crudo que ya se hace):
   - IP:puerto de origen;
   - si viene de la subred P2P;
   - el resultado de la regla QDLink, con los dos nombres;
   - la decisión tomada.
8. **Tiempos** y deltas entre ellos: pulsación, `connect` OK, grupo formado, IP P2P disponible, primer broadcast por
   P2P, ACK enviado, `accept` y primer `CAR_INFO`.
9. **Resumen** de una línea al terminar, por ejemplo: «P2P: coche «X» (GO, 5180 MHz), unión 3,2 s, primer broadcast a
   1,1 s, TCP a 0,4 s».

### 8.5 Mensajes de la UI (sugeridos)

- «Wi-Fi Direct: buscando… Toca el nombre del coche (el mismo que aparece en QDLink).»
- «Apaga la zona Wi-Fi del móvil para usar Wi-Fi Direct.» · «Activa la Wi-Fi del móvil.» · «Falta el permiso
  «Dispositivos cercanos».»
- «Conectando con «X» (máx. 60 s)…» · «El coche ha rechazado la conexión.» · «Tiempo agotado al unirse al coche.»
- «Conectado por Wi-Fi Direct con «X»: coche 192.168.49.1, móvil 192.168.49.23 (5180 MHz). Esperando el anuncio del
  coche…»
- «No llega el anuncio del coche por Wi-Fi Direct (30 s).»

### 8.6 Qué se puede comprobar en casa antes del viaje

1. Permisos, avisos y comportamiento con la zona Wi-Fi encendida (§7.3 #1).
2. Búsqueda y unión con el **Pixel** como «coche». Con la pantalla de Wi-Fi Direct del Pixel abierta, el Pixel es
   visible. Al aceptar en el Pixel, el Pixel queda como GO, porque nuestro móvil usa intent 0. Se comprueban el rol, las
   IPs y los logs.
3. Flujo completo por P2P con el PC como GO: `tools/p2pcar` (anuncio y aceptación de Windows Wi-Fi Direct) más
   `carsim --target <IP P2P del móvil>`. Cubre el broadcast, el ACK, el TCP y la sesión.

Lo que no se puede probar en casa es lo de §9.

---

## 9. Incógnitas (solo se resuelven en el coche)

| # | Incógnita | Cómo verlo en el coche, sin portátil |
|---|---|---|
| 1 | ¿El C10 usa zona Wi-Fi, Wi-Fi Direct o los dos? (Los dos textos de QDLink dicen «solo algunos modelos».) | Probar primero con QDLink cada modo |
| 2 | Nombre P2P del coche y si aparece siempre o solo con una pantalla concreta abierta | Lista de QDLink y Ajustes ▸ Conexiones ▸ Wi-Fi ▸ Wi-Fi Direct de Samsung; nuestro log de peers |
| 3 | ¿El coche es GO autónomo (`isGroupOwner` del peer) o negocia? ¿Qué intent usa? ¿El móvil queda como cliente? | Log de peers y `WifiP2pInfo.isGroupOwner` |
| 4 | ¿Hay que aceptar algo en la pantalla del coche? ¿Admite PBC? | Mirar la pantalla; `onGroupCreationFailed(USER_REJECTED)` / `onGroupNegotiationRejectedByUser` |
| 5 | Subred e IPs (¿`192.168.49.0/24`?), banda y frecuencia del grupo | Log del grupo y de la interfaz |
| 6 | ¿Emite `Connect_Broadcast` por la interfaz P2P? ¿A qué dirección (limitado, dirigido o unicast) y con qué periodo? | Log UDP: IP origen, cadencia y si viene de la subred P2P (la dirección destino no se ve desde el socket) |
| 7 | ¿El `DeviceName` del broadcast coincide con su nombre P2P (regla de QDLink)? | Log «regla QDLink: sí/no» con los dos nombres |
| 8 | ¿Anuncia servicios `QDLink_DnsSd_Instance` (`_ssplink._tcp`) o `QDLink_UPnP_Device`? ¿Con qué TXT? | Diagnóstico de servicios (§8.2 #8) |
| 9 | ¿Recuerda el grupo (persistente) y reconecta sin pedir nada la segunda vez? ¿Cambia su MAC P2P entre arranques? | Segunda conexión en el mismo viaje; comparar la MAC en los logs |
| 10 | ¿Corta el grupo si no hay TCP en N s o cuando se cierra el TCP? | Tiempos del log (`groupFormed=false` tras el fin de sesión) |
| 11 | ¿Le afecta que el móvil esté conectado a otra Wi-Fi (MCC)? ¿Y la zona Wi-Fi del propio coche? | Frecuencias de la Wi-Fi cliente y del grupo en el log |
| 12 | El resto del protocolo (JSON completo del broadcast, `CAR_INFO`, etc.) | 04 §11 y 01 §11 |

**Orden recomendado en el coche:**
1. QDLink en modo Wi-Fi Direct: anotar el nombre que sale y si proyecta.
2. QDLink en modo zona Wi-Fi.
3. Nuestra app en el modo que haya funcionado: exportar el log.
4. Nuestra app en el otro modo: exportar el log.

---

## Anexo A · Constantes y cadenas exactas

| Concepto | Valor | Ref. |
|---|---|---|
| Acciones registradas | `android.net.wifi.p2p.STATE_CHANGED`, `…DISCOVERY_STATE_CHANGE`, `…PEERS_CHANGED`, `…CONNECTION_STATE_CHANGE`, `android.net.wifi.WIFI_AP_STATE_CHANGED` | `WD/c.java:658-662` |
| Extras leídos | `wifi_p2p_state`, `discoveryState`, `wifiP2pDeviceList`, `wifiP2pInfo`, `p2pGroupInfo`, `wifi_state` | `WD/c.java:838`, `:808`, `:832`, `:760`, `:789`, `:606` |
| Rol / modo de QDLink | 0 / 0 | `WD/c.java:1106-1108` |
| `groupOwnerIntent` | 0 (15 solo con rol 1 y modo 0; con modo ≠0 queda `GROUP_OWNER_INTENT_AUTO` = -1) | `WD/c.java:990-997` |
| Instancia y tipo DNS-SD | `QDLink_DnsSd_Instance`, `_ssplink._tcp`, TXT `null` | `WD/c.java:98`, `:937` |
| Dispositivo UPnP | `QDLink_UPnP_Device` (uuid aleatorio, sin servicios) | `WD/c.java:101`, `:952` |
| Periodo del bucle de búsqueda | 3000 ms | `WD/c.java:571` |
| Plazo de unión | 60000 ms | `WD/c.java:916` |
| Caducidad de los servicios | 30000 ms | `WD/c.java:1030` |
| Canal operativo (código muerto) | 48 (`setWifiP2pChannels(lc=0, oc=48)`) | `WF/a.java:602`; `WD/c.java:896` |
| Regla de emparejado UDP | `LinkConfig.uuidName.contains(DeviceName)` | `WF/a.java:505` |
| Preferencias | `WUGAN_LINK`, `LINK_HISTORY`, `LINK_HISTORY_CURRENT`, `AUTO_LINK` (archivo `Q_DRIVE_SHARE_DATE`) | `QL/c.java:169-196`; `SharedPreferencesUtils.java:8` |
| Textos (ES) | «Wi-Fi Direct», «Conexión de punto de acceso», «Conexión automática» (sin uso), «Lista de dispositivos», «Buscando...», «Emparejado» | `ES:` 0x7f0f0043, 0x7f0f0042, 0x7f0f0046, 0x7f0f003b, 0x7f0f003d, 0x7f0f0027 |
| Logs de QDLink útiles para comparar | `connectDevice: called! macAddressToConnect: …`, `processConnectionBroadcast: in the Group! …`, `contains:name 包含:`, `directThread onJoinGroupSucceed() …` | `WD/c.java:968`, `:785`; `WF/a.java:506`, `:425` |

## Anexo B · Verificación con dexdump

| Método (dex) | Offsets | Qué confirma |
|---|---|---|
| `WD/c.E(String)` | `@004f-0071` | `new WifiP2pConfig()`, `deviceAddress`, intent 15 si `y==1` y 0 si `y==0` (solo con `z==0`), `connect` y plazo de 60 s |
| `WD/c.b0(Intent)` | `@0015-0031` | Al habilitarse P2P: `cancelConnect`, `removeGroup`, `c0`, `d0`; `createGroup` solo con `(z==0&&y==2)\|\|(z!=0&&y!=0)` |
| `WD/c.b0(Intent)` | `@0045-004c` | Al deshabilitarse: `g0()` + `h0()` con `C` ya a false, así que `h0` solo pone `A=false` |
| `WD/c.f0()` | `@001d-0036`, `@0057-0063` | `discoverPeers` si `z==0 && y!=2`; `A=true` si `(z==0&&y!=2)\|\|y==0` |
| `WD/c.h0()` | `@0000-002e` | `A=false`; `stopPeerDiscovery` solo con `D≠0` |
| `WD/c$x.run()` y `c$x$a.onPeersAvailable` | `@000b-0073`; `@0004-002c` | Bucle de 3 s: `requestPeers` si `C&&A&&D==0&&E==0` → `discoverPeers` si ningún peer está `INVITED` |
| `WD/c.X(Intent)` | `@0010-007e`, `@007f-00ad` | Grupo formado: datos, `E=2`, callback; salida: `E` 1→0 (fallo) o 2→0 |
| `WD/c$y.onReceive` | `@007a-00a2`, `@00c6-00ed` | Zona Wi-Fi 13 o 12: `peers=null` y callback de lista (jadx muestra un `break` falso) |
| `WF/a.run()` | `@00f4-0136` | `uuidName.contains(DeviceName)`; estado≠1 → `h0`, puerto, `x(P)`, `q(P, carIp)`, estado=1 |
| `WF/a$b.run()` | `@0105-0115`, `@0129-012d` | ACK a `carIp:18464` guardado en `LinkConfig`; `uuidName=""` en el `finally` |
| `classes.dex` + `classes2.dex` (búsqueda de `invoke-*`) | — | Los únicos métodos de `WifiP2pManager` que llama QDLink, todos desde `WD/c`: `initialize`, `discoverPeers`, `requestPeers`, `stopPeerDiscovery`, `connect`, `cancelConnect`, `createGroup`, `removeGroup`, `addLocalService`, `clearLocalServices`, `addServiceRequest`, `clearServiceRequests`, `discoverServices`, `setDnsSdResponseListeners` y `setUpnpServiceResponseListener`. Ninguna llamada a `WD/c.S()`, `WD/c.Q()` ni `IC/d.f()`. `isLocationEnabled`/`isProviderEnabled` solo en AndroidX |

---

## Registro de verificación

Verificación adversarial del 2026-10-04. Fuentes:
- `decomp/qdlink/sources` (jadx);
- `dexdump -d` de `classes.dex` y `classes2.dex`: búsquedas de llamadas y los offsets del Anexo B, todos reproducidos;
- `aapt` de `split_config.es.apk` y el manifiesto de QDLink;
- el SDK de android-36: `annotations.xml`, `api-versions.xml`, `broadcast_actions.txt` y la javadoc de
  `android-stubs-src.jar`;
- el código de `qdauto` citado en §7 y §8.

**Comprobado sin errores:**
- Nombres y tipos de servicio: `QDLink_DnsSd_Instance`, `_ssplink._tcp`, TXT `null` sin claves; `QDLink_UPnP_Device`
  con uuid aleatorio y sin servicios.
- `WifiP2pConfig`: constructor simple, que solo fija `deviceAddress` y `groupOwnerIntent`.
- Condición del intent: 0 con rol 0 y modo 0; 15 con rol 1 y modo 0.
- Condición de `createGroup`: el teléfono nunca crea el grupo.
- Reparto entre anuncio y petición de servicios según el rol.
- Regla `uuidName.contains(DeviceName)`, ACK de 174 B y paso a `WF/d`.
- Permisos y listas por API de QDLink.
- Niveles de API: `registerWifiP2pListener` 35, `NO_PERMISSION` 36, `requestP2pState` 29, `isWiFiDirectR2Supported` 36.
- Códigos de error 0-4, `GROUP_OWNER_INTENT_AUTO` = -1 y `NETWORK_ID_PERSISTENT` = -2.
- Ausencia de `setDeviceName`, `setWifiP2pChannels` y `WIFI_AP_STATE_CHANGED` en el SDK público.
- Cadenas `ES:`.
- Todas las citas `archivo:línea`, incluidas las de `qdauto`.

| Sección | Cambios |
|---|---|
| Cabecera | Sin cambios. |
| §0 | `SDK:` incluye ahora `android-stubs-src.jar` (javadoc) y `data/broadcast_actions.txt`, que el texto cita. |
| §1 | #1: la lista descarta los peers `FAILED` y `UNAVAILABLE`; antes decía «todos, sin ningún filtro». #3: otra excepción a «el GO es el coche»: un grupo persistente previo en el que el móvil quedó como GO. #4: la regla no comprueba el grupo ni la red de llegada. #5: la búsqueda solo se relanza con `E==0` (sin unión ni grupo) y sin peers `INVITED`. |
| §2.1 | Fila 7: faltaba el salto `WF/c.m(1)`. Además, a los 3 s, con la lista vacía, sale «Asegúrese…» aunque la Wi-Fi esté activada (`LinkDialog.java:247-249`, `:76-85`). |
| §2.2 | Sin cambios. |
| §2.3 | Faltaban escritores: de `C`, `i0()`; de `D`, la desactivación de P2P e `i0()`; de `E`, `B()` («Cancelar», 1→0), la desactivación de P2P e `i0()`. |
| §2.4 | Añadido: en los modos 1 y 2, los roles 1 y 2 son idénticos (crean el grupo y anuncian; nunca buscan, porque `A` no se activa). El rol 0 pide el servicio y se une con el intent automático. |
| §2.5 | Sin cambios. |
| §2.6 | «Cancelar» también pone `E` 1→0. Fila nueva en P2: un broadcast que cumple la regla dispara el ACK aunque el grupo no esté formado. La fila «Desconectar» incluye ahora el rechazo de la captura de pantalla (04 §7.5); `IC/d.A()` quita el grupo sin mirar `wifiModeType`. |
| §2.7 | Punto 2: la regla no mira `E` ni la IP o la interfaz de llegada; con `DeviceName` vacío se cumple siempre. Tabla de preguntas: añadida esa salvedad. Tabla de GO: fila nueva para el grupo persistente reinvocado [PLATAFORMA]. |
| §3.1 | `E≠0` incluye «en grupo»; antes solo decía «uniéndose». La parada de la búsqueda al conectar o al formarse el grupo está en la javadoc del SDK; antes solo llevaba [PLATAFORMA]. |
| §3.2 | Sin cambios. |
| §3.3 | Fila «Repetición»: en la práctica solo la hace el rol 0, porque con rol≠0 `A` nunca es true. Cadenas, tipos y TXT sin cambios. |
| §3.4 | Sin cambios. |
| §4 | Añadida la marca [PLATAFORMA] al nombre P2P por defecto del móvil. |
| §5.1 | El intent es 15 solo con rol 1 **y modo 0**. El valor por defecto se cita de la javadoc, con el offset `dex: c.E @0056-0066`. |
| §5.2 | Sin cambios. |
| §5.3 | `removeGroup`: añadido el rechazo de la captura de pantalla, que pasa por `IC/d.A()`. |
| §5.4 | Relanzar la búsqueda: añadida la condición «sin grupo formado (`E==0`)». |
| §5.5 | Sin cambios. |
| §5.6 | Comprobado en los dos `.dex` que no hay ninguna llamada. La javadoc dice que el extra del grupo puede ser null. Un `groupOwnerAddress` nulo también daría un NPE (`:788`). |
| §5.7 | La reinvocación persistente conserva los papeles de la primera vez. Añadido cuándo llega `groupFormed=true`: como cliente, tras el DHCP; como GO, al unirse el primer cliente. |
| §6 | Sin cambios. |
| §7.1 | Corregido: `isLocationEnabled` sí aparece en el APK, pero solo dentro de AndroidX y sin llamadas desde el código de QDLink. |
| §7.2 | Corregido: el texto condicional sale de la javadoc, no de la anotación (que solo dice `allOf` + `conditional`), y depende del targetSdk de la app. Rangos de `annotations.xml` corregidos: `:78-125`, `:149-154`, `:161-172` y `:138-143`. Broadcasts: hace falta además `ACCESS_WIFI_STATE` (javadoc). Marcas [PLATAFORMA] en lo que no tiene fuente en el SDK. Aclarado qué permisos faltan en el manifiesto. |
| §7.3 | #4: citada la javadoc y añadido «ni con el grupo formado». #5: la MAC aleatoria depende del fabricante; el `02:00:00:00:00:00` se cita de la javadoc. #8: cita corregida; `:44799` era solo `USER_REJECTED`, y ahora se citan `:44748`, `:44794-44799` y `:44890-44905`. |
| §8.1 | Sin cambios. |
| §8.2 | A.3: no usar `ContextCompat.registerReceiver` con `RECEIVER_NOT_EXPORTED`, porque por debajo de 33 exige un permiso que nuestro manifiesto quita. B.6: no relanzar la búsqueda con el grupo formado. |
| §8.3 | Fila «Relanzar `discoverPeers`»: las mismas condiciones que QDLink (sin unión ni grupo). |
| §8.4-§8.6 | Sin cambios. |
| §9 | Sin cambios. |
| Anexo A | `groupOwnerIntent`: 15 solo con rol 1 y modo 0; −1 con modo ≠0. |
| Anexo B | Todas las filas reproducidas sin cambios. Añadida una fila con la búsqueda de llamadas en los dos `.dex`. |
