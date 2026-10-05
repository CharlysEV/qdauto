# headqlink frente a QDAuto: dónde estamos

4 de octubre de 2026. Se compara **headqlink v0.1-beta** (github.com/ryazrm/headqlink, commit `fe095fff`, publicada hoy) con **QDAuto** en su estado de hoy, con el Wi-Fi Direct todavía a medio implementar.

- Este documento no cambia nada del proyecto: solo compara y recomienda.
- Lo que headqlink afirma del coche sale de su código y de sus comentarios. **Nosotros no lo hemos visto**, así que hay que confirmarlo en el viaje.

**Rutas abreviadas** (todas desde ``):

| Prefijo | Qué es |
|---|---|
| `HQ/` | `ref/headqlink/app/src/main/java/com/headqlink/link/`: el código que han escrito ellos |
| `OH/` | `ref/headqlink/app/src/main/java/com/andrerinas/openheadunit/`: Open Headunit, que heredan |
| `HQR/` | `ref/headqlink/`: README, manifiesto y licencia |
| `C10L` | Su commit anterior, `520e15be` («C10Link», 3-oct 23:58). En sus comentarios figuran la fecha y la hora de las pruebas en el coche, que se borraron en la v0.1-beta. Se lee con `git show 520e15be:app/src/main/java/com/c10link/link/<fichero>` |
| `CORE/` | `qdauto/core/src/main/kotlin/dev/qdauto/core/` |
| `APP/` | `qdauto/app/src/main/java/dev/qdauto/app/` |
| `DOC/` | `docs/protocol/` |

---

## 1. Dónde estamos

**¿Vamos más avanzados en todo? No.** En lo que vas a ver en el coche, headqlink nos lleva bastante ventaja: ya tiene Android Auto funcionando en un C10 real. Nosotros vamos por delante en lo que no se ve: el protocolo, las pruebas, la robustez, la seguridad y la licencia.

- **Ellos** tienen Android Auto completo en el C10: imagen, multitáctil de hasta 3 dedos, audio y mandos del volante por el Bluetooth del coche, y todo con el móvil bloqueado y la pantalla apagada. En nuestro plan eso equivale a **las fases 1 y 2 hechas y parte de la 3**. Según su propio README les falta el rendimiento («WIP») y probarlo en marcha. Solo lo han probado con un móvil.
- **Nosotros** estamos en la **fase 0**: una app de pruebas que manda un patrón de vídeo. La hemos verificado contra carsim en el PC, pero **nunca en el coche**, y no tenemos **ni una línea de Android Auto**.
- **En qué somos mejores**:
  - especificación del protocolo byte a byte, verificada contra QDLink;
  - 140 tests y un simulador del coche (ellos no tienen ni un test de su código);
  - un protocolo más completo y más robusto;
  - más seguridad;
  - libertad para elegir licencia.
- **Qué ganamos con su código**:
  - responde buena parte de nuestras incógnitas sobre el C10 (§3);
  - nos enseña el camino de la fase 1 y los problemas reales de rendimiento (§5);
  - sus fallos (§4) nos sirven de lista de lo que no hay que repetir.

| | headqlink v0.1-beta | QDAuto hoy |
|---|---|---|
| ¿Funciona en el C10? | Sí, con un móvil (Honor BVL-N49, Snapdragon 8 Gen 3, Android 16). El código cita pruebas del 3-oct | Sin probar. Con el S25 Ultra contra carsim: 1920×1080 a 30 fps con la pantalla apagada, 20/20 |
| Android Auto | Funciona | Ni una línea (fase 1 sin empezar) |
| Código | 14.222 líneas propias (64 ficheros Java) encima de unas 214.000 de Open Headunit (han modificado 16 ficheros) | 17.552 líneas en Kotlin (core 5.785, app 7.967, carsim 3.800) |
| Tests | Ninguno de su código (los de `app/src/test` son todos de Open Headunit) | 140 (67 en core, 46 en app, 27 en carsim) y carsim con 20 comprobaciones |
| Documentación del protocolo | No han publicado nada | `DOC/04-wire-spec.md` (byte a byte) y `DOC/05-wifi-direct.md` |
| Licencia | AGPL-3.0, heredada y obligatoria | Libre mientras no copiemos código copyleft |
| Áreas que se ven en el coche (filas 1-10 de §2) | Van por delante en 6 | Vamos por delante en 1; en las otras 3, empate o reparto |

---

## 2. Comparativa por áreas

