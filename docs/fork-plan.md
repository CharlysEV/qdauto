# Plan del fork: de headqlink a nuestra app (`qd/hql`, rama `qdauto`)

4 de octubre de 2026. Propuesta técnica: **no se ha cambiado código**. Este documento complementa a
`docs/comparativa-headqlink.md`, que explica qué tiene cada proyecto y qué sabemos del C10. Aquí se explica **cómo**
convertir el fork `qd/hql` (copia de headqlink v0.1-beta, commit `fe095fff`) en una app mejor, metiendo lo nuestro.

**Rutas abreviadas** (las mismas que en la comparativa, todas desde ``):

| Prefijo | Qué es |
|---|---|
| `HQ/` | `ref/headqlink/app/src/main/java/com/headqlink/link/`: código propio de headqlink |
| `OH/` | `ref/headqlink/app/src/main/java/com/andrerinas/openheadunit/`: Open Headunit, que heredan |
| `HQR/` | `ref/headqlink/` (manifiesto, recursos, `cpp/`) |
| `C10L` | commit `520e15be` de headqlink: sus comentarios llevan la fecha de las pruebas en el coche |
| `CORE/` | `qdauto/core/src/main/kotlin/dev/qdauto/core/` |
| `APP/` | `qdauto/app/src/main/java/dev/qdauto/app/` |
| `DOC/` | `docs/protocol/` |
| `CMP` | `docs/comparativa-headqlink.md` |

**Etiquetas**:

- **[en uso]**: lo hace la versión de headqlink que funciona en el coche.
- **[medido]**: hay una medida, suya, de Open Headunit o nuestra.
- **[plataforma]**: comportamiento de Android.
- **[hipótesis]**: sin comprobar.

Términos:

- **reenvío directo**: mandar al coche, tal cual, el H.264 de Android Auto (AA).
- **recodificar**: decodificar AA en el móvil, componer la imagen con GL y volver a codificarla con nuestro encoder.
  En headqlink se llama «último frame».

---

## 0. Resumen

1. **Partimos de headqlink porque ya tiene AA funcionando en un C10** (CMP §1). Se quedan:
   - su pila de AA (Open Headunit más sus ganchos);
   - su vídeo Android: encoder, compositor GL y recorte del SPS.

   Se sustituye el **enlace con el coche**: su `SspSession`, `Proto`, `UdpDiscovery` y `P2pLink` dejan paso a nuestro
   `:core` (`PhoneLink`, `PhoneSession`, `DiscoveryListener`) y a nuestro controlador de Wi-Fi Direct.
2. Una **capa adaptadora pequeña**, nueva y dentro del fork, une las dos mitades: `CarVideoSink`, `SessionBridge`,
   `TouchToAa`, `KeyToAa` y `AndroidLinkProbe` (§2.3).
3. **`:core` se sigue desarrollando en `qdauto/`** y entra en el fork como **dependencia versionada**. Así sigue siendo
   nuestro, con la licencia que elijamos, aunque la app combinada sea AGPL (§2.5, §2.6).
4. **Vídeo**:
   - el camino principal es **recodificar**; el reenvío directo queda como modo de bajo consumo;
   - el control de flujo junta su **puerta** (dejar de codificar con más de 64 KB pendientes en el kernel y más de un
     frame en cola) con **nuestra cola** (un solo hilo que escribe y el control siempre primero). Se implementa como
     políticas de `:core` (§3.4).
5. **Ruido Wi-Fi**: primero **medir y atribuir**, con una sonda ICMP, TCP_INFO y los escaneos registrados. Después,
   pruebas A/B en el coche:
   - pantalla apagada;
   - **zona Wi-Fi a 5 GHz**, que headqlink no tiene;
   - STA asociada a una red;
   - bitrate adaptable (§4).
6. **AA ≥ 17.4**: que sepamos, sin root **no se puede quitar** la accesibilidad. Sí se puede **reducir** su papel (§5.3):
   - buscar el menú por identificador, no por texto;
   - activarla solo unos segundos;
   - parar el servidor desde su notificación;
   - avisar en la pantalla del coche.
7. **Nueve hitos pequeños (M1-M9)**. Cada uno se prueba en casa (carsim, p2pcar o AA real) y después en el coche (§7).

---

## 1. Punto de partida

### 1.1 Qué hay en el fork (headqlink v0.1-beta)

```
LinkService (servicio en primer plano: wakelock parcial + WifiLock baja latencia + MulticastLock)
 ├─ P2pLink ........... se une al grupo Wi-Fi Direct del coche (prefijo «LeapMotor» o servicio UPnP)
 ├─ UdpDiscovery ...... 0.0.0.0:18463; el ACK sale al 18464 del coche en cada broadcast
 ├─ SspSession ........ ServerSocket, !BIN, handshake, heartbeat 3 s / watchdog 10 s, táctil,
 │                      cola de vídeo propia (puerta, vaciado > 150 ms, freno a AA, bitrate adaptable)
 └─ VideoSource
     ├─ AaPassthroughSource ── Open Headunit (AA por 127.0.0.1:5277, sin vista en el móvil)
     │     ├─ reenvío directo: VideoTap → H264SpsCrop → SspSession
     │     └─ recodificar: decodificador de OH → GlFrameRelay (+ panel CarUi) → VideoEncoder → SspSession
     ├─ AppSource (VirtualDisplay + gestos por accesibilidad)
     └─ PatternSource (diagnóstico, con reloj en ms)
AA ≥ 17.4: TouchService (accesibilidad) + AaServerStarter / AaGuardService / AaRecovery
Trazas: PerfTrace (CSV por sesión), CarTrace (diario del coche), SystemMonitor, NetStat (JNI: SIOCOUTQ + TCP_INFO)
```

Sus **ganchos en Open Headunit** ocupan unas 700 líneas repartidas en 16 ficheros (`git diff 7e9d813d fe095fff`):

- `VideoTap` y `HeadlessDriver`;
- el lienzo externo de 1920×882 (`OH/utils/HeadUnitScreenConfig.kt`);
- la ventana `max_unacked` de vídeo (`OH/aap/AapControl.kt:136-160`);
- el freno por MediaAck y el foco de vídeo sin vista (`OH/aap/AapTransport.kt:836-841,1162-1167`);
- la marca de tiempo de AA (`OH/aap/AapVideo.kt`);
- `NavTap`;
- `AaRecovery` (`OH/connection/CommManager.kt:595-598`);
- el servidor 5277 con accesibilidad (`OH/connection/self/launchers/SelfLauncherV17_4.kt:91-133`).

### 1.2 Qué aportamos nosotros

- **`:core`**: Kotlin puro, sin dependencias, con 67 tests.
  - Protocolo de QDLink idéntico byte a byte y un lector que recupera la sincronía.
  - `PhoneSession`: un solo hilo escribe en el socket y da prioridad al control; tiene watchdog y detector de `write()`
    bloqueado; las respuestas son configurables; da trazas y estadísticas.
  - `DiscoveryListener`, que valida y filtra el UDP.
  - `PhoneLink`, que reconecta solo.
  - `CarSim`, el coche simulado.
- **`:carsim`**: el coche en el PC, con 20 comprobaciones, lectura del SPS, guion táctil e informe JSON.
- **`:app`** (fase 0):
  - Wi-Fi Direct completo: `P2pMachine`, `PeerClassifier`, `P2pHandover`, `P2pCarStore`, y la escucha de la API 35
    con la frecuencia del grupo;
  - modo zona Wi-Fi verificado con la pantalla apagada (`NetworkWatcher`);
  - encoder con tres configuraciones de reserva y la tabla completa de niveles H.264;
  - log en un hilo propio, con rotación y exportación;
  - reconexión con espera exponencial.
- **Herramientas**:
  - `tools/p2pcar`: el PC con Windows hace de dueño del grupo Wi-Fi Direct;
  - `tools/ffmpeg`;
  - `tools/trip`: analizador de viajes, en preparación.
- **Especificación**: `DOC/04` (byte a byte) y `DOC/05` (Wi-Fi Direct).

### 1.3 Datos del coche que usa este plan

Están verificados en el código de headqlink; su fiabilidad está en CMP §3:

