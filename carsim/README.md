# `:carsim`: coche QDLink simulado

CLI que hace de **unidad del coche** (Leapmotor C10 con QDLink) desde el PC, para probar la app QDAuto de punta a
punta por la Wi-Fi de casa antes de bajar al coche. Usa el simulador `CarSim` de `:core`.

Qué hace, en orden:

1. Anuncia el coche: `Connect_Broadcast` por UDP al puerto **18463** cada segundo.
2. Espera el `Broadcast_ACK` del móvil en el UDP **18464** y lee el `MirrorPort`.
3. Se conecta por TCP a `IP_del_móvil:MirrorPort` (el móvil escucha y el PC conecta).
4. Hace el handshake del coche: `CAR_INFO` → `VIDEO_SUP_REQ` → `VIDEO_ARGS` → `VIDEO_CTRL{PlayStatus:1}`, esperando
   cada respuesta (`PHONE_INFO` + `UPDATE_NOTIFY`, `VIDEO_SUP_RSP`, `SPEECH_ARGS`).
5. Recibe y valida el vídeo: cabeceras de 16 + 32 bytes, Annex-B, orden SPS/PPS → IDR → P, fps, huecos e IDR.
   Lee el SPS y lo compara con la cabecera.
6. Con el primer frame de vídeo arranca el **guion táctil**: toques, arrastre y dos dedos (o el que se le dé).
7. Reproduce las **manías del C10** vistas en el coche (apartado 8): se cuelga con mensajes de vídeo de más de
   512 KiB y vigila los SPS/PPS repetidos (el coche reinicia el decodificador con cada uno). Con `--decode`, pasa
   el vídeo recibido por ffmpeg.
8. Al acabar la duración (o con Ctrl+C) saca un **informe** con todas las comprobaciones (PASS/WARN/FAIL) y termina
   con código 0 si ninguna falla.

---

## 1. Compilar e instalar

Desde Git Bash:

```bash
cd qdauto
export JAVA_HOME=/d/Android/jdk
./gradlew --console=plain :carsim:installDist
```

Desde PowerShell:

```powershell
cd qdauto
$env:JAVA_HOME = "D:\Android\jdk"
.\gradlew.bat :carsim:installDist
```

Queda instalado en `carsim\build\install\carsim` (`bin\carsim.bat` y `lib\*.jar`). El `.bat` necesita `JAVA_HOME` (o
`java` en el `PATH`).

## 2. Ejecutar

Desde `cmd` o PowerShell, en la carpeta `qdauto`:

```powershell
$env:JAVA_HOME = "D:\Android\jdk"
carsim\build\install\carsim\bin\carsim.bat --self-test
carsim\build\install\carsim\bin\carsim.bat --duration 60 --out recibido.h264 --report informe.json
```

En `cmd` es igual, con `set JAVA_HOME=D:\Android\jdk`. Desde Git Bash vale `carsim/build/install/carsim/bin/carsim`.

### Prueba en casa, paso a paso

1. PC (192.168.1.26/24) y móvil en **la misma Wi-Fi**, y que no sea una red de invitados ni tenga aislamiento de
   clientes.
2. En el móvil, **QDLink cerrado del todo** (Ajustes → Aplicaciones → QDLink → Forzar detención): si no, también
   escucha en el 18463 y contesta a los broadcasts. Abrir la app QDAuto.
3. Cortafuegos de Windows: dejar entrar el **UDP 18464** a `java.exe` (ver el apartado 6). Solo hace falta una vez.
4. `carsim.bat --self-test`: comprueba el lado del PC sin el móvil. Tiene que salir `RESULTADO: PASS`.
5. `carsim.bat --duration 60 --out recibido.h264 --report informe.json` y mirar el móvil: tienen que verse los
   toques en las posiciones del guion.

## 3. Opciones

