from logging import config

import httpx
import json
from LLM_interface import LLM
import yaml

config_path = "configs/agent.yaml"


class Vllm_call(LLM):
    def __init__(self):
        super().__init__()
        self.load_config(config_path)

    def load_config(self, config_path: str):
        with open(config_path, "r") as f:
            config = yaml.safe_load(f)
        print(f"Loaded config: {config}")
        self.model_name = config["vllm"]["model_name"]        
        self.model_ip = config["vllm"]["model_ip"]
    

    def generate(self, prompt: str, thinking: bool = False, max_tokens: int = 16000, stream: bool = True, temperature: float = 0.2) -> str:
        with httpx.Client(timeout=httpx.Timeout(connect=30, read=300, write=30, pool=30)) as c:
            body = {
                "model": self.model_name,
                "messages": [{"role": "user", "content": prompt}],
                "max_tokens": max_tokens,
                "stream": stream,
                "temperature": temperature,
            }
            if not thinking:
                body["chat_template_kwargs"] = {"enable_thinking": False}

            
            response_text = ""
            with c.stream("POST", f"http://{self.model_ip}:8000/v1/chat/completions",
                        headers={"Content-Type": "application/json"},
                        content=json.dumps(body)) as r:
                in_thinking = False
                for line in r.iter_lines():
                    if not line.startswith("data:") or "[DONE]" in line:
                        continue
                    delta = json.loads(line[5:])["choices"][0]["delta"]

                    if thinking and (t := delta.get("reasoning")):
                        if not in_thinking:
                            print("💭 Thinking:\n")
                            in_thinking = True
                        print(t, end="", flush=True)

                    if a := delta.get("content"):
                        if in_thinking:
                            print("\n\n" + "─"*60 + "\n")
                            in_thinking = False
                        print(a, end="", flush=True)
                        response_text += a
            return response_text
                        
                        
if __name__ == "__main__":
    agent = Vllm_call()
    prompt = "What is the capital of France?"
    agent.generate(prompt, thinking=False, max_tokens=100, stream=True, temperature=0.2)

    # print("\n\nFinal response:", response)
