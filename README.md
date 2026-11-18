# ChunkMinion

Plugin para Paper 26.1.2. Un armor stand pequeño (el "minion") que mantiene cargados los chunks a su alrededor.

## Uso
- `/minion give [jugador]` entrega el item del minion.
- Click derecho en un bloque para colocarlo.
- Agáchate + click derecho para recogerlo.
- `/minion clean` limpia registros de minions que ya no existen.
- `/minion reload` recarga `config.yml`.

## Configuración (`config.yml`)
- `radius`: radio de chunks cargados (2 = 5x5). Máximo 8.
- `max-per-player`: máximo de minions por jugador (0 = sin límite).

## Permisos
- `chunkminion.use` (todos): colocar minions.
- `chunkminion.admin` (op): dar items, recargar, recoger minions ajenos, sin límite.

## Compilar
Requiere Java 25 y Gradle.

```
gradle build
```

El `.jar` queda en `build/libs/`. Cópialo a la carpeta `plugins` del servidor.

Con GitHub Actions (`.github/workflows/build.yml`), cada push compila el plugin y sube el `.jar` como artifact en la pestaña *Actions*.

## Nota
Los chunks se mantienen con tickets de plugin (como `/forceload`). No es idéntico a tener un jugador cerca: no hace que aparezcan mobs de forma natural.

Si Gradle no encuentra `paper-api:26.1.2.build.+`, ajusta la coordenada en `build.gradle.kts` según la documentación de Paper.
