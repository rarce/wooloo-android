# Seguridad y licencias — 20/10/2026

Auditoría local sobre `46c69e8` más los cambios de este informe. Se revisaron dependencias
runtime, cuotas, confianza SSH, borrado/Keystore y avisos de terceros. No es una auditoría
exhaustiva del producto, una aprobación jurídica ni una validación de firma de producción.

## Hallazgos y cambios

| Hallazgo | Cambio / estado |
| --- | --- |
| Dependencias con cinco avisos OSV | SSHJ 0.39.0 → 0.40.0; Bouncy Castle prov/pkix/util alineados en 1.85. EdDSA 0.3.0 deja de estar en el grafo. |
| Mensajes HTTP de cuotas podían incorporar valores de headers rechazados | Error fijo sin causa ni mensaje interno. Regresiones de excepción con token y respuestas 403/429. |
| Validación de headers Access podía mostrar el secreto en un error de OkHttp | Mensaje seguro sin causa; prueba con un token inválido comprueba que no se envía ninguna petición. |
| Rechazo de host key clasificado por texto de excepción | Se usa el código `HOST_KEY_NOT_VERIFIABLE`, para que un rechazo no se trate como caída recuperable. |
| El ensayo repetido SSH encontró un TERM tardío contra canales ya terminados | Cierre idempotente; comandos completados cierran sin señal, y TERM queda reservado a cancelación explícita de procesos activos, como el observador. Evita que OpenSSH desconecte el transporte por una petición a un canal liberado. |
| Al borrar, se quitaban metadatos antes de terminar el borrado de secretos | Perfil/clave y entradas cifradas usan la transacción existente. Si falla almacenamiento, queda disponible el elemento para reintentar. Pruebas de fallo de preferencias, fallo de metadatos y borrado sin Keystore. |
| Política incompleta respecto de ML Kit | Se informa de métricas/uso y comunicaciones del SDK, separadas del procesamiento local de imágenes. |
| Inventario de POMs incompleto y sin notices originales | POM oficiales con herencia, hashes de artefactos y textos originales de AAR/JAR y upstream; lector offline de notices en Ajustes. |
| Cuotas Claude leen tokens OAuth de otro producto | **Bloqueo abierto de publicación:** decidir sustitución/desactivación o acreditar permiso del proveedor; ver abajo. No se eliminó silenciosamente esta función. |

## Dependencias: avisos y alcance

Consulta OSV por coordenada/version de `releaseRuntimeClasspath` el 20/10/2026:
[antes](osv-before.json), [después](osv-after.json), [inventario con hashes](runtime-inventory.json).
Antes: 118 coordenadas, cinco avisos sobre tres artefactos. Después: 117 coordenadas,
**ningún aviso devuelto por OSV**. El resultado no significa ausencia universal de vulnerabilidades.
No incluye Gradle/JDK/SDK del equipo, sistema Android ni CVE de componentes nativos embebidos
sin coordenada Maven propia. Reevaluar antes de cada release.

| Aviso | Componente anterior | Tratamiento |
| --- | --- | --- |
| CVE-2020-36843 / GHSA-p53j-g8pw-4w5f | net.i2p.crypto:eddsa:0.3.0 | Retirado mediante SSHJ 0.40.0. Smoke Ed25519 comprueba firma válida, mensaje alterado y firma no canónica S+L. |
| CVE-2026-5588 / GHSA-wg6q-6289-32hp | bcpkix 1.80 | Actualizado a 1.85; no se identificó uso propio de CompositeVerifier. No se afirma explotación demostrada en la app. |
| CVE-2026-0636 / GHSA-c3fc-8qff-9hwx | bcprov 1.80.2 | Actualizado a 1.85; no se identificó configuración de LDAPStoreHelper. |
| CVE-2026-8763 / GHSA-9pwp-9qqc-pr26 | bcprov 1.80.2 | Actualizado a 1.85, con corrección de restricciones de nombres. |
| CVE-2026-13506 / GHSA-qp49-qgx5-5m26 | bcprov 1.80.2 | Actualizado a 1.85; importación de claves utiliza parsing criptográfico, sin dar por demostrado el recorrido vulnerable específico. |