| # | Área | headqlink | QDAuto (nosotros) | Quién va por delante |
|---|---|---|---|---|
| 1 | Descubrimiento y Wi-Fi Direct | Se une solo al coche. Elige el primer dispositivo cuyo nombre empiece por «LeapMotor» (o que responda al UPnP de QDLink), entra como cliente y deja al coche como dueño del grupo. Al entrar, deja de buscar. Funciona en el C10. No tiene modo punto de acceso. El UDP acepta datagramas de cualquiera. | El modo punto de acceso está hecho y verificado con carsim. El Wi-Fi Direct está a medio implementar hoy y sin probar, pero con un diseño más completo: lista con pistas, plazos, cancelConnect y diagnóstico. El UDP se valida, se deduplica y se filtra por coche. | **Ellos**: lo suyo funciona en el coche |
| 2 | Protocolo y handshake | Lo mínimo que funciona: AppStatus, PHONE_INFO, UPDATE_NOTIFY, VIDEO_SUP_RSP, SPEECH_ARGS, LAND_MODE y heartbeat. Ignora BT_ADDR, PHONE_KEYS, Music y el heartbeat `!BIN`. El lector es frágil y los mensajes de control no tienen prioridad sobre el vídeo. | Todo el protocolo de QDLink en los dos sentidos, idéntico byte a byte y configurable. Lector que recupera la sincronía, un solo hilo que escribe y da prioridad al control, watchdog y detector de `write()` bloqueado. | **Nosotros**, en cobertura y robustez. Pero lo suyo ya ha hablado con un C10 |
| 3 | Vídeo y rendimiento | Mandan el vídeo de AA al coche por dos caminos: recodificándolo en el móvil (el camino por defecto: 20-60 fps y de 5 a 16 Mbps según el enlace) o reenviándolo tal cual (720p a 30 fps). Controlan la congestión midiendo la cola de envío del sistema, descartan los frames que llevan más de 150 ms esperando y usan un encoder de baja latencia (11,9 ms de mediana). Su README sigue marcando el rendimiento como «WIP». | Encoder propio con un patrón de prueba, verificado contra carsim. El encoder arranca de forma más robusta: lee lo que admite el móvil, calcula bien el nivel H.264 y tiene 3 configuraciones de reserva. En cambio, la cola reacciona tarde: solo descarta con 6 frames o 4 MiB en cola, más lo que retiene el sistema (256 KiB pedidos), y a 8 Mbps eso son unos 0,3-0,7 s de retraso. No medimos la red ni adaptamos el bitrate. | **Ellos**, claramente |
| 4 | Táctil | Pasan a AA hasta 3 dedos, con multitáctil real. Han comprobado en el coche que las coordenadas llegan en píxeles de su pantalla. | Leemos el mensaje con más rigor y lo dibujamos completo (todos los dedos y sus valores sin procesar), pero el toque no se manda a ningún sitio. Tenemos 5 hipótesis de coordenadas y ninguna confirmada. | **Ellos** |
| 5 | Teclas del volante | Play/pausa, siguiente y anterior llegan a AA por el Bluetooth del coche (AVRCP), gracias a código heredado de Open Headunit. Ignoran las teclas que el coche manda por el protocolo (PHONE_KEYS, Music). | Decodificamos PHONE_KEYS y Music, con tests, pero todavía no hay a quién mandarlas. | **Ellos**, en la práctica. Las teclas del protocolo, que ellos ignoran, nosotros ya las decodificamos |
| 6 | Pantalla apagada | Funciona con el móvil bloqueado y la pantalla apagada, y nunca mandan LOCK_SCREEN_STATUS. El problema: con AA 17.4 o posterior hay que desbloquear el móvil para arrancar AA. | Usamos la misma técnica: servicio en primer plano y bloqueos que mantienen despiertos el procesador y el Wi-Fi. Funciona a 1920×1080 y 30 fps con la pantalla apagada, pero solo lo hemos comprobado contra carsim. Mandamos LOCK_SCREEN_STATUS 3 por defecto y, por lo que han visto ellos, no hace falta. | **Empate** técnico, aunque ellos ya lo han probado en el coche |
| 7 | Android Auto | Pila de AA completa, la de Open Headunit. Arrancan AA de dos formas: si AA es anterior a la 17.4, directamente; si es la 17.4 o posterior, a través del servidor de desarrollador (puerto 5277), que encienden con el servicio de accesibilidad. Imagen ajustada a 1920×882, audio por el Bluetooth del coche, modo noche y recuperación de fallos. | Nada: la fase 1 no ha empezado. | **Ellos**, de lejos |
| 8 | Reconexión y arranque | No deshacen el grupo Wi-Fi Direct al cortar la sesión, y el coche vuelve a conectar en unos 5 s. La app arranca sola cuando se conecta el Bluetooth del coche. En cambio, si Android cierra el servicio, no vuelve a arrancar, y a los 30 s sin coche lo apagan todo. | Si Android cierra el servicio, lo vuelve a arrancar. Reintentamos sin límite, esperando cada vez más (de 1 a 30 s), y reabrimos la conexión si cambia la red. Pero deshacemos el grupo después de cada sesión, y hay que abrir la app y pulsar Iniciar. | **Reparto**: ellos arrancan sin tocar el móvil; nosotros aguantamos mejor |
| 9 | Diagnóstico y pruebas | No tienen ningún test. En cambio, miden muy bien el rendimiento: un CSV por sesión con el estado de la red cada 50 ms, congelaciones, escaneos Wi-Fi y temperatura, y un reloj para medir la latencia. Los logs se escriben en los mismos hilos del protocolo, no tienen límite de tamaño y solo se sacan por USB o adb. | 140 tests, carsim (20 comprobaciones) y p2pcar. El log se escribe en un hilo propio, rota y guarda en hexadecimal todo lo que no reconoce. «Exportar log» incluye un resumen. No medimos la red ni la radio. | **Reparto**: nosotros en pruebas y logs; ellos en medir el rendimiento |
| 10 | Extras de interfaz | Panel lateral con ruta, eficiencia, fotos, vídeos, web, TV, radio y juegos. Teclado en la pantalla del coche, asistente de configuración, perfiles de imagen según el móvil y ayudas para la batería en móviles de 10 fabricantes. | App de pruebas con diagnóstico y ajustes. En el coche, solo el patrón. | **Ellos** (con la pega de la fila 11) |
| 11 | Seguridad (en carretera y del móvil) | Le dicen al coche que todo se puede usar en marcha (también vídeo, TV o juegos), y a AA, que no hay restricciones de conducción. Su servicio queda abierto a otras apps sin pedir permiso. Mientras el servidor de AA está encendido, queda abierto a cualquiera de la red. | La lista blanca funciona como en QDLink. Nuestro servicio no está abierto a otras apps y no usamos accesibilidad. | **Nosotros** |
| 12 | Licencia | AGPL-3.0 obligatoria, y llevan dentro el certificado de Google. | Somos libres mientras no copiemos código copyleft. | **Nosotros** |

**Recuento.** En las 10 áreas que se notan en el coche (filas 1-10), ellos van por delante en 6, nosotros en 1 y las otras 3 quedan empatadas o repartidas. En seguridad y licencia (filas 11 y 12) vamos por delante nosotros.

**Evidencia** (headqlink · nosotros):

