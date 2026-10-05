# 02 — Pipeline de vídeo y capa de framing SSP — QDLink 1.9.7

> **Fuente**: APK `com.neusoft.qdrivelink` v1.9.7 (targetSdk 35) decompilado con jadx en `decomp/qdlink/sources`, y
> `tools/native/libsspLib.so` (split arm64-v8a, única librería nativa del APK). Todas las referencias `archivo:línea` son
> relativas a `decomp/qdlink/sources/` salvo indicación. El código está ofuscado: los nombres son los de jadx.
>
> **Alcance**: captura → codificación → empaquetado → envío del vídeo, más la capa "SSP" (`SSPProtocol` + `libsspLib.so`,
> `DataParser`, `CRC16`). Descubrimiento/transporte y mensajes de control están en otros documentos (ver
> `03-control-input.md`); aquí solo se tocan en lo imprescindible.
>
> **Convención**: "coche" = unidad principal (HU) del Leapmotor C10; "teléfono" = QDLink. Lo marcado **[INFERENCIA]**
> es deducción razonada, pendiente de confirmar con captura (§11).
>
> **Abreviaturas de rutas**: `managers/`, `glec/`, `ScreenCaptureService.java`, `mirror/utils/` →
> `com/neu/ssp/mirror/screencap/…` · `linkconnection/`, `wificonnection/`, `MirrorActivity.java`,
> `RotateScreenService.java`, `ic/utils/` → `com/neusoft/interconnection/…` · `qdrivelink/` → `com/neusoft/qdrivelink/…` ·
> `ssp/protocol/`, `parse/` → `com/neusoft/…` · `h0/`, `b0/`, `c0/`, `d0/`, `f0/` son paquetes de primer nivel. Dentro de
> una misma sección, `a.java:NNNN` sin prefijo = `linkconnection/a.java`.

---

## 0. Resumen ejecutivo

- **Códec**: H.264 **Annex‑B** (start codes `00 00 00 01`), perfil **Baseline**, nivel 3.1 (sugerido), `MediaCodec` con
  entrada por `Surface`, bitrate **VBR**. Sin claves de baja latencia, sin `repeat-previous-frame`, sin B-frames.
- **Captura**: dos modos. *Out-of-app* (983058): espejo real de la pantalla con `MediaProjection.createVirtualDisplay`.
  *In-app* (983057): la UI propia de QDLink dibujada en una `Presentation` sobre un `VirtualDisplay` privado.
- **Render GL intermedio** (`glec/*`): `VirtualDisplay → SurfaceTexture → textura OES → Surface del encoder`, redibujado
  con un `java.util.Timer` cada `1000/fps` ms. Así hay frames constantes aunque la pantalla no cambie, el PTS es
  sintético y el tamaño del VirtualDisplay queda desacoplado del del encoder.
- **Resolución**: la fija el coche con `CAR_INFO.CarWidth/CarHeight`, combinada con el aspecto real del teléfono (no
  con `VIDEO_ARGS.Width/Height`, que se ignoran). Bitrate, fps y GOP vienen de `VIDEO_ARGS`.
- **Mensaje de vídeo** = cabecera común `"5A5A"` de 16 B + cabecera extendida de 32 B + NALs Annex‑B. Todo
  **big‑endian**. **Sin CRC, sin timestamp, sin nº de secuencia y sin flag de keyframe**. Un buffer de salida de
  `MediaCodec` = un mensaje.
- **SPS/PPS** (`csd-0`+`csd-1`) van juntos en un mensaje de vídeo propio antes del primer frame y tras cada
  reconfiguración o `KEY_FRAME_REQ`. El buffer `BUFFER_FLAG_CODEC_CONFIG` se descarta.
- **Envío**: el mismo socket TCP del control (el teléfono es el *servidor*), con `write()+flush()` **síncrono desde el
  hilo del render GL**. No pasa por la librería nativa.
- **`libsspLib.so` no hace transporte, framing ni cifrado**: es un serializador de valores tipados ↔ JSON (cJSON
  embebido). Solo se usa para el payload de los mensajes de texto "A6A6" de la app.
- **"A6A6"**: formato ASCII con longitudes en hex y **CRC‑16/XMODEM** (poly 0x1021, init 0, sin reflexión; "123456789"
  → `0x31C3`, verificado). Viaja dentro de mensajes legados `"!BIN"` de tipo 3.
- **Keyframes**: nunca se pide un IDR al encoder (`PARAMETER_KEY_REQUEST_SYNC_FRAME` no aparece en el APK).
  `KEY_FRAME_REQ` solo hace que se reenvíen SPS/PPS.
- **Audio**: no se envía audio por Wi‑Fi. No hay `AudioRecord` ni `AudioPlaybackCapture`; la voz que manda el coche
  (msgType 12) se descarta.
- **Pantalla encendida**: hace falta porque se espeja la pantalla física. El VirtualDisplay de MediaProjection se crea
  `PUBLIC` y se "blanquea" cuando la pantalla se apaga. QDLink lo mitiga con un `FULL_WAKE_LOCK` y pasando a modo
  *in-app* al recibir `SCREEN_OFF`.

---

## 1. Mapa de clases (vídeo y SSP)

| Clase (jadx) | Rol |
|---|---|
| `com/neu/ssp/mirror/screencap/service/ScreenCaptureService.java` | Servicio en primer plano `mediaProjection`. Obtiene el token, calcula tamaños (`H()`), crea el gestor, prueba el encoder (`N()`) |
| `com/neu/ssp/mirror/screencap/managers/a.java` ("MiScreenCapture") | Hilo de captura. Configura `MediaCodec`, crea el VirtualDisplay, drena el encoder y **empaqueta** (`z()`), detecta la rotación |
| `com/neu/ssp/mirror/screencap/managers/d.java` | `Presentation` para el modo *in-app* (UI de QDLink en el display virtual) |
| `com/neu/ssp/mirror/screencap/glec/a.java` | EGL14 + `SurfaceTexture` + `Timer` de render ("EncodeDecodeSurface") |
| `com/neu/ssp/mirror/screencap/glec/b.java`, `glec/c.java` | Utilidades GL y `TextureRender` (shaders OES, quad a pantalla completa) |
| `com/neu/ssp/mirror/screencap/utils/e.java` | Constantes: 983041 (captura H.264), 983057 (in-app), 983058 (out-of-app), 48, 512 |
| `b0/a.java` | Bean de tamaños calculados (inCar, outHor, outVer, tempScreen, teléfono) |
| `c0/a.java` | Parámetros de captura (bitrate, fps, GOP, "DPI", tipo de captura) con valores por defecto |
| `d0/b.java` | Interfaz *sink* de vídeo: `b(byte[] buf, dataLen, totalLen, orient, angle, w, h, phoneL, phoneS, appType)` |
| `com/neusoft/interconnection/linkconnection/a.java` | Implementa `d0.b`: escribe las cabeceras y envía por el socket (`b()`, `l0()`, `D()`, `j()`) |
| `h0/a.java` | Cabecera común `"5A5A"` (16 B) |
| `h0/b.java`, `h0/d.java` | Cabecera extendida de vídeo (32 B) |
| `com/neusoft/interconnection/linkconnection/message/l.java` | Cabecera legada `"!BIN"` de pantalla (512 B, "ScreenHeader") |
| `com/neusoft/interconnection/linkconnection/message/a.java` | Envoltorio legado `"!BIN"` tipo 3 ("AppMessage"/HU message) para los textos A6A6 |
| `com/neusoft/interconnection/MirrorActivity.java` | Pide el permiso de MediaProjection y traduce `VIDEO_CTRL`/`VIDEO_ARGS` a parámetros de captura |
| `com/neusoft/interconnection/d.java` | Fachada que la app usa para cambiar entre in-app y out-of-app |
| `com/neusoft/interconnection/service/RotateScreenService.java` | Fuerza la orientación del teléfono con una ventana *overlay* (`LAND_MODE_REQ`) |
| `com/neusoft/interconnection/wificonnection/d.java` | `ServerSocket` TCP del teléfono (opciones del socket) |
| `com/neusoft/ssp/protocol/SSPProtocol.java`, `Handle*.java` | Envoltorio JNI de `libsspLib.so` |
| `com/neusoft/parse/DataParser.java`, `CRC16.java` | Formato de texto "A6A6" + CRC |

