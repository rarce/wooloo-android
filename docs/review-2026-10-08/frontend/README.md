# Revisión visual de wooloo mobile

Equipos, spaces y agentes ahora forman listas agrupadas, con separadores discretos y esquinas
solo en los extremos. La jerarquía distingue el nombre, el estado y la tarea; el foco de herdr
se muestra con una marca lateral neutra. Se conserva el orden y el estado enviados por el servidor.

## Dirección visual

El trabajo principal es reconocer una sesión remota y encontrar un agente rápidamente. Las
miniaturas de los spaces muestran la distribución del tab activo, aprovechando el vocabulario
visual de herdr. Se descartó repetir tarjetas independientes porque daban el mismo peso a
todos los niveles y ocupaban demasiado espacio entre agentes.

| Color base | Valor | Uso |
| --- | --- | --- |
| Meadow | `#E9EEEA` | Fondo claro |
| White | `#FFFFFF` | Lista clara |
| Ink | `#16212A` | Texto claro |
| Pasture Night | `#141D21` | Fondo oscuro |
| Corral | `#1D292E` | Lista oscura |
| Wool | `#E9EDE8` | Texto oscuro |

Los cinco colores de estado siguen siendo los de herdr. Atkinson Hyperlegible Next conserva
su función de texto legible: nombres de 17sp con peso medio, encabezados de 20sp y detalles de
13sp. La dirección de conexión usa la misma familia; la monoespaciada queda para terminales.

```text
Spaces                         Agents
┌─────────────────────────┐    ┌─────────────────────────────────┐
│ [layout] api        [⋮] │    │ ● integration-tests working [↩] │
│          ○ idle  5 panes│    │   Write integration tests       │
├─────────────────────────┤    ├─────────────────────────────────┤
│ [layout] web        [⋮] │    │ ● auth-refactor     blocked [↩] │
│          ● working     │    │   Waiting for the migration     │
└─────────────────────────┘    └─────────────────────────────────┘
```

El texto se alinea a la izquierda. Las acciones mantienen una zona táctil de 48dp. Las filas
de agentes ocupan aproximadamente 64–65dp con texto normal; con texto grande, el estado pasa
debajo del nombre. Los estados vacíos permiten desplazamiento para alcanzar las acciones en
una pantalla de poca altura y explican cómo conectar mediante QR.

## Capturas

Las capturas usan componentes de producción y datos de prueba, sin publicar datos de sesiones
personales. El emulador aislado tiene 376dp de ancho, comparable con los 373dp del Huawei.
La barra superior del entorno de prueba simplifica la navegación; las listas son las reales.

| Pantalla | Claro | Oscuro | Claro 200% | Oscuro 200% |
| --- | --- | --- | --- | --- |
| Equipos | [Ver](machines-light.png) | [Ver](machines-dark.png) | [Ver](machines-light-font200.png) | [Ver](machines-dark-font200.png) |
| Spaces | [Ver](spaces-light.png) | [Ver](spaces-dark.png) | [Ver](spaces-light-font200.png) | [Ver](spaces-dark-font200.png) |
| Agentes | [Ver](agents-light.png) | [Ver](agents-dark.png) | [Ver](agents-light-font200.png) | [Ver](agents-dark-font200.png) |

La segunda pasada comprueba la alineación de miniaturas, el contraste de texto secundario,
la separación entre el roster y el layout, y la adaptación del estado con fuentes grandes.
Los nombres y tareas largos se abrevian con puntos suspensivos; la lista permite desplazarse
para alcanzar los agentes y sus acciones fuera del viewport.

## Validación

- Compilación de aplicación y pruebas: correcta.
- Pruebas unitarias: 60 aprobadas, 7 omitidas, 0 fallidas.
- Lint: 0 errores; 43 advertencias y 1 sugerencia pendientes.
- [Pruebas de interfaz](session-list-tests.txt): 5 aprobadas, cubriendo las cuatro combinaciones
  de tema y tamaño de fuente, orden estable al cambiar los estados, altura compacta, acciones
  de mensaje y acciones del estado vacío con texto grande y viewport de 320dp de alto.
- Inspección inicial de equipos y spaces en Huawei. Versión final instalada el 8 de octubre
  de 2026 a las 12:33; apertura y pantalla de equipos verificadas, con los perfiles conservados.

Para repetir la revisión, instalar ambos APK en un emulador aislado y ejecutar:

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e class dev.herdroid.ui.SessionListsTest \
  dev.herdroid.test/androidx.test.runner.AndroidJUnitRunner
```
