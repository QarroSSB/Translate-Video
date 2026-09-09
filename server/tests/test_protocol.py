import base64

import pytest

from protocol import validate_audio_b64, validate_chunk_ms, validate_mode, validate_target_language


def test_target_language() -> None:
    assert validate_target_language("ru") == "ru"
    assert validate_target_language("pt-BR") == "pt-BR"
    assert validate_target_language("bad value!") == "ru"


def test_mode() -> None:
    assert validate_mode("live") == "live"
    assert validate_mode("MULTIVOICE") == "multivoice"
    assert validate_mode("nope") == "live"


def test_chunk_ms() -> None:
    assert validate_chunk_ms(1000) == 3000
    assert validate_chunk_ms(6000) == 6000
    assert validate_chunk_ms(50000) == 12000


def test_audio_validation() -> None:
    valid = base64.b64encode(b"\x00\x00" * 100).decode()
    assert validate_audio_b64(valid) == valid
    with pytest.raises(ValueError):
        validate_audio_b64("not-base64!")
    odd = base64.b64encode(b"123").decode()
    with pytest.raises(ValueError):
        validate_audio_b64(odd)