| Dato | CMP §3 | Qué implica para el plan |
|---|---|---|
| CAR_INFO 1920×882 (14,6" ≈ 145 ppi: 2113 px de diagonal) | n.º 9 | Todo el vídeo se dimensiona a 1920×882 o a 1280×588 |
| VIDEO_ARGS.FrameRate 30 (`C10L AaPassthroughSource.java:236`) | n.º 10 | Se empieza a 30 fps |
| Baseline 1920×882 hasta 60 fps, de 5 a 16 Mbit/s, con intra-refresh e IDR solo bajo demanda | n.º 17 | Así es el perfil de recodificación |
| Al empezar descarta el primer IDR y pide otro; sin él, pantalla negra | n.º 14 | Contestar siempre a KEY_FRAME_REQ con un IDR real |
| No habla hasta recibir el `!BIN` de 512 B; por Wi-Fi no se rellena a 512 | n.º 8, 13 | Ya lo hacemos; añadir los tests |
| No estira la imagen: pone bandas; respeta el recorte inferior del SPS | n.º 16 | Proporción exacta y recorte anclado arriba |
| Táctil: hasta 3 dedos, en px de la pantalla del coche, 1:1 desde la esquina superior izquierda | n.º 20-21 | `TouchToAa` |
| Wi-Fi Direct: el coche es el dueño del grupo y su nombre empieza por «LeapMotor»; Bluetooth «Leapmotor_BT» | n.º 1-3 | Pista para `PeerClassifier` y para arrancar al conectarse el Bluetooth |
| Si el móvil sigue contestando broadcasts, el coche reconecta en ~5 s | n.º 4-5 | Política de ACK y grupo conservado |
| Cortes de radio: la búsqueda P2P provoca picos de 100-350 ms; con la pantalla encendida y sin red, el móvil escanea cada ~10 s; con la puerta a 24 KB se perdía un tercio de los frames en cada corte y a 64 KB, no | n.º 6-7 | §3.4 y §4 |
| `Global/DarkModeOn`: el valor 1 = noche **no está confirmado** | n.º 19 | Registrar el valor crudo |

**Datos que no están en la comparativa** y que también usa el plan:

- **[medido, Open Headunit en otras unidades]** Los escaneos del móvil son el sospechoso principal de los cortes
  periódicos:
  - con la STA sin asociar, la imagen se perdía de 4 a 8 s cada 10 s; asociada a una red, no hubo ni un corte de más
    de 0,65 s en 138 s (`OH/connection/wifi/direct/StationCoexistencePolicy.kt:14-18`);
  - otras capturas: silencios de 1,59 s cada 11,57 s y de 5-6 s cada 10-11 s (`OH/aap/LinkGapMonitor.kt:7-10`).
- **[medido, Open Headunit]** Un punto de acceso a 2,4 GHz no aguanta 1080p60: 32 sesiones seguidas sin un solo frame
  en menos de 5 min. En el mismo punto de acceso, 800×480 a 30 fps sí aguanta, y a 5 GHz también 1080p60
  (`OH/connection/VideoStarvationPolicy.kt:5-12`).
- **[plataforma]** Una app normal **no puede** elegir la banda ni el canal de la zona Wi-Fi: `setSoftApConfiguration`
  exige `NETWORK_SETTINGS` (`OH/connection/wifi/modes/nativeaa/SoftApBandPolicy.kt:96-99`). Tiene que hacerlo el usuario
  en los ajustes.
- **[medido, Open Headunit]** AA solo manda un IDR cada **~69 s** (`OH/connection/CommManager.kt:893-897`). Para
  forzarlo hay que soltar y recuperar el foco de vídeo, y la imagen se congela unos 0,7-0,9 s
  (`HQ/AaPassthroughSource.java:38-42`).
- **[medido, Open Headunit]** AA no respeta del todo la ventana `max_unacked`: con un límite de 12 llegó a acumular 120
  (`OH/aap/AapControl.kt:153-156`). Por eso el «freno» de headqlink puede fallar.
- **[medido, nuestro]** El kernel duplica el `SO_SNDBUF` que se pide: pedimos 256 KiB y quedan 512 KiB, que a 8 Mbit/s
  son unos 0,5 s de vídeo escondidos (`trips/sample/app_logs/logs/qdauto-20261004-155126.log:79`).

---

## 2. Arquitectura

### 2.1 Mapa de piezas: lo suyo, lo nuestro y qué hacer

| Pieza de headqlink | Qué hace | Equivalente nuestro | Decisión |
|---|---|---|---|
| `HQ/Proto.java` | Códec 5A5A, `!BIN`, UDP y táctil | `CORE/wire/` (`Header`, `Frames`, `VideoMessage`, `BinBlock`, `UdpCodec`, `TouchCodec`, `PhoneMessages`) | **Sustituir** |
| `HQ/UdpDiscovery.java` | Escucha en 18463 y manda el ACK desde el mismo socket | `CORE/discovery/DiscoveryListener.kt` | **Sustituir**, con una política de ACK nueva: uno por broadcast (C1) |
| `HQ/SspSession.java`, parte de protocolo | `accept`, handshake, heartbeat, watchdog, despacho y táctil | `MirrorServer` + `PhoneSession` + `InboundHandler` + `PhoneLink` | **Sustituir** |
| `HQ/SspSession.java`, parte de vídeo | Cola, vaciado por antigüedad, freno, puerta, bitrate adaptable y estadísticas | `SendQueue` + `SocketWriter` (solo descarte por atasco) | **Fusionar**: sus ideas pasan a `:core` como políticas (§3.4) |
| `HQ/LinkService.java` | Servicio en primer plano, bloqueos, temporizadores del coche y de AA, arranque por Bluetooth | `APP/service/LinkService.kt`, `APP/link/LinkEngine.kt` | **Mantener** el suyo, que lleva el ciclo de vida de AA, y cambiar lo de dentro |
| `HQ/P2pLink.java` | Unión P2P mínima (UPnP + prefijo, reintento cada 15 s) | `APP/p2p/` (`WifiDirectController`, `P2pMachine`, `PeerClassifier`, `P2pHandover`, `P2pCarStore`) | **Sustituir** por el nuestro, aprovechando sus dos pistas: el prefijo y el UPnP |
| — | Zona Wi-Fi (el coche se une a la del móvil) | `APP/link/NetworkWatcher.kt` | **Añadir** (headqlink no lo tiene) |
| `HQ/NetStat.java` + `HQR/app/src/main/cpp/hql_netstat.c` | Cola del kernel (SIOCOUTQ) y TCP_INFO | — | **Mantener** y ofrecerlo a `:core` como `LinkProbe` (C2) |
| `PerfTrace`, `CarTrace`, `SystemMonitor`, `Jitter`, `L`, `LogcatCapture` | Trazas | `TraceEvent`, `QdLog`, `APP/log/` | **Mantener** sus ficheros y volcar en ellos nuestros eventos. Un solo formato de CSV para `tools/trip` |
| `VideoEncoder`, `VideoProfile`, `EncBench` | Encoder y perfiles | `APP/video/EncoderSetup.kt`, `H264Levels.kt` | **Mantener** el suyo y traer nuestras configuraciones de reserva y nuestra tabla de niveles (CMP §4 n.º 9) |
| `GlFrameRelay`, `AaPassthroughSource`, `H264SpsCrop`, `VideoSource` | Vídeo de AA | — | **Mantener** (es Android) y conectarlo a `CarVideoSink` |
| `TouchService`, `AaServerStarter`, `AaGuardService`, `AaRecovery`, `CarBtReceiver`, `PowerHelper` | AA ≥ 17.4, arranque y batería | — | **Mantener y mejorar** (§5) |
| `CarUi` y sus pantallas (rutas, radio, juegos, TV…) | Ocio en el panel lateral | — | Mantener tras un ajuste, **apagado por defecto** (seguridad y batería: CMP §4 n.º 1 y 12). Queda fuera de este plan |
| `OH/aap`, `OH/connection`, `OH/decoder` | Android Auto | — | **No tocar**, salvo los ganchos que ya existen |

### 2.2 Arquitectura objetivo

```
                    ┌──────────────────────────── fork qd/hql (AGPL) ────────────────────────────┐
  C10 (QDLink)      │ LinkService (servicio en primer plano)                                     │
  UDP 18463/18464 ◄─┼─► Transporte: Wi-Fi Direct (nuestro P2P) | Zona Wi-Fi                       │
  TCP MirrorPort ◄──┼─► QdCarLink ──► :core PhoneLink / PhoneSession ◄───────────────────────────┼── dependencia
                    │      │            (protocolo, colas, FlowController, trazas)                │   dev.qdauto:core
                    │      │                    ▲ LinkProbe ◄── AndroidLinkProbe (NetStat)        │
                    │      ▼                    │                                                 │
                    │  SessionBridge ──► TouchToAa / KeyToAa ──► CommManager (Open Headunit) ──► AA
                    │      │                                                                      │
                    │      └──► CarVideoSink ◄── VideoEncoder ◄── GlFrameRelay ◄── decodificador de OH ◄── AA
                    │                       ◄── AaVideoAdapter ◄── VideoTap (reenvío directo)    │
                    └────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Capa adaptadora (código nuevo en el fork, paquete `dev.qdauto.hql`)

Toda esta capa es AGPL, porque toca código de headqlink y de Open Headunit. Lo que no dependa de Android
(`TouchToAa`, `KeyToAa`) se escribe en Kotlin puro y se prueba con JUnit.

**`QdCarLink`** sustituye a `SspSession`, `UdpDiscovery` y la parte de UDP de `LinkService`:

- monta `PhoneLinkConfig` con la política de ACK «uno por broadcast» y un `SessionConfig` para el C10 (C6);
- arranca y para con `LinkService` y da `currentSession`;
- implementa `PhoneLinkListener`, que alimenta `LinkState` (la UI) y los temporizadores de «coche fuera» y «sin coche».

**`CarVideoSink`**: es lo que usan las fuentes de vídeo; antes llamaban a `SspSession.enqueueFrame`.

```kotlin
interface CarVideoSink {
    fun codecConfig(spsPps: ByteArray)
    /** false = descartado. onWritten se llama en el hilo que escribe, en cuanto el frame entra en el socket. */
    fun accessUnit(au: ByteArray, off: Int, len: Int, key: Boolean, ptsUs: Long, onWritten: Runnable? = null): Boolean
    /** Puerta de recodificar: ¿se puede dibujar y codificar un frame nuevo ahora? */
    fun ready(): Boolean
    /** Un frame dibujado ha entrado en el encoder. */
    fun submitted()
    /** Tamaño que va en la cabecera de 32 B: el del vídeo real (en 720p, 1280×588). */
    fun setVideoSize(w: Int, h: Int)
}
```

Implementación sobre `PhoneSession`:

| Método | Qué hace |
|---|---|
| `codecConfig` | `sendCodecConfig` |
| `accessUnit` | `sendFrame` |
| `ready()` | Pregunta al `FlowController` de `:core` (C3) |
| `setVideoSize` | `setVideoOverrides(VideoOverrides(width, height))` |

**`SessionBridge`**: implementa `SessionListener` y traduce los eventos de `:core` en acciones del fork.

| Evento de `:core` | Acción en el fork |
|---|---|
| `onCarInfo` | `source.setCarSize(w, h)` y `LinkState` |
| `onVideoArgs` | Guarda los fps que pide el coche (en la cabecera solo son informativos) |
| `onVideoControl(play = true)` | `startVideo()`: crea `VideoSource` y `CarVideoSink`. Si el vídeo ya estaba en marcha, pide un IDR |
| `onKeyframeRequested(reason)` | Al recodificar: `encoder.requestKeyFrame()`. En reenvío: `source.requestKeyFrame()` (ciclo de foco), con antirrebote de 600 ms y registro del motivo |
| `onTouch(TouchEvent)` | `TouchToAa`, o `CarUi` si el gesto empezó en el panel |
| `onKey(CarKey)` | `KeyToAa` → `CommManager.sendKey` |
| `onAppMessage` (`Global/DarkModeOn`) | `source.onCarDarkMode(…)`, registrando el valor crudo |
| `onWatchdogWarning`, `onClosed` | `PerfTrace` y temporizador de «coche fuera»: AA sigue vivo 30 s para reconectar rápido |
| `onTrace` | `CarTrace` (diario del coche) |

**`AaVideoAdapter`** (reenvío directo): recibe cada unidad de `VideoTap` y la separa en NAL con `CORE/h264/AnnexB`.

- Si solo trae SPS y PPS, recorta el SPS (C5) y llama a `codecConfig`.
- Si no, llama a `accessUnit`, con `key` = la unidad lleva una NAL 5.
- **Freno**: `VideoTap.ackGate` engancha la confirmación (MediaAck) de AA al `onWritten` del último frame. Un hilo
  aparte la suelta cuando la cola del kernel baja de 24 KiB, como mucho a los 250 ms (`HQ/SspSession.java:886-892`). Así
  el hilo que escribe nunca espera.

**`TouchToAa`** (Kotlin puro, con tests):

- guarda el estado de cada dedo (id → x, y);
- convierte de px del coche a px de AA: `(x − panelX)·s`, `y·s`, con `s = videoW / carW`, y lo limita a la zona de AA;
- un DOWN de un solo dedo empieza un gesto nuevo;
- **manda un evento de AA por cada dedo que cambia**. Así se corrige el dedo «pegado» de headqlink (CMP §4 n.º 14).

**`KeyToAa`**:

| Tecla del coche | Código que recibe AA (`KeyEvent`) |
|---|---|
| `HOME` | 3 |
| `BACK` | 4 |
| `PLAY_PAUSE` | 85 |
| `PLAY` | 126 |
| `PAUSE` | 127 |
| `NEXT` | 87 |
| `PREVIOUS` | 88 |
| `RECENTS` | se ignora: AA no tiene recientes |

Hay que comprobar que Open Headunit anuncia esos códigos en su `ServiceDiscoveryResponse`.

**`AndroidLinkProbe`**: `NetStat.open(socket)`, para dar a `:core` la cola del kernel, el RTT, las retransmisiones y el
ritmo de entrega (C2).

**`TraceBridge`**: lleva `QdLog` a `L` y `TraceEvent` a `CarTrace`. Además vuelca en `PerfTrace` los eventos nuevos de
§3.7.

### 2.4 Cambios propuestos en `:core`

Se harán **cuando acabe el trabajo en curso en `qdauto/`**. Ese trabajo ya incluye:

- 1920×882 y 30 fps por defecto;
- dejar de mandar el aviso de pantalla desbloqueada;
- la medición de latencia.

| # | Cambio | Para qué |
|---|---|---|
| C1 | Política de ACK «**uno por cada `Connect_Broadcast`** mientras no llegue el TCP» (`CORE/discovery/DiscoveryModels.kt:33-44`) | Es lo que hace headqlink y el coche lo acepta [en uso]. Reconexión en ~5 s |
| C2 | Interfaz `LinkProbe` (pura) + `SessionStats.link` + acceso al `Socket` de la sesión para la app | La puerta, el bitrate adaptable y las trazas usan la cola del kernel y TCP_INFO sin meter Android en `:core` |
| C3 | `FlowController` con tres políticas: `DROP_TO_IDR` (la actual), `LATEST_FRAME` y `NO_DROP`. Además: `readyForFrame()`, vaciado por antigüedad (`maxFrameLagMs`) y aviso por cada frame escrito | §3.4 |
| C4 | `SessionConfig.trafficClass`: DSCP CS5 = `0xA0` (`HQ/SspSession.java:68-69,216-220`) | Prioridad WMM de vídeo. Es Java puro |
| C5 | `h264`: mover a `:core` el lector de SPS de carsim, más un **recorte de SPS escrito por nosotros** y una VUI de baja latencia, con tests | Recorte a 1920×882 sin depender de código AGPL |
| C6 | `SessionConfig` preparado para el C10: lista blanca `ALWAYS` con valor según la pantalla (§5.5), respuesta a `DISCONNECT_REQ` con cierre (`HQ/SspSession.java:340-342`) y `SO_SNDBUF` según la política | Un solo sitio con los valores del coche |
| C7 | **Perfil «C10» de carsim** (CMP §5.3): 1920×882, FrameRate 30, espera el `!BIN` antes de CAR_INFO, manda KEY_FRAME_REQ tras el primer IDR y mide cuánto tarda el segundo, DarkModeOn, 3 dedos con acción global `0x105/0x106` opcional, reconexión en bucle a los 5 s y comprobación de que no hay relleno | Probar en casa lo que hace el coche de verdad |
| C8 | **Averías en carsim**: pausas de lectura (100-350 ms cada 10 s), límite de caudal y cortes largos (2-6 s) | Medir en casa la puerta, el vaciado y el bitrate adaptable |
| C9 | `maven-publish` en `:core`, cabecera de licencia y `NOTICE` | §2.5 y §2.6 |

### 2.5 Cómo meter `:core` en el fork (Gradle)

Las dos compilaciones no casan:

| | Fork | `qdauto` |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | 8.13.1 |
| Kotlin | 1.9.22 (`build.gradle.kts` raíz) | 2.2.20 |
| Bytecode de Java y Kotlin | 1.8 | 17 |
| minSdk | 16 (`github`) / 21 (`playstore`) | — |

Hay que resolver tres cosas:

1. Un compilador de Kotlin 1.9 no lee clases compiladas con Kotlin 2.2 (versión de los metadatos) [plataforma]. Hay dos
   salidas:
   - **subir el fork a Kotlin 2.2** (habrá que revisar el `kapt` de Glide);
   - o compilar `:core` con `languageVersion` y `apiVersion` antiguos, si el compilador 2.2 aún lo permite.
2. Las funciones `inline` compiladas para JVM 17 no se pueden insertar en código para JVM 1.8. Hay que **subir el fork a
   JVM 17**, algo que AGP 8 ya exige para compilar.
3. headqlink solo funciona en Android 13 o posterior (`HQR/app/src/main/AndroidManifest.xml`, comentario de Media3).
   **Subir el minSdk del fork a 29 o más** simplifica el código y no pierde a nadie: el fork es solo para móviles.

| Opción | Cómo | A favor | En contra |
|---|---|---|---|
| **A. Artefacto versionado** (recomendada) | `:core` publica `dev.qdauto:core:<versión>` en Maven local (o en un repositorio propio) y el fork lo pide en `dependencies`. Para el primer prototipo vale un `.jar` con `implementation(files(…))` | Separación limpia, también de licencia; versiones claras; el fork no necesita la carpeta `qdauto/` | Hay que publicar cada vez que cambia `:core` |
| B. Compilación compuesta | `includeBuild("../qdauto")` | Los cambios de `:core` se ven al momento | Junta dos AGP y dos Kotlin distintos (conflicto probable) y arrastra la configuración de `:app` y `:carsim` |
| C. Copiar el código | `hql/core/src/…` | Lo más fácil de compilar | Dos copias que se separan con el tiempo, dentro de un repositorio AGPL, donde es fácil mezclar código suyo |

**El Wi-Fi Direct y `NetworkWatcher` viven en nuestro `:app`**, que es Android. Hay dos formas de llevarlos al fork:

1. Moverlos a un módulo de librería Android en `qdauto/` (por ejemplo `:link-android`) y usarlo como `:core`. Así siguen
   siendo nuestros y valen también para QDAuto.
2. Copiarlos al fork, donde pasan a ser AGPL.

Si queremos que QDAuto siga vivo como app propia, mejor la primera.

### 2.6 Licencia, explicado sencillo

- headqlink y Open Headunit son **AGPL-3.0**. El fork es una obra derivada, así que **la app que salga de `qd/hql` es
  AGPL, sí o sí**.
- **Si la usas tú y no la das a nadie**, la AGPL no te obliga a nada.
- **Si la distribuyes** (GitHub, un foro, un amigo), tienes que:
  - dar el código fuente completo de esa app, incluido lo que lleve de `:core`, bajo AGPL o una licencia compatible;
  - conservar los avisos de copyright de Michael Reid, Open Headunit y headqlink;
  - decir qué has cambiado.
- **`:core` es nuestro**, y en su repositorio puede tener la licencia que queramos:
  - Apache-2.0 o MIT, que son compatibles con la AGPL;
  - o una **licencia doble**: AGPL para todos y otra licencia para quien la pida.

  Lo que no puede ser es **cerrado** si va dentro de una app AGPL que se distribuye: dentro de la app viaja con su código
  fuente.
- Para que `:core` siga libre de la AGPL hay que mantenerlo **separado**:
  1. se desarrolla en `qdauto/`, no en el fork;
  2. el fork lo usa como dependencia versionada (opción A de §2.5), sin copiar ficheros;
  3. **nunca se copia ni se adapta dentro de `:core` código de headqlink o de Open Headunit**. Por ejemplo,
     `HQ/H264SpsCrop.java`: el recorte de C5 se escribe desde la norma H.264, partiendo de nuestro lector de SPS de
     carsim;
  4. las contribuciones a `:core` se aceptan en su repositorio y con su licencia, no a través del fork.
- **El certificado de Google** que trae Open Headunit (`HQR/app/src/main/res/raw/cert` y `privkey`, CMP §4 n.º 6) es un
  riesgo aparte que la AGPL no cubre (§5.5).

No es asesoramiento legal: antes de publicar, que lo revise alguien que sepa.

---

## 3. Vídeo

### 3.1 Los dos caminos

| | Reenvío directo («Básico» en headqlink) | Recodificar («Muy alto», «Alto», «Medio», «Muy bajo») |
|---|---|---|
| Qué es | El H.264 de AA, con el SPS recortado, va directo al coche | AA → decodificador de OH → `SurfaceTexture` → GL (recorte, panel, pantalla de carga) → nuestro encoder |
| Coste en el móvil | Bajo. Aun así, Open Headunit sigue decodificando en una superficie invisible para su recuperación de keyframes (`HQ/AaPassthroughSource.java:519-535`) | Decodificar y codificar 1080p: batería y temperatura |
| Latencia que añade | Casi ninguna | Mediana de 11,9 ms con optimizaciones, 16,9 ms sin ellas (`HQ/VideoProfile.java:18-19`), más el GL |
| Calidad | La de AA, sin compresión doble | Compresión doble [hipótesis: a 8-16 Mbit/s apenas se nota] |
| IDR bajo demanda | Ciclo de foco: la imagen se congela 0,7-0,9 s; por su cuenta, AA solo manda uno cada ~69 s | Inmediato (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) |
| Ante un corte de radio | No se puede descartar (sin IDR barato), así que se acumula. Freno por confirmaciones, con ventana 2, que AA no siempre respeta | La puerta deja de codificar; al volver la radio sale la imagen actual, no una cola de frames viejos |
| Bitrate | Lo decide AA | Lo decidimos nosotros (bitrate adaptable) |
| Interfaz propia (panel, avisos en la pantalla del coche) | No | Sí |
| Problema medido | A 60 fps y 14 Mbit/s, el coche acumula frames en los movimientos rápidos (`C10L AaPassthroughSource.java:236-237`) | Batería y temperatura (CMP §4 n.º 12) |
| **Uso propuesto** | Modo «ahorro»: 30 fps, en 720p o 1080p | **Camino principal**: 1920×882, 30 fps al empezar; 60 fps si las medidas lo permiten |

### 3.2 Tamaño y recorte a 1920×882

- **AA no tiene 1920×882.** Se le pide 1920×1080 en modo «CONTAIN», con 198 filas de margen, **todas abajo**
  (`UpdateUiConfigRequest` L0 T0 R0 B198).
  - El SPS de AA codifica 1920×1088 y recorta 8 filas. Se reescribe para que recorte **206** (1088 → 882), anclado
    arriba (`HQ/AaPassthroughSource.java:207-221,293-306`; `HQ/H264SpsCrop.java:138-150`).
  - En 720p: 1280×720 con 132 filas de margen, que da 1280×588. El coche lo escala, porque la proporción coincide
    (CMP: «supuesto»).
- **Al recodificar**, el encoder se configura a 1920×882 directamente:
  - el códec codifica 1920×896 y el SPS recorta 14 filas, algo que el coche respeta [en uso];
  - el GL coge la franja superior de la textura de AA.

  Ojo con nuestro `fitSize` (CMP §5.2): si redondeara a 1920×896 sin recortar, saldrían bandas.
- **Reglas**:
  - la cabecera de 32 B lleva el mismo tamaño que el SPS recortado (carsim ya lo comprueba: `sps_vs_cabecera`);
  - la proporción, exactamente la de CAR_INFO;
  - nunca relleno hasta 512.
- **Mejoras**:
  1. **Recorte propio en `:core`** (C5), probado contra el lector de SPS de carsim y con vectores de prueba.
  2. **VUI de baja latencia**: `max_num_reorder_frames = 0` y `max_dec_frame_buffering` mínimo. headqlink ya lo tiene
     escrito, pero apagado (`HQ/H264SpsCrop.java:29-33,153-157`). Probarlo en el reenvío a 60 fps: es el primer
     candidato contra «el coche acumula frames». Comprobar también la VUI que sale de nuestro encoder.
  3. **Nivel H.264 correcto**. `levelFor` da nivel 3.2 a 1920×882 a 30 fps, y ese nivel no admite un frame de 6720
     macrobloques (CMP §4 n.º 9). Usar `APP/video/H264Levels.kt`.

### 3.3 Fotogramas clave: intra-refresh e IDR bajo demanda

- **El primer IDR se pierde** (CMP n.º 14). Tras `VIDEO_CTRL{1}`, el coche manda KEY_FRAME_REQ, y hay que contestar
  **siempre** con un IDR real precedido del SPS/PPS.
  - `:core` ya lo hace: reenvía el SPS/PPS y pide un IDR forzado (`CORE/session/InboundHandler.kt:91-95`).
  - En el reenvío, el antirrebote de 600 ms del ciclo de foco no se puede tragar ese segundo IDR. Medirlo con la métrica
    «KEY_FRAME_REQ → IDR enviado» (§3.7).
- **Al recodificar**, la configuración de partida es la suya, que funciona en el coche (`HQ/SspSession.java:676-694`;
  `HQ/VideoEncoder.java:64-114`):
  - Baseline y VBR. Con CBR, el encoder rellena hasta 8 Mbit/s con la pantalla quieta y la radio va siempre cargada;
  - `KEY_I_FRAME_INTERVAL` de 30 s, es decir, sin IDR periódicos en la práctica;
  - **intra-refresh de 1 s** (`KEY_INTRA_REFRESH_PERIOD` = fps);
  - `KEY_REPEAT_PREVIOUS_FRAME_AFTER` de 100 ms;
  - claves de baja latencia de Qualcomm cuando las haya.

  El IDR solo se pide al empezar, cuando lo pide el coche y después de un vaciado.
- **Por qué intra-refresh.** El IDR es el frame más grande. Si cae en un hueco de radio, retrasa todo lo que viene
  detrás; con intra-refresh no hay picos. A cambio, tras descartar frames, la imagen sale manchada hasta completar la
  vuelta de 1 s. Por eso, tras un vaciado, se sigue pidiendo un IDR.
- **Por probar**:
  1. **IDR «pequeño» tras un vaciado**: bajar el bitrate un momento justo antes de pedirlo (`setParameters`).
  2. **Variante sin intra-refresh** (`enc_no_ir` ya existe): comparar el tamaño máximo de frame y los tirones.
  3. **Que el encoder de Qualcomm siga dando un IDR de verdad** al pedirlo con el intra-refresh activo. Se cuentan las
     NAL 5 en el vídeo que graba carsim.
- **En el reenvío**, el IDR solo se pide al empezar y cuando lo pide el coche, siempre con ciclo de foco. Nunca por un
  atasco nuestro, porque en ese camino no se descartan frames.

### 3.4 Control de flujo: nuestra cola más su puerta

**Lo nuestro** (`CORE/session/SendQueue.kt:28-106`):

- un solo hilo escribe y el control va siempre antes que el vídeo, así que los heartbeats nunca se quedan atascados
  detrás de un IDR;
- descarta P-frames y espera un IDR cuando hay **6 frames o 4 MiB** en cola.

El problema es el kernel: con 256 KiB pedidos se queda 512 KiB, unos 0,5 s a 8 Mbit/s. **El atasco se ve tarde.** Para
el patrón de prueba sirve; para AA, no.

**Lo suyo** (`HQ/SspSession.java:553-609,813-892`):

- **Puerta antes de dibujar**: menos de 3 frames en el encoder (si uno no vuelve en 200 ms, se deja de esperarlo), como
  mucho 1 frame en su cola y la cola del kernel por debajo de 64 KB. Lo que no se dibuja nunca se codifica [medido:
  con 24 KB se perdía un tercio de los frames en cada corte].
- **Vaciado por antigüedad**: si el frame que va a salir lleva más de 150 ms esperando y no es un IDR, se vacía la cola y
  se pide un IDR. Solo al recodificar.
- **Freno en el reenvío**: ningún descarte. La confirmación de AA se retiene hasta que el frame sale y la cola del kernel
  baja de 24 KB (250 ms como mucho). Se anuncia a AA una ventana de 2 y `SO_SNDBUF` es de 48 KiB.
- **Bitrate adaptable**.

El punto débil: escriben desde varios hilos con un candado y el watchdog va dentro del heartbeat (CMP §4 n.º 8).

**Diseño combinado**, en `:core` (C3). La lógica es pura y se prueba con una sonda falsa; las señales las da
`AndroidLinkProbe`. Los valores de la tabla son de partida y se ajustan con las medidas:

| Política | Se usa en | Dónde se frena | Descartes | `SO_SNDBUF` pedido (el kernel lo duplica) | Valores de partida |
|---|---|---|---|---|---|
| `LATEST_FRAME` | Recodificar | **Puerta**: `readyForFrame()` = cola de vídeo ≤ 1 frame, `SIOCOUTQ` < 64 KiB y menos de 3 frames en el encoder (los que pasan de 200 ms dejan de contar) | Vaciado si un frame espera más de 150 ms (y se pide IDR); el descarte por atasco de `:core` queda de red de seguridad con `videoBacklogFrames` 2 | 64-96 KiB | Bitrate adaptable activo (§3.5) |
| `NO_DROP` + freno | Reenvío directo | Confirmaciones a AA (ventana 2) | Ninguno, salvo emergencia: un frame con más de 1,5 s de espera vacía la cola y fuerza un ciclo de foco [propuesta] | 48 KiB | Medir `aa_q` (la cola dentro de AA, por su marca de tiempo) |
| `DROP_TO_IDR` | Patrón de prueba y carsim | Cola de `:core` | Los actuales | 256 KiB | Tal cual |

**Lo que no cambia en ninguna política**: el control antes que el vídeo, el watchdog y el detector de `write()`
bloqueado.

**Experimentos**:

- **`TCP_NOTSENT_LOWAT`** (16-32 KiB), con `android.system.Os.setsockoptInt` sobre el descriptor del socket. Limita lo
  que el kernel tiene **sin enviar**, sin limitar lo que está en vuelo; el `write()` bloquea antes y el atasco se queda
  en nuestra cola, donde se puede descartar. Si funciona, sustituye a un `SO_SNDBUF` pequeño, que puede limitar el caudal
  cuando el RTT sube. Hay que comprobar que el kernel del móvil lo respeta: comparar `SIOCOUTQ` y `SIOCOUTQNSD`.
- **DSCP CS5** (C4), en A/B.

### 3.5 Bitrate adaptable (versión 2)

**El suyo** (`HQ/SspSession.java:582-609`): cada segundo, si la puerta del kernel se ha cerrado 5 veces o más, baja un
20 %; tras 2 s sin cierres, sube 1 Mbit/s. Rango de 5 a 16 Mbit/s («Muy alto»).

**Propuesta**:

- **Señales**:
  - cierres de la puerta;
  - aumento del RTT de TCP sobre el mínimo de la sesión;
  - ritmo de entrega de TCP_INFO (`tcpi_delivery_rate`).
- **Bajada rápida**: ×0,7 si hay 3 cierres o más, o si el RTT supera el mínimo en más de 40 ms durante 2 muestras.
- **Subida lenta**: +0,5 Mbit/s tras 2 s tranquilos, con un tope del 80 % de la mediana del ritmo de entrega de los
  últimos 5 s.
- **Rangos de partida**: de 3 a 12 Mbit/s a 30 fps y de 5 a 16 Mbit/s a 60 fps.
- **Si el problema dura**: bajar de 60 a 30 fps con la cadencia fija del GL (`HQ/GlFrameRelay.java:163-171`). El cambio
  de resolución exige reiniciar el encoder, así que solo se hace entre sesiones.
- **Cada cambio va a la traza.**
- **Pruebas en casa**: carsim con límite de caudal y con pausas (C8). Criterio: tras un corte de 300 ms, ningún frame con
  más de 200 ms de espera, y el bitrate vuelve a su valor en menos de 10 s.

### 3.6 Ritmo de fotogramas

- **El protocolo no lleva marcas de tiempo**: el coche muestra cada frame en cuanto llega. **El jitter de la red se ve
  directamente en la imagen.**
- **Al recodificar**:
  - el GL dibuja al llegar cada frame de AA, con un intervalo mínimo de medio periodo (`HQ/GlFrameRelay.java:157-161`);
  - con cadencia fija (rejilla regular) cuando hay una pantalla nuestra delante o con el perfil «Medio»;
  - el encoder repite el último frame a los 100 ms;
  - la puerta impide mandar ráfagas al volver de un corte.

  Probar la **cadencia fija también con AA**, para que el ritmo sea regular aunque AA entregue a saltos.
- **En el reenvío**, el ritmo lo marca AA, con el freno.
- **Medir** el intervalo entre frames en tres puntos: la llegada desde AA, la salida del encoder y el final de cada
  `write()`. Objetivo: p95 del intervalo a la salida < 1,5 periodos.
- **30 o 60 fps** se decide con las medidas. El coche pide 30, y a 60 en reenvío acumula frames.

### 3.7 Qué medir

Todo va a un único CSV por sesión (`PerfTrace`, con las columnas de `HQ/PerfTrace.java:14-31` más las nuevas), que lee
`tools/trip`:

| Métrica | De dónde sale | Frecuencia | Para qué |
|---|---|---|---|
| `frame`: bytes, IDR o no, espera en cola, duración del `write()` y frames en cola | Aviso por frame escrito de `:core` (C3) | Cada frame | Latencia del lado del móvil y tirones |
| `net`: `SIOCOUTQ` (y `SIOCOUTQNSD`), rtt, rttvar, retransmisiones, cwnd, ritmo de entrega, tiempo limitado por el buffer o por el receptor | `NetStat` | 50 ms | Atascos y cortes de radio |
| `probe`: RTT ICMP y pérdidas hacia el coche | Sonda nueva (§4.3) | 20 Hz | Huecos de radio, con y sin vídeo |
| Radio y sistema: escaneos Wi-Fi, pantalla, STA asociada o no, frecuencia y banda del grupo, Bluetooth A2DP/HFP, errores y descartes de `p2p0`, temperatura, Doze | `SystemMonitor` + nuestro P2P (`APP/p2p/P2pListenerApi35.kt`) | Por evento o cada 1 s | Atribuir cada hueco a su causa |
| Vídeo: fps de AA, frames dibujados y descartados por el GL, `enc_ms`, `abr_kbps`, `aa_q`, `aa_ack` | `PerfTrace` | Cada 1 s o por evento | Origen de los tirones |
| Coche: KEY_FRAME_REQ y tiempo hasta el IDR, intervalo de sus heartbeats, hueco máximo entre sus mensajes | `SessionStats` de `:core` | Por evento | Salud del enlace en el sentido coche → móvil |
| Marcas del usuario: «tirón» y «segmento A/B» | Botón en la notificación, que pulsa el acompañante | Por evento | Unir lo que se ve con lo que se mide |
| Latencia total | `HQ/LatencyActivity.java` + el reloj del patrón, grabados con una cámara | 2 o 3 veces por prueba | Latencia de punta a punta |

**Indicadores que saca el analizador**, por segmento:

- % de segundos con fps ≥ 90 % del objetivo;
- huecos de más de 100, 250 y 500 ms por cada 10 min;
- p95 de la espera en cola;
- RTT de la sonda: p50 y p99, picos de más de 100 ms por minuto y su periodicidad;
- tiempo desde KEY_FRAME_REQ hasta el IDR;
- vaciados y ciclos de foco;
- batería (%/h) y temperatura.

---

## 4. Ruido Wi-Fi

### 4.1 Qué sabemos

- **headqlink**:
  - el enlace tiene cortes periódicos [medido];
  - buscar dispositivos con el grupo formado provoca picos de 100-350 ms;
  - con la pantalla encendida y sin red, el móvil escanea cada ~10 s (`HQ/LinkService.java:62`;
    `HQ/SystemMonitor.java:22-26`). Al apagarla, ese escaneo para.
- **Open Headunit**, en otras unidades y con la misma física [medido]:
  - con la STA sin asociar se pierde la imagen cada ~10 s; asociada, va limpia;
  - 2,4 GHz no da para 1080p60; 5 GHz, sí (§1.3).
- **Foro**: un usuario, probablemente el autor de headqlink, cree que es **el coche** el que escanea cada pocos segundos.
  No da pruebas.
- **[plataforma]** Lo que una app normal no puede hacer:
  - parar los escaneos del sistema;
  - elegir la banda o el canal de su zona Wi-Fi;
  - elegir la banda del grupo P2P, que decide el coche como dueño del grupo.

  Lo que sí puede: **leer** la frecuencia del grupo (`WifiP2pGroup.getFrequency()`, ya en `APP/p2p/P2pDescribe.kt`).
- **[plataforma]** El WifiLock de baja latencia solo actúa con la pantalla encendida y la app en primer plano
  (`APP/service/SystemLocks.kt:12-13`).

**Conclusión**: el sospechoso principal son los escaneos periódicos. Los del móvil, seguro; los del coche, por ver.
**Primero hay que medir y atribuir**, y después elegir el remedio.

### 4.2 Estrategias

| # | Estrategia | Qué ataca | Coste y límites | Cómo verificarla en el coche |
|---|---|---|---|---|
| 1 | **Móvil bloqueado, con la pantalla apagada**, durante la sesión (ya funciona así) | Escaneos del móvil con la pantalla encendida | Ninguno | A/B de 5 min, pantalla encendida y apagada: escaneos (`SCAN_RESULTS`) y picos de la sonda por minuto |
| 2 | **No buscar nada con el grupo formado** (ni dispositivos ni servicios), y que Open Headunit no monte su propio Wi-Fi: modo `MANUAL` (`HQ/AaPassthroughSource.java:264-267`) | Búsquedas P2P (100-350 ms) y que nos echen del grupo del coche | Ninguno | El log no debe tener ni una llamada a `discover*` durante la sesión |
| 3 | **Zona Wi-Fi del móvil a 5 GHz** como transporte alternativo: el coche se une a la del móvil, como en la «Conexión de punto de acceso» de QDLink (`DOC/01` §2.1) | Coexistencia con el Bluetooth en 2,4 GHz; escaneos de la STA (en Samsung, encender la zona Wi-Fi apaga la STA [plataforma, por comprobar]); el canal lo elige el móvil | La banda la pone el usuario en Ajustes; el C10 tiene que unirse a 5 GHz (QDLink: «solo algunos modelos»); el coche podría gastar datos del móvil; excluye el Wi-Fi Direct (`DOC/05` §7.3 n.º 1) | A/B entre P2P, zona a 2,4 GHz y zona a 5 GHz: picos de la sonda, huecos de vídeo y fps |
| 4 | **STA asociada** a alguna red, si hay una a mano (la zona Wi-Fi de un acompañante, un router de viaje) | Escaneos «sin red» | Si la red va en otro canal, el chip reparte el tiempo entre canales (MCC); en Open Headunit no empeoró | A/B en P2P, asociada y sin asociar |
| 5 | **Ajustes del móvil**: «Búsqueda de redes Wi-Fi» (Ubicación), «Activar Wi-Fi automáticamente» o Wi-Fi inteligente (Samsung), precisión de ubicación de Google | Escaneos para la ubicación: Maps, navegando, pide la ubicación [hipótesis] | Ninguno | Escaneos por minuto con Maps navegando y con Maps parado |
| 6 | **Banda del grupo P2P**: registrarla. Si es 2,4 GHz y suena música por Bluetooth, sospechar de la coexistencia | Coexistencia Bluetooth/Wi-Fi | No podemos elegir la banda | A/B con música por Bluetooth sonando y en pausa |
| 7 | **Puerta, vaciado y bitrate adaptable** (§3.4 y §3.5) | Cualquier hueco: que no se acumule retraso | Algo de calidad | Carsim con pausas en casa; en el coche, retraso máximo y huecos después de cada pico |
| 8 | **30 fps en vez de 60** cuando el enlace va justo | Menos tiempo de aire | Menos fluidez | A/B 30/60 |
| 9 | **DSCP CS5** (WMM de vídeo) | Prioridad frente al resto del tráfico del móvil | Casi ninguno; el coche puede ignorarlo | A/B con una descarga en el móvil |
| 10 | (Idea) **Prever los escaneos periódicos**: si la sonda ve picos cada ~10 s, no pedir un IDR ni subir el bitrate justo antes | IDR que caen en un hueco | Complejidad | Solo si no bastan las estrategias 1-9 |

### 4.3 Sonda de latencia

- **ICMP «ping» sin root**: `Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` y `Os.sendto`/`Os.recvfrom`. Android permite
  sockets ICMP a las apps [plataforma, por comprobar en el S25]. Si no se pudiera, se usaría `/system/bin/ping`.
- **Destino**:
  - en P2P, la IP del dueño del grupo;
  - en la zona Wi-Fi, la IP de origen del `Connect_Broadcast`.
- **Ritmo**: 20 Hz, 56 B por paquete (unos 9 kbit/s) y 1 s de plazo. Empieza **en cuanto se forma la red, antes del TCP**,
  para medir la radio en reposo, y sigue durante la sesión.
- **Fila del CSV**: `probe,rtt_ms,seq,perdido`.
- **Si el coche no contesta al ping**, se usa:
  - el RTT de TCP_INFO (solo cuando hay tráfico);
  - el intervalo de los heartbeats del coche;
  - el hueco máximo entre sus mensajes (`SessionStats.maxCarGapMs`).
- **En casa** se prueba la misma sonda contra el PC (p2pcar) y contra el router.
- **El analizador** saca:
  - p50, p95 y p99 del RTT;
  - picos de más de 100 ms por minuto;
  - el periodo de los picos (autocorrelación);
  - su coincidencia con un escaneo (±0,5 s), con el estado de la pantalla y con el del Bluetooth.

### 4.4 Protocolo de prueba en el coche

**Reglas**:

- Primero **aparcado**, con el coche encendido; después **en marcha**, siempre con un acompañante que maneje el móvil.
- Segmentos de 5 min, **cambiando una sola variable cada vez**, y el inicio de cada uno marcado con el botón.
- Si es en marcha, por el mismo recorrido.

**Orden de los segmentos**:

1. P2P con la pantalla apagada: es la base.
2. P2P con la pantalla encendida.
3. Zona Wi-Fi a 5 GHz con la pantalla apagada, si el C10 se une.
4. Zona Wi-Fi a 2,4 GHz.
5. STA asociada, si hay red.
6. Música por Bluetooth sonando y en pausa.
7. 30 frente a 60 fps.
8. Recodificar frente a reenvío directo.

**Decisión**: exportar los logs y pasar `tools/trip`. Por ejemplo, si la zona Wi-Fi a 5 GHz quita más de la mitad de los
picos de más de 100 ms, se recomienda por defecto en los coches que la admitan.

---

## 5. Android Auto

### 5.1 Cómo arranca AA en la versión 17.4 o posterior

1. Llega `VIDEO_CTRL{1}` y arranca la fuente: `AaPassthroughSource.start…()` y `configureAa()`
   (`HQ/AaPassthroughSource.java:264-291`), que deja:
   - Open Headunit en `MANUAL`, con imagen `CONTAIN`;
   - 1080p o 720p, a 30 o 60 fps;
   - 200 dpi y lienzo externo de 1920×882.

   Después: `VideoTap` sin vista → `ensureAaConnected()` → `AapService.ACTION_START_SELF_MODE`.
2. `SelfLauncherV17_4` prueba tres rutas:
   - las rutas 1 y 2 son *receivers* de AA, desactivados desde la 17.4;
   - la ruta 3 conecta a `127.0.0.1:5277`. Si se rechaza, `AaServerStarter.startAndWait()` (accesibilidad), espera 1,5 s
     y vuelve a conectar (`OH/connection/self/launchers/SelfLauncherV17_4.kt:91-133`).
3. Handshake de AA. El SSL usa el **certificado de referencia de Google** que trae Open Headunit. Después,
   `HeadlessDriver` arranca la lectura, el foco de vídeo se da directamente y `VideoTap` entrega el H.264.
4. Al terminar, `LinkService.shutdownAll()` para AA y su servidor, por accesibilidad o con el botón de la notificación.
   Si el móvil está bloqueado y no se puede, `AaGuardService` «aparca» la sesión.

### 5.2 Qué hace exactamente el servicio de accesibilidad (`HQ/TouchService.java`)

Configuración (`HQR/app/src/main/res/xml/hql_touch_service.xml`):

- eventos: `windowStateChanged`, `windowContentChanged` y `notificationStateChanged`;
- permisos: `canRetrieveWindowContent` y `canPerformGestures`.

Qué hace:

1. **Enciende y apaga el servidor de unidad principal** (`HQ/AaServerStarter.java:237-319`):
   - abre `DefaultSettingsActivity` de AA;
   - busca el botón «Más opciones» por su **descripción en español o en inglés**;
   - lo pulsa y busca la opción «unidad principal / head unit»;
   - lee si dice «Iniciar» o «Detener» y la pulsa si hace falta;
   - cierra con ATRÁS (hasta 3 veces, o INICIO).
2. **Comprueba si el modo desarrollador de AA está activo**: si la opción no aparece en el menú, aunque lo reabra una vez,
   concluye que no.
3. **Tapa la automatización**: muestra una capa opaca «Conectando con Android Auto…» de tipo
   `TYPE_ACCESSIBILITY_OVERLAY` (`HQ/TouchService.java:58-109`).
4. **Guarda el botón «Detener» de la notificación del servidor**: es el `PendingIntent` del canal
   `gearhead_connection_status`. Con él puede apagarlo sin abrir los ajustes, incluso con el móvil bloqueado
   (`HQ/TouchService.java:111-120`; `HQ/AaServerStarter.java:92-128`).
5. Al desbloquear, **ejecuta el apagado pendiente**.
6. **Solo en el modo «app»**: inyecta gestos en el VirtualDisplay. Con AA no hace falta, porque el táctil va por el
   protocolo de AA.

**Requisitos y fragilidades**:

- hay que **desbloquear el móvil** para arrancar el servidor (CMP §4 n.º 4);
- el modo desarrollador de AA, activado una vez;
- busca por texto, solo en español e inglés (CMP §4 n.º 5);
- Android la desactiva al actualizar la app (`HQ/LinkService.java:139-141`), y también si el proceso se cae en bucle
  (`HQ/LinkService.java:113-117`);
- en Android 13 o posterior, si la app no viene de una tienda, antes de activarla hay que permitir los «ajustes
  restringidos» en la información de la app [plataforma].

### 5.3 Ideas para quitar o reducir la accesibilidad

Con honestidad sobre lo que se puede hacer:

| Idea | Qué quita | Viabilidad | Coste o riesgo |
|---|---|---|---|
| A. **Buscar por identificador interno** (`viewIdResourceName`) en vez de por texto, con el texto como respaldo | La fragilidad por idioma | Alta | Si Google cambia los identificadores, se rompe igual. Registrar el árbol de nodos cuando falle |
| B. **Accesibilidad solo bajo demanda**: con `WRITE_SECURE_SETTINGS`, concedido **una vez** por adb (o Shizuku), la app la activa unos segundos para arrancar o parar y la vuelve a apagar | La accesibilidad siempre activa, y que se apague al actualizar | Media: técnica conocida, por comprobar en One UI 8 / Android 16 | Un paso con adb. Si falla, se vuelve al modo normal |
| C. **Parar desde la notificación** con un `NotificationListenerService`, que lee la notificación del servidor cuando sea, aunque el proceso se haya reiniciado | La accesibilidad para parar | Media-alta: headqlink ya usa ese `PendingIntent` | Otro acceso especial, también restringido en apps instaladas a mano |
| D. **Arranque manual guiado**: un atajo que abre los ajustes de desarrollador de AA, y un aviso en la pantalla del coche: «Inicia el servidor de unidad principal» | Toda la accesibilidad para arrancar | Alta, siempre funciona | Un paso a mano en cada viaje |
| E. **Arrancarlo antes**: al desbloquear el móvil con el Bluetooth del coche conectado, arrancar el servidor con la capa encima | Tener que desbloquear justo al arrancar | Alta | El puerto queda abierto unos minutos antes de tiempo (§5.5) |
| F. **Arrancar sin desbloquear**: Smart Lock / «Extend Unlock» con el Bluetooth del coche como dispositivo de confianza, más una actividad propia que encienda la pantalla y descarte el bloqueo | El desbloqueo | Baja-media [hipótesis, sin probar] | El móvil queda desbloqueado dentro del coche |
| G. **Buscar en la APK de AA** (con `tools/jadx`) un punto de entrada exportado que arranque el servidor | Toda la accesibilidad | Baja: Google cerró los disparadores en la 17.4 | 4 h como mucho. Si existe, puede desaparecer en la versión siguiente |
| H. **Quedarse en AA 17.3 o anterior** (ruta antigua, sin servidor ni accesibilidad) | Todo | Hoy media; a futuro, baja | Sin actualizaciones de AA, y Google puede exigir una versión mínima |
| I. Root, o Shizuku para algo más que lo de la idea B | — | Shizuku no basta: el shell no puede arrancar componentes no exportados; solo con root | No vale para la mayoría |

**Conclusión**: no conocemos ninguna forma de **quitar** la accesibilidad con AA 17.4 o posterior en un móvil sin root.
Proponemos **A + C + E siempre**, **B como opción avanzada**, **D como respaldo** y **G como investigación acotada**.

### 5.4 Robustez

**`AaGuardService`** (`HQ/AaGuardService.java:27-35`): si la sesión acaba con el móvil bloqueado y el servidor no se
puede apagar, mantiene ocupada la única conexión que acepta AA:

- con el foco de vídeo en «nativo», para que AA deje de codificar;
- con un ping cada 5 s, porque sin vídeo Open Headunit da la conexión por perdida a los 15 s.

Al desbloquear, apaga el servidor y suelta la sesión. Si la conexión se pierde antes, avisa.

Mejoras:

- con la idea C casi nunca hará falta;
- medir cuánta batería gasta mientras está aparcado;
- avisar también en la pantalla del coche mientras siga conectada.

**`AaRecovery`** (`HQ/AaRecovery.java:10-14`): cuando el servidor acepta el TCP pero no contesta («sordo»), lo reinicia
por accesibilidad, como mucho una vez por minuto, y vuelve a lanzar el Self-Mode.

Mejoras:

- **evitar que se quede sordo**: siempre mandar `ByeByeRequest` antes de cerrar, y nada de conexiones de prueba al 5277
  (CMP §5.5);
- si no se puede reiniciar porque el móvil está bloqueado, decirlo en la pantalla del coche;
- probarlo en casa, con un servidor 5277 falso que acepte la conexión y se calle.

**Además**:

- **Volver a lanzar AA** si se cae a mitad de sesión con el coche conectado (CMP §4 n.º 13).
- **Mantener AA 30 s** si se va el coche (ya lo hacen), y que al volver salga un IDR al instante.
- **Política de reinicio del servicio**: hoy, si Android lo cierra, no vuelve. Que vuelva cuando haya una sesión en
  curso, con cuidado de no repetir el bucle que desactiva la accesibilidad.
- **Mensajes en la pantalla del coche** («Desbloquea el móvil», «Android Auto no responde»). Hasta que llega el primer
  frame de AA, esa pantalla es nuestra: es la capa de carga de `GlFrameRelay`.
- **Comprobar que no hay ninguna VPN activa** durante la sesión, por ejemplo la `DummyVpnService` de la variante
  `github` de Open Headunit: se quedaría las rutas.

### 5.5 Seguridad

- **Lista blanca honesta**: `WhitelistAppOn` = 1 solo con interfaces pensadas para conducir (AA, navegadores) y 0 con
  vídeo, TV, web o juegos, para que el coche aplique su restricción en marcha. headqlink lo calcula pero manda siempre 1
  (CMP §4 n.º 1).
- **Estado de conducción real para AA**. Open Headunit manda `UNRESTRICTED` (`OH/aap/AapControl.kt:506-508`); el estado
  real se puede sacar de la velocidad del GPS del móvil.
- **`LinkService` cerrado a otras apps**: `exported=false`, o protegido con un permiso de firma. Los extras por adb, solo
  en la variante de depuración (CMP §4 n.º 2).
- **Servidor 5277**: escucha en todas las interfaces mientras está encendido. Hay que apagarlo al terminar, aparcar la
  sesión si no se puede y avisar.
- **Certificado de Google** (CMP §4 n.º 6). Experimento: ¿acepta el servidor de desarrollador un certificado propio? Si
  lo acepta, se quita del fork la clave de Google.
- **UDP**: filtrar por coche, con `DiscoveryListener` y `carFilter`.

---

## 6. Estrategia de pruebas

### 6.1 Herramientas

| Herramienta | Qué prueba | Dónde |
|---|---|---|
| Tests JVM de `:core`: los actuales más los de recorte del SPS, `FlowController` con sonda falsa, `TouchToAa`, `KeyToAa` y la política de ACK | La lógica, sin móvil | PC (`./gradlew :core:test`) |
| **carsim, perfil C10** (C7), contra el móvil por la Wi-Fi de casa, con `--out` y `--report` | Protocolo, handshake, IDR tras KEY_FRAME_REQ, recorte, táctil y teclas | PC + móvil |
| **carsim con averías** (C8) | Puerta, vaciado, bitrate adaptable y recuperación | PC + móvil |
| **p2pcar + carsim** | El Wi-Fi Direct completo, con el PC como dueño del grupo: unión, broadcast, ACK, TCP y reconexión | PC con Wi-Fi + móvil |
| **Zona Wi-Fi del móvil + PC unido a ella + carsim** | El transporte «zona Wi-Fi», que es lo que hará el coche | PC + móvil |
| **El fork con AA real en casa**: servidor 5277 + carsim | Toda la cadena de AA hasta el coche, sin coche. El vídeo grabado se revisa con `tools/ffmpeg` (`ffplay`, `ffprobe -show_frames`), y el guion táctil pulsa la interfaz de AA | PC + móvil |
| `EncBench` (acción `ENC_BENCH` del fork) | Latencia del encoder en cada variante | Móvil |
| `tools/trip` | Indicadores por segmento a partir de `PerfTrace`, `CarTrace`, logcat y nuestros logs | PC |
| En el coche: QDLink como referencia; `LatencyActivity` + cámara | Lo que no se puede ver en casa | Coche |

**Reglas**:

- Cada hito deja guardados, como referencia, un informe de carsim (JSON) y un CSV de `PerfTrace`. El hito siguiente no
  puede empeorar los indicadores sin explicarlo.
- Las pruebas con el móvil y en el coche las hace el usuario; los agentes no tocan el móvil.

### 6.2 Qué herramienta se usa en cada hito

| Hito | Tests JVM | carsim C10 | carsim con averías | p2pcar | Zona Wi-Fi + PC | AA real en casa | Coche |
|---|---|---|---|---|---|---|---|
| M1 Línea base | — | ✔ | — | ✔ | — | ✔ | Aparcado |
| M2 Medidas + zona Wi-Fi | ✔ (analizador) | ✔ | — | ✔ | ✔ | — | Aparcado + en marcha |
| M3 `:core` en el fork | ✔ | ✔ (todo PASS) | — | ✔ | ✔ | ✔ | Aparcado |
| M4 Control de flujo | ✔ | ✔ | ✔ | — | ✔ | ✔ | A/B en marcha |
| M5 Nuestro Wi-Fi Direct | ✔ | ✔ | — | ✔ | — | — | Aparcado + reconexiones |
| M6 Afinar el vídeo | ✔ (SPS) | ✔ | ✔ | — | — | ✔ | Latencia + A/B |
| M7 Android Auto | ✔ | — | — | — | — | ✔ (bloqueo, AA caído, servidor sordo) | Arranque en frío |
| M8 Entrada y seguridad | ✔ | ✔ (teclas, DarkModeOn) | — | — | — | ✔ | Con acompañante |
| M9 Endurecer y publicar | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ (60 min) | Viaje largo |

---

## 7. Hitos

**Esfuerzo**: S ≤ ½ día · M = 1-2 días · L = 3-5 días, contando el trabajo de los agentes y tu revisión. La columna
«Coche» dice cuántas salidas hacen falta.

| Hito | Objetivo | Riesgo | Esfuerzo | Coche |
|---|---|---|---|---|
| **M1** | Línea base: el fork tal cual, medido | Bajo | S | 1 corta |
| **M2** | Medidas comunes, sonda y transporte «zona Wi-Fi» | Bajo | M | 1 |
| **M3** | `:core` dentro del fork, solo el protocolo, con selector de motor para A/B | Medio | L | 1 |
| **M4** | Control de flujo unificado y bitrate adaptable v2 | Medio-alto | L | 1-2 |
| **M5** | Nuestro Wi-Fi Direct y reconexión rápida | Medio | M | 1 |
| **M6** | Afinar el vídeo: VUI, fps, cadencia e IDR | Bajo-medio | M | 1 |
| **M7** | Android Auto: accesibilidad mínima y robustez | Medio-alto | M-L | 1 |
| **M8** | Entrada (teclas, táctil) y seguridad en marcha | Bajo (voz: alto) | M | 1 con acompañante |
| **M9** | Endurecer y publicar | Bajo | M | 1 larga |

Total aproximado: **3-4 semanas de trabajo y unas 8 salidas al coche**.

**Por qué este orden**:

1. Primero **medir** (M1, M2), con su motor, que ya funciona.
2. Después **cambiar el motor sin cambiar el comportamiento** (M3), con A/B.
3. Después **mejorar** lo que más se nota (M4-M6).
4. Al final, **AA, entrada y seguridad** (M7-M9).

M3 y M4 dependen de los cambios de `:core` de §2.4, que esperan a que acabe el trabajo en curso en `qdauto/`.

### M1. Línea base (otra tarea está compilando el fork ahora)

- **Qué**:
  1. compilar `qd/hql` tal cual e instalarlo;
  2. una sesión contra carsim (perfil C10 en cuanto exista; mientras, `--width 1920 --height 882 --fps 30`) y una con AA
     real en casa;
  3. una sesión **aparcado** en el coche con su perfil por defecto;
  4. guardar el CSV de `PerfTrace`, el diario de `CarTrace` y el informe de carsim.
- **Sale cuando**: hay un APK que funciona y un informe de referencia con fps, huecos, espera en cola y temperatura.
- **Riesgo**: bajo. **Esfuerzo**: S.

### M2. Medidas comunes y transporte «zona Wi-Fi»

- **Qué**:
  1. la sonda ICMP (§4.3);
  2. las marcas del usuario (botón en la notificación);
  3. la frecuencia y banda del grupo, el estado de la STA y el del Bluetooth en `PerfTrace`;
  4. un **selector de transporte**:
     - «Wi-Fi Direct» (`P2pLink`, como ahora);
     - «Zona Wi-Fi»: sin `P2pLink`, porque su reintento de búsqueda cada 15 s metería ruido; solo el UDP en `0.0.0.0`;
  5. `tools/trip` lee el CSV y saca los indicadores de §3.7.
- **Pruebas**:
  - en casa: p2pcar + carsim, y zona Wi-Fi + PC + carsim (la sonda contra el PC);
  - en el coche: los segmentos 1-4 de §4.4.

  La zona Wi-Fi solo tiene sentido si el viaje de hoy confirma que el C10 se une a la zona del móvil.
- **Sale cuando**: el analizador da los indicadores por segmento y tenemos la primera atribución del ruido (móvil o
  coche, y qué banda).
- **Riesgo**: bajo. **Esfuerzo**: M.

### M3. `:core` dentro del fork (solo el protocolo)

- **Qué**:
  1. integración en Gradle (§2.5): artefacto, Kotlin y JVM alineados, minSdk ≥ 29;
  2. `QdCarLink` y `SessionBridge` sustituyen a `SspSession`, `Proto` y `UdpDiscovery`; `P2pLink` se queda de momento;
  3. el vídeo entra por `CarVideoSink` **sin cambiar las políticas**: reenvío con freno y recodificar con su puerta,
     reimplementada en el adaptador;
  4. `TouchToAa` corrige el dedo pegado;
  5. **selector de motor «hql | qd»** para comparar.

  Cambios necesarios en `:core`: C1, C2, C6 y C7.
- **Pruebas**:
  - tests JVM;
  - carsim C10 con todas las comprobaciones en PASS con los dos motores;
  - 50 reconexiones seguidas sin hilos ni sockets colgados;
  - KEY_FRAME_REQ → IDR medido;
  - AA real en casa: el guion táctil pulsa iconos de AA y se ve en el vídeo grabado;
  - en el coche: arranque, 3 dedos, día/noche y reconexión en ~5 s.
- **Sale cuando**: el motor `qd` es al menos igual que `hql` en los indicadores de M1 y M2.
- **Riesgo**: medio (hilos, ganchos de AA, versiones de Gradle). **Esfuerzo**: L.

### M4. Control de flujo unificado y bitrate adaptable

- **Qué**:
  1. `FlowController` en `:core` (C3), con `LinkProbe` desde `NetStat`;
  2. las políticas `LATEST_FRAME` y `NO_DROP` con freno;
  3. `SO_SNDBUF` según la política, y el experimento con `TCP_NOTSENT_LOWAT`;
  4. vaciado por antigüedad;
  5. bitrate adaptable v2 (§3.5);
  6. DSCP (C4).

  Cambios necesarios en `:core`: C3, C4 y C8.
- **Pruebas**:
  - carsim con pausas de 100-350 ms cada 10 s, límite de 6 Mbit/s y cortes de 2-6 s;
  - criterios: ningún frame con más de 200 ms de espera tras un corte de 300 ms; recuperación (siguiente IDR o frame
    limpio) en menos de 300 ms; la cola no crece sin límite; el bitrate converge;
  - en el coche: A/B entre M3 y M4 en los mismos segmentos.
- **Sale cuando**: hay menos huecos de más de 250 ms por cada 10 min que en M3, con el mismo fps o más.
- **Riesgo**: medio-alto (empeorar el rendimiento sin darse cuenta). **Esfuerzo**: L.

### M5. Nuestro Wi-Fi Direct y reconexión rápida

- **Qué**:
  1. sustituir `P2pLink` por nuestro controlador (módulo `:link-android` o copia, según §2.5), con:
     - la pista «LeapMotor», sin distinguir mayúsculas;
     - el UPnP `QDLink_UPnP_Device` como dato para el diagnóstico;
     - confirmación si hay varios coches (CMP §4 n.º 11);
     - plazos y `cancelConnect`;
     - grupo conservado entre sesiones;
     - nada de búsquedas con el grupo formado;
  2. un ACK por cada broadcast (C1);
  3. arranque al conectarse el Bluetooth del coche (ya lo tienen).
- **Pruebas**:
  - p2pcar + carsim: unión, pérdida del grupo y vuelta;
  - en el coche: 10 cortes provocados (cerrar la sesión, salir del alcance), midiendo el tiempo hasta volver a tener
    imagen. Objetivo: menos de 8 s;
  - varios coches: solo si se encuentran.
- **Sale cuando**: reconecta solo en todos los cortes, y el log de P2P responde a las incógnitas de `DOC/05` §9.
- **Riesgo**: medio (caprichos del P2P en Samsung). **Esfuerzo**: M.

### M6. Afinar el vídeo

- **Qué**:
  1. recorte del SPS propio en `:core` (C5), con VUI de baja latencia;
  2. en reenvío: 30 frente a 60 fps, con y sin la VUI;
  3. al recodificar:
     - cadencia fija;
     - intra-refresh activado y desactivado;
     - IDR «pequeño» tras un vaciado;
     - 1920×882 frente a 1920×896;
     - nivel H.264 correcto;
  4. latencia total con `LatencyActivity`.
- **Pruebas**:
  - `EncBench`;
  - carsim, que comprueba el SPS, el recorte, las NAL 5 tras cada petición de IDR y que no hay relleno;
  - en el coche: A/B y 3 medidas de latencia en cada configuración.
- **Sale cuando**: hay una configuración por defecto elegida con datos, y una latencia total medida (objetivo
  orientativo: menos de 150 ms al recodificar a 30 fps).
- **Riesgo**: bajo-medio. **Esfuerzo**: M.

### M7. Android Auto: accesibilidad mínima y robustez

- **Qué**:
  1. ideas A, C y E de §5.3, B como opción avanzada y D como respaldo;
  2. investigación G, de 4 h como mucho;
  3. `ByeByeRequest` siempre antes de cerrar, y relanzar AA si se cae;
  4. mensajes en la pantalla del coche;
  5. comprobar que no hay ninguna VPN;
  6. experimento del certificado propio (§5.5).
- **Pruebas en casa**:
  - arrancar con el móvil bloqueado (con E, ya estará arrancado);
  - matar AA a mitad de sesión;
  - servidor 5277 sordo, simulado;
  - actualizar la app y ver si la accesibilidad vuelve sola (con B);
  - el menú de AA en otro idioma.
- **En el coche**: arranque en frío con el móvil en el bolsillo.
- **Sale cuando**: arranca sin tocar el móvil en ≥ 9 de 10 intentos con el móvil ya desbloqueado una vez en el coche, y
  nunca queda el servidor encendido sin aviso.
- **Riesgo**: medio-alto (depende de Google). **Esfuerzo**: M-L.

### M8. Entrada y seguridad en marcha

- **Qué**:
  1. `KeyToAa` (`PHONE_KEYS` y `Music`);
  2. comprobar la acción global del segundo dedo (¿`0x105/0x106`?);
  3. confirmar el valor de `DarkModeOn`;
  4. `WhitelistAppOn` honesto;
  5. estado de conducción para AA a partir del GPS;
  6. **experimento con la voz del coche**: mandar `SPEECH_CTRL{SpeechStatus:1}` y ver si llegan mensajes msgType 12 con PCM
     a 16 kHz. Si llegan, llevarlos al canal de micrófono de AA. Es incierto: QDLink nunca lo usa (`DOC/03`).
- **Pruebas**:
  - carsim (guion con teclas, DarkModeOn y 3 dedos);
  - en el coche, con acompañante: teclas, noche, y qué hace el coche con `WhitelistAppOn` = 0 en marcha.
- **Sale cuando**: las teclas y el modo noche funcionan en el coche, y la restricción en marcha se comporta como se
  espera.
- **Riesgo**: bajo; la voz, alto. **Esfuerzo**: M.

### M9. Endurecer y publicar

- **Qué**:
  1. `LinkService` no exportado;
  2. sensores (GPS, giroscopio, consultas a internet) solo cuando una pantalla los use (CMP §4 n.º 12);
  3. límite de tamaño y hilo propio para los logs;
  4. ajustes limpios, con el panel de ocio apagado por defecto;
  5. si se distribuye: cumplir la AGPL (enlace al código, avisos), una guía de instalación y cómo apagar el servidor de AA
     y el modo desarrollador.
- **Pruebas**:
  - 60 min en casa con AA real y carsim: batería, temperatura y memoria estable;
  - un viaje largo en el coche.
- **Sale cuando**: no hay ningún fallo en 60 min, la temperatura no pasa de «moderada» y los indicadores son al menos los
  de M6.
- **Riesgo**: bajo. **Esfuerzo**: M.

---

## 8. Riesgos y preguntas abiertas

**Riesgos**:

| Riesgo | Impacto | Qué hacer |
|---|---|---|
| Google cambia AA: cierra el servidor de desarrollador o bloquea el certificado de referencia | Se cae todo el camino de AA | Seguir las versiones de AA; experimento del certificado (M7); el patrón y el modo «app» siguen funcionando sin AA |
| El C10 no se une a una zona Wi-Fi, o no a 5 GHz | Sin estrategia 3 | Quedan las estrategias 1, 2 y 4-9 |
| Es el coche el que escanea | Hay un límite físico | Aguantar los huecos sin acumular retraso (puerta, vaciado, bitrate) y bajar a 30 fps |
| AA no respeta la ventana de confirmaciones | El freno no sirve en el reenvío | Reenvío solo a 30 fps o en 720p; el principal es recodificar |
| Conflicto de versiones en Gradle (Kotlin 1.9 frente a 2.2, JVM 8 frente a 17) | Retrasa M3 | Resolverlo al principio de M3 con un prototipo `.jar` |
| Mantenerse al día con Open Headunit y headqlink, que cambian deprisa | Fusiones costosas | Ganchos mínimos, todo lo nuestro en `dev.qdauto.hql` y en `:core`, fusiones periódicas con los tests de carsim como red |
| Batería y temperatura al recodificar a 60 fps | Se corta en viajes largos | 30 fps por defecto; vigilar la temperatura (`SystemMonitor`); modo de reenvío |

**Preguntas abiertas**, que se cierran en el coche:

- ¿En qué banda va el grupo P2P del C10?
- ¿Contesta al ping?
- ¿Se une a la zona Wi-Fi del móvil? ¿A 5 GHz?
- ¿Qué valor tiene `DarkModeOn` de día y de noche?
- ¿Qué acción global trae el segundo dedo?
- ¿El coche usa el tamaño de la cabecera o el del SPS? headqlink siempre pone el mismo en los dos.
- ¿Qué hace en marcha con `WhitelistAppOn` = 0?
- ¿Se reserva el borde izquierdo de la pantalla?
- ¿Manda voz (msgType 12) si se le pide?
