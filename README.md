# 🧠 NeuraLite

**NeuraLite** is a fully offline, multilingual, AI-powered assistant designed for both productivity and exploration. It combines on-device Large Language Models (LLMs), coding capabilities, travel navigation, and voice communication — all packed into a lightweight mobile and desktop application.

---

## ✨ Key Features

### 🔌 Offline & Private
- 100% offline — no data leaves your device
- Works without Wi-Fi or mobile data
- No background tracking, no telemetry

### 🧑‍💻 Coding Mode
- Write, run, and debug Python code locally
- Integrated **DeepSeek-Coder** or **Code Llama**
- Dark, hazy-dripped aesthetic with custom icon
- “Copy Code” button for quick sharing
- Smart syntax highlighting

### 🌍 Travel Mode
- Offline World Map (OpenStreetMap-based)
- Pan/zoom/search locations with zero connectivity
- GPS integration (Android)
- Mode toggle for Coding ↔️ Travel

### 🗣️ Voice Interaction
- Speak in **English** or **Hindi**
- Whisper for real-time speech recognition
- Piper for human-like speech output
- Fully usable without keyboard

---
---

## ⚙️ System Requirements

| Platform        | Specs                      | Status        |
|----------------|-----------------------------|---------------|
| Android (ARM64) | ≥ 4GB RAM, Android 10+      | ✅ Supported  |
| Windows (x64)   | ≥ 4GB RAM, Python 3.10+     | ✅ Supported  |
| iPad (iOS)      | Requires sideload / dev env | ⚠️ Experimental |

---

## 📦 Installation Guide

### 📱 Android (via APK)

1. [Download the APK](#) (coming soon)
2. Enable **Unknown Sources** in your device settings
3. Install the app
4. Grant mic, storage, and location permissions
5. Start using offline

> 💡 To save space, use **Model Toggle Mode** to disable unused AI modules (e.g. only enable LLMs when coding).

---

## 🧠 Models Used

| Purpose         | Model Used             | Offline? | Source |
|----------------|------------------------|----------|--------|
| Coding LLM     | DeepSeek-Coder / Code Llama | ✅       | HF     |
| Chat LLM       | Mistral 7B (4-bit GGUF) | ✅       | HF     |
| Voice Input    | Whisper Small/Medium    | ✅       | OpenAI |
| Voice Output   | Piper TTS (en-IN, hi-IN)| ✅       | RHVoice|
| Maps           | OSM Vector Tiles        | ✅       | OSM    |

---

## 🔧 Developer Features

- Modular toggle for disabling unused AI models
- Option to move model files to **SD card**
- Energy Saver Mode (pauses voice + trims LLM tokens)
- Python REPL view with I/O logging
- GPT-compatible prompt format support

---

## 🚀 Future Plans

- iOS sideloadable build
- Plugin SDK (for community-built modules)
- Offline dictionary, translator, and weather modules
- Custom TTS voice cloning

---
## 🧾 License

MIT License © 2025 — NeuraLite Core Team

---

## 👥 Contributors

- **BitWolf** — Vision, specs, testing
- **ChatGPT-4o** — Architecture, design, LLM integration
- **Open Source Community** — Piper, Whisper, Mistral, OSM

---

## 📬 Contact

Feel free to reach out via [GitHub Issues](https://github.com/bitxwolf/NeuraLite/issues) for bugs, feature requests, or collaboration.

---

_“Your AI companion — anywhere, anytime, offline.”_
