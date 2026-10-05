# QDAuto

Motor del protocolo **QDLink / SSPLink** (la proyección del móvil de Neusoft que llevan los Leapmotor), escrito desde
cero en Kotlin, más las herramientas para probarlo. Es la base de transporte del fork
[CharlysEV/headqlink](https://github.com/CharlysEV/headqlink) (rama `qdauto`), que lleva **Android Auto** a la
pantalla del **Leapmotor C10** sin dongle y con el móvil bloqueado.

Proyecto personal y experimental, para interoperabilidad. No tiene relación con Leapmotor, Neusoft ni Google.

## Estado

Probado en un C10 real con un Samsung S25 Ultra (Android 16):

- **Viaje 1:** app de prueba en modo punto de acceso del móvil.
  - Handshake completo.
  - Vídeo de 1920×882 a 30 fps, también con la pantalla apagada.
  - Táctil multipunto 1:1.
  - Reconexión automática.
  - Detalle en [docs/viaje-1-2026-10-04.md](docs/viaje-1-2026-10-04.md).
- **Viaje 2:** **Android Auto** a través de este motor, dentro del fork.
  - Sesiones de unos 16 minutos a unos 50 fps.
  - Pendiente: temperatura y apagado de pantalla.
  - Detalle en [docs/viaje-2-2026-10-05.md](docs/viaje-2-2026-10-05.md).

## Contenido

| Carpeta | Qué es |
|---|---|
| `core/` | Motor del protocolo en Kotlin/JVM, sin dependencias. Incluye: tramas 5A5A, descubrimiento UDP (18463/18464), servidor TCP, sesión con handshake, heartbeat, watchdog, cola de envío de vídeo con descarte, IDR bajo demanda, táctil y simulador del coche (`CarSim`). Con tests JVM, incluidos vectores byte a byte. |
| `app/` | App Android de prueba («QDAuto Test»): envía un patrón H.264 al coche y registra todo lo que llega, sobre todo el táctil. |
| `carsim/` | El coche simulado en el PC (CLI), con 20 comprobaciones por sesión. |
| `docs/protocol/` | Especificación del protocolo deducida por ingeniería inversa: `04-wire-spec.md` (byte a byte) y `05-wifi-direct.md`. |
| `docs/` | Resultados de los viajes, comparativa con headqlink y plan del fork. |

La documentación describe el protocolo para interoperar; no incluye código de QDLink.

## Compilar

Requisitos: JDK 21, Android SDK (compileSdk 36).

```bash
./gradlew :core:test :carsim:installDist :app:assembleDebug
```

Coche simulado contra un móvil de la misma red: `carsim/build/install/carsim/bin/carsim --target <IP del móvil>`.
Las opciones están en [carsim/README.md](carsim/README.md).

## Aviso

**SIN GARANTÍA DE NINGÚN TIPO.** Software experimental basado en un protocolo no documentado. Úsalo bajo tu propia
responsabilidad y **nunca manipules el móvil ni la pantalla del coche mientras conduces**.

Leapmotor, C10, QDLink, Neusoft y Android Auto son marcas de sus respectivos dueños.

## Licencia

[GNU AGPL-3.0](LICENSE), la misma que el fork en el que se integra. Gracias a
[headqlink](https://github.com/ryazrm/headqlink) y a [open-headunit](https://github.com/andreknieriem/open-headunit).
