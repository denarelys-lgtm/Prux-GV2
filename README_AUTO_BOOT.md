# DetectCamera — arranque automático

## Qué hace esta versión

- `BootReceiver` inicia `ServerService` después de `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`.
- `ServerService` mantiene una única instancia del `WebServer` mediante `WebServerManager`.
- `ServerService` usa `specialUse`, evitando el tipo `dataSync`, porque Android 15 impone restricciones de arranque desde `BOOT_COMPLETED` y además limita `dataSync` a 6 horas por 24 horas para apps que apuntan a API 35+.
- `CameraService` reutiliza ese mismo servidor y ya no lo detiene en `onDestroy()`.
- `ServerService` comprueba periódicamente la disponibilidad de Shizuku y emite el estado mediante un broadcast interno.
- El servidor puede permanecer activo aunque `CameraService` sea recreado.

## Importante sobre MediaProjection

El arranque automático no intenta falsificar ni saltarse el consentimiento de captura de pantalla de Android. Shizuku puede estar disponible después del reinicio, pero la sesión/ticket de MediaProjection no se debe asumir como persistente entre reinicios.

Por eso la versión automática garantiza el servidor y la detección de Shizuku; la captura de pantalla continúa dependiendo de una autorización de MediaProjection válida.

## HyperOS

Para máxima persistencia, permitir en el sistema:

- Inicio automático para DetectCamera.
- Batería: Sin restricciones.
- Notificaciones para la notificación del Foreground Service.