---

## 2. Secuencia de arranque del vídeo (vista desde el vídeo)

```
COCHE                                   TELÉFONO (QDLink)
CAR_INFO {CarWidth,CarHeight,CarType…} ─▶ ScreenCaptureService.H(carW,carH) → tamaños (b0.a)
                                        ◀─ PHONE_INFO {MirrorWidth/Height = outHor, *InApp = car}   (linkconnection/a.java:914-959)
VIDEO_SUP_REQ {VideoFormat}            ─▶ q0(): prueba el encoder (ScreenCaptureService.N) → diálogo MediaProjection
                                        ◀─ VIDEO_SUP_RSP {VideoFormat:3, VideoSupport:1|0}           (a.java:2398-2411)
VIDEO_ARGS {Width,Height,EncodingType,
            FrameRate,BitRate,FrameInterval} ─▶ guarda Y (a.java:1967-1971)
                                        ◀─ SPEECH_ARGS {EncodingType:1,SampleRate:16000,ChannelConfig:1,AudioFormat:16} (a.java:2472-2488)
VIDEO_CTRL {PlayStatus:1}              ─▶ g0.a.a("play",W,H,fps,br,gop,enc,"5A5A",linkMode) (a.java:2003-2016)
                                           → MirrorActivity.a/q/s → binder t(c0.a) + startForegroundService(code,data)
                                           → ScreenCaptureService.onStartCommand → getMediaProjection → I()
                                           → MirrorActivity.b() → binder A(protocol,linkMode) → S() → managers.a.start()
                                        ◀─ [5A5A msg vídeo: SPS+PPS] [IDR] [P] [P] …
KEY_FRAME_REQ                          ─▶ managers.a.N(): reenviar SPS/PPS antes del siguiente buffer
LAND_MODE_REQ {Orientation}            ─▶ RotateScreenService + LAND_MODE_RSP
```

- `VIDEO_ARGS` es obligatorio antes de emitir: `l0()` lee `this.Y.getEncodingType()` etc. y, si `Y==null`, el NPE se
  traga en `b()` y el frame se pierde (`linkconnection/a.java:1463-1466`, `2466`).
- `VIDEO_CTRL` con `PlayStatus != 1` **no hace nada** (`a.java:2005-2008`): QDLink no pausa el vídeo cuando el coche lo
  pide. La cadena `"play"` es fija (`ic/utils/a.java` `f9698x`), así que la rama `stop` de `MirrorActivity.q()`
  (`MirrorActivity.java:229-232`) nunca se ejecuta desde el coche.
- `EncodingType` decide el tipo de captura: 3 → `983041` (H.264, la única implementada); 1 → `983042`, que hace que
  `d0()` no cree el VirtualDisplay, es decir, **no se emite nada** (`MirrorActivity.java:233-237`, `managers/a.java:693-703`).

---

## 3. Captura

### 3.1 Servicio y token de MediaProjection

- Servicio en primer plano con `android:foregroundServiceType="mediaProjection"` (`resources/AndroidManifest.xml:158-161`).
  En API≥29 llama a `startForeground(id, notif, 32 /*FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION*/)` solo si el manifiesto
  declara `FOREGROUND_SERVICE_MEDIA_PROJECTION`; si no, `stopSelf()` (`ScreenCaptureService.java:898-912`, `848-863`).
- Petición del permiso: API≥34 usa `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`
  (fuerza "pantalla completa", no "una app"); en versiones anteriores, `createScreenCaptureIntent()`. Se lanza con
  `startActivityForResult(…, 123)` desde una Activity (`ScreenCaptureService.java:187-203`).
- Con el resultado: `getMediaProjection(code, data)` + `registerCallback()` (requisito de API 34)
  (`ScreenCaptureService.java:913-927`). El callback `onStop` solo escribe en el log (`75-84`).
- En API 34+ un token solo admite **un** `createVirtualDisplay`. QDLink reutiliza el VirtualDisplay con
  `resize()` + `setSurface()` y captura `SecurityException` (`managers/a.java:733-749`).

### 3.2 Modos de presentación

| Constante | Valor | Nombre en logs | Qué se captura | Cómo |
|---|---|---|---|---|
| `mirror/utils/e.C` | 983057 (0xF0011) | 应用内 "in-app" | UI propia de QDLink (un `ViewGroup` de la app) | `DisplayManager.createVirtualDisplay("MiScreenCapture-display", V, U, dpi, surface, flags=2 /*PRESENTATION*/)` + `Presentation` (`managers/d.java`) |
| `mirror/utils/e.D` | 983058 (0xF0012) | 应用外 "out-of-app" | Espejo de la pantalla del teléfono | `MediaProjection.createVirtualDisplay("MiScreenCapture_display_presentation", V, U, dpi, flags=1 /*PUBLIC*/, surface, cb, null)` |

Referencias: `managers/a.java:717-752`. El modo inicial es in-app (`ScreenCaptureService.java:70`). La app cambia de modo
con `com/neusoft/interconnection/d.java:193-217` (`h()` → in-app, `i()` → out-of-app): pasa a out-of-app al abrir una
app de terceros desde el lanzador de QDLink (`qdrivelink/mainpage/MainPageView.java:282,304,394`), vuelve a in-app al
regresar a QDLink (`qdrivelink/MainActivity.java:146,441,579`) y también **al apagarse la pantalla**
(`ScreenCaptureService.java:110-127`).

> Aunque el modo in-app no usa los píxeles de MediaProjection, `d0()` exige igualmente que haya token
> (`managers/a.java:693-697`).

La `Presentation` usa `TYPE_PRIVATE_PRESENTATION` (2030) con `FLAG_HARDWARE_ACCELERATED | FLAG_LOCAL_FOCUS_MODE |
FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD` (`managers/d.java:90-93`). Por eso se ve aunque el móvil esté bloqueado.

### 3.3 Resolución: de dónde sale

1. `CAR_INFO.CarWidth/CarHeight` → `ScreenCaptureService.e.a(w,h)` → `H(w,h)` (`linkconnection/a.java:919`; si vienen
   0×0 se usa **800×480**).
2. `H()` (`ScreenCaptureService.java:466-661`) combina el tamaño del coche con `getRealSize()` del teléfono. Pseudocódigo
   (sabor `QDriveLink`, coche apaisado `carW > carH`):

> *Fragmento del código decompilado de QDLink omitido en la versión pública.*

3. Campos de `b0/a.java` que se usan después: `f()/c()` = inCar W/H, `b()/a()` = outHor W/H, `h()/g()` = outVer W/H,
   `l()/j()` = lado largo/corto del teléfono, `n()/o()` = tempScreen. La densidad real del teléfono se guarda
   (`E.x(densityDpi)`, línea 490) pero **no se usa**.
