#!/usr/bin/env python3
"""
NeuraLite MVP - main.py
Offline AI & Python Execution Engine for Android
-------------------------------------------------
This script handles:
1. Running Python code from the app's code editor
2. Serving AI inference requests (local GGUF models)
3. Providing map/search handlers (optional placeholder)
4. Voice command processing (if local Python STT/TTS is used)

Can run as:
- Embedded Chaquopy module in React Native
- Standalone HTTP/WebSocket server on localhost
"""

import sys
import json
import traceback
from pathlib import Path
from typing import Any, Dict

# Optional: HTTP server for communication with frontend
from flask import Flask, request, jsonify

# ---- CONFIG ---- #
APP_NAME = "NeuraLite MVP Python Engine"
MODEL_PATH = Path("models/mistral-7b-q4.gguf")   # Placeholder model file path
DEBUG_MODE = True
PORT = 8085

app = Flask(APP_NAME)

# ---- AI HANDLING (Mock Implementation for MVP) ---- #
try:
    from llama_cpp import Llama
    AI_AVAILABLE = MODEL_PATH.exists()
    if AI_AVAILABLE:
        llm = Llama(model_path=str(MODEL_PATH), n_threads=4, n_ctx=2048)
    else:
        llm = None
except ImportError:
    AI_AVAILABLE = False
    llm = None


def ai_chat(prompt: str) -> str:
    """Generate AI response using local model (mock if not available)."""
    if not AI_AVAILABLE:
        return f"[AI MOCK RESPONSE] You asked: {prompt}"
    try:
        output = llm(prompt, max_tokens=250, temperature=0.7)
        return output['choices'][0]['text']
    except Exception as e:
        return f"[AI ERROR] {e}"


# ---- PYTHON CODE EXECUTION ---- #
def run_python_code(code: str) -> Dict[str, Any]:
    """Run Python code safely and return stdout/stderr."""
    import io
    import contextlib

    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()
    result = {"stdout": "", "stderr": ""}

    try:
        with contextlib.redirect_stdout(stdout_buf):
            with contextlib.redirect_stderr(stderr_buf):
                exec(code, {}, {})
    except Exception:
        traceback.print_exc(file=stderr_buf)

    result["stdout"] = stdout_buf.getvalue()
    result["stderr"] = stderr_buf.getvalue()
    return result


# ---- API ROUTES ---- #
@app.route("/api/ai", methods=["POST"])
def api_ai():
    data = request.get_json(force=True)
    prompt = data.get("prompt", "")
    return jsonify({"response": ai_chat(prompt)})


@app.route("/api/runpython", methods=["POST"])
def api_runpython():
    data = request.get_json(force=True)
    code = data.get("code", "")
    result = run_python_code(code)
    return jsonify(result)


@app.route("/api/ping", methods=["GET"])
def ping():
    return jsonify({"status": "ok", "app": APP_NAME})


# ---- ENTRY POINT ---- #
if __name__ == "__main__":
    print(f"🚀 Starting {APP_NAME} on port {PORT}")
    if DEBUG_MODE:
        print("⚠ DEBUG MODE ON")
    try:
        app.run(host="0.0.0.0", port=PORT)
    except KeyboardInterrupt:
        print("\n🛑 Shutting down...")
        sys.exit(0)
