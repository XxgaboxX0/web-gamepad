# Web Gamepad — Guía de integración

Contenedor Android (Kotlin + XML) para juegos web de PC: navegador con Google/URL directa
y un gamepad táctil flotante que genera eventos de teclado reales mediante JavaScript.

## 1. Crear el proyecto

Android Studio → **New Project → Empty Views Activity**
- Language: **Kotlin**
- Package name: **com.example.webgamepad** (si usas otro, cambia la línea `package` de `MainActivity.kt`
  y asegúrate de que `namespace` en `build.gradle` coincida)
- Minimum SDK: **API 24**

## 2. Dónde va cada archivo

```
app/
├── build.gradle.kts                      ← añadir dependencias (paso 3)
└── src/main/
    ├── AndroidManifest.xml               ← REEMPLAZAR
    ├── assets/                           ← crear carpeta: clic derecho en main → New → Directory → "assets"
    │   └── gamepad_controller.js         ← NUEVO
    ├── java/com/example/webgamepad/
    │   └── MainActivity.kt               ← REEMPLAZAR
    └── res/
        ├── layout/activity_main.xml      ← REEMPLAZAR
        ├── drawable/
        │   ├── bg_gamepad_button.xml     ← NUEVO
        │   └── bg_url_input.xml          ← NUEVO
        └── values/
            ├── strings.xml               ← REEMPLAZAR
            └── themes.xml                ← REEMPLAZAR
```

Además: **borra `res/values-night/themes.xml`** (el tema oscuro de la plantilla ya no hace falta).

## 3. Dependencias (`app/build.gradle.kts`)

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")   // inyección de JS en todos los frames
}
```
Pulsa **Sync Now**. (Si tu plantilla usa catálogo de versiones, añade las líneas igualmente o
conviértelas a `libs.*`. Puedes subir las versiones a las más recientes.)

## 4. Cómo funciona

1. `MainActivity` lee `gamepad_controller.js` de `/assets` y lo registra con
   `WebViewCompat.addDocumentStartJavaScript` → se ejecuta al inicio de **cada frame**,
   también los `<iframe>` donde muchos portales alojan los juegos.
   Si el WebView es antiguo, hay plan B con `evaluateJavascript` (solo frame principal).
2. Cada botón del gamepad tiene un `android:tag` con el nombre de la tecla (`KeyboardEvent.code`).
   `ACTION_DOWN` → `window.__vgp.down('ArrowUp')`; `ACTION_UP/CANCEL` → `window.__vgp.up('ArrowUp')`.
3. El JS crea `keydown` / `keypress` / `keyup` con `key`, `code`, `keyCode`, `which`, modificadores
   y auto-repetición (`repeat: true`), y los lanza sobre el elemento con foco, el `<canvas>` más grande o
   `<body>`. Al burbujear llegan a `document` y `window`, que es donde escuchan los juegos.
   Los eventos se reenvían a los iframes con `postMessage`.

> Nota: se lanza sobre el elemento destino y no directamente con `document.dispatchEvent`, porque así
> también funcionan los juegos que escuchan en el `<canvas>` o en `<body>`. Si no hay destino mejor, se usa `document`.

## 5. Uso

- **Modo navegar**: escribe una URL (`ejemplo.com`) o un texto (se busca en Google) → `→`.
- **🎮**: entra en modo juego (horizontal, pantalla completa, gamepad visible).
- **☰** (arriba a la derecha) o el botón *Atrás*: vuelve al modo navegar.
- Multitáctil: puedes pulsar varios botones a la vez (↑ + Shift + Space).

## 6. Personalizar

**Añadir un botón**: copia un `<TextView style="@style/GamepadButton" .../>` dentro de `gamepadOverlay`
y pon `android:tag` con el código de tecla. No hay que tocar Kotlin.

Teclas soportadas: `ArrowUp/Down/Left/Right`, `Space`, `Enter`, `Escape`, `Tab`, `Backspace`,
`ShiftLeft`, `ControlLeft`, `AltLeft`, `KeyA`…`KeyZ`, `Digit0`…`Digit9`.
Ejemplo para WASD: `android:tag="KeyW"`.

**Auto-repetición**: en `gamepad_controller.js`, objeto `CONFIG` (`autoRepeat`, `repeatDelayMs`, `repeatRateMs`).

**User-Agent**: `DESKTOP_MODE` en `MainActivity.kt` (`true` = se presenta como PC).

## 7. Problemas frecuentes

| Síntoma | Causa / solución |
|---|---|
| `Unresolved reference: R` | Falta sincronizar Gradle o el `package` no coincide con el `namespace`. |
| `FileNotFoundException: gamepad_controller.js` | La carpeta debe llamarse exactamente `assets` y estar en `src/main/`. |
| Error de tema / `Theme.WebGamepad` no encontrado | No se reemplazó `themes.xml` o queda un `values-night/themes.xml`. |
| El juego no reacciona a los botones | Toca antes el juego (algunos exigen foco). Prueba con un "keyboard tester" online para verificar los eventos. |
| Juego en iframe sin reacción | Comprueba que `addDocumentStartJavaScript` es compatible (actualiza *Android System WebView*). |
| Página http no carga | Revisa `usesCleartextTraffic` en el manifest. |

## 8. Notas de seguridad y licencia

- `usesCleartextTraffic="true"` permite http; ponlo en `false` si solo usarás https.
- `allowFileAccess` está desactivado y se bloquean esquemas `intent:`, `market:`, etc.
- Los errores SSL **no** se ignoran (comportamiento por defecto: cancelar).
- Al publicarlo como Open Source añade un `LICENSE` (MIT o Apache-2.0 son habituales).