4. `VIDEO_ARGS.Width/Height` llegan a `c0.a.n()/m()` (`MirrorActivity.java:238-239`), pero `ScreenCaptureService.L()` solo
   pasa al gestor `a()` (bitrate), `b()` ("DPI"), `d()` (fps), `c()` (GOP) y `h()` (tipo) (`ScreenCaptureService.java:715`):
   **el ancho y el alto de `VIDEO_ARGS` se ignoran**.

**Tamaño del VirtualDisplay, del encoder (SPS) y de la cabecera según el modo** (`managers/a.java:492-502`, `537-565`,
`633-660`). `X` = `myScreenType`: vale 1 solo si `CAR_INFO.CarType` ∈ {`28B`,`297`,`298`,`299`,`29A`,`29B`,`2C7`}
(`qdrivelink/interconnection/DLinkNotifyL.java:1989`, `linkconnection/a.java:1626-1645`). Según la BD interna, el C10 es
`CarType` `2D4`/`2D5` (ver `03-control-input.md` §9), así que **para el C10 `X = 0`** [INFERENCIA, confirmar `CarType`].

| Modo | X | VirtualDisplay (V×U) | Encoder / SPS | W×H en la cabecera ext. | orient / ángulo | appType |
|---|---|---|---|---|---|---|
| in-app | 0 o 1 | inCar | inCar | inCar | **1 / 90 fijos** | 1 |
| out-of-app | **0** | **outHor** (siempre) | **inCar** → el GL **estira** outHor sobre inCar | **outHor** (`e.I=false` por defecto) | reales | 2 |
| out-of-app | 1 | outVer (vertical) / outHor (horizontal) | = VirtualDisplay | = VirtualDisplay | reales | 2 |

Con X=0 en out-of-app, el contenido codificado tiene una relación de aspecto distinta de la que anuncia la cabecera.
Por ejemplo, coche 1920×1080 y teléfono 2400×1080: el VirtualDisplay mide 1920×864, se codifica a 1920×1080 (estirado
×1,25 en vertical) y la cabecera dice 1920×864. Lo normal es que el coche reescale usando los campos de la cabecera
[INFERENCIA, §11].

Valores calculados reproduciendo `H()` con la aritmética de Java (script propio):

| Coche (CAR_INFO) | Teléfono | inCar (encoder X=0) | outHor (VD espejo) | outVer | rc (div. entera) |
|---|---|---|---|---|---|
| 800×480 | 1080×2400 | 800×480 | 800×360 | 216×480 | 1.0 |
| 1280×720 | 1080×2400 | 1280×720 | 1280×576 | 324×720 | 1.0 |
| 1920×720 | 1080×2400 | 1920×720 | **1920×864** (> alto del coche, por el bug) | 324×720 | 2.0 (real 2,67) |
| 1920×1080 | 1080×2400 | 1920×1080 | 1920×864 | 486×1080 | 1.0 |
| 2560×1440 | 1080×2400 | 2560×1440 | 2560×1152 | 648×1440 | 1.0 |
| 2560×1440 | 1440×3120 | 2560×1440 | 2560×1182 | 664×1440 | 1.0 |

### 3.4 DPI

El `densityDpi` del VirtualDisplay es `c0.a.b()`, que vale **1** por defecto (`c0/a.java:24`). Nada lo cambia: el log
`"Car_DPI====="` lo muestra (`ScreenCaptureService.java:765`), pero `MirrorActivity.q()` nunca llama a `j()`. En el espejo
da igual, porque MediaProjection fuerza `AUTO_MIRROR` y se copia la composición de la pantalla principal. En in-app
tampoco importa, porque el `ViewGroup` ya está inflado con la densidad del teléfono y se escala a mano con
`setScaleX/Y` (`managers/d.java:110-128`).

### 3.5 Rotación

- **El coche pide la orientación**: `LAND_MODE_REQ {Orientation}` → `linkconnection/a.java:1557-1606` →
  `RotateScreenService` añade una ventana *overlay* de 100×100 con `screenOrientation` 0 (landscape) o -1 (libre)
  (`RotateScreenService.java:54-61`, `120-131`, `145-157`) y responde `LAND_MODE_RSP {Orientation, Authority, StatusArg}`
  (`a.java:2937-2981`).
- **Detección en el teléfono**: un `Runnable` en el *looper* principal consulta cada **1 s**
  `DisplayManager.getDisplay(0).getRotation()` (`managers/a.java:164-211`, programado en `504-506`). Pone
  `O` = 0 (vertical) / 1 (horizontal) y `P` = 0/90/180/270. El listener de acelerómetro (`D()`, `513-524`, `1145-1190`) y
  el `OrientationEventListener` de `managers/b.java` son **código muerto** (nadie los registra).
- `O` y `P` viajan en **cada** mensaje de vídeo (bytes 30 y 28-29). `O` empieza valiendo **-1 (0xFF)** hasta la primera
  consulta (`managers/a.java:454`, también en `Z()` `1107-1110`).
- Solo con `X==1` y out-of-app un cambio de orientación **recrea el encoder** (`Q()`, `1016-1034`), con resolución
  nueva y SPS/PPS nuevos a mitad de stream. Con X=0 el VirtualDisplay mantiene el tamaño outHor y el sistema mete el
  contenido girado con bandas negras.

### 3.6 Ruta GL (`glec`) y por qué existe

Cadena: `VirtualDisplay → Surface(SurfaceTexture, tamaño V×U) → textura GL_TEXTURE_EXTERNAL_OES → quad a pantalla
completa → EGLSurface de ventana sobre MediaCodec.createInputSurface()`.

- EGL14 con dos contextos compartidos: el primero sobre un pbuffer V×U (RGBA8888, ES2) y el segundo sobre la Surface
  del encoder (`glec/a.java:188-226`). La config de ventana **no** pide `EGL_RECORDABLE_ANDROID` (el hueco está relleno
  con `EGL_NONE`, `230`).
- Shaders: el clásico `TextureRender` de AOSP/CTS (`glec/c.java:27-30`). La matriz `uSTMatrix` es la **identidad**, no
  se llama a `getTransformMatrix()`; la inversión vertical va en las coordenadas de textura (`glec/c.java:56-63`). Se
  ignora cualquier recorte o transformación que indique el `SurfaceTexture`.
- Bucle (`glec/a.java:86-111`, programado con `Timer.schedule(task, 0, 1000/fps)` en `342-355`, `237-245`):

> *Fragmento del código decompilado de QDLink omitido en la versión pública.*

**Por qué existe** (deducido del diseño):

