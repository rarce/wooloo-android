> Archivo histórico del checklist anterior a la depuración del 20/10/2026.
> Sus estados antiguos no sustituyen [el seguimiento actual](../../todo.md).

# Publicación de wooloo-mobile

Revisión inicial y revisión adversarial: 17 de octubre de 2026, código base `10bd451`. Alcance: código, workflows, documentación y artefacto local; configuración remota y comportamiento del candidato de producción en teléfono pendientes de verificar. El seguimiento de acceso remoto distingue las validaciones posteriores de ramas aún sin integrar.

**Diagnóstico:** hay una base funcional, pruebas y automatización de APK firmado. Todavía falta demostrar el recorrido de distribución y actualización con la firma definitiva, cerrar privacidad/licencias y ejecutar integración real. No reutilizar como bloqueos los problemas ya corregidos del [informe anterior](../../docs/review-2026-10-11.md).

Ruta de trabajo asumida: primera distribución por GitHub Releases, porque es la implementada. Google Play tiene requisitos adicionales y se detalla por separado. P0 identifica seguridad y entrega esenciales incluso para un piloto externo; P1 identifica requisitos de calidad antes del público general; P2 admite seguimiento posterior con limitaciones documentadas. Una decisión de marca o una actualización opcional de dependencia no es por sí sola un bloqueo técnico.

Para cerrar cada casilla registrar responsable, fecha, SHA, versión/código, canal, huella de firma, hash del artefacto y evidencia aplicable en el PR o release. Desglosar los resultados de las casillas que agrupan escenarios; un caso aprobado no cierra el resto. Los requisitos condicionales se cierran como «no aplica» con motivo, nunca como prueba aprobada.

## Actualización local — 20/10/2026

Integradas en el árbol de trabajo de `main` las correcciones de `31f0ead`, `5121d18`,
`9e9363f` y los cambios posteriores de `test-release-readiness`, sin alterar las ramas
ni sus cambios originales. **Sin commit, push, tag o publicación en esta ejecución.**
Responsable de implementación: Codex; propietario pendiente de identificar para operación.
Versión usada solo para validar: `0.92.0` / código `92000`; no es una versión inicial elegida.
Evidencia actual: [validación del 20/10](../../docs/release-readiness/validation-2026-10-20.md).
Los diagnósticos originales más abajo describen la revisión del 17/10; este apartado y
las casillas corregidas indican el estado actual. Integración local no equivale a release.

- Seguridad, recuperación del actualizador y controles de publicación incorporados; limpieza
  de sesiones huérfanas y cierre de canales SSH incluidos.
- AndroidX ExifInterface 1.4.2 y orientación con reflejos; consultas de cuotas sin redirecciones.
- Política de privacidad, MIT, OFL e inventario de 118 artefactos accesibles offline en Ajustes;
  README con instalación/onboarding y plantilla de bugs. El inventario no cierra auditoría de
  vulnerabilidades, notices transitivos, términos de ML Kit ni derechos de marca.