Fuentes del proveedor: [SSHJ 0.40.0](https://github.com/hierynomus/sshj/releases/tag/v0.40.0),
[reporte EdDSA](https://github.com/str4d/ed25519-java/issues/82),
[BC 1.85](https://github.com/bcgit/bc-java/releases/tag/r1rv85v2),
[BC LDAP](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%900636),
[BC CompositeVerifier](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%905588),
[BC Name Constraints](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%908763),
[BC ASN.1](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9013506).

## Cuotas y credenciales

- El consentimiento se vincula mediante hash a la identidad remota, incluido host, usuario,
  transporte, puerto, auth, clave, fingerprint y ruta herdr. Cambiar la etiqueta no lo revoca;
  cambiar identidad requiere consentimiento nuevo. Tests `QuotaConsentTest`.
- Se comprueba consentimiento antes y después de leer archivos; invalidar la conexión o
  revocar cancela polling y limpia caché/estado. Tests `QuotaInvalidationTest`.
- Destinos HTTP fijos; no siguen redirecciones; timeout 20 s, respuesta máxima 512 KiB.
  401/403 rechazan credencial, 429 informa límite; errores no muestran cuerpo del proveedor.
  Los mensajes internos de HTTP ya no se propagan. No hay logging propio de tokens en los
  puntos inspeccionados; esto no acredita ausencia de todo dato sensible en Android o librerías.
- No se renuevan los tokens del agente. Fallos de cuotas no cierran SSH; Codex puede usar
  uso extraído del log remoto. Las peticiones completadas antes de revocar no se pueden retirar.
- No se usaron credenciales reales de Anthropic/OpenAI en esta auditoría.

**Pendiente de producto/proveedor:** la [documentación de Anthropic](https://code.claude.com/docs/en/legal-and-compliance#authentication-and-credential-use)
restringe que terceros recopilen, almacenen o intermedien credenciales/tokens de Claude.ai.
La implementación lee el token de Claude Code por SSH y lo usa desde Android; por ello no se
considera aprobada para distribución. El consentimiento del usuario y la etiqueta experimental
no demuestran autorización del proveedor. Resolver mediante sustitución/desactivación o
permiso explícito antes del lanzamiento. Esta es una evaluación de compatibilidad técnica
con la documentación publicada, no un dictamen jurídico.

Para Codex, [OpenAI Docs: autenticación](https://learn.chatgpt.com/docs/auth) describe la protección
de credenciales y [App Server](https://learn.chatgpt.com/docs/app-server) ofrece una integración
documentada. Las páginas consultadas no establecen un contrato público para que esta app lea
`chatgpt.com/backend-api/wham/usage` directamente con tokens extraídos del host. Mantener pendiente
la evaluación de una alternativa documentada o logs sin extracción de credenciales; no inferir
permiso ni prohibición universal a partir de la ausencia de documentación.

## SSH, borrado y recuperación

La verificación de host key precede a la autenticación. La clave conocida permite continuar;
una clave desconocida/cambiada requiere revisión, salvo fingerprint previamente importado y
revisado explícitamente. `HostSaveReview` protege cambios de destino con credenciales guardadas.
Se añade integración real que presenta una huella anterior incorrecta, rechaza el cambio y
comprueba que no se invoca el proveedor de credenciales. El recorrido E2E existente cubre
primera confianza y autenticación con una clave generada en un fixture desechable.

Al eliminar una máquina se desconecta, revoca el consentimiento actual y borra perfil,
contraseña y Access secret. Claves SSH y confianza host/puerto son independientes y pueden
ser compartidas por otros perfiles: **no se eliminan por borrar una máquina**. El historial
de hashes de consentimiento de identidades anteriores puede permanecer; no contiene tokens
ni autoriza una nueva identidad, que tiene otro identificador de perfil.

Borrar una clave elimina su secreto y passphrase local, no la clave pública autorizada en
el servidor. Revocarla requiere quitarla de `authorized_keys`. Si Keystore falla, lectura
produce un mensaje seguro, conserva ciphertext y no genera una clave de reemplazo; se puede
borrar aun sin Keystore y reimportar/generar otra identidad. Las transacciones cubren fallos
runtime, no un corte de energía atómico entre SharedPreferences y JSON.

Los adjuntos usan cache privada y límites de lectura; su limpieza por antigüedad no es un
borrado programado garantizado. Los adjuntos remotos, copias compartidas y registros del
proveedor permanecen fuera del control del borrado local. La política ya lo explica.
Restablecer almacenamiento Android elimina también preferencias/confianza/cache; no hacerlo
como reparación si solo existe allí una clave necesaria. Desinstalación/reinstalación y
restauración de fabricantes sobre teléfono físico siguen pendientes.

## Licencias y notices

- [Inventario de las 117 coordenadas](../dependencies.md): POM de Google Maven/Maven Central,
  incluidos parents cuando la licencia se hereda; origen y hash registrados. Sin deducir
  licencia a partir del nombre del paquete ni usar caché ausente como declaración negativa.
- 68 textos distintos, aproximadamente 1,05 MB antes de cabeceras, preservados desde los
  artefactos y [fuentes upstream versionadas](upstream/sources.json). La deduplicación es por
  hash del texto exacto; se conservan todas las atribuciones y procedencias.
- Se leen `third_party_licenses.json`/`.txt` de Google con offsets **en bytes**, así como
  LICENSE/NOTICE de JAR/AAR y sus JAR anidados. Incluyen avisos de componentes internos como
  TensorFlow, libyuv, libjpeg-turbo, Eigen y bibliotecas de runtime. No se descartan notices
  porque no tengan coordenada Maven independiente.
- Textos Apache, BC y SSHJ/NOTICE suplementarios incluidos; SLF4J tiene licencia embebida.
  Los LICENSE.md repetidos de BC se fusionan en empaquetado, además de su lectura offline.
- MIT del proyecto y OFL de la fuente se mantienen separados. Google ML Kit y Play services
  tienen términos adicionales; el conjunto del APK no se presenta como enteramente MIT.
- [Google: notices OSS](https://developers.google.com/android/guides/opensource) explica la
  responsabilidad de conservar los avisos. [ML Kit: términos y privacidad](https://developers.google.com/ml-kit/terms)
  distingue procesamiento local de imágenes y envío de métricas; la política fue actualizada.

**Pendiente de cierre jurídico/distribución:** revisar obligaciones específicas de los avisos
nativos (incluidas licencias con requisitos adicionales y excepciones de runtime), aceptación
aplicable de términos Google/ML Kit, procedencia/derechos de iconos y marca. No se afirma que
recopilar textos por sí solo satisfaga cada obligación. La política pública y canal privado
siguen dependiendo de la configuración de publicación y del propietario.

Ejemplos concretos a revisar en los textos conservados: Eigen/Public Suffix List incluyen
referencias MPL; `gcc_14_1_0` incluye GPL y GCC Runtime Library Exception; los avisos compuestos
de Kotlin contienen referencias adicionales GPL/LGPL/MPL. Su presencia en notices no prueba
por sí sola que todo el APK esté sujeto a esas licencias: hay que determinar componente,
uso, modificaciones y excepción aplicable antes de cerrar el requisito.

## Reproducibilidad

```sh
./gradlew -I scripts/dependency_inventory.init.gradle :app:exportAuditRuntime
python3 scripts/dependency_notices.py app/build/audit/runtime-artifacts.json
python3 scripts/audit_runtime.py app/build/audit/runtime-artifacts.json docs/security-licenses/osv-after.json
python3 -m unittest discover -s scripts -p 'test_*.py'
```

El exportador solo lee `releaseRuntimeClasspath`. El generador consulta POM públicos y conserva
su caché en `app/build/audit/poms`; no envía fuentes, tokens ni perfiles. Reutiliza los textos
upstream versionados en este directorio. Tras cambiar dependencias, regenerar, revisar notices,
repetir OSV y validar el APK. Los informes no contienen claves privadas de los fixtures.

## Validación

- Build limpio: 235 casos JVM, **229 aprobados y 6 opt-in omitidos**, cero fallos/errores.
  Incluye los dos casos SSH contra el fixture aislado: recorrido completo y rechazo de
  host key cambiada antes de autenticación. Cloudflare real y capturas opt-in no se ejecutaron.
- Regresión del cierre SSH: tres ejecuciones enfocadas consecutivas aprobadas, más la suite
  limpia completa. Se corrigió el fallo intermitente observado; no se ocultó mediante reintentos
  automáticos dentro de la prueba.
- Python: 5 pruebas aprobadas, incluidas preservación Unicode/offsets de notices y rechazo
  de rangos truncados.
- JaCoCo general y métricas, Android lint y builds debug/release: aprobados desde cero.
- Android debug: **11/11 aprobadas** en el emulador aislado Android 16/API 36.
- Android release smoke: **1/1 aprobada** con R8 y firma temporal debug; verifica claves
  Ed25519 generadas/importadas, firma válida, mensaje alterado y rechazo de S+L.
- Verificado dentro de ambos APK que los 68 notices y la política corresponden a los assets
  actuales. [Índice offline](evidence/02-notices.png) y
  [licencia leída sin Wi-Fi/datos](evidence/04-bouncy-license.png).
- [Resumen y hashes](validation.json). El release normal es unsigned; no se publicó ni firmó
  un candidato de producción. No hubo teléfono físico ni tokens reales de proveedores.
