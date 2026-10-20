# Correcciones de la revisión en emulador

20/10/2026, cambios locales sobre `803c669`, APK debug 0.1.0.
Mismo emulador aislado Android 16/API 36 y fixture SSH/herdr 0.9.5 del informe original.

## E1: recuperación de terminal

El stream distingue un cierre explícito del protocolo de una interrupción del transporte.
El controlador recupera la interrupción tanto si el EOF llega antes del cambio de estado
SSH como si llega después. Conserva el modo observador y respeta cierre y takeover.
Si el servidor todavía retiene el control anterior, explica la situación y ofrece
**Take control** o **Watch only**, sin apropiarse automáticamente de otro cliente.

Tres ciclos reales de Wi-Fi/datos desactivados y restaurados:

- Dos ciclos con control: ambos llegan a la decisión explícita, sin quedar en Stream ended.
  [Estado tras reconectar](12-recovered.png), [control recuperado](13-control-restored.png).
- Seleccionar Watch only recupera la pantalla. Un tercer corte en ese modo vuelve
  automáticamente a Watching con el contenido intacto:
  [observador recuperado](16-watch-auto-recovered.png).

Cuatro regresiones JVM cubren los dos órdenes, observación y cierres definitivos/takeover.

## E2: lectura accesible

La vista nativa expone el texto visible y una acción accesible **Read terminal text**.
El menú ofrece la misma acción. Abre una instantánea seleccionable y desplazable,
actualizable a voluntad, con opción de leer las últimas 400 líneas.
No se anuncian continuamente los repintados del terminal.

La jerarquía del emulador contiene ahora comando, salida y prompt:
[XML](07-font200-accessible.xml). El [lector](09-reader.png) muestra el mismo texto.
Las pruebas verifican el nodo nativo, ejecución de la acción sin enviar entrada al PTY,
instantánea estable, actualización explícita y conservación del texto ante errores.

La prueba real del historial descubrió un valor incorrecto del protocolo: `recent-unwrapped`.
Se corrigió a `recent_unwrapped` y se añadió una regresión del request y de la respuesta.
El APK final cargó el historial real sin error:
[captura](19-history-fixed.png), [jerarquía](19-history-fixed.xml).

## E3: fuentes grandes

La cabecera crece con su contenido; con fuente grande o pantalla estrecha coloca las
métricas debajo del título y la ruta. Se verificó visualmente al 200% y con una prueba
de geometría y ausencia de elipsis: [captura](07-font200-accessible.png).

## E4: notificaciones

Se eliminó la solicitud automática al crear la actividad. Ajustes conserva el acceso
explícito a la configuración de notificaciones de Android.
Con permiso denegado y sus flags reiniciados, ni el arranque ni la recreación provocan
el diálogo: [arranque](01-no-permission-prompt.png),
[recreación con fuente grande](02-recreate-no-permission.png).
Una regresión comprueba ambos casos y el intent de Ajustes.

## Validación y límites

- JVM: 228 casos, 221 aprobados, 7 opt-in omitidos; cero fallos/errores.
- Instrumentación Android: 11/11 aprobadas sobre el APK final y datos limpios.
- Gates JaCoCo general y de métricas, Android lint, APK debug y release: aprobados.
- [Hashes de artefactos y resumen](validation.json).
- SSH directo manual con servidor real: reconexión, entrada/salida y lectura accesible.
- No se ejecutó una auditoría auditiva completa de TalkBack, integración Cloudflare,
  pruebas en teléfono físico ni actualización con firma definitiva.
- Los siete casos opt-in de la suite JVM permanecen omitidos; el recorrido manual
  SSH no equivale a ejecutar esos casos.
