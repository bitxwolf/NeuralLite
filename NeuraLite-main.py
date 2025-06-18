"""
main.py - Entry point for NeuraLite
Offline AI Assistant with LLM, Voice, and Maps
"""

import os
import subprocess
from modules.llm.chat import start_chat
from modules.voice.stt import transcribe_audio
from modules.voice.tts import speak_text
from modules.maps.offline_map import launch_map


def main():
    print("🧠 Welcome to NeuraLite - Offline AI Assistant")
    while True:
        print("\nChoose Mode:")
        print("1. Chat")
        print("2. Coding")
        print("3. Voice Assistant")
        print("4. Offline Map")
        print("0. Exit")
        choice = input(">> ")

        if choice == "1":
            start_chat()
        elif choice == "2":
            print("Launching Coding Mode...")
            os.system("python modules/code_editor/editor.py")
        elif choice == "3":
            print("Listening (Whisper)...")
            text = transcribe_audio("input.wav")
            print("User:", text)
            response = start_chat(prompt=text)
            speak_text(response)
        elif choice == "4":
            launch_map()
        elif choice == "0":
            print("Exiting NeuraLite. Goodbye!")
            break
        else:
            print("Invalid choice.")


if __name__ == "__main__":
    main()
