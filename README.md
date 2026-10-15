# Grabador de voz

App Android sencilla para grabar notas de voz.

- Grabar, pausar, retomar, guardar o descartar.
- Lista de grabaciones con duración, fecha y tamaño.
- Ondas en vivo mientras graba.
- Reproductor con las ondas del audio: se arrastra la bolita para adelantar/atrasar, botones de ±10 s, velocidad 1x / 1.5x / 2x.
- Compartir (WhatsApp, Drive, etc.), renombrar y eliminar.
- Graba en segundo plano, con notificación para pausar, retomar o guardar.

## Descargar

Cada cambio en `main` compila el APK con GitHub Actions y lo publica en **Releases** como `grabador-voz.apk`.
Desde el teléfono: Releases → última versión → `grabador-voz.apk` → instalar.

## Compilar localmente

Abrir en Android Studio y usar *Build → Build APK(s)*, o:

```
./gradlew assembleRelease
```
