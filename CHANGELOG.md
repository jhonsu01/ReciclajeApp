# Changelog

Todas las novedades relevantes de este proyecto se documentan en este archivo.
El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el
versionado sigue [SemVer](https://semver.org/lang/es/).

## [0.3.0] - 2026-07-02

### Corregido

- El botón **Llamar** del panel de escritorio no hacía nada (`window.prompt` no existe
  en Electron): ahora abre un selector de módulos.
- La pantalla del cliente mezclaba el recibo del turno anterior con el turno nuevo.

### Añadido

- **Recibo legible** en el panel: tabla de materiales con subtotales, estado del pago,
  quién autorizó y cuándo, firma abreviada y botón **Descargar JSON**.
- **Materiales personalizados**: agregar nuevos materiales, editar nombres y precios,
  y eliminar (si tienen pesajes históricos solo se ocultan).
- **Cliente sin PIN**: el rol cliente conecta automáticamente y se identifica con su
  documento.
- **Seguridad por sesión para roles elevados** (pesaje, administrador y kiosko):
  - El escritorio genera **PINs aleatorios de 6 dígitos por rol en cada arranque**.
  - El PIN se ingresa una sola vez por dispositivo: el emparejamiento entrega un token
    persistente, con soporte para **múltiples dispositivos** por rol.
  - Sección **Dispositivos emparejados** en el panel: ver último acceso y **revocar
    accesos** al instante; botón para regenerar los PINs de sesión.
- **Notificación de pago al cliente**: cuando el administrador autoriza el desembolso,
  el teléfono vibra, muestra "💵 ¡Pago recibido!" y limpia el turno. El turno permanece
  en pantalla hasta que se pague (no se puede pedir turno nuevo antes).
- **Historial personal de pagos**: el cliente consulta sus pagos anteriores con su
  documento, sin ver los de otras personas.

## [0.2.0] - 2026-07-01

### Añadido

- **Autodescubrimiento del servidor**: la app Android encuentra el servidor automáticamente
  por broadcast UDP (puerto 18300) — ya no es necesario escribir la IP.
- **Selección de rol al emparejar** en la app Android:
  - 👤 **Cliente** (vende material): flujo de turno con QR + PIN, como antes.
  - ⚖️ **Pesaje**: estación móvil que llama turnos, registra peso, ve el valor generado
    y finaliza con recibo.
  - 💵 **Administrador**: lista de recibos pendientes y **autorización de desembolso**
    del efectivo desde el teléfono.
  - 📺 **Modo Kiosko**: pantalla del turnero a pantalla completa para TVs Android,
    sin configurar IP ni PIN (detección automática + reconexión sola).
- **PINs separados por rol** (cliente / pesaje / administrador), configurables en el panel.
- **Estado de pago en los recibos** (`pagado`, `autorizado_por`, `fecha_pago`), con botón
  "Autorizar desembolso" también en el panel de escritorio.
- Compatibilidad con Android TV (categoría Leanback, ícono banner, funciona sin pantalla táctil).

### Cambiado

- Las rutas de pesaje de la API ahora aceptan el PIN de pesaje desde la red local
  (antes solo funcionaban desde el equipo servidor).
- Los operadores (pesaje/admin) ven el documento completo del turno; el público lo ve enmascarado.
- La ventana de escritorio ya no muestra la barra de menú de Electron.

## [0.1.0] - 2026-07-01

### Añadido

- **Aplicación de escritorio Windows (MSI)**: servidor local (Express + WebSocket + SQLite)
  con panel de administración (gestión de turnos, pesaje, materiales/precios y configuración).
- **Pantalla de turnero** (`/display.html`): modo oscuro (#0D0D0D) para pantallas informativas,
  con llamados en tiempo real, sonido, y cinta de precios de materiales.
- **Aplicación Android (APK)**: registro de turno con documento y material opcional,
  QR + PIN del turno, seguimiento del estado en tiempo real, vibración al ser llamado,
  recibo final y cola offline con reintentos automáticos.
- **API REST local**: `/api/turnos`, `/api/materiales`, `/api/pesaje`, `/api/recibos`,
  `/api/config`, `/api/ping` con emparejamiento por PIN para clientes móviles.
- **Seguridad**: rutas administrativas restringidas a localhost, PIN de emparejamiento
  para la app móvil, recibos firmados con HMAC-SHA256.
- **Flujo de estados del turno**: ESPERANDO → LLAMADO → EN_PESAJE → FINALIZADO,
  con timeout configurable para NO_PRESENTADO.
- **13 materiales precargados** (Vidrio, Papel, Plástico, Metal) con precios referenciales
  Mayo 2025 editables desde el panel.
- **CI/CD**: workflow de release que compila y adjunta el MSI y el APK a cada release de
  GitHub al hacer push de un tag `v*` (o ejecución manual), y CI de verificación en `main`.
