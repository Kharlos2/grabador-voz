# Grabador de voz

App Android sencilla para grabar notas de voz.

- Grabar, pausar, retomar, guardar o descartar.
- Lista de grabaciones con duración, fecha y tamaño.
- Reproductor con barra para adelantar/atrasar, botones de ±10 s, velocidad 1x / 1.5x / 2x.
- Compartir (WhatsApp, Drive, etc.), renombrar y eliminar.
- No graba en segundo plano: al salir de la app, la grabación se pausa.

## Descargar

Cada cambio en `main` compila el APK con GitHub Actions y lo publica en **Releases** como `grabador-voz.apk`.
Desde el teléfono: Releases → última versión → `grabador-voz.apk` → instalar.

## Compilar localmente

Abrir en Android Studio y usar *Build → Build APK(s)*, o:

```
./gradlew assembleRelease
```
