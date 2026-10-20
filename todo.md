# Publicación de wooloo-mobile

Estado depurado el 20/10/2026, después de publicar `803c669` y `46c69e8` en `origin/main`.
Canal de trabajo: APK directo mediante GitHub Releases. Google Play es condicional.
Este checklist registra pendientes actuales; el [checklist anterior](docs/release-readiness/checklist-history-2026-10-20.md)
conserva diagnósticos, decisiones y resultados históricos. Las rutas mostradas como código en el histórico son relativas a la raíz del repositorio.

## Completado e integrado

- [x] Seguridad de enlaces/QR: revisión de destino y reutilización de credenciales; secretos fuera del estado restaurable (R5/R9).
- [x] Actualizador: recuperación persistida, limpieza de sesiones huérfanas, permiso propio recuperable, confirmación por actualización y error correcto ante HTTP 404 (R1/R2/R4, implementación).
- [x] Release: SHA y rama autorizados, validación del tag/versión/feed, ensayo sin publicación, gates antes de firma y mapping/informes durante 90 días. Las protecciones remotas y la ejecución alojada se verifican aparte.
- [x] Validación de rangos/overflow de `versionCode`; documentación de transición desde debug y recuperación mediante versión superior.
- [x] MIT para el proyecto, OFL de Atkinson, política de privacidad e inventario de dependencias accesibles offline; README de instalación/onboarding y plantilla de bugs.
- [x] AndroidX ExifInterface 1.4.2, reflejos EXIF y cuotas sin redirecciones.
- [x] Job de instrumentación API 36 en CI; no equivale a ejecución alojada aprobada.
- [x] Revisión de emulador E1–E4: recuperación del terminal, lectura accesible, cabecera al 200% y eliminación de solicitud automática de notificaciones. Historial `pane.read` corregido.
- [x] Validación de `46c69e8`: 221 pruebas JVM aprobadas, 7 opt-in omitidas; 11 Android aprobadas; lint, ambos gates de cobertura y builds debug/release aprobados. Tres ciclos manuales de red con SSH/herdr 0.9.5.

Evidencia: [release readiness](docs/release-readiness/validation-2026-10-20.md),
[ensayos con firma temporal](docs/release-readiness/live-validation.md),
[revisión y correcciones en emulador](docs/review-2026-10-20/fixes/README.md).
Las validaciones debug o con firma temporal no acreditan el candidato de producción.

## P0 — Firma, canal y publicación

- [ ] Confirmar propietario operativo, URL/canal definitivo y versión inicial; identificar las instalaciones que deberá actualizar.
- [ ] Identificar o crear la firma definitiva, registrar huella SHA-256 y custodiar copia cifrada con responsables y recuperación. No reemplazar firmas de instalaciones públicas existentes.
- [ ] Configurar/verificar secretos de firma en GitHub sin exponer valores; restringir tags `v*`, rama/SHA y acceso al entorno de publicación.
- [ ] Acreditar GitHub Actions verde sobre el SHA candidato. El bloqueo histórico de facturación no se considera resuelto por los checks locales.
- [ ] Revisar historial/artefactos antes de abrir el repositorio y comprobar acceso anónimo al feed y assets. Última consulta registrada (20/10): repositorio privado; no se ha cambiado su visibilidad.
- [ ] Verificar certificado definitivo del APK publicado y repetir gates con su versión real; nunca distribuir el APK firmado con `releaseSmoke`.
- [ ] Ejecutar ensayo aislado con la firma prevista y después validar el feed definitivo público. El ensayo local existente no acredita ese canal.
- [ ] Consultar aplicabilidad de verificación de desarrollador/registro del paquete para países y canales elegidos con fuentes oficiales vigentes.
- [ ] Resolver cuotas Claude antes de distribuir: la documentación actual del proveedor restringe recopilar/intermediar sus tokens. Sustituir o desactivar esta vía, o acreditar autorización. El consentimiento local no cierra este requisito.

## P0 — Actualización del candidato definitivo

- [ ] Completar A → B → C con firma definitiva y teléfono: descarga, permiso, confirmación, retorno, notificación y conservación de perfiles, claves, preferencias y consentimiento de cuotas.
- [ ] Repetir muerte del proceso/recreación durante instalación y denegación/reanudación del permiso propio (R1, validación final).
- [ ] Probar rechazo real de firma/paquete/hash/versión incorrectos, red interrumpida, poco almacenamiento, cancelación y reintento con PackageInstaller.

## P1 — Seguridad, privacidad y licencias

