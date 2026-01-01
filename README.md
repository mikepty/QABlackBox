# QA BlackBox - Black Box Testing Tool

Herramienta nativa de Android para grabación y análisis de sesiones de testing manual.

## 🎯 Características

- ✅ Grabación de pantalla con MediaProjection
- ✅ Captura de eventos de UI (AccessibilityService)
- ✅ Logging de sistema (Logcat via Shizuku)
- ✅ Botón flotante para control durante testing
- ✅ Sincronización automática de eventos
- ✅ Protección de contraseñas (auto-redacción)

## 📦 Compilación

Este proyecto usa GitHub Actions para compilación automática.

### Para descargar el APK:

1. Ve a la pestaña "Actions"
2. Selecciona el workflow más reciente con ✅
3. Descarga el artifact "QA-BlackBox-Debug"
4. Descomprime y instala `app-debug.apk`

## 🔧 Requisitos

- Android 10+ (API 29+)
- Permisos necesarios:
  - Grabación de pantalla
  - Servicio de accesibilidad
  - Almacenamiento
  - Ventana flotante
  - Notificaciones

## 📱 Uso

1. Instalar APK
2. Abrir app y conceder permisos
3. Iniciar grabación
4. Probar app objetivo
5. Detener desde botón flotante
6. Reportes en `/Movies/QA_BlackBox/`

## 🛡️ Seguridad

- Las contraseñas se redactan automáticamente como `[REDACTED]`
- Los datos permanecen en el dispositivo
- Compatible con Android 14/15

## 📄 Licencia

Proyecto educativo - Uso libre
