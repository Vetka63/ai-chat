"""Явная сериализация разрешённых полей профиля для контекста LLM."""
import json
from .models import UserProfile


def profile_data(profile: UserProfile) -> dict:
    """Не раскрывает внутренние ID, версии и другую память."""
    return {'name': profile.name, 'preferences': profile.preferences.model_dump()}


def profile_text(profile: UserProfile) -> str:
    """Единый текст блока используется и при отправке, и при подсчёте токенов."""
    return json.dumps(profile_data(profile), ensure_ascii=False)
