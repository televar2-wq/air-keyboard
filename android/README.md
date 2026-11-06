# Air Keys

Camera-based "invisible keyboard" for Android. The phone watches your hands on a desk,
draws a keyboard over the camera view, and types the keys you tap into any app.

## Get the APK
Every push to `main` builds the app on GitHub Actions and publishes it under **Releases**.
Latest APK: https://github.com/televar2-wq/air-keyboard/releases/latest/download/AirKeys.apk

## First run
1. Open Air Keys and tap the four setup buttons: Camera, Floating window, Turn on keyboard, Choose keyboard.
2. Pick where the phone is: standing in front of your hands (front camera) or looking down (rear camera).
3. Start the camera and drag the keyboard's corners so it lines up with your desk.
4. Open any app, tap a text field, and type on the desk. The floating window shows what the camera sees.

## Notes
- Tap detection works best with the phone at desk level looking across at your fingers.
- Use the sensitivity slider if it misses taps (raise) or types extra letters (lower).
- The camera runs as a foreground service with a notification; tap Stop there to end it.