- [x] Revisión local de cuotas: consentimiento por identidad, revocación, destinos fijos, sin redirecciones, límites HTTP y errores 401/403/429. Mensajes HTTP y validación de headers Cloudflare sanitizados para no revelar credenciales.
- [ ] Sustituir/evaluar acceso directo al endpoint interno Codex con una integración documentada o logs sin extraer tokens; la disponibilidad actual no acredita soporte contractual. Restricción Claude elevada a P0.
- [x] Auditoría Maven runtime del 20/10: cinco avisos iniciales; SSHJ 0.40.0 elimina EdDSA 0.3.0 y BC 1.85 corrige los avisos registrados. OSV final: 117 coordenadas, cero avisos devueltos; no equivale a ausencia universal de vulnerabilidades.
- [ ] Completar auditoría de componentes nativos internos y herramientas de build, fuera del alcance del escaneo Maven; repetir avisos al preparar el candidato.
- [x] Revisión de confianza SSH: primera conexión y rechazo de clave cambiada antes de usar credenciales; clasificación por código de error SSHJ. Revisión explícita de fingerprints/enlaces ya integrada.
- [ ] Completar revisión de logs/capturas del candidato en teléfono, incluyendo datos de bibliotecas/sistema y revocaciones reales.
- [x] Inventario de 117 dependencias con POM oficiales/parents, hashes y 68 textos originales de notices de JAR/AAR/Google/upstream, legibles offline en Ajustes. Política actualizada sobre métricas ML Kit.
- [ ] Cerrar obligaciones concretas de notices nativos, términos Google/ML Kit y derechos de iconos/marca. Recopilar textos no es una aprobación jurídica ni sustituye cumplir requisitos adicionales.
- [x] Borrado de perfil/clave con rollback de fallos runtime y eliminación sin Keystore; recuperación y retención de confianza/adjuntos documentadas.
- [ ] Verificar desinstalación/reinstalación y limpieza/recuperación con almacenamiento real del teléfono y comportamientos del fabricante.
- [ ] Publicar la política de privacidad para acceso anónimo cuando el canal sea público.
- [ ] Confirmar derechos de nombre/icono/materiales y describir relación con herdr/proveedores sin sugerir afiliación.
- [ ] Habilitar y probar un canal privado de seguridad; GitHub Issues queda para reportes sanitizados.

Evidencia y límites de este bloque: [auditoría del 20/10](docs/security-licenses/README.md).
Validación nueva: 229 JVM aprobadas, 6 opt-in omitidas; 11 Android debug y 1 release smoke
aprobadas; 5 pruebas Python, lint, cobertura y builds normales correctos.
Estos cambios son posteriores a `46c69e8`; su integración en Git no equivale a una release de producción.

## P1 — Pruebas del candidato en dispositivo

- [ ] Ejecutar runtime con páginas de 16 KB, además del análisis ZIP/ELF del APK firmado (R3). Abrir app, cámara/QR y adjuntos.
- [ ] Probar APK release reducido con SSH real: claves generadas/importadas, PEM/OpenSSH cifradas, contraseña y errores. Cubrir Cloudflare y Access con service token autorizado.
- [ ] Repetir interoperabilidad sobre versiones definitivas de wooloo y Android; ambas PR de acceso remoto están integradas. Registrar versiones.
- [ ] Completar recorrido máquinas/espacios/agentes/terminal: crear, renombrar, cerrar, composer, adjuntos, reintentos, watch/takeover, IME, pegar/copiar, scroll/resize.
- [ ] Completar ciclo de conexión: cambio Wi-Fi/datos, suspensión/pantalla apagada, muerte de proceso, varias máquinas, desconexión desde notificación y sesiones intensas/largas con medición de memoria/batería.
- [ ] Completar permisos denegados/revocados, QR por cámara/imagen/portapapeles/enlace, host desconocido y validación de campos sobre el candidato firmado.
- [ ] Ejecutar instrumentación debug/release smoke y prueba manual firmada con matriz por riesgo: API 26, 31/33/34/36, al menos un teléfono físico y Gboard/SwiftKey. Añadir API 37 solo si se anuncia soporte.
- [ ] Completar auditoría TalkBack auditiva, foco, contraste, temas, insets, rotación y pantallas pequeñas. Texto accesible y cabecera al 200% ya corregidos y comprobados; no equivalen a esta auditoría completa.
- [ ] Clasificar warnings del lint del candidato y resolver los que afecten ejecución, seguridad o accesibilidad.

## P1 — Materiales y operación

- [ ] Alinear nombre visible, README, capturas, icono adaptativo/monocromo y materiales con la convención aprobada; mantener `dev.herdroid` y deep links.
- [ ] Convertir `docs/releases/first-release.md` en notas versionadas con SHA, certificado y hash definitivos; actualizar capturas con datos ficticios.
- [ ] Ejecutar piloto limitado con criterios de salida y responsable de soporte.
- [ ] Revisar README y `docs/updates.md` contra el canal realmente publicado y comprobar mapping/informes por release.

## Solo si se publica en Google Play

- [ ] Crear variante sin `REQUEST_INSTALL_PACKAGES` ni autoactualizador GitHub; adaptar manifest/UI.
- [ ] Validar AAB, upload key/Play App Signing y compatibilidad de firma con APK directo.
- [ ] Justificar foreground service special use; completar ficha, Data safety, privacidad, audiencia, contacto y entorno de revisión.
- [ ] Verificar requisitos vigentes de cuenta, países, target SDK y pruebas cerradas; revisar prelaunch y 16 KB de APK generados desde AAB; obtener aprobación de consola.

## P2 — Mejoras posteriores

- [ ] Extraer textos a recursos y decidir idiomas soportados.
- [ ] Ampliar cobertura de orquestación de terminal/sesiones y ciclo de conexión; las regresiones nuevas no cubren toda la integración.
- [ ] Evaluar verificación de dependencias Gradle y checksum del wrapper.

## Criterio de cierre

Registrar por requisito SHA, versión/código, canal, certificado, hash del artefacto y evidencia.
Para piloto externo: P0 aplicables cerrados. Para publicación general: también P1 aplicables,
piloto aprobado y feed público probado. No cerrar una casilla amplia con evidencia parcial;
separar implementación de validación final y justificar requisitos no aplicables.