1. `HQ/P2pLink.java:23-25,43-50,56-66,102-116,126-134`; `HQ/UdpDiscovery.java:56-72` · `APP/settings/AppSettings.kt:35-41`; `APP/p2p/` (en desarrollo); `CORE/discovery/DiscoveryListener.kt:106-114,160-199`; `CORE/wire/UdpCodec.kt:57-90`.
2. `HQ/SspSession.java:251-282` (lector), `:310-343` (respuestas), `:400-426` (PHONE_INFO), `:474-497` (heartbeat, watchdog y escritura con un solo candado) · `CORE/wire/FrameReader.kt:41-46,71-144`; `CORE/session/SendQueue.kt:29-61,119-134`; `CORE/session/PhoneSession.kt:173-182,402-426`; `CORE/session/InboundHandler.kt:47-119`.
3. `HQ/VideoProfile.java:14-24,65-78`; `HQ/SspSession.java:54-67,553-609,813-845`; `HQ/NetStat.java` y `HQR/app/src/main/cpp/hql_netstat.c`; `HQR/README.md:27` · `APP/video/EncoderSetup.kt:51-201`; `APP/video/H264Levels.kt:36-47`; `CORE/session/SessionConfig.kt:76-82,164-172`; `CORE/session/SendQueue.kt:86-94`.
4. `HQ/AaPassthroughSource.java:426-508`; `HQ/SspSession.java:928-944`; `C10L AaPassthroughSource.java:342-346` · `CORE/wire/Touch.kt:80-117`; `APP/touch/TouchMapping.kt:7-91`.
5. `HQR/README.md:24`; `OH/aap/AapService.kt:1438-1502`; `HQ/SspSession.java:310-343` (no tiene PHONE_KEYS ni Music) · `CORE/wire/CarMessages.kt:148-170`; `CORE/session/InboundHandler.kt:104-108`.
6. `HQR/README.md:9,22`; `HQ/LinkService.java:379-388`; `HQ/AaServerStarter.java:24-25,72-77` · `APP/service/SystemLocks.kt:22-52`; `APP/settings/SettingsSchema.kt:163-166`.
7. `OH/aap/AapSslContext.kt:24-103`; `OH/connection/self/launchers/SelfLauncherV17_4.kt:90-135`; `OH/connection/self/launchers/SelfLauncherLegacy.kt:22-45`; `HQ/AaServerStarter.java:19-26`; `HQ/AaPassthroughSource.java:264-306`; `HQR/README.md:30-41` · `DOC/00-resumen.md:112-123` (solo el plan).
8. `HQ/LinkService.java:41-42,111-119,209-212,297-306`; `HQ/CarBtReceiver.java:30-69`; `HQ/P2pLink.java:151-162` (`stop()` no hace removeGroup) · `APP/service/LinkService.kt:61`; `APP/link/ReconnectBackoff.kt:3-6`; `APP/settings/SettingsSchema.kt:158,254`.
9. `HQR/app/src/test` (nada de headqlink); `HQ/PerfTrace.java:14-31`; `HQ/SspSession.java:349-378`; `HQ/LatencyActivity.java:17-21`; `HQ/L.java:68-87`; `HQ/LogActivity.java:18-51` · `qdauto/{core,app,carsim}/src/test` (140 `@Test`); `APP/log/LogFileWriter.kt:16-56`; `APP/log/LogExport.kt:15-74`.
10. `HQ/CarUi.java:36-99,528-586`; `HQ/CarKeyboard.java:10-13`; `HQ/SetupActivity.java:85-109`; `HQ/VideoProfile.java:121-182`; `HQ/PowerHelper.java:22-64` · `APP/video/TestPattern.kt`; `APP/ui/MainActivity.kt`.
11. `HQ/SspSession.java:457-460` (`int v = 1`); `HQ/CarUi.java:44,531`; `OH/aap/AapControl.kt:506-508`; `HQR/app/src/main/AndroidManifest.xml:529-531`; `HQ/Config.java:175-183` · `CORE/session/PhoneSession.kt:316-335`; `qdauto/app/src/main/AndroidManifest.xml:86-89`.
12. `HQR/LICENSE:1-2`; `HQR/COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`; `HQR/app/src/main/res/raw/cert` y `privkey` · `qdauto/` (sin código de terceros).

---

## 3. Lo que su código nos enseña del C10 real

Fiabilidad de cada dato:

- **Probado**: un comentario cita la fecha y la hora de una prueba en el coche.
- **Medido**: un comentario da una medida, pero sin fecha.
- **En uso**: es lo que hace su versión publicada, que funciona en el coche; si el coche no lo aceptara, no funcionaría.
- **Supuesto**: lo dan por hecho, pero no se ve ninguna prueba.

### 3.1 Conexión

| # | Dato del C10 | Fiabilidad | Evidencia | Qué cambia para nosotros |
|---|---|---|---|---|
| 1 | Funciona por **Wi-Fi Direct**, con el coche como dueño del grupo y el móvil como cliente (`groupOwnerIntent` 0). Nadie ha probado el modo punto de acceso en el C10. | En uso | `HQ/P2pLink.java:17-18,113-115,126-134`; `HQR/README.md:8` | Responde `DOC/05` §9 n.º 1 y 3. **En el viaje, lo primero es Wi-Fi Direct**; el punto de acceso queda en segundo lugar |
| 2 | El coche solo aparece con **la app de espejo abierta en su pantalla**. | En uso | `HQR/README.md:45,60` | Responde en parte `DOC/05` §9 n.º 2. Hay que abrirla antes de buscar |
| 3 | Su nombre de Wi-Fi Direct empieza por **«LeapMotor»** (con M mayúscula), y el de Bluetooth es **«Leapmotor_BT»**. | En uso | `HQ/P2pLink.java:24,43-50`; `HQ/Config.java:159-162` | Sirven para reconocer el coche sin distinguir mayúsculas y para arrancar al conectarse el Bluetooth. Hay que confirmar el nombre exacto |
| 4 | Manda el `Connect_Broadcast` por la conexión Wi-Fi Direct, y basta con escuchar en `0.0.0.0:18463`. Acepta un ACK con nombre y UUID rellenos, y también que se repita en cada broadcast. | En uso | `HQ/UdpDiscovery.java:35-45`; `HQ/LinkService.java:270-287`; `HQ/SspSession.java:184-199` | Responde en parte `DOC/05` §9 n.º 6 y `DOC/04` §11.2. Sigue sin saberse si el coche exige el puerto de origen 18463 |
| 5 | Si el móvil cierra la sesión **sin deshacer el grupo**, el coche vuelve a conectar en **unos 5 s**. | En uso | `HQ/LinkService.java:209-212`; `HQ/P2pLink.java:151-162` | Responde `DOC/05` §9 n.º 10. Como por defecto deshacemos el grupo (`p2p_keep_group=false`, `APP/settings/SettingsSchema.kt:158`), seguramente reconectamos más despacio: hay que probarlo con el grupo conservado |
| 6 | Buscar dispositivos con el grupo ya formado saca la radio del canal del coche y provoca **cortes de 100 a 350 ms**. Los **escaneos Wi-Fi del sistema** (cada unos 10 s con la pantalla encendida) también la sacan del canal. | Medido | `HQ/P2pLink.java:108`; `HQ/LinkService.java:62` | Confirma `DOC/05` §8.2 B.6: va mejor con la pantalla del móvil apagada |
| 7 | El enlace tiene **cortes de radio periódicos**. Si se dejaba de codificar con más de 24 KB pendientes de envío en el móvil, se perdía un tercio de los frames en cada corte. Con 64 KB (unos 2 frames), solo se perdían en los cortes de verdad. | Probado (3-oct 20:49) | `C10L SspSession.java:53-56`; `HQ/SspSession.java:54-59` | Es nuevo: no está ni en `DOC/04` ni en `DOC/05`. Es la base del control de congestión (§5.6) |

