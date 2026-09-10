"""Загружает только tokenizer официальной модели Mistral, без весов модели."""
import sys
from huggingface_hub import hf_hub_download

if __name__ == "__main__":
    path = hf_hub_download(repo_id="mistralai/Ministral-3-3B-Instruct-2512",
        filename="tekken.json", revision="b35d4dfe56c142746f54dbd64f579faab2744308", local_dir=sys.argv[1])
    print(f"Tokenizer downloaded: {path}")
