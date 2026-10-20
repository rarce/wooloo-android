# Revisión crítica en emulador — 20 de octubre de 2026

**Seguimiento:** E1–E4 corregidos posteriormente en el árbol de trabajo.
Consulta [cambios y nueva validación](fixes/README.md). Este informe conserva
las observaciones originales sobre `803c669`.

**Veredicto:** la app permite configurar una máquina, autenticar con una clave generada
localmente y operar un shell real, pero no consideraría cerrada su calidad de uso diario.
Hay un fallo reproducido de recuperación del terminal ante pérdida de red y una carencia
importante de accesibilidad. La suite instrumentada pasa, pero no detecta estos recorridos.

Se revisó `803c669`, APK **debug 0.1.0**, sin cambios de código durante esta revisión.
No es el candidato firmado de producción. Entorno: Android 16/API 36, imagen 36.1 ARM64,
pantalla 1080×2424 a 420 dpi, páginas de 4096 bytes, Gboard, fuentes 100 % y 200 %.
El emulador `wooloo_review_20261020` fue creado exclusivamente para esta prueba.
[Identidad del entorno y SHA-256 del APK](environment.json).

## Hallazgos

### E1 — P1: recuperar la conexión no recupera el terminal

**Confirmado en dos ciclos de pérdida/restauración de red.**

1. Conectar al fixture SSH, abrir su espacio y entrar al shell.
2. Desactivar Wi-Fi y datos del emulador durante unos segundos.
3. Restaurar ambas redes y esperar a que reaparezcan las métricas del host.
4. La sesión SSH se recupera, pero el terminal conserva **Stream ended / Software caused
   connection abort** y exige **Reconnect**. No vuelve a aceptar entrada normalmente.

En el primer ciclo, Reconnect desembocó además en **Pane in use**, atribuyendo el control
a otro cliente, aunque el fixture solo había sido usado por este emulador. **Take control**
permitió continuar con el contenido del shell intacto. Esa segunda parte se observó una
vez; la falta de recuperación automática se repitió dos veces.

Evidencia: [red restaurada, primer ciclo](17-network-restored.png),
[reconfirmación](43-retest-network-restored.png),
[control retenido tras reconectar](18-manual-reconnect.png).
Las jerarquías XML correspondientes conservan los mensajes y las métricas.

**Explicación respaldada por el código:** `TerminalController.kt:63` llama a `open()` al
volver a Connected solo si el estado es Offline. En las líneas 64–68, Reconnecting/Failed
solo convierte Live u Opening a Offline. En `onClosed` (líneas 136–143), un cierre recibido
antes de que `connection.isLive` cambie puede dejar el estado en Closed. Esa combinación
explica que la recuperación posterior no reabra el stream. El orden exacto de callbacks
no se instrumentó en esta prueba; la relación causal es una inferencia de ese código y
el estado observado, no una traza temporal completa.

**Recomendación:** distinguir cierre definitivo del panel de interrupción del transporte,
recuperar el segundo al reconectar y probar ambos órdenes de eventos. Tratar por separado
el control retenido por la conexión anterior, sin tomar automáticamente el control de un
cliente legítimamente distinto. Criterio de aceptación: ambos órdenes de caída recuperan
el stream o muestran una acción de recuperación precisa, preservando la protección de takeover.

### E2 — P1: la salida del terminal no está expuesta a accesibilidad

Se escribió y ejecutó `echo WOOLOO_REVIEW_OK` por el teclado del terminal. El resultado
aparece en pantalla, pero **ni el comando ni su salida ni el prompt aparecen en la jerarquía
de accesibilidad**: únicamente se exponen la cabecera, métricas y botones de teclas.

Evidencia: [terminal con salida visible](13-terminal-keyboard.png) y
[árbol accesible de esa pantalla](13-terminal-keyboard.xml).
`TerminalView.kt:203` dibuja las celdas en Canvas; la clase no implementa nodos accesibles
que representen ese texto. El contexto usado por el IME no sustituye a una representación
para lectores de pantalla.

**Impacto:** la función principal carece de una vía estándar de lectura de su contenido
para usuarios de lector de pantalla. No se ejecutó una auditoría hablada completa con
TalkBack; el hallazgo confirmado es la ausencia de contenido en el árbol accesible,
corroborada por el código.

**Recomendación:** ofrecer una vista/transcripción accesible del contenido y acciones para
leer pantalla/historial, con actualización controlada para no anunciar cada frame. Verificar
lectura y navegación con TalkBack real, sin depender de copiar el terminal a otra app.

### E3 — P2: cabecera del terminal recortada al 200 %

Con el terminal activo, establecer la fuente del sistema al 200 %. El nombre queda
elidido, el breadcrumb se corta verticalmente bajo la cabecera y las métricas muestran
solo parte de CPU/RAM. No es simplemente una línea larga que se puede desplazar: se pierde
contenido vertical de la segunda línea.