### 3.2 Sesión y vídeo

| # | Dato del C10 | Fiabilidad | Evidencia | Qué cambia para nosotros |
|---|---|---|---|---|
| 8 | El coche **no habla hasta recibir el AppStatus `!BIN`**: 512 B, idénticos a los nuestros. | En uso | `HQ/Proto.java:98-120` | Responde `DOC/04` §11.3. Ya lo hacemos (`CORE/wire/BinBlock.kt:80-121`) |
| 9 | **CAR_INFO = 1920×882**, no 1920×1080. | En uso | `HQ/AaPassthroughSource.java:33,47-52`; `HQ/H264SpsCrop.java:9-10`; `HQ/Config.java:301-315`. Su captura de la interfaz para el coche, `HQR/docs/car_screen.png`, mide 1920×882 | Responde `DOC/04` §11.5 y `DOC/00` §9. Nuestra app ya ajusta el vídeo a CAR_INFO, pero **carsim supone 1920×1080** (`CORE/sim/CarSimConfig.kt:18-19`). Con el S25 Ultra, QDLink mandaría Mirror ≈1912×882 e inCar 1920×882 (`CORE/session/MirrorGeometry.kt:37-58`) |
| 10 | **VIDEO_ARGS.FrameRate = 30**. | Probado | `C10L AaPassthroughSource.java:236` («AA a los fps que pide el coche (30)») | Responde en parte `DOC/04` §11.6. Siguen sin conocerse Width, Height, BitRate y FrameInterval |
| 11 | El coche admite variantes de PHONE_INFO: claves JSON en otro orden, todos los tamaños iguales a los del coche, nombre y UUID rellenos, una marca que no es Samsung y Version «1.9.7». | En uso | `HQ/SspSession.java:400-435` | Es un plan B por si nuestro PHONE_INFO, idéntico al de QDLink, fallara. El core ya admite forzar los tamaños (`CORE/session/SessionConfig.kt:28-34`), pero la app solo deja cambiar el nombre, el UUID y la versión (`APP/settings/SettingsSchema.kt:204-206`). Responde en parte `DOC/04` §11.14 |
| 12 | El coche manda algo al menos cada 10 s aunque no pase nada: si no, el watchdog de 10 s de headqlink cortaría las sesiones. | En uso | `HQ/SspSession.java:40,474-488` | Confirma, sin mucha fuerza, `DOC/04` §5.3. Nuestro corte a 15 s deja margen |
| 13 | **Por Wi-Fi no se rellena**: con relleno hasta un múltiplo de 512, «el coche pierde la sincronía y corta la sesión». | En uso | `HQ/Proto.java:73-76` | Confirma `DOC/04` §3.5 y §8.4. Conviene convertirlo en un test |
| 14 | Al empezar el vídeo, el coche **descarta el primer keyframe y pide otro** con KEY_FRAME_REQ. Si no recibe un IDR de verdad, **la pantalla se queda en negro**. | Probado (3-oct 20:46) | `C10L SspSession.java:47-49`; `HQ/SspSession.java:48-53` | Responde `DOC/04` §11.10 y **confirma nuestra decisión** de mandar un IDR real (`CORE/session/InboundHandler.kt:91-95`). QDLink solo reenvía el SPS/PPS |
| 15 | Funcionan la cabecera de vídeo in-app (16+32 B; appType 1, ángulo 90, orientación 1, repetición de VIDEO_ARGS) y el SPS/PPS en un mensaje aparte. | En uso | `HQ/Proto.java:60-96`; `HQ/SspSession.java:709-714,846-850` | Confirma `DOC/04` §8.1, §8.2 y §8.7: es lo que ya hacemos |
| 16 | El coche **no estira** la imagen: si la proporción no coincide, pone bandas. Respeta el recorte que indica el SPS: un vídeo de 1920×1080 recortado a 1920×882 se ve a pantalla completa. | Probado / En uso | `C10L AaPassthroughSource.java:232-234`; `HQ/H264SpsCrop.java:5-11` | Responde en parte `DOC/04` §11.7. El vídeo tiene que tener exactamente la proporción de CAR_INFO |
| 17 | El coche decodifica H.264 Baseline a 1920×882 y **60 fps**, de 5 a 16 Mbps, con intra-refresh y sin IDR periódicos. Pero al reenviar el vídeo de AA tal cual a 60 fps y 14 Mbps, «el coche acumula frames en los movimientos rápidos». | En uso / Probado (traza del 3-oct) | `HQ/VideoProfile.java:16-17,65-78`; `C10L AaPassthroughSource.java:236-237` | Responde en parte `DOC/04` §11.8. Empezar a 30 fps; 60 fps solo con control de congestión |
| 18 | Mandar WhitelistAppOn=1 cada segundo, empezando 1,5 s después de conectar, no le molesta al coche parado. | En uso | `HQ/SspSession.java:238-240,457-472` | No responde `DOC/04` §11.11: sigue sin saberse qué hace el coche en marcha con un 0 |
| 19 | El coche manda `Global/DarkModeOn` al pasar del día a la noche y al revés. El valor no está confirmado: suponen que 1 es noche. | Supuesto | `HQ/SspSession.java:388-394` | Registrar el valor en el viaje. Servirá para el modo noche de AA |

