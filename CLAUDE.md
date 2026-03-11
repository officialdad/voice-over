# CLAUDE.md

## MCP Usage
- **Always use Context7 MCP** when needing library/API documentation.

## Project Overview
**Voice Over** - An Android app for recording voice narration over videos with synchronized preview.

### Tech Stack
- **Language**: Kotlin 2.1.0
- **Build**: Gradle (Kotlin DSL), AGP 8.7.3
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Dependencies**: AndroidX Core KTX, AppCompat, Material Design, ConstraintLayout, Coroutines

### Project Structure
```
app/src/main/
├── java/com/voiceover/
│   ├── MainActivity.kt          # Video picker, permissions
│   ├── RecordingActivity.kt     # Video preview + voice recording
│   ├── AudioRecorderManager.kt  # MediaRecorder wrapper
│   └── AudioMixer.kt            # Merge voice onto video via MediaMuxer
├── res/
│   ├── layout/                  # XML layouts
│   ├── drawable/                # Icons and shapes
│   ├── values/                  # Colors, strings, themes
│   └── mipmap-*/                # App icons
└── AndroidManifest.xml
```

### Build Commands (Termux)
- **Debug build**: `gradle assembleDebug`
- **Clean**: `gradle clean`
- **Install**: `termux-open app/build/outputs/apk/debug/app-debug.apk`

**Important**: All development is done in Termux on Android. Always run `termux-open` as the final step after a successful build so the user can test the app.