- Job de instrumentación API 36 añadido a CI; su ejecución alojada sigue sin demostrar.
- Consulta GitHub del 20/10: `rarce/herdroid` sigue **PRIVATE**. wooloo PR #65 fusionado
  el 17/10 a las 18:11:01 UTC, commit `48306bdab7430e7f321515b932f09743d370c5a0`,
  [Build and test aprobado](https://github.com/rarce/wooloo/actions/runs/39054675782/job/117460117813).
- No hay dispositivos conectados en esta ejecución. Los ensayos históricos con firma temporal
  están en [live-validation](../../docs/release-readiness/live-validation.md); no acreditan firma
  definitiva, teléfono físico, API 26, páginas de 16 KB o Cloudflare Access con token real.
- Bloqueos externos: propietario/canal/versión inicial, firma y custodia, secretos/protecciones
  y facturación GitHub, acceso anónimo, contacto privado de seguridad y piloto físico.
  No se creó una clave de producción ni se abrió el repositorio.

## Ejecución secuencial de los cinco frentes

Seguimiento del 20/10 posterior al commit `803c669`: corregidos E1–E4 de la revisión
en emulador (recuperación de terminal, lectura accesible, cabecera al 200% y solicitud
de notificaciones). Historial `pane.read` ajustado al protocolo real.
[Implementación y evidencia](../../docs/review-2026-10-20/fixes/README.md).

Solicitada con subagentes GPT-6.1 Sol, razonamiento low. Se implementa y revisa cada bloque antes del siguiente; las correcciones en ramas todavía requieren integración en el candidato de publicación.

1. **Seguridad de credenciales: implementación y revisión completadas**, commit `31f0ead`, rama `fix/qr-credential-trust` (pendiente de integrar). R5 exige revisar destino/confianza antes de guardar o conectar y R9 evita guardar borradores sensibles en el estado restaurable. 197 pruebas JVM: 190 aprobadas y 7 opt-in omitidas; 11 Android aprobadas; lint, cobertura y builds debug/release correctos. Revisión independiente sin hallazgos; cancelación comprobada también en emulador. Evidencia: `docs/review-2026-10-17/security/README.md` de esa rama.
2. **Actualizador: implementación y revisión completadas**, commit `5121d18`, rama `fix/update-recovery` (incluye el bloque 1; pendiente de integrar). Recupera sesión/fase persistidas, reintentos y permiso de instalación de wooloo; cada actualización requiere confirmación. El 404 es un error, sin falso éxito. 209 pruebas JVM: 202 aprobadas y 7 opt-in omitidas; cobertura, lint y builds debug/release correctos. Revisión independiente sin hallazgos. Instalador real y firma definitiva siguen pendientes. Evidencia: `docs/review-2026-10-17/updater/README.md` de esa rama.
3. **Publicación y CI: controles implementados y revisados**, commit `9e9363f`, rama `fix/release-gates` (incluye bloques anteriores; pendiente de integrar). SHA autorizado antes de scripts, tag comprobado antes de publicar, gates con `appVersion`, ensayo sin firma/publicación, validación de versiones y feed, mapping e informes 90 días. Pruebas Python/contrato Kotlin, límites Gradle, cobertura/lint/builds correctos; feed generado sobre APK real reducido: 297 bytes para APK de 28.168.201 bytes. Consulta remota: repositorio privado, lista de secretos vacía y ningún entorno configurado; rulesets respondió 403 por plan/visibilidad. Facturación, protecciones reales y firma definitiva siguen pendientes. Evidencia: `docs/review-2026-10-17/release/README.md` de esa rama.
4. **Pruebas reales: en curso**, solo con emulador y fixtures desechables. Debug limpio: 11 aprobadas; Cloudflare real: 2 aprobadas; SSH/herdr 0.9.5: recorrido completo aprobado tras corregir cierre del puente observador (TERM/EOF del proceso del canal, sin terminar el servidor). Seis regresiones de canales aprobadas y revisión independiente sin hallazgos. APK reducido autenticó SSH con clave generada dentro de la app; el 404 se comprobó también en Ajustes. Ensayo A → B → C completado posteriormente mediante HTTPS local y CA exclusiva de los APK de prueba (ver `docs/release-readiness/live-validation.md`); no hay teléfono físico, firma definitiva, runtime de 16 KB ni token Access real proporcionados. El intento de feed por túnel público fue rechazado por revisión automática; se cerró ese hosting y se sustituyó por un fixture local.
5. **Privacidad, licencias y materiales:** implementación local de política, MIT, acceso offline y soporte completada el 20/10; auditoría transitiva, publicación accesible y canal privado pendientes. El propietario eligió **MIT** para el código y **GitHub Issues** como canal de reportes; no se inventará un contacto privado.

## Estado comprobado

- [x] Nombre Gradle `wooloo-mobile`, nombre de APK configurado `wooloo-mobile-X.Y.Z.apk` y marca visible `wooloo mobile` existentes. Fuentes: `settings.gradle.kts`, `strings.xml`, `release.yml`. No demuestra que exista una release pública.
- [x] Se preservan `dev.herdroid`, Keystore y enlaces `herdroid://add-host`; conservarlos para mantener compatibilidad.
- [x] Release usa R8 y reducción de recursos; CI declara cobertura, lint y builds debug/release.
- [x] Workflow separa compilación sin secretos de firma/publicación; actions fijadas por SHA.
- [x] Actualizador implementa HTTPS, tamaño/hash, paquete, versión y comprobación de firmante cuando Android lo informa; Android valida la firma al instalar.
- [x] Almacenamiento de secretos cifrado con Keystore; manifest con `allowBackup=false` y reglas que excluyen archivos/preferencias de backup y transferencia. Esto verifica configuración, sin probar todos los mecanismos de migración de fabricantes.

## Seguimiento — PR de acceso remoto

Actualización del 17/10/2026. Cambios complementarios publicados en ambos repositorios. Android PR #19 fusionado en `main` remoto como `c671ae0cd7da512443244d8a8478ab597c7a8155` el 17/10 a las 17:30:55 UTC. PR #65 de wooloo confirmado fusionado en la consulta del 20/10 (ver actualización arriba). La integración de código no cierra por sí sola los requisitos de producción de este checklist.

- **Android, `feat/mobile-remote-access`, base `1f0d2ab`:** PublicKeyCard copia únicamente `key.publicKey`; instrucciones de QR para SSH directo o Cloudflare del Mac local; README y dos pruebas instrumentadas nuevas. Validación propia: 183 pruebas JVM aprobadas y 7 opt-in omitidas; cobertura, lint y builds debug/release correctos; 11 pruebas Android aprobadas y las dos nuevas repetidas tras precisar el texto de la huella. APK debug actualizado conservado en `emulator-5556`. Informe y cuatro capturas en `docs/review-2026-10-17/remote-access/README.md`, integrados en `main` remoto mediante PR #19.
- **Entrega Android:** commit `fd63f7e66401d1a33838c81f241aeeabd372bf0d`, [PR #19](https://github.com/rarce/herdroid/pull/19), ya fusionado. El check alojado [Tests, lint and release](https://github.com/rarce/herdroid/actions/runs/39053472072/job/117456593763) terminó en fallo sin ejecutar pasos. La anotación de GitHub confirma pagos fallidos o límite de gasto de la cuenta; no es evidencia de una prueba o compilación fallida. Resolver el arranque del job y obtener validación alojada antes de cerrar el gate de CI. El commit de instrucciones `AGENTS.md` y su merge local también están publicados en `main` (`09fa9af`).
- **wooloo, `feat/mobile-ssh-remote-access`:** el responsable de esa implementación reporta 15 comprobaciones nuevas aprobadas, incluidas pruebas y snapshot. En la suite de 1034 pruebas reporta cuatro fallos de snapshots de documentación reproducidos también en `origin/main` (`550573e`); no presentarlos como una suite completa verde. Plan y alcance en `docs/mobile-remote-access.md` de esa rama de wooloo.
- **Entrega wooloo:** commit `9deebc40d6a23e5c76a0e7268bb9b77b8efa4b68`, [PR #65](https://github.com/rarce/wooloo/pull/65). El check [Build and test](https://github.com/rarce/wooloo/actions/runs/39053450834/job/117456535627) estaba en ejecución el 17/10; consulta del 20/10 confirma CI final aprobado en el run enlazado en la actualización superior.
- **Interoperabilidad comprobada:** PNG generado por `RemoteAccessLink` de Swift, leído mediante ML Kit y selector de fotos de Android. Se verificaron transporte SSH, host, puerto, usuario, sesión, ruta de herdr y fingerprint del host. Destino ficticio `server.example.test`; no se pulsó Connect ni se probó autenticación SSH real.
- **Alcance:** clave pública por copiar/compartir y pegar; autorización en wooloo con cuenta, huella de la clave pública y ruta de destino revisables; SSH directo al servidor seleccionado. El teléfono debe poder alcanzar host/puerto. Pairing automático y gestión de túneles remotos quedan fuera; Cloudflare sigue limitado al Mac local en la interfaz de wooloo.
- [ ] wooloo PR #65 ya fusionado y CI aprobado (consulta del 20/10); falta repetir interoperabilidad sobre el par definitivo de versiones. Android PR #19 estaba fusionado; el ensayo histórico SSH real con herdr 0.9.5 está separado en `docs/release-readiness/live-validation.md`. Firma definitiva y actualización de producción siguen pendientes.

## P0 — Identidad, canal y firma

- [ ] Confirmar propietario y URL pública definitiva del repositorio. El feed está fijado a `rarce/herdroid`, aunque la marca sea wooloo-mobile (`app/build.gradle.kts`, `docs/updates.md`). Si se renombra, actualizar documentación y builds nuevos, y probar que las versiones ya instaladas siguen obteniendo el feed anterior. Consulta del 20/10 confirma `rarce/herdroid`; falta decisión definitiva del propietario.
- [ ] Comprobar acceso anónimo al repositorio y a sus assets. `docs/updates.md` todavía incluye «hacer público» como pendiente; esta revisión no verifica visibilidad remota. Antes de abrir el código, revisar historial y archivos para evitar publicar secretos, capturas con datos privados o fixtures reales.
- [ ] Crear o identificar la clave definitiva de firma; custodiar copia cifrada fuera del repo y documentar recuperación y responsables. Registrar la huella pública SHA-256. No regenerar una clave si ya existen instalaciones públicas firmadas.
- [ ] Configurar/verificar en GitHub los secretos `HERDROID_KEYSTORE_BASE64`, `HERDROID_KEYSTORE_PASSWORD`, `HERDROID_KEY_ALIAS` y opcional `HERDROID_KEY_PASSWORD`, sin imprimir valores. Su existencia no se comprobó aquí.
- [ ] Probar que el APK publicado está firmado con el certificado definitivo (`apksigner verify --print-certs`) y nunca con `-PreleaseSmoke`, que firma con debug.
- [ ] Elegir la versión inicial y comprobar que su `versionCode` supera las instalaciones que deba actualizar con el mismo paquete y firma. Validar límites de minor/patch, ceros iniciales y overflow: la fórmula actual `major * 1_000_000 + minor * 1_000 + patch` admite colisiones (`0.1.1000` y `0.2.0`) y usa `Int` sin validar rangos. Una instalación debug con otra firma requiere un plan distinto, aunque su código sea menor.
- [ ] Consultar la aplicabilidad de verificación de desarrollador y registro de `dev.herdroid` para los países y canales elegidos; conservar prueba de propiedad de la firma. Android inició despliegue regional el 30/09/2026 para tiendas participantes; no asumir que todos los canales y regiones tienen idéntico requisito. [Guía oficial](https://developer.android.com/developer-verification).

## P0 — Release y actualizaciones reales

- [ ] Restringir quién puede crear/mover tags `v*` y acceder a secretos de publicación; comprobar que el SHA etiquetado pertenece a la rama de release autorizada y pasó sus checks. `release.yml` acepta cualquier tag `v*`, no comprueba pertenencia a `main` ni declara un entorno protegido. Verificar reglas remotas existentes antes de añadir otras; fijar actions por SHA y separar jobs no prueba por sí solo que el código a firmar sea confiable.
- [ ] Confirmar GitHub Actions operativo y una ejecución verde sobre el commit que se va a etiquetar. En el PR #19 se confirmó que el job no arrancó por pagos fallidos o límite de gasto de la cuenta; sigue pendiente acreditar una ejecución alojada correcta.
- [x] `release.yml` ejecuta pruebas JVM, ambos gates de cobertura, lint y builds debug/release sobre el SHA autorizado y con `appVersion`. Incorporado localmente; ejecución alojada pendiente.
- [x] Ejecutados localmente los gates con `-PappVersion=0.92.0` (versión de validación); ver informe del 20/10. Repetir sobre el candidato definitivo.
- [ ] Preparar ensayo con repositorio/feed separado mediante `-PupdateFeedUrl=...` y firma prevista. El workflow actual no tiene modo dry-run ni prerelease: un tag estable de prueba puede publicar y marcar latest. Después verificar el feed definitivo y sus assets anónimamente. Un ensayo privado valida mecánica con autenticación, pero no demuestra acceso anónimo al canal público.
- [ ] Completar actualización A → B con firma definitiva: descarga, permiso para instalar, confirmación, retorno a la app, notificación y conservación de máquinas, claves, preferencias y consentimiento de cuotas. Repetir B → C y registrar si el SO exige interacción; la actualización silenciosa depende de requisitos del instalador y del sistema.
- [ ] **R1 — Recuperación implementada; falta candidato definitivo.** `AppUpdater` persiste fase, release y sesión; recupera confirmación y limpia sesiones huérfanas, con regresiones de recreación. Ensayo histórico con proceso eliminado y firma temporal documentado en `docs/release-readiness/live-validation.md`. Repetir en el candidato de producción y teléfono antes de cerrar el requisito completo.
- [ ] Permiso de instalación propio implementado con `canRequestPackageInstalls` y `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, con retorno recuperable. Hay evidencia histórica en emulador; falta repetir denegación/reanudación y recreación en el candidato final.
- [x] **R2 — Corregido falso «actualizado».** HTTP 404 es un error de comprobación; regresiones `UpdateHttpTest` y `AppUpdaterTest` incorporadas. No confunde feed inaccesible con versión vigente.
- [ ] Probar rechazo de APK con otra firma, paquete, hash o versión; red interrumpida, poco almacenamiento, permiso denegado, cancelación y reintento. Las pruebas de la máquina de estados no sustituyen `PackageInstaller` real.
- [x] El workflow genera y valida `update.json` con límites de tamaño, HTTPS, hash, versión y notas acotadas; pruebas Python y contrato `UpdateFeed.parse` incorporados. La prueba del feed público definitivo sigue abierta.
- [x] Documentada transición desde debug en `docs/updates.md`, incluida incompatibilidad de firma, pérdida de datos al desinstalar y reautorización de claves sin prometer migración automática.
- [x] Workflow configurado para conservar mapping R8 e informes durante 90 días. Su existencia para cada release debe comprobarse al publicar.
- [x] Documentado en `docs/updates.md` cómo retirar el feed defectuoso y recuperar mediante una versión de código mayor; no se promete downgrade.

## P0 — Frontera de confianza de enlaces y QR

- [x] **R5 — Revisión explícita de destino/confianza y reutilización de credenciales incorporada.** `HostSaveReview` exige confirmar antes de guardar cambios sensibles. Regresiones de cancelación sin modificar perfil y cambio legítimo incluidas; evidencia histórica en `docs/review-2026-10-17/security/`.

## P1 — Privacidad, seguridad y licencias

- [ ] Publicar la política para acceso anónimo: ya está escrita en `app/src/main/assets/legal/privacy.txt`, enlazada desde README y accesible offline desde Ajustes. Incluye perfiles, credenciales, cámara/QR, adjuntos, terminal, GitHub, Cloudflare, cuotas y eliminación. Falta publicación pública porque el repositorio sigue privado.
- [ ] Completar revisión de cuotas: la política ya explica lectura y envío de tokens desde el teléfono; redirecciones deshabilitadas con regresión. Verificar consentimiento por identidad y revocación, destinos, redirecciones, ausencia de tokens en logs y comportamiento ante respuestas 401/403/429. Fuentes: `AgentQuotaMonitor.kt`, `QuotaConsentTest`, `QuotaInvalidationTest`.
- [x] **R9 — Secretos fuera del estado restaurable.** Contraseña y Access secret usan estado solo en memoria; prueba de restauración de UI exige reentrada de credenciales. No se afirma inspección de archivos privados de Android.
- [ ] Evaluar soporte y condiciones de los endpoints de cuotas de Anthropic y `chatgpt.com/backend-api/wham/usage`; son una dependencia externa que puede cambiar. Documentar degradación sin bloquear SSH y decidir si se anuncia como función experimental.
- [x] Sustituido `android.media.ExifInterface` por AndroidX 1.4.2; incluidos reflejos EXIF y regresiones de las ocho orientaciones y metadatos inválidos. Ver [fuente AndroidX](https://developer.android.com/jetpack/androidx/releases/exifinterface).
- [ ] Inventariar dependencias directas/transitivas y sus avisos de seguridad con fuentes de los proveedores. Resolver vulnerabilidades aplicables o documentar mitigaciones; no confundir «hay versión más nueva» con vulnerabilidad ni actualizar todas las librerías sin validación.
- [ ] Revisar confianza SSH: primera conexión, cambio de host key, fingerprints importados por QR, enlaces manipulados y ausencia de conexión automática. Verificar que logs y capturas de fallos no revelen secretos ni contenido sensible.
- [ ] Completar auditoría de licencias/notices transitivos, SDK nativos e iconos antes de distribuir. MIT elegida por el propietario ya está en `LICENSE` y Ajustes; OFL de Atkinson también accesible. Inventario de 118 artefactos y declaraciones POM en `docs/dependencies.md` y dentro de la app; esto no sustituye recopilar y revisar notices originales ni términos adicionales.
- [ ] Comprobar derechos del nombre, icono y materiales de marca; aclarar relación con wooloo/herdr y proveedores sin sugerir afiliaciones no existentes.
- [ ] Documentar eliminación de datos y recuperación cuando el Keystore pierde acceso. Verificar desinstalación/reinstalación, borrado de perfil/clave y limpieza de archivos temporales; explicar qué adjuntos permanecen en el servidor.

## P1 — Pruebas del candidato en dispositivo

- [ ] **R3 — Comprobar páginas de 16 KB también en APK directo.** Validar ZIP/ELF del candidato firmado y abrir app, cámara/QR y adjuntos en un entorno con `adb shell getconf PAGE_SIZE` = `16384`. Hay librerías nativas aunque el código propio sea Kotlin. El chequeo estático local de ocho `.so` de 64 bits y `zipalign` pasó; falta ejecución en ese entorno. [Guía Android](https://developer.android.com/guide/practices/page-sizes).
- [ ] Autenticar contra un herdr real con APK release reducido: Ed25519 generado/importado, PEM/OpenSSH cifrada, contraseña y errores de autenticación. El smoke criptográfico no autentica contra SSH.
- [ ] Ejecutar integración opt-in SSH/herdr y Cloudflare con fixtures autorizados, incluyendo Access con service token. Registrar versiones de servidor; README declara probado herdr 0.9.3. No usar máquinas guardadas ni modificar `authorized_keys` ajenos sin autorización.
- [ ] Recorrer máquinas → espacios → agentes → terminal; crear/renombrar/cerrar, composer, adjuntos y reintentos, watch-only/takeover, entrada IME, pegar/copiar, scroll y resize.
- [ ] Probar pérdida/restauración de Wi-Fi, cambio a datos móviles, suspensión, pantalla apagada, proceso eliminado, varias máquinas y desconexión desde notificación. Medir batería/memoria durante salida intensa y sesiones largas; verificar que no quede servicio activo sin conexiones.
- [ ] Probar permisos denegados/revocados de cámara y notificaciones, QR desde cámara/imagen/portapapeles/enlace externo, host desconocido y campos inválidos. Confirmar que importación conserva revisión explícita antes de conectar.
- [ ] Ejecutar `connectedDebugAndroidTest` y `connectedReleaseAndroidTest -PreleaseSmoke` en emulador aislado y smoke manual sobre el APK final firmado. Registrar modelo/API/ABI, tamaño de página, teclado y resultado por escenario. Cubrir mínimo soportado API 26, límites de permisos/servicios/instalador en API 31/33/34 y target API 36, con al menos un teléfono físico y Gboard/SwiftKey. Se puede usar una matriz por riesgos; no implica ejecutar todos los casos en todas las combinaciones. Si se anuncia soporte para API 37, añadirlo explícitamente.
- [ ] Revisar TalkBack, foco, descripciones de controles y lectura del terminal Canvas; fuentes al 200 %, contraste, temas claro/oscuro, insets, rotación y pantallas pequeñas. Definir corrección o limitación explícita de accesibilidad antes del lanzamiento.
- [ ] Clasificar las advertencias propias de lint y resolver las que afecten ejecución, seguridad o accesibilidad. La revisión histórica del 11/10 y la validación del 17/10 tienen conteos distintos; usar el reporte del candidato, sin ocultar warnings globalmente.

## P1 — Materiales y operación del lanzamiento

- [ ] Aplicar **wooloo-mobile** como nombre del producto solicitado y documentar si el launcher conserva la forma legible `wooloo mobile`; alinear recursos, README, imágenes, ficha y notas con esa convención. Mantener `dev.herdroid`, storage y deep links. La puntuación del nombre no bloquea por sí sola la distribución técnica.
- [x] **R4 — Actualizaciones siempre confirmadas.** Instalador y documentación requieren confirmación para cada actualización y recuperan `STATUS_PENDING_USER_ACTION`; no se promete actualización silenciosa.
- [ ] Aprobar versión inicial y convertir `docs/releases/first-release.md` en `vX.Y.Z.md`, incorporando SHA, firma y hash definitivos. El borrador y el recorrido de instalación/onboarding del README ya existen.
- [x] README documenta servidor herdr, acceso SSH, autorización de clave pública, túnel/QR, revisión de fingerprint y primera conexión.
- [x] README enlaza a `releases/latest`, independiente del nombre versionado del APK. Accesibilidad pública pendiente mientras el repositorio siga privado.
- [ ] Actualizar capturas y materiales con datos ficticios y marca definitiva; incluir lista, terminal, QR y ajustes. Revisar icono adaptativo/monocromo y apariencia en launcher.
- [ ] Habilitar y probar canal privado para seguridad. GitHub Issues y plantilla de bugs ya definidos con versión, Android, reproducción y evidencia sanitizada; `SECURITY.md` pide no divulgar vulnerabilidades sensibles públicamente. No existe contacto privado anunciado.
- [ ] Establecer piloto con usuarios limitados, criterios de salida (sin fallos críticos de conexión, datos o actualización) y responsable de soporte de la primera semana.
- [ ] Revisar `docs/updates.md` y README para que reflejen el estado vigente. Conservar el informe anterior como histórico con fecha y enlace al seguimiento actual; no reescribir resultados anteriores como si fueran validaciones nuevas. Mantener este checklist como fuente del estado de lanzamiento.

## P0 condicional — Si se publica en Google Play

Estas tareas se suman a las anteriores solo para ese canal. El APK actual está diseñado para distribución directa.

- [ ] Crear variante de Play sin `REQUEST_INSTALL_PACKAGES` ni instalación de actualizaciones desde GitHub; adaptar la UI y el flujo de actualización. Desactivar solo `BuildConfig.UPDATES` no elimina el permiso del manifest. La [política oficial](https://support.google.com/googleplay/android-developer/answer/12085295) prohíbe usar ese permiso para autoactualización salvo excepciones específicas.
- [ ] Generar y validar AAB (`bundleRelease` para la variante correspondiente), configurar Play App Signing/upload key y decidir compatibilidad de firma con APK directo antes del primer lanzamiento.
- [ ] Justificar `FOREGROUND_SERVICE_SPECIAL_USE` y aportar declaración/material demostrativo en consola. El manifest incluye la descripción del subtipo; eso no garantiza aprobación. [Tipos de servicio y revisión](https://developer.android.com/about/versions/14/changes/fgs-types-required).
- [ ] Completar ficha, icono, capturas, gráficos, contacto, clasificación de contenido, audiencia, anuncios y Data safety de acuerdo con el comportamiento real de app/SDK. Publicar política de privacidad y proporcionar instrucciones de acceso/revisión con un entorno de demostración seguro.
- [ ] Comprobar requisitos actuales de target SDK y avisos de prelaunch sobre el AAB final; repetir la validación de 16 KB en los APK generados desde ese AAB. El chequeo del APK directo no verifica el empaquetado que distribuye Play.
- [ ] Verificar cuenta de desarrollador, países, requisitos de pruebas cerradas aplicables a esa cuenta y realizar pruebas internas antes de enviar a producción. Registrar aprobación de consola como condición de salida.

## P2 — Mejoras posteriores con seguimiento

- [ ] Extraer textos hardcodeados a recursos y decidir idiomas soportados; la mayoría de pantallas están en inglés y `strings.xml` es reducido.
- [ ] Mejorar cobertura de orquestación de terminal/sesiones y ciclo de conexión. Los porcentajes actuales no demuestran cobertura integral de UI ni integración real.
- [x] Añadido job CI con emulador API 36 para `connectedDebugAndroidTest` y smoke criptográfico release. Falta acreditar su primera ejecución alojada; no sustituye la matriz física.
- [ ] Evaluar verificación de dependencias Gradle y checksum del wrapper. Las restricciones de tags, SHA autorizado y acceso a firma quedan en P0; no aplazarlas junto con estas mejoras de reproducibilidad.

## Resultado de la revisión adversarial del checklist

Estos hallazgos describen fallos del plan original. Las correcciones siguientes son del documento; los cambios de código y pruebas indicados siguen abiertos.

| ID | Prioridad | Omisión o afirmación débil | Evidencia y corrección del plan |
| --- | --- | --- | --- |
| R5 | P0 | «No conecta automáticamente» no cubría cambio de destino con secretos existentes | `HostLink.kt`, `HostEditScreen.kt`, `HerdrConnection.kt`, `CloudflareTunnelSocket.kt`; nueva prueba y control específico de confianza |
| R1 | P0 | La prueba A → B no incluía muerte del proceso durante instalación | `AppUpdater.onConfirmationNeeded`, estado en memoria y `PackageUpdateInstaller` sin recuperación de sesión; añadir regresión y caso en teléfono |
| R2 | P0 | Un feed inaccesible podía aparentar éxito | `UpdateHttp.fetchFeed`, `AppUpdater.check` y test de 404; distinguir resultado desconocido de versión vigente |
| R6 | P0 | Separación de jobs y checks verdes no autenticaban el origen del tag | `release.yml` no valida rama/checks ni declara environment; verificar reglas remotas y restringir publicación antes de usar firma |
| R7 | P0 | Validación del feed no incluía límites del parser ni ensayo aislado | `UpdateFeed` limita bytes, workflow no limita notes y publica tags directamente; validar el resultado y aislar el feed de ensayo |
| R3 | P1 | 16 KB figuraba únicamente como requisito de Play | APK incluye ocho `.so` de 64 bits; mover ejecución a todos los canales y conservar comprobación adicional del AAB |
| R4 | P1 | La actualización silenciosa quedaba como expectativa sin comprobar precondiciones | Permiso requerido ausente; corregir promesa o implementar requisitos, sin bloquear lanzamiento por exigir silencio |
| R9 | P1 | Cifrar el almacén no cubría el estado guardado de los campos secretos | `HostEditScreen` usa `rememberSaveable` en contraseña y token; añadir revisión de serialización y prueba de restauración segura |
| R8 | P1/P2 | Casillas amplias y criterios mezclaban decisiones editoriales con fallos técnicos | Reclasificar nombre, registrar evidencia por candidato/escenario, permitir «no aplica» motivado y preservar informes históricos |

## Evidencia y criterio de salida

Comando de revisión local (JDK 17, SDK 36, dependencias en caché):

```sh
./gradlew jacocoDebugCoverageVerification paneMetricsCoverageVerification lintDebug assembleDebug assembleRelease --offline --console=plain
```

Resultado de la revisión inicial: **BUILD SUCCESSFUL**, 1 min 3 s; debug, release de producción con R8, lint y ambos gates de cobertura correctos. JVM, cobertura y debug se reutilizaron como `UP-TO-DATE`; release y análisis lint sí ejecutaron trabajo. No equivale a repetir pruebas en dispositivo. Lint: 0 errores, 51 advertencias y 1 sugerencia; el compilador reporta APIs obsoletas de Compose/clipboard.

La revisión adversarial no repitió Gradle porque solo modifica este documento. Inspeccionó `app-release-unsigned.apk` local (28.151.817 bytes): ocho librerías de `arm64-v8a`/`x86_64` con alineación `PT_LOAD` de 16.384 bytes; `zipalign -c -P 16 4` terminó con código 0. Esto no acredita instalación, carga JNI, RELRO ni compatibilidad del candidato final firmado o de un AAB.

Evidencia previa en [docs/testing.md](../../docs/testing.md), 17/10/2026: 190 pruebas JVM (183 aprobadas, 7 opt-in omitidas), 9 instrumentadas aprobadas; core 70,92 %, global 53,88 %, métricas de panel 100 % líneas y 97,14 % ramas. No son nuevas ejecuciones instrumentadas de esta revisión.

En la revisión inicial/adversarial no se ejecutaron SSH/Cloudflare reales, generación de capturas, instalación/actualización en teléfono ni consultas a secretos/configuración remota. El seguimiento de acceso remoto registra por separado las capturas y pruebas posteriores en emulador de ese cambio; tampoco acreditan conexión SSH real ni actualización de producción. Las siete omisiones opt-in no prueban integración backend.

**Entrada al piloto externo:** P0 cerrados con evidencia del candidato firmado y su canal de ensayo. **Publicación general:** además, P1 aplicables cerrados, piloto aprobado, feed público descargable y recuperación probada. Si un defecto conocido puede exponer credenciales o perder datos, no se sustituye su corrección por una nota de limitaciones. Los P2 restantes deben tener seguimiento. Una nueva compilación/firma requiere volver a identificar el artefacto y repetir las comprobaciones afectadas.