### 3.3 Táctil, teclas y pantalla

| # | Dato del C10 | Fiabilidad | Evidencia | Qué cambia para nosotros |
|---|---|---|---|---|
| 20 | Manda hasta **3 dedos** por mensaje, con una acción por dedo (1 abajo, 2 arriba, 3 mover) y 10 B por dedo, igual que nuestro formato. | En uso | `HQR/README.md:23`; `HQ/Proto.java:164-191`; `HQ/SspSession.java:936` | Responde `DOC/04` §11.9: el multitáctil es real |
| 21 | Las coordenadas llegan en **píxeles de la pantalla del coche** (el espacio de CAR_INFO), 1:1 desde la esquina superior izquierda del vídeo. Escalarlas según los márgenes desplazaba los toques hasta un 20 %. | Probado (3-oct) | `C10L AaPassthroughSource.java:342-346`; `HQ/AaPassthroughSource.java:33-35,465-474` | Responde `DOC/00` §9: son píxeles del coche. Nuestra hipótesis CAR_PX es la candidata, pero escala x e y por separado (`APP/touch/TouchMapping.kt:77-80`), y con bandas fallaría |
| 22 | La acción global seguiría el formato de MotionEvent: acción \| (índice << 8). | Supuesto (no la usan) | `HQ/Proto.java:172` | Sigue abierta (`DOC/04` §9.2). Si es así, el segundo dedo llegaría como 0x105/0x106, mientras que carsim manda 5/6 (`CORE/sim/CarSim.kt:421-422`) |
| 23 | Puede que el coche se reserve el borde izquierdo de la pantalla para un gesto propio. | Supuesto («puede») | `HQ/CarUi.java:81-84` | Comprobarlo, y mientras tanto no poner controles pegados a ese borde |
| 24 | Los mandos del volante van por **Bluetooth (AVRCP/HFP)**, no por el protocolo. | En uso | `HQR/README.md:24` | Confirma `DOC/04` §9.5 |
| 25 | Con el móvil **bloqueado y la pantalla apagada**, el coche sigue mostrando la imagen, aunque nunca le mandan LOCK_SCREEN_STATUS. | En uso | `HQR/README.md:9,22` | Confirma `DOC/04` §7.4. El LOCK_SCREEN_STATUS 3 que mandamos por defecto sobra (`APP/settings/SettingsSchema.kt:163-166`): probar con y sin él |

**Lo que su código NO demuestra:**

- Que el coche anuncie el servicio UPnP `QDLink_UPnP_Device`. Lo buscan, pero nada indica que lo hayan encontrado, y según nuestro análisis del código de QDLink, QDLink no lo usa (`DOC/05` §3.3). La pregunta n.º 8 de `DOC/05` §9 sigue abierta.
- Que un vídeo de 1280×588 se vea a pantalla completa mientras los toques siguen llegando en píxeles del coche. Lo suponen (`HQ/AaPassthroughSource.java:33-36`; `HQ/VideoProfile.java:36`).
- Si el coche toma el tamaño de la cabecera de vídeo o el del SPS: ellos siempre ponen el mismo en los dos.
- Qué hace el coche en marcha: solo lo han probado parado y con la marcha puesta (`HQR/README.md:28`).

---

## 4. Puntos débiles de headqlink

