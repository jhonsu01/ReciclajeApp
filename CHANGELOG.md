# Changelog

Todas las novedades relevantes de este proyecto se documentan en este archivo.
El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el
versionado sigue [SemVer](https://semver.org/lang/es/).

## [0.6.0] - 2026-07-10

### Añadido

- **APK independiente para el cliente (`Reciclaje Cliente`)**: nuevo instalador
  (`ReciclajeCliente-vX.Y.Z.apk`, paquete `com.reciclaje.turnero.cliente`) que abre
  directamente el flujo del reciclador, **sin mostrar los demás roles**. Ícono y nombre
  propios. La app completa sigue disponible para pesaje y administrador.
- La app del **administrador** ahora muestra el **detalle de materiales pesados**
  (material, kg, precio y subtotal) al autorizar el desembolso, igual que el panel de
  escritorio.

### Cambiado

- En el panel de escritorio, la tabla de turnos ordena el **más reciente arriba** (para no
  desplazarse hacia abajo cuando hay muchos turnos).

## [0.5.0] - 2026-07-02

### Añadido

- **APK independiente para TV (`Reciclaje TV`)**: nuevo instalador solo-kiosko
  (`ReciclajeKiosko-vX.Y.Z.apk`, paquete `com.reciclaje.turnero.kiosko`) pensado para
  televisores Android. Se instala aparte de la app multirol, con su propio ícono y nombre.
- **Vinculación única del televisor**: el TV se vincula una sola vez (detección automática
  del servidor + PIN de sesión de kiosko). Queda emparejado de forma permanente; al apagar,
  reiniciar o encender ya **no vuelve a pedir el PIN**.
- **Auto-arranque al encender el TV**: el turnero se abre automáticamente tras reiniciar el
  televisor (receptor de `BOOT_COMPLETED`), sin intervención.
- **Revocación con nuevo PIN por rol**: al revocar un dispositivo desde el panel se regenera
  el PIN de sesión **solo de ese rol** y se muestra al administrador. El televisor revocado
  lo detecta (chequeo periódico) y vuelve a pedir vinculación con el nuevo PIN.
- **Regeneración de PIN por rol individual** en el panel (botón por cada rol), además de la
  regeneración global existente.

### Notas

- La app completa (`Reciclaje Turnero`) sigue incluyendo el modo kiosko para pruebas; para
  los televisores de la bodega se recomienda el APK dedicado `Reciclaje TV`.

## [0.4.0] - 2026-07-02

### Corregido

- **Turno duplicado con pago pendiente**: un cliente con turno FINALIZADO pero sin pagar
  podía sacar otro turno con el mismo documento. Ahora ese turno cuenta como abierto:
  al volver a solicitar, la app retoma el turno pendiente en vez de crear uno nuevo.
- **Textos cortados en la app** (roles pesaje y administrador): las listas ahora usan
  tarjetas verticales con el documento completo, el total en grande y botón de ancho completo.

### Añadido

- **Estado de pago visible en el panel**: los turnos finalizados se muestran como
  "POR PAGAR 💵" o "PAGADO ✓", con botón directo "Ver y pagar".
- **Filtros en la tabla de turnos**: búsqueda por documento o número de turno, filtro por
  estado (incluye "Por pagar") y selector de fecha para consultar días anteriores.
- **Columnas Registro y Atención**: hora de registro del turno y hora en que fue llamado.
- **Historial del cliente con pendientes**: separa "⏳ Pendientes de pago" (con total por
  cobrar) de "✅ Pagados" (con total recibido).
- **Marquesina configurable**: velocidad de desplazamiento ajustable y mensaje
  personalizado (teléfonos, avisos) que aparece a continuación de la lista completa de
  precios en las pantallas y el modo kiosko.

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