| Opción | Por defecto | Qué hace |
|---|---|---|
| `--target <ip\|broadcast>` | `broadcast` | Destino del `Connect_Broadcast`. `broadcast` = 255.255.255.255 y el broadcast de subred de cada interfaz IPv4 activa (en Windows el limitado sale por una sola interfaz, que puede ser la de Hyper-V o la VPN). Con la IP del móvil se le manda directamente (si el router filtra los broadcasts). Se puede repetir o separar por comas |
| `--discovery-timeout <s>` | 120 | Espera máxima del ACK |
| `--width <px>`, `--height <px>` | 1920 x 1080 | `CarWidth`/`CarHeight` de `CAR_INFO` (la suposición del C10 de `:core`) |
| `--car-type <código>` | `2D4` | `CarType` (2D4 = C10 con volante a la izquierda, 2D5 a la derecha) |
| `--fps <n>` | 30 | `VIDEO_ARGS.FrameRate` |
| `--bitrate <bps>` | 4000000 | `VIDEO_ARGS.BitRate` en bit/s; admite `k` y `M` (`4M`, `800k`) |
| `--gop <s>` | 1 | `VIDEO_ARGS.FrameInterval` (segundos entre IDR para Android) |
| `--duration <s>` | 30 (8 en el autotest) | Segundos de vídeo a observar tras el handshake. `0` = hasta Ctrl+C. Si el guion táctil dura más, se espera a que acabe |
| `--touch-script <guion>` | el de abajo | Un fichero, el guion escrito en la propia opción, `default` o `none` |
| `--out <fichero.h264>` | - | Guarda el vídeo recibido en Annex-B (`ffplay -f h264 recibido.h264`) |
| `--report <fichero.json>` | - | Guarda el informe completo en JSON |
| `--verbose` | - | Cada mensaje que entra y sale (vídeo incluido) y los logs internos de `:core` |
| `--self-test` | - | Autotest en local (apartado 5) |
| `--help` | - | Ayuda |
| `--limit <KiB>` | 512 | Límite del receptor del coche: con un mensaje de vídeo mayor, el coche simulado se cuelga como el C10 (apartado 8). `0` o `--no-limit` = no colgarse |
| `--hang <s>` | 10 | Segundos sin leer antes de cerrar cuando se cuelga |
| `--no-sps-check` | - | No evaluar `sps_repetido` |
| `--no-quirks` | - | `--no-limit` y `--no-sps-check` |
| `--decode` | - | Al acabar, pasa el vídeo recibido por ffmpeg; comprobación `decodifica` |
| `--ffmpeg <ruta>` | `tools\ffmpeg\...\ffmpeg.exe` o el del `PATH` | Ejecutable de ffmpeg (implica `--decode`) |

Códigos de salida: **0** ninguna comprobación FAIL (los WARN no cuentan), **1** alguna comprobación FAIL, **2** opciones
incorrectas, **3** error al arrancar (p. ej. el UDP 18464 ocupado por otro carsim).

## 4. Guion táctil

Órdenes separadas por líneas o por `;`, y `#` para comentarios. Las coordenadas van en px del coche o en % de su
ancho/alto (las distancias del pellizco, en % del ancho). También valen los nombres en inglés (`wait`, `tap`, `drag`,
`two`, `pinch`, `key`, `music`, `idr`) y da igual poner tildes.

| Orden | Qué manda |
|---|---|
| `espera <ms>` | nada (pausa) |
| `toque <x> <y> [ms]` | un dedo: down, espera (50 ms) y up |
| `arrastre <x0> <y0> <x1> <y1> [ms] [pasos]` | un dedo: down, `pasos` moves repartidos en `ms` (300 ms, 10) y up |
| `dos <x0> <y0> <x1> <y1> [ms]` | dos dedos a la vez: DOWN, POINTER_DOWN, espera (500 ms), POINTER_UP y UP |
| `pellizco <cx> <cy> <d0> <d1> [ms] [pasos]` | dos dedos en horizontal que pasan de estar a `d0` a estar a `d1` |
| `tecla inicio\|atras\|recientes\|<código>` | `PHONE_KEYS` 1, 2, 3 (o el código dado) |
| `musica play\|pausa\|playpausa\|siguiente\|anterior\|silencio` | msgType 13 `Music/…` |
| `keyframe` | `KEY_FRAME_REQ` |

