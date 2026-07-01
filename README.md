# ♻ Reciclaje Turnero

Sistema de gestión de reciclaje con turnero inteligente, **offline-first** y de red local:

| Componente | Tecnología | Entregable |
| --- | --- | --- |
| 🖥️ Escritorio (servidor + panel admin) | Electron + Express + WebSocket + SQLite | Instalador **MSI** |
| 📱 Cliente reciclador | Android (Kotlin) | **APK** |
| 📺 Pantallas informativas | Web en modo oscuro (`/display.html`) | Servida por el escritorio |

Los instaladores se publican automáticamente en la sección **[Releases](../../releases)** de este repositorio.

## Arquitectura

```
[APP ANDROID] ←— WiFi local + PIN —→ [SERVIDOR LOCAL (DESKTOP)]
                                            │  API REST + WebSocket + SQLite
                                            ▼
                                  [PANTALLAS TURNERO /display.html]
```

- **Offline-first**: todo funciona en red local, sin internet. La app Android encola la
  solicitud y reintenta automáticamente si pierde conexión.
- **Emparejamiento por PIN**: los clientes móviles se autentican con el PIN configurado
  en el panel de administración (header `X-PIN`).
- **Seguridad**: las rutas administrativas solo aceptan conexiones desde el propio equipo
  servidor; los recibos se firman con HMAC-SHA256; el número de documento se enmascara en
  las respuestas públicas del turnero.

## Instalación

1. Descarga desde [Releases](../../releases):
   - `ReciclajeTurnero-X.Y.Z.msi` → instálalo en el PC Windows (servidor).
   - `ReciclajeTurnero-vX.Y.Z.apk` → instálalo en los teléfonos Android (permite orígenes desconocidos).
2. Abre **Reciclaje Turnero** en el PC. En el encabezado del panel verás la IP del servidor
   y el PIN de emparejamiento.
3. En el teléfono (misma red WiFi o hotspot del PC): ingresa IP, puerto (3000) y PIN.
4. En las pantallas informativas abre `http://IP_DEL_SERVIDOR:3000/display.html`
   (Chromium en modo kiosk recomendado: `chrome --kiosk http://...`).

## Flujo del sistema

1. **Registro**: el reciclador solicita turno desde la app (tipo y número de documento,
   material opcional) → recibe número de turno, QR y PIN.
2. **Visualización**: las pantallas muestran los turnos en espera y los llamados con su módulo.
3. **Llamado**: el administrador llama al turno → el teléfono vibra y muestra el módulo.
4. **Pesaje**: el pesador registra material y kilos (puede corregir el material declarado);
   el sistema calcula el valor con los precios configurados.
5. **Finalización**: se genera un recibo JSON firmado, visible en el panel y en la app.
6. **Ausencias**: si el reciclador no se presenta en el tiempo configurado, el turno pasa a
   NO_PRESENTADO automáticamente y sigue el siguiente.

## API REST local

| Método | Ruta | Acceso | Descripción |
| --- | --- | --- | --- |
| GET | `/api/ping` | PIN | Verificación de emparejamiento |
| POST | `/api/turnos` | PIN | Crear turno |
| GET | `/api/turnos` | Público* | Turnos del día (documento enmascarado) |
| GET | `/api/turnos/:id` | PIN | Detalle y estado de un turno |
| PUT | `/api/turnos/:id/estado` | Solo servidor | Cambiar estado / asignar módulo |
| GET | `/api/materiales` | Público | Materiales y precios |
| PUT | `/api/materiales/:id` | Solo servidor | Actualizar precios |
| POST | `/api/pesaje` | Solo servidor | Registrar pesaje |
| POST | `/api/turnos/:id/finalizar` | Solo servidor | Generar recibo y finalizar |
| GET | `/api/recibos/:turno_id` | PIN | Recibo firmado |
| GET/PUT | `/api/config` | Solo servidor | Configuración (PIN, timeout, módulos) |

\* Pensado para las pantallas informativas. WebSocket en `/ws` para actualizaciones en vivo.

## Desarrollo

### Escritorio

```bash
cd desktop
npm install
npm start            # ejecutar en desarrollo
npm run dist         # generar el MSI (dist/ReciclajeTurnero-X.Y.Z.msi)
node ../.ci/smoke-test.js   # smoke test de la API
```

### Android

```bash
cd android
./gradlew assembleDebug      # APK de depuración
./gradlew assembleRelease    # APK firmado (app/build/outputs/apk/release/)
```

> ⚠️ El keystore incluido (`android/keystore/`) es de desarrollo, para que CI y builds
> locales firmen igual. Para producción genera uno propio y muévelo a GitHub Secrets.

## Releases automáticas

Cada push de un tag `v*` compila el MSI (Windows) y el APK (Linux) en GitHub Actions y los
adjunta a una Release con notas generadas automáticamente:

```bash
git tag v0.2.0
git push origin v0.2.0
```

¿El tag no subió (GitHub Desktop)? Ejecuta el workflow **Release** manualmente desde la
pestaña *Actions* indicando el tag deseado.

## Precios de materiales

Los 13 materiales precargados (familias Vidrio, Papel, Plástico y Metal) usan **precios
referenciales de Mayo 2025 en COP/kg**. Son editables desde *Panel → Materiales y precios*;
varían por región, calidad y volumen.

## Licencia

[MIT](LICENSE)
