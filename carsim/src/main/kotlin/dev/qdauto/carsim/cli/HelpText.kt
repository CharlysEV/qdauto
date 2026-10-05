package dev.qdauto.carsim.cli

import dev.qdauto.carsim.decode.Ffmpeg
import dev.qdauto.carsim.touch.TouchScripts
import dev.qdauto.core.sim.CarSimConfig

/** Ayuda de la CLI (`--help`). */
object HelpText {
    fun text(): String {
        val car = Options.DEFAULT_CAR
        val video = Options.DEFAULT_VIDEO
        return """
carsim: simulador del coche (unidad Leapmotor C10 con QDLink) para probar la app QDAuto por Wi-Fi.

Hace de coche: anuncia su presencia por UDP (Connect_Broadcast al puerto 18463), espera el Broadcast_ACK del
móvil en UDP 18464, se conecta por TCP al MirrorPort, hace el handshake (CAR_INFO, VIDEO_SUP_REQ, VIDEO_ARGS,
VIDEO_CTRL), valida el vídeo que recibe, manda toques de prueba y termina con un informe de comprobaciones
(PASS/FAIL).

Uso:
  carsim [opciones]
  carsim --self-test

Red:
  --target <ip|broadcast>    Destino del Connect_Broadcast. 'broadcast' (por defecto) = 255.255.255.255 y el
                             broadcast de subred de cada interfaz. Con la IP del móvil se le manda directo (útil
                             si el router filtra los broadcasts). Se puede repetir o separar por comas.
  --discovery-timeout <s>    Espera máxima del ACK del móvil (por defecto ${Options.DEFAULT_DISCOVERY_TIMEOUT_MS / 1000}).

Coche (CAR_INFO):
  --width <px>               CarWidth (por defecto ${car.carWidth}, la suposición del C10).
  --height <px>              CarHeight (por defecto ${car.carHeight}).
  --car-type <código>        CarType (por defecto ${car.carType}: C10 con volante a la izquierda; 2D5 a la derecha).

Vídeo (VIDEO_ARGS):
  --fps <n>                  FrameRate (por defecto ${video.frameRate}).
  --bitrate <bps>            BitRate en bit/s; admite k y M: 4M = 4000000 (por defecto ${video.bitRate}).
  --gop <s>                  FrameInterval: segundos entre IDR para Android (por defecto ${video.frameInterval}).

Prueba:
  --duration <s>             Segundos de vídeo tras el handshake (por defecto ${Options.DEFAULT_DURATION_MS / 1000}; ${Options.SELF_TEST_DURATION_MS / 1000} en el autotest).
                             0 = hasta Ctrl+C. Si el guion táctil dura más, se espera a que acabe.
  --touch-script <guion>     Guion táctil: un fichero, el guion escrito en la propia opción (órdenes separadas
                             por ';'), 'default' o 'none'. Ver más abajo.
  --out <fichero.h264>       Guarda el vídeo recibido en Annex-B (se ve con: ffplay -f h264 fichero.h264).
  --report <fichero.json>    Guarda el informe completo en JSON.
  --verbose                  Muestra cada mensaje que entra y sale, y los logs internos.
  --self-test                Autotest en local: un teléfono simulado (PhoneSession de :core) con frames falsos
                             contra este coche, todo en 127.0.0.1 y con puertos libres. Tiene que salir PASS.
  --help                     Esta ayuda.

Manías del C10 (vistas en el coche el 2026-10-05; activas por defecto):
  --limit <KiB>              Límite del receptor del coche (por defecto ${CarSimConfig.C10_RECEIVER_LIMIT_BYTES / 1024}): con un mensaje de vídeo
                             (48 B de cabeceras + payload) mayor, el coche simulado deja de leer el TCP (el
                             teléfono se bloquea en write()) sin dejar de mandar heartbeats, y cierra pasados
                             --hang segundos. Comprobación 'tamano_mensaje' (FAIL); aviso a partir de ${CarSimConfig.RECOMMENDED_MAX_MESSAGE_BYTES / 1024} KiB,
                             que es a lo que debe recortar el teléfono. 0 o --no-limit = no colgarse.
  --hang <s>                 Segundos sin leer antes de cerrar (por defecto ${Options.DEFAULT_HANG_MS / 1000}).
  --no-sps-check             No evaluar 'sps_repetido': el coche reinicia el decodificador con cada SPS/PPS, así
                             que solo se admite el primero y los que precedan a un IDR pedido con KEY_FRAME_REQ
                             (WARN si el IDR no se pidió, FAIL si no hay IDR detrás).
  --no-quirks                --no-limit y --no-sps-check.
  --decode                   Al acabar, pasa el vídeo recibido por ffmpeg (-f h264 -i - -f null -): cualquier
                             error del decodificador hace fallar 'decodifica'. Sin ffmpeg, SKIP.
  --ffmpeg <ruta>            Ejecutable de ffmpeg (implica --decode). Por defecto se busca en
                             ${Ffmpeg.DEFAULT_PATH.path} y en el PATH.

Guion táctil. Coordenadas en px del coche o en % de su ancho/alto; órdenes separadas por líneas o por ';';
'#' empieza un comentario. También valen en inglés: wait, tap, drag, two, pinch, key, music, idr.
  espera <ms>                                  pausa
  toque <x> <y> [ms]                           pulsación con un dedo (ms pulsado: 50)
  arrastre <x0> <y0> <x1> <y1> [ms] [pasos]    arrastre con un dedo (300 ms, 10 pasos)
  dos <x0> <y0> <x1> <y1> [ms]                 dos dedos a la vez (500 ms)
  pellizco <cx> <cy> <d0> <d1> [ms] [pasos]    dos dedos que se separan (d0 < d1) o se juntan
  tecla inicio|atras|recientes|<código>        PHONE_KEYS (1, 2, 3)
  musica play|pausa|playpausa|siguiente|anterior|silencio
  keyframe                                     KEY_FRAME_REQ
El guion empieza con el primer frame de vídeo (o 5 s después de VIDEO_CTRL si no llega vídeo). Por defecto:
${wrap(TouchScripts.DEFAULT_TEXT, "  ")}

Salida: 0 = todo PASS, 1 = alguna comprobación FAIL, 2 = opciones incorrectas, 3 = error al arrancar.

Ejemplos:
  carsim
  carsim --duration 60 --out recibido.h264 --report informe.json
  carsim --target 192.168.1.50 --width 2560 --height 1440 --fps 60 --bitrate 8M
  carsim --touch-script "espera 2000; toque 50% 50%; tecla atras"
  carsim --decode --out recibido.h264
  carsim --no-quirks
  carsim --self-test
""".trim()
    }

    /** Parte un guion "a; b; c" en líneas de como mucho [width] caracteres, cortando por los ';'. */
    private fun wrap(script: String, indent: String, width: Int = 100): String {
        val lines = ArrayList<String>()
        var line = StringBuilder(indent)
        for (part in script.split("; ")) {
            if (line.length > indent.length && line.length + part.length + 2 > width) {
                lines += line.toString().trimEnd()
                line = StringBuilder(indent)
            }
            line.append(part).append("; ")
        }
        lines += line.toString().removeSuffix("; ")
        return lines.joinToString("\n")
    }
}