| # | Problema | Evidencia | Qué hacer para no repetirlo |
|---|---|---|---|
| **Graves** | | | |
| 1 | **Seguridad en carretera.** Mandan siempre WhitelistAppOn=1 (`int v = 1`), aunque se esté viendo vídeo, TV, web o juegos. Calculan qué tipo de contenido se ve, pero nunca lo usan. Además, Open Headunit le dice a AA que no hay restricciones de conducción (UNRESTRICTED). Así se anula cualquier restricción en marcha. | `HQ/SspSession.java:457-460`; `HQ/CarUi.java:44,531`; `OH/aap/AapControl.kt:506-508` | Mantener nuestro modo AUTO (`CORE/session/PhoneSession.kt:316-335`) y darle a AA el estado de conducción real |
| 2 | **Servicio abierto a otras apps.** Su servicio principal no pide permiso, así que cualquier app del móvil puede cambiar sus ajustes (por ejemplo, dejar encendido el servidor de AA) o lanzar la automatización de accesibilidad. | `HQR/app/src/main/AndroidManifest.xml:529-531`; `HQ/LinkService.java:143-149`; `HQ/Config.java:88-113` | El nuestro no está abierto a otras apps (`qdauto/app/src/main/AndroidManifest.xml:86-89`), y hay que mantenerlo así |
| 3 | **Puertos abiertos a la red.** Mientras está encendido, el servidor de AA escucha en todas las redes del móvil, y lo dicen ellos mismos; con AA anterior a la 17.4, también el puerto 5288 de Open Headunit. Cualquiera de la misma red podría conectarse haciéndose pasar por el coche. Además, el UDP acepta datagramas de cualquiera: en una Wi-Fi compartida, cualquiera podría recibir la imagen. | `HQ/Config.java:175-183`; `HQ/AaGuardService.java:27-36`; `OH/connection/wifi/server/WirelessServer.kt:103-106`; `HQ/UdpDiscovery.java:56-72` | Apagar el servidor al terminar y mantener ocupada su única conexión mientras esté encendido. Validar y filtrar el UDP por coche, como ya hacemos |
| 4 | **Con AA 17.4 o posterior, no arranca si el móvil está bloqueado.** Para arrancar el servidor hay que desbloquear, y por defecto lo apagan al final de cada sesión. Si la app arranca sola por Bluetooth con el móvil en el bolsillo, el coche se queda en «Arrancando Auto…», y el error solo aparece en el móvil. | `HQ/AaServerStarter.java:24-25,72-77`; `HQ/Config.java:175-183` | Avisar en la pantalla del coche («Desbloquea el móvil») y decidir de forma consciente si se deja el servidor encendido |
| 5 | **Automatización frágil.** Buscan el menú de AA por su texto, y solo en español e inglés: cualquier cambio de Google o de idioma lo rompe. Además, Android desactiva la accesibilidad cada vez que se actualiza la app. Su README dice que con AA anterior a la 17.4 no hace falta la accesibilidad, pero el botón «Conectar» la exige siempre. | `HQ/AaServerStarter.java:281,321-333`; `HQ/LinkService.java:139-141`; `HQ/HomeActivity.java:98-107` frente a `HQR/README.md:35,39` | Si lo automatizamos, buscar los elementos del menú por su identificador interno y no por el texto, para que funcione en cualquier idioma |
| 6 | **Certificado de Google dentro de la app.** Para conectar con AA usan el certificado y la clave «Google-Android-Reference» (emisor «Google Automotive Link») que trae Open Headunit. La AGPL no lo cubre, Google puede bloquearlo y es legalmente delicado. | `HQR/app/src/main/res/raw/cert` y `privkey`; `OH/ssl/SingleKeyKeyManager.kt:86-101` | Es la primera incógnita de nuestra fase 1 (§5.5) |
| **Robustez** | | | |
| 7 | **Lector frágil.** Si una cabecera no empieza por 5A5A, se salta 496 B a ciegas, sin recuperar la sincronía. Un solo byte suelto desincroniza la sesión para siempre. | `HQ/SspSession.java:251-282` | Nuestro FrameReader recupera la sincronía (`CORE/wire/FrameReader.kt:41-46,71-144`) |
| 8 | **El control no tiene prioridad.** Los mensajes de control y el vídeo se escriben desde varios hilos que se turnan para escribir, y el watchdog va dentro del heartbeat. Si la radio deja bloqueada una escritura, se quedan sin heartbeat y sin watchdog. | `HQ/SspSession.java:474-497` | Lo nuestro ya lo resuelve: un solo hilo escribe, el control va primero y vigilamos las escrituras bloqueadas (`CORE/session/SendQueue.kt:29-61`; `CORE/session/PhoneSession.kt:173-182`) |
| 9 | **Encoder.** Si falla al arrancar, la sesión se queda sin vídeo para siempre, porque solo capturan IOException. Además, para 1920×882 a 30 fps o menos eligen un nivel H.264 (3.2 o inferior) demasiado bajo para ese tamaño; con un encoder estricto, eso acaba en el fallo anterior. | `HQ/SspSession.java:720-749`; `HQ/VideoEncoder.java:326-333` | Mantener nuestras configuraciones de reserva y la tabla de niveles completa (`APP/video/EncoderSetup.kt:51-201`; `APP/video/H264Levels.kt:36-47`) |
| **Vídeo, Wi-Fi Direct y batería** | | | |
| 10 | **Reenviar el vídeo de AA tal cual.** No se descarta ningún frame y la cola no tiene límite. Para pedirle un keyframe a AA sueltan la imagen y la vuelven a pedir: se congela 0,7-0,9 s, y Open Headunit avisa de que así se puede perder la imagen para siempre (por su cuenta, AA solo manda un keyframe cada unos 69 s). Su freno, no tener más de 2 frames sin confirmar, depende de que AA lo respete, y Open Headunit ha medido que no: con un límite de 12, llegó a acumular 120. | `HQ/SspSession.java:524,775-806`; `HQ/AaPassthroughSource.java:38-42`; `OH/connection/CommManager.kt:893-897,913-917`; `OH/aap/AapControl.kt:153-156` | En la fase 2, recodificar debe ser el camino principal (§5.6) |
| 11 | **Wi-Fi Direct.** Se une al **primer** dispositivo cuyo nombre empiece por «LeapMotor»: en un aparcamiento con varios C10 puede unirse al de otro. El reintento cada 15 s puede pisar una conexión a medias, no hay tiempo límite ni cancelConnect, y nunca deshacen el grupo. | `HQ/P2pLink.java:43-50,56-66,126-134,151-162` | Pedir confirmación si hay varios coches y usar plazos y cancelConnect, como en `DOC/05` §8.2 |
| 12 | **Batería y calor.** En el modo por defecto recodifican a 60 fps en el móvil y, aunque solo se vea AA, mantienen el GPS a 5 lecturas por segundo, el giroscopio y consultas a internet cada 3-5 s para el panel. | `HQ/Config.java:340-342`; `HQ/VideoProfile.java:65-78`; `HQ/CarSensors.java:160-171`; `HQ/CarUi.java:290-293` | Encender los sensores solo cuando una pantalla los use, y vigilar la temperatura |
| 13 | **Ciclo de vida.** Si Android cierra el servicio, no vuelve a arrancar. A los 30 s sin coche apagan también AA, y si AA se cae a mitad de sesión, nada lo vuelve a lanzar. | `HQ/LinkService.java:111-119,297-306`; `OH/decoder/video/HeadlessDriver.kt:23-37` | Que Android pueda volver a arrancar nuestro servicio, como ahora, y añadir que se vuelva a lanzar AA si se cae |
| **Táctil y calidad** | | | |
| 14 | **Táctil (posible fallo).** De cada mensaje solo aplican el primer dedo que cambia: si el coche manda dos cambios a la vez, un dedo puede quedarse «pegado» en AA. | `HQ/AaPassthroughSource.java:446,476-498` | Mandar a AA un evento por cada dedo que cambie |
| 15 | **Calidad.** No hay ni un test en 14.222 líneas, lo han probado con un solo móvil y los umbrales están ajustados a mano. Los logs se escriben en los hilos del protocolo y no tienen límite de tamaño. Han modificado 16 ficheros de Open Headunit, lo que hace difícil seguir sus actualizaciones. | `HQR/app/src/test`; `HQ/L.java:68-87`; `HQ/CarTrace.java:136-155`; `HQR/README.md:46-47` | Seguir con tests y carsim, y con el log en su propio hilo y con rotación (`APP/log/LogFileWriter.kt`) |

