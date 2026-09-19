"""Управление локальными персонами; это не аккаунты и не аутентификация."""
from fastapi import APIRouter, Request
from capabilities.personalization.models import CreateProfile, UpdateProfile, UserProfile

router = APIRouter(prefix='/api/v1/profiles', tags=['profiles'])


@router.get('', response_model=list[UserProfile])
async def profiles(request: Request):
    return await request.app.state.profiles.list()


@router.get('/{profile_id}', response_model=UserProfile)
async def profile(profile_id: str, request: Request):
    return await request.app.state.profiles.get(profile_id)


@router.post('', response_model=UserProfile, status_code=201)
async def create_profile(body: CreateProfile, request: Request):
    return await request.app.state.profiles.create(body)


@router.put('/{profile_id}', response_model=UserProfile)
async def update_profile(profile_id: str, body: UpdateProfile, request: Request):
    return await request.app.state.profiles.update(profile_id, body)