El guion empieza con el primer frame de vídeo (o 5 s después de `VIDEO_CTRL` si no llega vídeo). El de por defecto:

```
espera 3000; toque 25% 25%; espera 700; toque 75% 75%; espera 700; toque 50% 50%; espera 700;
arrastre 10% 50% 90% 50% 800 20; espera 700; dos 35% 50% 65% 50% 600
```

Con 1920x1080 los toques caen en (480, 270), (1440, 810) y (960, 540). Los códigos de `action` de dos dedos (5 y 6, los
de `MotionEvent`) son una suposición (spec §9.2): QDLink solo distingue 0 (empieza) y 1 (termina).

## 5. Autotest (`--self-test`)

Monta en el mismo proceso un **teléfono simulado** con las piezas de `:core` que usará la app (`DiscoveryListener`,
`MirrorServer` y `PhoneSession`) y un encoder falso: SPS/PPS válidos (Baseline, como QDLink) y frames IDR/P falsos
(no se pueden decodificar) a los fps, bitrate y GOP de `VIDEO_ARGS`. Todo va por 127.0.0.1 y en puertos libres, así
que no hace falta ni el móvil ni el cortafuegos. Pasa por el mismo camino que una prueba real y añade:

- `autotest_tactil`: el teléfono recibe exactamente los táctiles enviados (acción, dedos y x/y bit a bit).
- `autotest_teclas`: llegan `PHONE_KEYS` y `Music` del guion.
- `autotest_keyframe`: `KEY_FRAME_REQ` hace que el teléfono reenvíe SPS/PPS y pida un IDR.
- `autotest_hilos`: al acabar no queda vivo ningún hilo del coche ni del teléfono.

Tiene que salir `RESULTADO: PASS` con código 0. Los tests de Gradle (`:carsim:test`) incluyen una versión corta y otras
con fallos a propósito que tienen que dar FAIL: la cabecera de vídeo mal, un IDR de 600 KiB (el coche se cuelga),
SPS/PPS repetidos delante de un P-frame. `--decode` no se evalúa en el autotest (los frames son falsos).

## 6. Cortafuegos de Windows

El móvil contesta con un **UDP entrante al puerto 18464** del PC. Lo demás no necesita reglas: los broadcasts
salen del PC y la conexión TCP la abre el PC hacia el móvil.

La primera vez que se ejecuta, Windows puede mostrar la "Alerta de seguridad de Windows" para "OpenJDK Platform
binary": marcar **Redes privadas** y permitir. Si no salió o se rechazó, crear la regla a mano en un PowerShell
**como administrador**:

```powershell
New-NetFirewallRule -DisplayName "carsim UDP 18464 entrante" -Direction Inbound -Protocol UDP -LocalPort 18464 `
    -Program "D:\Android\jdk\bin\java.exe" -Action Allow -Profile Private