1. **Cadencia constante**: un VirtualDisplay solo produce buffers cuando cambia la pantalla. El GL redibuja la última
   textura en cada tick, así el encoder recibe ~fps frames/s aunque la imagen esté quieta (equivale a
   `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, que no se usa) y el coche nunca se queda sin frames.
2. **PTS monótono y controlado**: `n·1e9/fps`, independiente del reloj real.
3. **Desacoplar tamaños**: el VirtualDisplay (V×U) puede ser distinto del encoder (inCar con X=0); el quad estira la
   imagen al viewport del encoder.
4. **Orientación de la imagen** y **drenado del encoder en el mismo hilo**.

El fps del timer es `VIDEO_ARGS.FrameRate`, o **120** si viniera ≤0 (`glec/a.java:237-245`), mientras que el encoder
usaría 24 en ese caso (`managers/a.java:568-573`). En la práctica no pasa: `ScreenCaptureService.P()` solo copia el fps
si es ≠0 y si no deja el 24 de `c0/a.java:21` (`ScreenCaptureService.java:782-784`).

---

## 4. Encoder (`managers/a.java:537-608`)

> *Fragmento del código decompilado de QDLink omitido en la versión pública.*

| Aspecto | Valor en QDLink |
|---|---|
| MIME | `video/avc` (`managers/a.java:39`) |
| Ancho×alto | §3.3 (para el C10, con X=0: `CarWidth×CarHeight` redondeado a par) |
| Bitrate / modo | `VIDEO_ARGS.BitRate` / **VBR** (1). Valores por defecto en `c0/a.java:12-27` (bitrate 2 764 800, fps 24, GOP 4, DPI 1, 1280×720) |
| fps | `VIDEO_ARGS.FrameRate` (si llega 0, se usa 24) |
| Intervalo de I‑frames | `VIDEO_ARGS.FrameInterval` (si llega 0, se usa 4) → `KEY_I_FRAME_INTERVAL` (segundos) |
| Perfil / nivel | Baseline / 3.1 (pista; muchos encoders suben el nivel si la resolución lo exige) |
| Claves de baja latencia | **Ninguna**: ni `KEY_LATENCY`, `KEY_LOW_LATENCY`, `KEY_PRIORITY`, `KEY_OPERATING_RATE`, `KEY_MAX_B_FRAMES`, `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, ni `KEY_REPEAT_PREVIOUS_FRAME_AFTER` (grep en todo `com/`) |
| Formato de color | Surface (`0x7F000789`) |
| Prueba previa | `ScreenCaptureService.N()` (`722-745`): configura 800×480, 1,5 Mbps, 24 fps, **CBR** (2), complexity 2, GOP 1 s, Baseline/3.1. Si falla, `VIDEO_SUP_RSP.VideoSupport=0` (`linkconnection/a.java:1507-1519`) |

### 4.1 SPS/PPS

- Con `INFO_OUTPUT_FORMAT_CHANGED` (-2) se llama a `K()` → `y()`, que guarda `csd-0` (SPS) y `csd-1` (PPS) con
  `getOutputFormat().getByteBuffer("csd-N").array()` (`managers/a.java:669-672`, `618-625`, `762-780`), y a `N()`, que
  pone `bSendSps=false`.
- El buffer con `BUFFER_FLAG_CODEC_CONFIG` se descarta (`size=0` → `byteBuffer=null`, `783-789`).
- Si `!L || !bSendSps` (primer frame del gestor o flag reiniciado), antes del siguiente buffer se envía un mensaje de
  vídeo cuyo payload es `csd-0 ‖ csd-1` (cada uno con su start code), con las mismas cabeceras que un frame
  (`790-809`).

### 4.2 Peticiones de keyframe

- `KEY_FRAME_REQ` (5A5A, `linkconnection/a.java:2048-2054`) y `cmd 17` (legado, `1161-1163`) → `ScreenCaptureService.e.o()`
  → `managers.a.N()` (`627-631`): **solo** pone `bSendSps=false`.
- **No** se llama nunca a `MediaCodec.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)`. El IDR siguiente llega cuando
  toque por el GOP (por defecto, hasta 4 s), o cuando se recrea el encoder (cambio de modo, rotación con X=1,
  `resumeScreenCapture`).

### 4.3 Drenado (`b0()`, `managers/a.java:663-684`)

`dequeueOutputBuffer(info, 200 µs)` y, mientras devuelva ≥0, `z(getOutputBuffer(i))` + `releaseOutputBuffer(i,false)`.
Se ejecuta en el hilo del `Timer` **antes** de `eglSwapBuffers`: la salida del frame N se recoge en el tick N+1
(**un tick de latencia extra**, 33-42 ms a 24-30 fps). `presentationTimeUs` y los flags se ignoran salvo
`CODEC_CONFIG`.

---

## 5. Empaquetado y envío

### 5.1 Qué ruta se usa

`managers/a.java:782-927` (`z()`) reserva la cabecera según `linkMode` (`W(i)`, 0 = USB/AOA, 1 = Wi‑Fi) y el protocolo
detectado (`"5A5A"` nuevo / `"!BIN"` legado; en los logs, al legado lo llaman "A6A6"):

| linkMode | Protocolo | Hueco de cabecera | Relleno a múltiplo de 512 | Escritor |
|---|---|---|---|---|
| **1 (Wi‑Fi)** | (cualquiera) | **48 B** | **no** | `linkconnection.a.b()` → `l0()` (5A5A) |
| 0 (USB) | `5A5A` | 48 B | sí | `l0()` |
| 0 (USB) | `!BIN`/otro | 512 B | sí | `D()` (ScreenHeader) |

En `linkconnection/a.java:2450-2470`, con el sabor `QDriveLink`, `5A5A` va por `l0()` y cualquier otro protocolo por
`D()`. `D()` copia una cabecera de **512 B** encima de un buffer que en Wi‑Fi solo tiene 48 B reservados: lanza una
excepción (que se traga) o machaca el H.264. **Por Wi‑Fi solo funciona el protocolo `5A5A`.**

### 5.2 Cabecera común "5A5A" (16 bytes, `h0/a.java:35-53`; parseo en `114-122`)

```
 off  tam  campo                         valor en vídeo        valor en control JSON
 ───  ───  ────────────────────────────  ────────────────────  ─────────────────────
  0    4   magic ASCII "5A5A"            35 41 35 41           35 41 35 41
  4    4   totalSize   u32 BE            16+32+N               16+len(JSON)
  8    2   extendHeaderTotalSize u16 BE  0x0020 (32)           0x0000
 10    1   msgType                       0x01 (vídeo)          0x00
 11    1   ¿? (siempre 0)                0x00                  0x00
 12    1   ¿? (siempre 0)                0x00                  0x00
 13    1   payLoadFormat                 0x02 (H.264)          0x01 (JSON)
 14    1   reservedOne                   0x00                  0x00 (subtipo en msgType 99)
 15    1   relleno                       0x00                  0x00
```

Los nombres de campo salen de los propios logs (`linkconnection/a.java:1933`, `1942`). El magic es **texto ASCII**
"5A5A", no los bytes `0x5A 0x5A`. Otros msgType del mismo framing: 0 control JSON, 2 táctil binario (coche→tel.),
12 voz (coche→tel.), 13 datos de app JSON, 99 personalizado (ver `03-control-input.md` §3).

### 5.3 Cabecera extendida de vídeo (32 bytes, `h0/d.java:71-88`, rellenada en `linkconnection/a.java:1453-1478`)

```
 off msg  off ext  tam  campo                         origen
 ───────  ───────  ───  ────────────────────────────  ─────────────────────────────────────────────
   16        0      2   extLen u16 BE = 32            h0/b.a(), "32" en h0/d.java:9
   18        2      1   = 1 (¿tipo/versión?)           B.e((byte)1)                         a.java:1458
   19        3      1   = 0                            (no se asigna)
   20        4      4   width  u32 BE                  B.C(i7)  "dataWidth"                 a.java:1459
   24        8      4   height u32 BE                  B.w(i8)  "dataHeight"                a.java:1460
   28       12      2   angle  i16 BE (0/90/180/270)   B.z(i6)  "ang"                       a.java:1461
   30       14      1   orientation i8 (0 vert/1 horiz/-1 desconocida)  B.y(i5) "orls"    a.java:1462
   31       15      1   encodingType (VIDEO_ARGS)      B.t(Y.getEncodingType())             a.java:1463
   32       16      4   frameRate u32 (VIDEO_ARGS)     B.v(Y.getFrameRate())                a.java:1464
   36       20      4   bitRate   u32 (VIDEO_ARGS)     B.s(Y.getBitRate())                  a.java:1465
   40       24      4   frameInterval u32 (VIDEO_ARGS) B.u(Y.getFrameInterval())            a.java:1466
   44       28      1   appType: 1 = in-app, 2 = espejo  B.x(i11) "iAppCapture"             a.java:1467
   45       29      3   0
   48       32      N   payload H.264 Annex-B (un buffer de salida de MediaCodec)
```

- `h0/d` tiene además un `int` (`A()`) y un `long` (`B()`) que nunca se asignan ni se serializan: quizá estaban pensados
  para secuencia o timestamp en otra versión. **No hay timestamp en el cable.**
- `frameRate`, `bitRate` y `frameInterval` son un **eco** de `VIDEO_ARGS`, no los valores reales del encoder (que solo
  cambian si `VIDEO_ARGS` trae ceros).
- Los parámetros `phoneL/phoneS` de `d0.b.b(...)` (i9, i10) no se usan en 5A5A.

### 5.4 Payload

- **Annex‑B** tal cual sale de MediaCodec (start codes de 4 bytes; `csd-*` también los incluyen). No hay conversión a
  AVCC ni prefijos de longitud.
- **No hay flag de keyframe**: el coche tiene que mirar el `nal_unit_type` (5 = IDR, 7 = SPS, 8 = PPS) [INFERENCIA].
- **No hay CRC** ni checksum (el CRC16 solo existe en los textos "A6A6", §7).

### 5.5 Ejemplos en hex (generados con las mismas reglas)

Mensaje SPS+PPS (out-of-app, coche 1920×1080 → cabecera 1920×864, horizontal, ángulo 90, EncodingType 3, 30 fps,
4 Mbps, GOP 1). **El SPS es ilustrativo**:

```
0000: 35 41 35 41 00 00 00 54 00 20 01 00 00 02 00 00   "5A5A" total=0x54 ext=32 type=1 fmt=2
0010: 00 20 01 00 00 00 07 80 00 00 03 60 00 5a 01 03   extLen=32,1,0  W=1920 H=864 ang=90 orient=1 enc=3
0020: 00 00 00 1e 00 3d 09 00 00 00 00 01 02 00 00 00   fps=30 br=4000000 gop=1 appType=2
0030: 00 00 00 01 67 42 c0 1f …                         SPS (Annex-B)
004c: 00 00 00 01 68 ce 3c 80                            PPS
```

Frame en modo in-app (1920×1080, siempre ángulo 90 y orientación 1, appType 1), payload IDR:

```
0000: 35 41 35 41 00 00 xx xx 00 20 01 00 00 02 00 00
0010: 00 20 01 00 00 00 07 80 00 00 04 38 00 5a 01 03
0020: 00 00 00 1e 00 3d 09 00 00 00 00 01 01 00 00 00
0030: 00 00 00 01 65 …                                  slice IDR
```

Mensaje de control con la misma cabecera: `35 41 35 41 00 00 00 23 00 00 00 00 00 01 00 00` +
`{"CMD":"HEARTBEAT"}`.

### 5.6 Socket, hilo y función que envían

- Socket: el teléfono es **servidor TCP**. `wificonnection/d.java:78-98` hace `new ServerSocket(mirrorPort)` → `accept()`
  y configura `setTcpNoDelay(true)`, `setSendBufferSize(4 MiB)`, `setReceiveBufferSize(6 MiB)` y `setKeepAlive(true)`.
  Los streams quedan en `ic/utils/a.java` `f9696v/f9697w`. El `mirrorPort` es dinámico y se anuncia por UDP
  (`wificonnection/a.java:168-169`, `512-513`; ver el documento de transporte). **Un único socket** para control,
  vídeo y táctil.
- Cadena de llamadas: `glec.a` TimerTask → `managers.a.b0()` → `z()` → `U()` (`633-660`) → `linkconnection.a.b()`
  (toma el lock `f9275b`, `2450-2470`) → `l0()` (escribe las cabeceras dentro del buffer, `1453-1481`) → `j()` →
  `wifiOutputStream.write(buf); flush()` (`2835-2847`). El mensaje entero (cabeceras + NALs) se escribe en **un solo
  `write()`**.
- Si hay `IOException`: `L()` cierra la sesión Wi‑Fi (`a.java:2848-2855`). Si el stream es `null`, el frame se tira en
  silencio.
- **No interviene `SSPProtocol` ni código nativo.**

### 5.7 Ruta legada "!BIN" (USB o protocolo antiguo, como referencia)

`D()` (`linkconnection/a.java:961-983`) rellena un "ScreenHeader" de 512 B (`linkconnection/message/l.java:130-145`,
nombres de campo en su `toString()`, línea 236) y pone a cero el relleno. Todos los enteros son u32 BE: `[0]"!BIN" [4]dataType=1 [8]totalsize
(con relleno) [12]headersize=512 [16]commonHeaderSize=64 [20]requestHeaderSize=28 [24]responseHeaderSize=88 [28]action
[32..63]mark [64..]` campos `mCapture*`/`mEncodingType`… copiados de la petición del coche. Además: `[92]` tamaño H.264,
`[100]/[104]` lado largo/corto del teléfono, `[116]/[120]` ancho/alto, `[132]` codificación=3, `[136]` ángulo,
`[140]` orientación, `[148]=1920 [152]=1080` (¿tamaño máximo del decodificador? [INFERENCIA]), `[180..188]`
fps/bitrate/GOP, `[204]` appType. El payload empieza en el byte 512.

---

## 6. `SSPProtocol` / `libsspLib.so`

### 6.1 Métodos nativos (registrados con `RegisterNatives`; tabla `g_NativeMethods` @0x59868, decodificada con las relocaciones)

Lo carga `System.loadLibrary("sspLib")` (`ssp/protocol/SSPProtocol.java:17-23`). `JNI_OnLoad` (0x23dbc) registra la clase
`com/neusoft/ssp/protocol/SSPProtocol` y cachea `Integer`, `Double`, `Byte`, `ArrayList` y `Handle`, con los métodos
`intValue`/`doubleValue`/`byteValue`/`add`.

| Método Java (`ssp/protocol/SSPProtocol.java:40-66`) | Firma JNI | Símbolo C++ | Qué hace (desensamblado con capstone) |
|---|---|---|---|
| `native_AddressSspDataNewBaseType(String fmt, Object[])` | `(Ljava/lang/String;[Ljava/lang/Object;)I` | `iNewBaseType` 0x21dc4 | Crea el árbol cJSON con `SPP_Data_cNew_BaseType_1` y devuelve el **puntero truncado a int** |
| `native_AddressSspDataNewBaseType_x64(String, Object[])` | `(…)Ljava/lang/String;` | `iNewBaseType_x64` 0x221bc | Lo mismo, pero devuelve `cJSON_PrintUnformatted()` (**texto JSON**) |
| `native_AddressSspDataNewArrayType()` | `()I` | `iNewArrayType` 0x2263c | `{"D":1,"T":"a","V":[]}` como puntero |
| `native_AddressSspDataNewArrayType_x64()` | `()Ljava/lang/String;` | `iNewArrayType_x64` 0x22650 | Ídem, como texto JSON |
| `native_SspDataAddArrayType(int,int)` | `(II)I` | `iAddArrayType` 0x226c0 | Añade el elemento al array `"V"` (punteros) |
| `native_SspDataAddArrayType_x64(String,String)` | `(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | `iAddArrayType_x64` 0x226cc | `cJSON_Parse` ×2, `SPP_Data_bAdd_ArrayType` y vuelta a texto |
| `native_SspDataRelease(int)` | `(I)V` | `vRelease` 0x227e0 | `cJSON_Delete` |
| `native_SspDataGetBaseType(int,String,Object[])` | `(ILjava/lang/String;[Ljava/lang/Object;)Ljava/util/ArrayList;` | `GetBaseType` 0x227e8 | `SSP_Data_bGet_BaseType_1` → `ArrayList` de Integer/Double/Byte/String |
| `native_SspDataGetBaseType_x64(String json,String fmt,Object[])` | `(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/ArrayList;` | `GetBaseType_x64` 0x22adc | `cJSON_Parse` y luego lo mismo |
| `native_SspDataIterLoop(int,int,String,Object[])` | `(IILjava/lang/String;[Ljava/lang/Object;)Ljava/util/ArrayList;` | `IterLoop` 0x22e7c | Elemento i de `"V"` → `SP_Data_bGet_BaseType_va1` |
| `native_SspDataIterLoop_x64(String,int,String,Object[])` | `(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/Object;)Ljava/util/ArrayList;` | `IterLoop_x64` 0x231c0 | Ídem, sobre texto JSON |
| `native_GetString(int)` | `(I)Ljava/lang/String;` | `GetString` 0x2352c | `cJSON_PrintUnformatted(ptr)` |
| `native_Trans(String)` | `(Ljava/lang/String;)I` | `iTrans` 0x2357c | `cJSON_Parse(str)` → puntero |
| `native_GetVersion()` | `()Ljava/lang/String;` | `GetVersion` 0x235e4 | Devuelve `"JNI_1.1_SDK_1.0"` |

Las variantes sin `_x64` meten un puntero de 64 bits en un `int` Java, así que están rotas en arm64. La app **solo** usa
`sspDataNewBaseType_x64`, `getStr_x64` (Java puro) y `sspDataGetBaseType_x64` (`qdrivelink/interconnection/DLinkNotifyL.java:1303-1441`, `1795-1870`).

### 6.2 Formato "SSP data" (JSON, cJSON, orden de inserción D‑T‑V)

Sale de `SP_Data_New_UnitType` (0x260e8), `SPP_Data_cNew_ArrayType` (0x274f8) y `SPP_Data_cNew_BaseType` (0x27054):

```json
{"D":0,"T":"i","V":123}                       // entero ("i"); también "y" byte, "d" double
{"D":0,"T":"s","V":"texto"}                   // string
{"D":1,"T":"(isiiss)","V":[{"D":0,"T":"i","V":1},{"D":0,"T":"s","V":"x"}, …]}   // tupla
{"D":1,"T":"a","V":[ … ]}                     // array
```

`D` = profundidad (0 escalar, 1 tupla/array; `SP_Data_CheckDepth` rechaza más de 1). `T` = tipo o formato.
`V` = valor. En una tupla, `v` mete un sub-JSON ya serializado.

### 6.3 Exportaciones y strings

- Exporta: API de cJSON completa (`cJSON_Parse`, `cJSON_PrintUnformatted`, …), `SP_Data_*`/`SPP_Data_*`/`SSP_Data_*`,
  las funciones JNI de arriba, `JNI_OnLoad` y libc++/libc++abi estáticas. Compilada con NDK clang 14.0.1 (LLD 14.0.1).
- **Importa** solo libc (memoria, cadenas, `strto*`, `snprintf`, `syslog`, pthread, `__system_property_get`,
  `dl_iterate_phdr`). **No** importa `socket/connect/send/recv`, nada de criptografía ni de códecs.
- Strings: nombres y firmas JNI, `"JNI_1.1_SDK_1.0"`, nombres de clase Java, `u%04x`/`%.0f`/`null`/`true`/`false` (cJSON).
  **No aparecen** `5A5A`, `A6A6`, `!BIN`, puertos, nombres de mensaje ni `%08x`.

### 6.4 Conclusión

La capa nativa **no hace transporte, framing ni cifrado**; ni siquiera calcula el CRC (que está en Java). Para el
cliente Kotlin basta con generar el JSON `{"D","T","V"}` a mano. Y solo hace falta si se usan los mensajes de texto
A6A6 (legados), que no intervienen en el vídeo.

---

## 7. `DataParser` / `CRC16` (formato de texto "A6A6")

### 7.1 Formato (`parse/DataParser.java:20-49` crea, `75-113` parsea)

Todo es **texto ASCII**; las longitudes van en **hex en minúsculas** y se miden en bytes UTF‑8:

```
"A6A6" | flowId %02x | totalLen %08x | appIdLen %02x | appId | logicIdLen %02x | logicId | dataCount %02x |
        { dataLen_i %08x | data_i } × dataCount | CRC16 %04x
```

- `totalLen` = longitud total del texto, CRC incluido: `24 + len(appId) + len(logicId) + Σ(8+len(data_i))`
  (`DataParser.java:28-37`).
- CRC: `CRC16.crcProcess()` sobre **todos los bytes ASCII anteriores** al campo CRC (`DataParser.java:47`).
- Al parsear se comprueba el CRC (`crc(bytes[0..len-4)) == parseInt(últimos 4, 16)`), el magic `A6A6` (sin distinguir
  mayúsculas) y que `totalLen == bytes.length` (`DataParser.java:77-84`).
- Usos: `APP_ID = "QDRIVE_ASSISTANT"`, `LOGIC_ID` ∈ {`ssphome`, `HUINFO`, `ASSISTANT-04`, `BTADDRESS`,
  `CARBTADDRESS`, `BT_AUTO_CONNECTED`, `SUBAPP_PKGMD5`, `NEEDUPGRADE`, `phoneinitok`, `LEGAL_APP_ON`, `phoneready`,
  `phonereadynew`, `COMPARE_OK/ERROR`} (`qdrivelink/interconnection/h.java`, `qdrivelink/interconnection/utils/CompareBT.java:70-74`). Los datos son
  JSON SSP (§6.2).

### 7.2 CRC16 (`parse/CRC16.java:7-35`)

> *Fragmento del código decompilado de QDLink omitido en la versión pública.*

Equivale a **CRC‑16/XMODEM** (= CCITT, poly 0x1021, init 0x0000, sin reflexión, xorout 0). Lo comprobé con una
implementación de referencia: "123456789" → `0x31C3`, `"A6A6"` → `0x0745` y 0..255 → `0x7E55` en ambas.

### 7.3 Ejemplos (generados y verificados)

```
createData(0,"QDRIVE_ASSISTANT","phoneinitok",[])
 → A6A6 00 00000033 10 QDRIVE_ASSISTANT 0b phoneinitok 00 dc49            (51 bytes, sin espacios)
createData(0,"QDRIVE_ASSISTANT","LEGAL_APP_ON",['{"D":0,"T":"i","V":1}'])
 → A6A6 00 00000051 10 QDRIVE_ASSISTANT 0c LEGAL_APP_ON 01 00000015 {"D":0,"T":"i","V":1} f441
```

### 7.4 Cómo viajan

Dentro de un mensaje legado `"!BIN"` de **tipo 3** ("AppMessage", `linkconnection/message/a.java:93-127`), enviado con
`linkconnection.a.j0()` (`2860-2875`) **sea cual sea el protocolo de la sesión**: `[0]"!BIN" [4]3 [8]totalSize
(512+N+relleno) [12]512 [16]64 [20]64 [24]0 [28]2(action) [32..63] bytes 0x20..0x3F [64]dataSize=N [68]0`, texto A6A6
desde el byte 512 y relleno con ceros hasta múltiplo de 512 (todo u32 BE). Al recibirlos: `W()` dataType 3, action 1
(`a.java:1214-1239`) → `DLinkNotifyL.w()` → `DataParser.parse` (`qdrivelink/interconnection/DLinkNotifyL.java:1795-1870`).

---

## 8. Control de flujo, rendimiento y tirones

**No hay cola ni descarte explícito en la app.** El único "buffer" son la `BufferQueue` del `SurfaceTexture`, los buffers
internos de MediaCodec y el buffer de envío TCP (4 MiB).

| # | Causa | Dónde | Efecto |
|---|---|---|---|
| 1 | **Envío síncrono en el hilo de render/encoder** | `glec/a.java:97-99` → `linkconnection/a.java:2844-2845` | Si el TCP se atasca (Wi‑Fi, coche lento), se para todo el bucle: no se dibuja, no se drena el encoder y `eglSwapBuffers` acaba bloqueándose. Al recuperarse, los frames viejos salen en ráfaga |
| 2 | `SO_SNDBUF` = **4 MiB** | `wificonnection/d.java:90` | Antes de que el `write()` bloquee se pueden acumular segundos de vídeo en el kernel (a 2,7 Mbps, 4 MiB ≈ 12 s): **latencia creciente** en lugar de descarte |
| 3 | Sin IDR bajo demanda | §4.2 | Tras una pérdida o corrupción, el coche espera hasta el siguiente I‑frame del GOP (por defecto 4 s): imagen con artefactos o congelada |
| 4 | Sin adaptación de bitrate | — | VBR fijo; no reacciona a la congestión |
| 5 | `Timer.schedule` de **retardo fijo** y periodo entero `1000/fps` | `glec/a.java:349`, `243` | El fps real queda por debajo del nominal y oscila (*judder* al muestrear a 24-30 Hz una pantalla de 60-120 Hz). El PTS sintético se desvía del tiempo real (el control de tasa VBR asume el fps nominal) |
| 6 | Drenado antes del *swap* | §4.3 | +1 tick de latencia fija |
| 7 | `updateTexImage` como mucho una vez por tick, en FIFO | `glec/a.java:164-169` | Si el VirtualDisplay produce más rápido, se consumen frames con algo de retraso. SurfaceFlinger usa modo asíncrono para el *sink* de los displays virtuales, así que los frames sobrantes se descartan en la BufferQueue [INFERENCIA AOSP] |
| 8 | Estiramiento outHor→inCar (X=0, espejo) | §3.3 | Pasada GPU extra y desenfoque; aspecto erróneo si el coche no compensa |
| 9 | Un `byte[]` nuevo por frame y cadenas de log construidas siempre | `managers/a.java:816,916`; `linkconnection/a.java:1480` | Presión sobre el GC. Con el "log switch" activado, cada `g.c()` hace además `getStackTrace()` (`ic/utils/g.java:90-100,132`) |
| 10 | Un solo lock para vídeo, heartbeat y control | lock `f9275b`: `a.java:2454`, `1368`, `2632/2656` | El control (y el heartbeat cada 3 s) espera a que termine el `write()` del frame en curso |
| 11 | Lectura no robusta del lado del teléfono | `a.java:330`, `2382` | Un `read()` único por cabecera y por cuerpo; si un mensaje del coche llega partido, el flujo se desincroniza (ver `03-control-input.md` §3.5) |
| 12 | `VIDEO_CTRL` de pausa ignorado | `a.java:2005-2008` | Se sigue emitiendo aunque el coche no muestre el vídeo |

Temporizadores relacionados: heartbeat `{"CMD":"HEARTBEAT"}` a 1 s y luego cada 3 s (`a.java:1742-1764`); vigilancia de
lectura cada 5 s, que corta la sesión si en 5A5A no llega nada del coche en más de 5 s (`a.java:1659-1681`, `683-704`).

**Audio por Wi‑Fi: ninguno.**

- Ni `AudioRecord`, ni `AudioTrack`, ni `AudioPlaybackCaptureConfiguration`, ni `MediaCodec` de audio en todo el APK
  (grep).
- `SPEECH_ARGS` (16 kHz, mono, 16 bit) solo anuncia al coche el formato de la voz del micrófono. Lo que llega por
  msgType 12 (o dataType 12 en el legado) se pasa a `DLinkNotifyL.r()`, que está **vacío**
  (`qdrivelink/interconnection/DLinkNotifyL.java:1738-1739`; `linkconnection/a.java:2167-2175`, `1263-1287`).
- El audio multimedia tiene que ir por Bluetooth (A2DP) [INFERENCIA]: el protocolo solo lleva la MAC BT y comandos de
  reproducción.

---

## 9. Por qué QDLink necesita la pantalla encendida y qué espera el decodificador del coche

### 9.1 Pantalla encendida

1. **Se espeja la pantalla física**: en modo out-of-app el contenido es la composición del display por defecto. Con la
   pantalla apagada o bloqueada no hay nada útil que capturar: las apps pasan a `onStop` y el keyguard es seguro, así
   que la captura lo muestra en negro.
2. **El VirtualDisplay de MediaProjection se crea `PUBLIC`** (`flags=1`, `managers/a.java:736`). En AOSP,
   `VirtualDisplayAdapter` solo marca como `FLAG_NEVER_BLANK` los displays **no** públicos. Al apagarse la pantalla,
   `DisplayManagerService` pone el resto en `STATE_OFF`: les asigna la *layer stack* vacía y llama a
   `VirtualDisplay.Callback.onPaused()` [comportamiento AOSP, verificar en el dispositivo]. QDLink solo lo registra en
   el log (`managers/a.java:217-221`), y el bucle GL sigue reenviando la **última textura**, así que en el coche se ve
   la imagen congelada.
3. **Mitigaciones de QDLink**:
   - `FULL_WAKE_LOCK` (nivel 26) desde que se crea la conexión (`linkconnection/a.java:897-902`, se libera en `J()`
     `1027-1035`). Evita que la pantalla se apague por inactividad, pero no impide que el usuario la apague con el
     botón.
   - Al recibir `SCREEN_OFF` pasa a **in-app** (`ScreenCaptureService.java:120-124`). Ese VirtualDisplay es privado
     (`flags=2`), así que no se blanquea, y la `Presentation` lleva `SHOW_WHEN_LOCKED|DISMISS_KEYGUARD`: el coche sigue
     viendo la UI de QDLink.
   - Avisa al coche con `LOCK_SCREEN_STATUS` (1 = pantalla apagada, 2 = encendida, 3 = desbloqueada / USER_PRESENT)
     (`qdrivelink/MainActivity.java:467-516` → `linkconnection/a.java:3096-3118`).
   - *Wake locks* breves `ACQUIRE_CAUSES_WAKEUP|SCREEN_BRIGHT` (268435466) de 10 s antes de lanzar apps o inyectar
     toques (`qdrivelink/MainActivity.java:769-773`, `qdrivelink/otherapp/OtherAppView.java:145-149`, …). No deja lanzar apps con el
     keyguard puesto (`qdrivelink/otherapp/OtherAppView.java:209-211`, "unlock your phone").
4. **Consentimiento**: el permiso de MediaProjection exige una Activity visible y el teléfono desbloqueado. En
   Android 14+ el token es de un solo uso por sesión y obliga a registrar el callback antes de capturar; QDLink lo
   cumple. Versiones recientes de Android endurecen además las sesiones de proyección (p. ej. pararlas al bloquear el
   dispositivo en algunas *builds*) [verificar en el teléfono del usuario].
5. **Consecuencia para el proyecto**: si se envía el H.264 de Android Auto, no hace falta MediaProjection ni pantalla
   encendida. AA renderiza en su propio display virtual y ya entrega H.264; basta con reempaquetarlo (§10).

### 9.2 Pistas sobre el decodificador del coche

- El coche espera H.264: `VIDEO_SUP_RSP.VideoFormat = 3` (`linkconnection/a.java:2400`), `EncodingType` 3 en la
  cabecera ext. y prueba del encoder en Baseline/3.1. Nada de HEVC.
- Annex‑B con SPS/PPS en un mensaje aparte. Sin timestamps, así que con mucha probabilidad decodifica y presenta según
  llega [INFERENCIA]. Baseline evita el reordenamiento (sin B-frames).
- Se toleran **cambios de resolución en mitad del stream** (in-app ↔ out-of-app, rotación con X=1): cada vez llega un SPS
  nuevo y un ancho/alto nuevo en la cabecera. El coche debe reconfigurar su decodificador o reescalar [INFERENCIA].
- La cabecera anuncia W×H, orientación y ángulo para que el coche coloque o reescale la imagen (necesario con X=0, §3.3).
- Indicio de límite: el legado fija `[148]=1920 [152]=1080` (`linkconnection/a.java:964-965`) → posible máximo de
  1080p [INFERENCIA].
- Valores por defecto que el coche "espera" si no manda parámetros (legado, `a.java:1179-1205`): 24 fps,
  bitrate = W·H·3 y GOP 4.

---

## 10. Implicaciones para el cliente Kotlin (inyectar H.264 de Android Auto)

- Usar el protocolo **5A5A** y escribir **cada mensaje completo en un solo `write()`** (cabeceras + NALs), como QDLink.
- Enviar primero un mensaje con **SPS+PPS** (Annex‑B, start codes de 4 bytes) y después un mensaje por *access unit*.
  Si el origen (AA) entrega SPS/PPS como "codec config", mapearlos 1:1. Si entrega AVCC, convertir a Annex‑B.
- Para no depender de cómo el coche interpreta los campos, que el tamaño codificado (SPS) = W×H de la cabecera = área
  del coche (`CarWidth×CarHeight`), con `angle=0`, `orientation=1` y `encodingType=3`. Los campos fps/bitrate/GOP
  pueden ser un eco de `VIDEO_ARGS`. `appType` 2 (espejo) o 1 (in-app): §11.
- Ante `KEY_FRAME_REQ`, reenviar SPS/PPS **y** forzar un IDR en el origen (QDLink no lo hace; mejora la recuperación).
- No bloquear el productor con la red: cola acotada y descarte de P‑frames hasta el siguiente IDR si se acumula retraso,
  y un `SO_SNDBUF` mucho menor que 4 MiB para no esconder la congestión.
- Esqueleto del empaquetado:

```kotlin
fun videoMsg(nal: ByteArray, w: Int, h: Int, fps: Int, br: Int, gop: Int,
             angle: Int = 0, orient: Int = 1, enc: Int = 3, appType: Int = 2): ByteArray =
    ByteBuffer.allocate(48 + nal.size).order(ByteOrder.BIG_ENDIAN).apply {
        put("5A5A".toByteArray(Charsets.US_ASCII)); putInt(48 + nal.size); putShort(32)
        put(1); put(0); put(0); put(2); put(0); put(0)                  // msgType, b11, b12, payloadFormat, reserved, pad
        putShort(32); put(1); put(0); putInt(w); putInt(h); putShort(angle.toShort())
        put(orient.toByte()); put(enc.toByte()); putInt(fps); putInt(br); putInt(gop)
        put(appType.toByte()); put(0); put(0); put(0); put(nal)
    }.array()
```

---

## 11. Incógnitas (necesitan captura en vivo)

1. **Protocolo del C10 por Wi‑Fi**: ¿el primer mensaje del coche empieza por `"5A5A"`? Por lo visto en §5.1 debería.
2. **`CAR_INFO` real**: `CarWidth/CarHeight` (¿2560×1440, 1920×1080, un área recortada?), `CarType` (2D4/2D5 → X=0) y
   `MirrorTypeReq`. De esto dependen el tamaño del encoder y el del espejo.
3. **`VIDEO_ARGS` real**: `EncodingType` (¿3?), `FrameRate`, `BitRate`, `FrameInterval` (¿segundos o frames?) y si
   `Width/Height` coinciden con `CarWidth/CarHeight`.
4. **Uso de la cabecera ext. por el coche**: ¿reescala con W×H de la cabecera o con el tamaño del SPS? ¿Usa
   `orientation`/`angle`? ¿Tolera `orientation=0xFF` (primer segundo)? ¿Qué diferencia hay para el coche entre
   `appType` 1 y 2 (p. ej. mapeo del táctil)?
5. **Significado de los bytes fijos**: cabecera 5A5A bytes 11, 12 y 15; ext. byte 2 (=1), byte 3 y bytes 29-31.
6. **Requisitos del decodificador**: ¿hace falta SPS/PPS en un mensaje aparte o vale en línea con el IDR? ¿Acepta
   Main/High, niveles > 3.1, 60 fps, cambios de resolución? ¿Bitrate máximo?
7. **Feedback del coche**: ¿cuándo manda `KEY_FRAME_REQ` (al conectar, tras errores)? ¿Manda `VIDEO_CTRL` con
   `PlayStatus` ≠ 1 y espera que el teléfono pare? ¿Hay algún control de flujo implícito (tamaño de ventana TCP del
   coche, cortes)?
8. **¿Comprueba el coche `totalSize` exacto?** (por Wi‑Fi QDLink no rellena el vídeo; el legado sí rellena a 512).
9. **¿Hace falta el "AppStatus" legado `!BIN`** que QDLink envía también en sesiones 5A5A? (ver `03-control-input.md`).
10. **Comportamiento real con la pantalla apagada** en el teléfono del usuario: en logcat `"mVirtualDisplay callback
    onPaused"` (`managers/a.java:220`) y `"Activity screen off"` → cambio a in-app.

**Cómo capturarlo:**

- **Logs de QDLink** (lo más rápido): en *Ajustes*, tocar **6 veces en menos de 2,2 s** la `View` invisible de 60×60 dp de
  la esquina inferior izquierda (`R.id.log_view`, `resources/res/layout/layout_setting.xml:296-304`;
  `qdrivelink/mine/setting/SettingView.java:576-586`, `647-649`, `678`). Se abre `UploadActivity` con dos
  interruptores: "log开关" (logs) y "保存h264数据" (`qdrivelink/upload/UploadActivity.java:49-87`). Con los logs activos,
  `adb logcat` muestra todo el JSON del coche (`"parsingNewData … strData:"`, `a.java:1942`: CAR_INFO, VIDEO_ARGS…),
  `VIDEO_SUP_RSP`/`PHONE_INFO` y una línea por frame enviado con ancho, alto, ángulo, orientación y longitud
  (`a.java:1480`).
- **Volcado H.264**: el segundo interruptor guarda el elemental Annex‑B en
  `/sdcard/Android/data/com.neusoft.qdrivelink/cache/save264/screendatah264`. **Solo funciona en modo USB**: la rama
  Wi‑Fi de `z()` (`managers/a.java:790-825`) no llama a `S()`.
- **Red**: el coche es el cliente y el teléfono el servidor, así que una VPN de captura (PCAPdroid) probablemente no vea
  la conexión entrante. Hace falta root + `tcpdump` en el teléfono, o un PC en medio. Otra opción: un primer prototipo
  del cliente Kotlin que haga de servidor y registre lo que manda el coche (CAR_INFO, VIDEO_ARGS, KEY_FRAME_REQ).