---

## 5. Qué hacemos ahora

### 5.1 Antes de nada: ¿seguimos con QDAuto?

- Si lo único que quieres es Android Auto en tu C10 cuanto antes, **headqlink puede que ya te lo dé**, con las pegas de §4:
  - hay que activar el modo desarrollador de AA y darle permiso de accesibilidad;
  - el servidor de AA queda abierto a la red mientras está encendido;
  - la lista blanca va siempre a 1;
  - solo lo han probado con un Honor.
- Seguir con QDAuto tiene sentido si quieres algo **más robusto, más seguro, que puedas mantener y con la licencia que tú elijas**. Lo que ya tenemos sirve de base para eso.
- **Opcional**: instalar su APK una vez en el coche, como referencia, y desinstalarlo después (apagando el servidor de AA y el modo desarrollador). Su diario `CarTrace` guarda todo lo que manda el coche, incluidos CAR_INFO y VIDEO_ARGS.

### 5.2 En el viaje al coche (con nuestra app de pruebas)

**Lo primero es Wi-Fi Direct**: según headqlink, es el modo del C10, y nadie ha probado el punto de acceso. Si vas con la versión instalada, que solo tiene punto de acceso, puede que no llegue a conectar. Aun así, la prueba con QDLink y sus logs (`DOC/04` §11, «Cómo cerrarlas en el viaje») sigue sirviendo para ver CAR_INFO y VIDEO_ARGS.

Antes de empezar: abre la app de espejo en la pantalla del coche y apaga el punto de acceso del móvil.

| Qué comprobar | Dónde verlo | Dato de §3 |
|---|---|---|
| El modo, el nombre del coche (¿«LeapMotor…»?), si el coche es el dueño del grupo y si solo aparece con la app de espejo abierta | Log `p2p` | n.º 1-3 |
| CAR_INFO y VIDEO_ARGS completos: ¿1920×882? ¿FrameRate 30? Width, Height, BitRate y FrameInterval | Log de la sesión | n.º 9-10 |
| Que el encoder salga a 1920×882 exactos. Nuestro `fitSize` redondea el tamaño hacia arriba según lo que exija el encoder (`APP/video/EncoderSetup.kt:226-238`): si exigiera múltiplos de 16, saldría 1920×896 y habría bandas | Línea «alineación» del log del encoder | n.º 16 |
| El arranque del vídeo: tiempos entre VIDEO_CTRL, KEY_FRAME_REQ y el IDR, y que la pantalla no se quede en negro | Log | n.º 14 |
| Táctil: las cuatro esquinas y el centro del patrón; 2 y 3 dedos; el valor de la acción global (¿0x105/0x106?); el borde izquierdo | TouchView y log | n.º 20-23 |
| Reconexión: cerrar la sesión con `p2p_keep_group` activado y desactivado, y medir cuánto tarda en volver | Log | n.º 5 |
| Tirones con la pantalla del móvil encendida y apagada (escaneos Wi-Fi) | Contadores y traza por frame | n.º 6-7 |
| Una sesión con `report_unlocked` desactivado (sin LOCK_SCREEN_STATUS) | ¿Cambia algo? | n.º 25 |
| Valor de DarkModeOn al encender y apagar las luces | Log | n.º 19 |
| Con alguien al volante, qué hace el coche en marcha con la lista blanca (dejarla en AUTO) | Pantalla del coche | n.º 18 |

### 5.3 Cambios baratos que justifica su código (no se ha aplicado ninguno)

- **carsim**:
  - añadir un perfil «C10» con CAR_INFO 1920×882 y FrameRate 30;
  - simular el KEY_FRAME_REQ justo después del primer IDR y exigir otro IDR;
  - comprobar que no se rellena.
- **Documentación**:
  - pasar los datos de §3 a `DOC/04` §11 y `DOC/05` §9, marcados como «visto por headqlink, por confirmar»;
  - añadir la fila 1920×882 a `DOC/04` §8.6.
- **Corregir `DOC/00-resumen.md` §8**:
  - punto 2: la accesibilidad no hace falta para el táctil, pero sí para arrancar y parar el servidor de AA si queremos que sea automático;
  - punto 3: AA no admite una resolución de 1920×882, así que hay que pedirle 1920×1080 con márgenes y recortar, o recodificar.
- **PHONE_INFO**: sacar a los ajustes de la app los tamaños que el core ya permite forzar, para poder aplicar en el coche el plan B de §3 n.º 11 sin recompilar.
- **Wi-Fi Direct** (se está implementando ahora):
  - tomar un nombre que empiece por «LeapMotor», sin distinguir mayúsculas, como pista fuerte, y pedir confirmación si hay varios;
  - no buscar dispositivos con el grupo ya formado;
  - después del viaje, decidir el valor por defecto de `p2p_keep_group`.
- **Arrancar sin tocar el móvil**: arrancar el servicio al conectarse el Bluetooth del coche («Leapmotor_BT», por confirmar), con una notificación «Toca para conectar» si Android no deja arrancarlo. Valorar CompanionDeviceManager, la función de Android que avisa a la app cuando aparece un dispositivo asociado.
- **Medidas para el viaje**:
  - un CSV por sesión con el tamaño de cada frame, el tiempo que espera en la cola y lo que tarda en enviarse;
  - un detector de congelaciones de más de 150 ms;
  - eventos de pantalla, escaneos Wi-Fi y temperatura;
  - los contadores de la interfaz Wi-Fi Direct (`p2p0`).

  Opcional: medir también lo que el sistema tiene pendiente de enviar y el estado de la conexión TCP. Para eso hace falta un poco de código nativo (JNI).

