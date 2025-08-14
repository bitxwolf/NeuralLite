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
| Android (ARM64) | ≥ 4GB RAM, Android 10+      | ✅ Comming soon  |
| Windows (x64)   | ≥ 4GB RAM, Python 3.10+     | ✅ Comming soon  |
| iPad (iOS)      | Requires Vibecode App | ⚠️ BETA |

---

## 📦 Installation Guide

### # NeuraLite (Beta)

NeuraLite is currently in **beta**. This is a testing build for iOS and iPadOS users — expect bugs, glitches, and occasional AI mood swings.  
The **final version** will be released soon with all planned features fully polished.

---

## 📥 Download (iOS / iPadOS)
**Open in Vibecode**:  
[https://www.vibecodeapp.com/projects/84e35c5f-51ac-405e-95b0-f50a18e3bc2d](https://www.vibecodeapp.com/projects/84e35c5f-51ac-405e-95b0-f50a18e3bc2d)]
(runs only when "vibecode" installed from App store)
---

## ⚠️ Notes
- This is an early preview build — functionality may change.
- Performance and stability improvements are coming in the final version.
- Feedback is welcome to help shape the final release.

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

- Plugin SDK (for community-built modules)
- Offline dictionary, translator, and weather modules
- Custom TTS voice cloning

---
## 🧾 License

MIT License © 2025 — NeuraLite Core Team

---

## 👥 Contributors

- **BitWolf** — Vision, specs, testing
- **Vibecode** — Architecture, design, LLM integration
- **Open Source Community** — Piper, Whisper, Mistral, OSM

---

## 📬 Contact

Feel free to reach out via [GitHub Issues](https://github.com/bitxwolf/NeuraLite/issues) for bugs, feature requests, or collaboration.

---

_“Your AI companion — anywhere, anytime, offline.”_