Evidencia: [terminal al 200 %](20-active-font200.png).
`TerminalScreen.kt:493` fija la cabecera en **56 dp** para dos líneas de texto escalables;
`PaneMetrics.kt:64` limita la columna a 150 dp, con una línea sin tratamiento explícito
de overflow para CPU/RAM.

**Recomendación:** permitir crecer la cabecera y reorganizar o trasladar métricas cuando
falte espacio. Mantener identificable el panel actual y accesibles las acciones. Añadir
prueba/captura del terminal completo al 200 %, además de los tests actuales de listas.

### E4 — P2: vuelve a pedir notificaciones después de una negativa y una recreación

En la primera apertura se solicita notificaciones. Tras elegir **Don’t allow**, cambiar
el tamaño de fuente del sistema recrea la actividad y vuelve a mostrar el mismo permiso,
sin que el usuario haya intentado activar alertas. Se observó inicialmente y se reprodujo
restableciendo solo los flags de ese permiso en el emulador desechable.

Evidencia: [primera solicitud](38-notification-first-denial.png) y
[segunda solicitud tras cambiar la fuente](39-notification-reprompt.png).
`WoolooMobileRoot.kt:99–100` lanza el permiso en `LaunchedEffect(Unit)` cada vez que se crea
la composición, sin registrar la negativa o vincularlo a una intención de activar alertas.

**Recomendación:** solicitarlo cuando el usuario habilite notificaciones o tras una explicación
contextual, y respetar la negativa durante recreaciones/reaperturas. Comprobar primera
solicitud, negativa, recreación y activación explícita desde Ajustes.

## Qué funcionó

| Recorrido | Resultado observado |
| --- | --- |
| Suite instrumentada completa | **11/11 aprobadas**, cero fallos u omisiones. [XML](instrumented-tests.xml) |
| Formulario vacío | Errores específicos de Host/User, foco en el primer campo; no guardó una máquina inválida |
| Formulario y Ajustes al 200 % | Contenido desplazable, controles principales utilizables; la excepción confirmada es la cabecera del terminal |
| Clave Ed25519 creada en la app | Generada correctamente y utilizada para autenticar contra OpenSSH real |
| Importación por deep link | Abrió el editor; la conexión solo empezó al pulsar Connect |
| Primera confianza SSH | Mostró fingerprint, comparado con el host key del fixture antes de aceptar |
| Navegación | Máquinas → espacios → layout/panel → terminal operativa |
| Entrada directa y composer | Comandos de prueba ejecutados, salida visible; teclado abre y cierra |
| Red perdida | Detectada y mostrada como error; el contenido remoto sobrevivió. Recuperación automática defectuosa según E1 |
| Cámara denegada | Explicación y alternativas de imagen/enlace disponibles. [Captura](29-camera-denied.png) |
| Enlace inválido | Rechazado en el diálogo; no inició conexión. [Captura](32-invalid-link.png) |
| Privacidad offline al 200 % | Abrió un diálogo desplazable con cierre accesible. [Captura](36-privacy-font200.png) |
| Reinicio del proceso | Perfil y clave persistieron y permitieron reconectar al fixture |
| Registro de crashes | Buffer de crash vacío al terminar el recorrido; no se observó cierre inesperado |

La suite se ejecutó con:

```sh
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline --console=plain
```

La parte manual usó la app instalada y adb para toques, texto, capturas, jerarquía accesible
y ajustes del dispositivo. No se sustituyó el flujo de conexión por una respuesta simulada.
SSH escuchó únicamente en loopback, puerto 22349, con host key y authorized_keys temporales.
La única clave autorizada fue la pública creada en la app. herdr **0.9.5**, sesión exclusiva
`wooloo-review-20261020`; no se controlaron sesiones existentes del usuario. Las cuotas
permanecieron desactivadas y no se leyeron credenciales de proveedores.

## Límites y seguimiento

No se probaron un teléfono físico, API 26, runtime de 16 KB, Cloudflare/Access, agentes
reales de IA, cuotas contra proveedores, adjuntos de extremo a extremo, QR óptico, actualizador
con firma definitiva ni una sesión larga de batería/memoria. La función de actualización
está desactivada en este APK debug. Las métricas CPU del Mac de pruebas no son una medición
de consumo Android. La lentitud de primer arranque observada en el emulador no se clasifica
como defecto de rendimiento sin aislar carga del host y repetir en release.

Prioridad propuesta: **E1 recuperación**, **E2 accesibilidad**, luego **E3 escala de texto**
y **E4 consentimiento de notificaciones**. Esta revisión deja hallazgos y evidencia;
no incorpora fixes ni implica aprobación para publicar.

## Estado al terminar

Servidores SSH y herdr del fixture detenidos. Red del emulador restaurada, fuente al 100 %
y app abierta en máquinas, con el perfil/clave desechables preservados para inspección.
El emulador permanece abierto; el perfil no podrá conectar mientras el fixture esté apagado.
No se hicieron commits ni cambios de código. El cambio previo de `.gitignore` quedó intacto.
