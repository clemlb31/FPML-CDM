import lmstudio as lms

from agent.llm_call.LLM_interface import BaseLLM


class LMstudio_call(BaseLLM):
    """LMStudio-specific LLM subclass. Overrides generate to strip <think> tags
    emitted by reasoning models (e.g. Qwen3)."""

    def __init__(self, model_name: str = "qwen/qwen3.5-9b", lmstudio_base_url: str = "http://localhost:1234"):
        super().__init__()
        self.model_name = model_name or "qwen/qwen3.5-9b"
        self.lmstudio_base_url = lmstudio_base_url.rstrip("/")
        self._lms_client = lms.Client()
        self._lms_client.__enter__()
        self._lms_model = self._lms_client.llm.model(self.model_name)
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._lms_client is not None:
            self._lms_client.__exit__(exc_type, exc_val, exc_tb)
            self._lms_client = None
            self._lms_model = None
            
    def generate(self, prompt: str) -> str:
        response = self._lmstudio_chat([{"role": "user", "content": prompt}])
        if "</think>" in response:
            response = response.split("</think>")[-1].strip()
        return response

    def _lmstudio_chat(self, messages: list[dict]) -> str:
        if self._lms_model is None:
            raise RuntimeError(
                "LLM not initialized. Use as a context manager: `with LLM(...) as llm:`"
            )
        # Single-message: pass prompt directly for clean output
        if len(messages) == 1:
            return str(self._lms_model.respond(messages[0]["content"]))
        # Multi-turn: format as labelled conversation
        prompt = "\n".join(f"{m['role'].upper()}: {m['content']}" for m in messages)
        return str(self._lms_model.respond(prompt))
    
    

if __name__ == "__main__":
    llm = LMstudio_call(model_name="qwen/qwen3.5-9b")
    print(llm.generate("What is the capital of France?"))