### 5.4 Licencia: ¿reutilizar su código o escribir el nuestro?

Hay que decidirlo **antes de escribir la primera línea de la fase 1**: lo que se copie después ya no se puede «des-copiar». Lo que sí podemos usar libremente son los datos de este documento sobre el coche y el protocolo: los hechos no tienen derechos de autor; el código, sí.

| Opción | Rapidez | Qué implica |
|---|---|---|
| **A. Capa de AA propia**, escrita desde cero a partir de cómo funciona el protocolo, sin copiar ni adaptar su código ni sus `.proto` | La más lenta | QDAuto sigue siendo 100 % nuestro y podemos elegir la licencia. Las implementaciones libres de AA que conocemos tienen todas licencias que obligan a compartir el código (Open Headunit, AGPL; aasdk y openauto, GPL-3.0), así que no hay atajo |
| **B. Reutilizar Open Headunit** (o headqlink) | La más rápida: su pila de AA está madura | QDAuto pasaría a ser una obra derivada bajo AGPL-3.0. Si se distribuye el APK a cualquiera (gratis, a un amigo o en GitHub), hay que entregarle el código fuente completo de QDAuto bajo AGPL-3.0. Además arrastra unas 214.000 líneas |
| **C. Como B, pero solo para uso personal**, sin distribuir | Rápida | La AGPL no obliga a nada mientras no se distribuya. Si un día se publica, pasa a ser B |

- En las tres opciones, el certificado de Google es un riesgo aparte que la AGPL no cubre.
- Recomendación: si QDAuto es para tu coche, lo práctico es C, que siempre se puede convertir en B. Si quieres publicarlo con otra licencia, la opción es A.

### 5.5 Fase 1 (Android Auto): cómo plantearla

- **Primera prueba técnica: el certificado.** Para hablar con AA hay que presentarle un certificado de head unit. Open Headunit, y con él headqlink, usa el de referencia de Google (§4 n.º 6). Hay que comprobar cuanto antes si el servidor de desarrollador de AA (puerto 5277) acepta uno propio. Si exige uno firmado por Google, solo queda usar ese, sabiendo el riesgo.
- **Contar con AA 17.4 o posterior** (hay que comprobar la versión en el S25):
  - hay que activar una vez el modo desarrollador de AA, y el servidor de head unit solo se arranca con el móvil desbloqueado;
  - se puede arrancar a mano y dejarlo encendido, con el riesgo de §4 n.º 3, o automatizarlo con accesibilidad, buscando el menú por su identificador interno;
  - si hace falta desbloquear, avisarlo en la pantalla del coche: hasta que arranca AA, esa pantalla la controlamos nosotros.
- **Reglas con el servidor de AA**, aprendidas por Open Headunit (`OH/connection/CommManager.kt:227-236`; `HQ/AaGuardService.java:27-36`):
  - solo atiende una conexión, y una conexión aceptada y abandonada lo deja «sordo», así que nada de conexiones de prueba;
  - si se queda sordo, reiniciarlo, y si no basta, forzar la detención de AA;
  - apagarlo desde el botón de su notificación.
- **Imagen para el C10** (`HQ/AaPassthroughSource.java:264-306`): pedirle a AA 1920×1080 con 198 px de margen vertical (o 1280×720 con 132), unos 200 dpi y H.264 Baseline. Después, pedirle que coloque su interfaz arriba a la izquierda (UpdateUiConfigRequest).
- **Táctil**: mandar a AA todos los dedos apoyados y una acción por cada dedo que cambie, evitando su fallo del dedo pegado.
- **Audio**: dentro del móvil, decirle a AA que solo hay canal de sistema y micrófono, como hacen ellos. Así la música y el navegador suenan por el Bluetooth del coche (`OH/aap/protocol/messages/ServiceDiscoveryResponse.kt:163-243`).
- **Lo que ellos no hacen y nosotros podemos hacer**:
  - pasar a AA las teclas PHONE_KEYS y Music del coche, que ya decodificamos;
  - usar DarkModeOn para el modo noche de AA;
  - darle a AA el estado de conducción real;
  - volver a lanzar AA si se cae con el coche conectado;
  - mantener AA vivo unos 30 s si se va el coche, para reconectar rápido.

### 5.6 Fase 2 (rendimiento): la estrategia

- **El camino principal es recodificar** (lo que ellos llaman «último frame»): la imagen de AA se recorta en el móvil y nuestro encoder la codifica solo cuando el enlace tiene sitio. El reenvío tal cual no sirve como camino principal, porque no se puede pedir un keyframe sin congelar la imagen unos 0,7 s ni se pueden descartar frames. Como modo de bajo consumo sí puede servir, frenando a AA con las confirmaciones de frames (podremos hacerlo porque seremos su head unit), sabiendo que AA no siempre respeta ese freno (§4 n.º 10).
- **Control de congestión en el móvil**:
  - medir lo que el sistema tiene pendiente de enviar y el estado de la conexión TCP;
  - no codificar con más de unos 64 KB pendientes;
  - bitrate adaptable: un 20 % menos si hay atasco y 1 Mbps más tras 2 s estables;
  - descartar los frames por antigüedad (más de 150 ms), no solo por número;
  - bajar `videoBacklogFrames` de 6 a 1-2 y probar un buffer de envío de 64-192 KiB (`CORE/session/SessionConfig.kt:82,165-167`).
- **Encoder**: probar las opciones de baja latencia de Android y las del fabricante (Qualcomm, en el S25), y el intra-refresh con IDR solo cuando se pida. Mantener la cadencia de keyframes de QDLink como alternativa.
- **fps**: primero 30, que es lo que pide el coche; 60 solo si las medidas lo permiten.
- **Medir**:
  - cuánto tarda el encoder en cada frame;
  - la latencia de punta a punta: un reloj en ms en el patrón y otro en el móvil, y grabar los dos a la vez.
- **Opcional**: marcar el tráfico como vídeo (DSCP CS5, `setTrafficClass(0xA0)`) para que el Wi-Fi le dé prioridad.