```

- La Wi-Fi de casa tiene que estar como red **privada**: `Get-NetConnectionProfile` y, si hace falta,
  `Set-NetConnectionProfile -InterfaceAlias "Wi-Fi" -NetworkCategory Private`.
- Si el `.bat` usa otro Java (otro `JAVA_HOME`), la regla tiene que apuntar a ese `java.exe`.
- Para quitarla: `Remove-NetFirewallRule -DisplayName "carsim UDP 18464 entrante"`.

## 7. Salida e informe

Mientras corre, carsim escribe cada hito con su hora desde el arranque: el ACK, la conexión, cada mensaje del
handshake con su JSON, el primer SPS/PPS, IDR y P, el SPS decodificado, cada paso del guion y cada JSON distinto que
mande el teléfono. Además saca una línea de estado con el estado, fps y kbit/s recibidos, frames, IDR, errores y
toques enviados. En consola se reescribe cada segundo; si la salida va a un fichero, se escribe cada 5 s.

El informe final tiene:

- **Cronología** del handshake, con la hora de cada paso y el tiempo desde el anterior.
- **Mensajes del teléfono al coche**: cada JSON distinto, por orden de llegada, con cuántas veces llegó y cada cuánto
  (los heartbeats, por ejemplo). El JSON tiene la lista completa en orden.
- **Vídeo**: mensajes (SPS/PPS, IDR, P), bytes, fps medio y mínimo/máximo por segundo, bitrate medio y máximo, hueco
  máximo entre frames, intervalos entre IDR (en ms y en frames), cabeceras de 32 bytes distintas y errores de
  validación agrupados. Además, el mensaje más grande y cuántos pasan de 480 KiB, si el coche se colgó, cada
  SPS/PPS con su contexto (cuánto tardó, si había `KEY_FRAME_REQ` pendiente, qué vino detrás, si es idéntico al
  anterior) y, con `--decode`, lo que dijo ffmpeg.
- **SPS**: perfil, constraint flags, nivel, tamaño con el recorte aplicado (y el codificado), croma, POC, referencias,
  fps de la VUI y reordenación, con su hex. Indica si coincide con las cabeceras de 32 bytes que lo acompañan.
- **Guion táctil**: cada paso con su resultado y las coordenadas reales.
- **Comprobaciones**:

| Id | Qué comprueba |
|---|---|
| `descubrimiento` | Llega un `Broadcast_ACK` |
| `ack_formato` | El ACK es idéntico al de QDLink (mismo JSON y orden de claves) |
| `tcp` | El coche se conecta al `MirrorPort` |
| `appstatus` | Llega un único AppStatus `!BIN` de 512 B idéntico al de QDLink |
| `handshake` | Llegan `PHONE_INFO`, `UPDATE_NOTIFY`, `VIDEO_SUP_RSP` y `SPEECH_ARGS`, con su tiempo de respuesta |
| `orden` | En ese orden, con el AppStatus delante |
| `phone_info` | Eco de `CarWidth`/`CarHeight` en los `*InApp` y `MirrorWidth`/`MirrorHeight` según la geometría de QDLink |
| `heartbeat` | `HEARTBEAT` del teléfono sin huecos de más de 5 s (se evalúa si la sesión dura 5 s o más) |
| `video_recibido` | Llega vídeo tras `VIDEO_CTRL{1}` |
| `video_valido` | Cabeceras, Annex-B, SPS/PPS primero, IDR antes que cualquier P, eco de `VIDEO_ARGS` y tamaño |
| `cabecera_tamano` | Ancho x alto de la cabecera = par(`CarWidth`) x par(`CarHeight`) (in-app) |
| `cabecera_eco` | Eco de `VIDEO_ARGS` y valores in-app: appType 1, ángulo 90, orientación 1 |
| `sps` | Hay SPS y se puede leer |
| `sps_vs_cabecera` | Tamaño del SPS (con recorte) = ancho x alto de la cabecera de cada mensaje |
| `fps` | fps medio entre el 50 % y el 150 % del pedido (con 2 s de vídeo o más) |
| `huecos` | Ningún corte de vídeo de más de 1 s |
| `idr` | Un IDR como mínimo cada 2 x `FrameInterval` s |
| `tamano_mensaje` | Ningún mensaje de vídeo de más de 512 KiB (FAIL: el coche se colgó, o se colgaría con `--no-limit`); WARN si alguno pasa de 480 KiB. Informa del mensaje más grande |
| `sps_repetido` | SPS/PPS solo al principio o delante de un IDR pedido con `KEY_FRAME_REQ`; WARN si precede a un IDR que nadie pidió, FAIL si no hay IDR detrás. Informa del número y los intervalos |
| `decodifica` | Con `--decode`: ffmpeg decodifica el vídeo sin ninguna línea de error (SKIP sin `--decode`, sin ffmpeg o en el autotest) |
| `tactil` | El guion se envía entero |
| `inesperados` | Nada raro del teléfono: bytes basura, msgType inesperados, `!BIN` que no sea el AppStatus |
| `sesion` | La conexión dura hasta el final de la prueba |

`SKIP` significa que no se pudo evaluar (por ejemplo, sin vídeo no se mira el SPS) y `WARN` que está bien pero hay
algo que señalar; ninguno de los dos cuenta como fallo. Si no llega el ACK, el informe termina con una lista de cosas
a revisar.

Ctrl+C para la prueba y saca el informe igualmente (y el JSON, si se pidió). El código de salida sale de las
comprobaciones.

## 8. Manías del C10

Dos cosas que hace el coche de verdad (vistas el 2026-10-05) y que no se veían en casa. carsim las reproduce para
que se cacen antes de publicar. Están activas por defecto.

1. **Límite de 512 KiB por mensaje.** El receptor QDLink del coche se cuelga con cualquier mensaje de vídeo
   (48 bytes de cabeceras + payload) de más de 524 288 bytes: deja de leer el TCP (el `write()` del teléfono se
   bloquea en cuanto se llenan los búferes) sin dejar de mandar heartbeats, hasta que el watchdog del teléfono corta
   la sesión unos 10 s después. carsim hace exactamente eso: al recibir uno de más deja de leer `--hang` segundos
   (10) y cierra. La comprobación `tamano_mensaje` da FAIL con el tamaño y la hora del mensaje, y avisa (WARN) si
   alguno pasa de 480 KiB, que es el tope que tiene que aplicar el teléfono. `--limit 0` (o `--no-limit`) desactiva
   el cuelgue, pero la comprobación sigue fallando si algún mensaje pasa de 512 KiB.
2. **Reinicio del decodificador con cada SPS/PPS.** El coche reinicializa el decodificador con cada mensaje de
   configuración (`VIDEO_CONFIG`), aunque sea idéntico al anterior, y eso se ve como artefactos. `sps_repetido`
   admite el primero de cada conexión (una reconexión es una conexión nueva) y los que preceden a un IDR pedido con
   `KEY_FRAME_REQ`; un SPS/PPS delante de un IDR que nadie pidió es WARN, y uno sin IDR detrás (seguido de un P-frame
   o de otro SPS/PPS) es FAIL. En el informe sale cada uno con el intervalo desde el anterior. `--no-sps-check` lo
   deja en SKIP.
3. **Decodificación real (`--decode`).** Al acabar, el Annex-B recibido se manda por la entrada estándar de ffmpeg
   (`-f h264 -i - -f null -`, con `-loglevel error`) y cualquier línea de error del decodificador hace fallar
   `decodifica`; en el detalle van los frames decodificados y las primeras líneas de error. ffmpeg se busca en
   `--ffmpeg`, en `C:\Users\calva\Desktop\qd\tools\ffmpeg\ffmpeg-master-latest-win64-gpl\bin\ffmpeg.exe` y en el
   `PATH`; si no está, SKIP. En el autotest también SKIP (los frames son falsos).

`--no-quirks` desactiva 1 y 2 a la vez (útil para comparar con un coche que no tenga estas manías).

## 9. Notas y limitaciones

- No se sabe cómo es de verdad el `Connect_Broadcast` del C10, ni el formato y periodo de sus heartbeats, ni sus
  tiempos. carsim imita lo que espera QDLink, con los valores de `CarSim` (spec §11).
- `CarSim` solo admite un destino de broadcast. El primero de `--target` sale del UDP 18464, como haría el coche. El
  resto sale de un puerto efímero (`BroadcastFanout`), lo que da igual porque el teléfono contesta siempre a
  `IP_origen:18464`.
- Para leer el SPS hacen falta los bytes del vídeo, y `CarSim` solo los da grabados. Sin `--out` se graba en un
  fichero temporal que se borra al terminar.
- El vídeo del autotest no se puede reproducir (los frames son falsos). El de una prueba con el móvil sí.
