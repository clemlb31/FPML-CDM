#!/usr/bin/env python3
"""Interactive GitHub Copilot CLI.

Uses the unified LLM interface with the 'copilot' backend.
Maintains conversation history for multi-turn chat.

Usage:
    python copilot_call.py [--model gpt-4o]

Requirements:
    GH_KEY in .env must be a GitHub OAuth token (ghu_...) obtained from
    VS Code's Copilot extension auth flow — NOT a Personal Access Token (ghp_...).
"""
import argparse
import sys
from pathlib import Path
import os
import requests

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agent.llm_interface.LLM_interface import LLM, COPILOT_MODELS




DEFAULT_MODEL = "openai/gpt-4o"
SYSTEM_PROMPT = "You are a helpful coding assistant. Answer in the same language as the user."
# GitHub Models endpoint — accepte les PATs classiques (ghp_...) avec le scope models:read
# https://github.com/marketplace/models
GITHUB_MODELS_URL = "https://models.github.ai/inference/chat/completions"

# Modèles disponibles sur GitHub Models
# Voir : https://github.com/marketplace/models
COPILOT_MODELS = [
    "openai/gpt-4o",
    "openai/gpt-4o-mini",
    "openai/gpt-4.1",
    "openai/gpt-4.1-mini",
    "openai/o4-mini",
    "openai/o3",
    "meta/llama-4-scout",
    "meta/llama-4-maverick",
    "mistral-ai/mistral-large-2411",
    "deepseek/deepseek-v3-0324",
    "deepseek/deepseek-r1",
    "xai/grok-3",
    "xai/grok-3-mini",
    "microsoft/phi-4",
    "cohere/cohere-command-r-plus-08-2024",
]


class Copilote_call(LLM):
    def __init__(self, model_name: str, copilot_token: str = None):
        super().__init__()
        self.model_name = model_name
        self._pat = copilot_token or os.getenv("GH_KEY")
        if not self._pat:
            raise ValueError(
                "Copilot backend requires GH_KEY in .env or copilot_token argument.\n"
                "Create a GitHub PAT at https://github.com/settings/tokens\n"
                    "with the scope 'models:read', then activate GitHub Models at\n"
                    "https://github.com/marketplace/models"
                )

    # def _copilot_chat(self, messages: list[dict]) -> str:
    #     resp = requests.post(
    #         GITHUB_MODELS_URL,
    #         headers={
    #             "Authorization": f"Bearer {self._pat}",
    #             "Content-Type": "application/json",
    #         },
    #         json={
    #             "model": self.model_name,
    #             "messages": messages,
    #             "stream": False,
    #         },
    #         timeout=60,
    #     )
    #     resp.raise_for_status()
    #     return resp.json()["choices"][0]["message"]["content"]
def main():
    parser = argparse.ArgumentParser(description="Interactive GitHub Copilot CLI")
    parser.add_argument("--model", default=DEFAULT_MODEL, choices=COPILOT_MODELS)
    args = parser.parse_args()

    try:
        llm = LLM(backend="copilot", model_name=args.model)
    except ValueError as e:
        print(f"[error] {e}")
        sys.exit(1)

    history: list[dict] = []
    print(f"Copilot Chat [{args.model}] — 'exit' pour quitter, 'reset' pour effacer l'historique\n")

    while True:
        try:
            user_input = input("Toi : ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nAu revoir.")
            break

        if not user_input:
            continue
        if user_input.lower() == "exit":
            print("Au revoir.")
            break
        if user_input.lower() == "reset":
            history.clear()
            print("Historique effacé.\n")
            continue

        history.append({"role": "user", "content": user_input})
        messages = [{"role": "system", "content": SYSTEM_PROMPT}] + history

        print("Copilot : ", end="", flush=True)
        try:
            reply = llm.generate_chat(messages)
            print(reply)
            history.append({"role": "assistant", "content": reply})
        except requests.HTTPError as e:
            print(f"Erreur API : {e}")
            history.pop()  # retire le message sans réponse
        print()


if __name__ == "__main__":
    main()
