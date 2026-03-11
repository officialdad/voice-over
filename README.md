<div align="center">

# Voice Over

**Record voice narration over videos with synchronized preview**

[![Latest Release](https://img.shields.io/github/v/release/officialdad/voice-over)](https://github.com/officialdad/voice-over/releases/latest)
[![License](https://img.shields.io/github/license/officialdad/voice-over)](LICENSE)

---

</div>

## Installation

### Obtainium (Recommended)

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/icon_small.png" alt="Get it on Obtainium" height="80">](https://github.com/ImranR98/Obtainium)

1. Install Obtainium
2. Add app with URL: `https://github.com/officialdad/voice-over`
3. Obtainium will download & notify you of updates automatically

### GitHub Release

[<img src="https://github.com/machiav3lli/oandbackupx/blob/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="80">](https://github.com/officialdad/voice-over/releases/latest)

1. Download the `voice-over.apk` in release
2. Install app normally

## Features

- **Video Selection** - Pick any video from your device to narrate over

- **Multi-Segment Recording** - Record voice at any point on the video timeline
  - Seek to different positions and record multiple segments
  - Last-recorded segment wins on overlap

- **Segment Timeline** - Visual indicator showing where you've recorded on the video

- **Preview Playback** - Listen to your recording synced with the video before saving
  - Pause/resume preview at any time
  - Seek through the preview

- **Save & Export** - Merges voice recording onto the video and saves to Movies/VoiceOver

- **Aspect Ratio Preserved** - Video preview maintains original proportions

## Usage

### Recording Flow

| Step | Action |
|------|--------|
| **1** | Select a video from your device |
| **2** | Seek to the position where you want to narrate |
| **3** | Tap the mic button to start recording |
| **4** | Tap stop to end the segment |
| **5** | Repeat steps 2-4 for additional segments |
| **6** | Tap Preview to hear your narration with the video |
| **7** | Tap Save to export the final video |

### Controls

| Button | Action |
|--------|--------|
| **Mic** | Start recording at current position |
| **Stop** | End current recording segment |
| **Preview** | Play video with recorded audio |
| **Re-record** | Clear all segments and start over |
| **Save** | Merge audio and export video |

## System Requirements

- **Android 7.0+** (API 24 or higher)
- **Permissions:** Microphone, Media access

## Building

### Termux (Android)

```bash
gradle assembleDebug
```

### Linux/macOS

```bash
./gradlew assembleDebug
```

### Windows

```bash
gradlew.bat assembleDebug
```

**Output:** `app/build/outputs/apk/debug/app-debug.apk`

## Contributing

Contributions are welcome! Feel free to:

- Report bugs
- Suggest features
- Submit pull requests

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
