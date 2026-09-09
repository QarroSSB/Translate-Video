import io
import wave

from audio_utils import duration_s, pcm16_to_wav_bytes, rms_normalized, slice_pcm


def test_wav_and_slice() -> None:
    pcm = b"\x00\x00" * 24_000
    wav = pcm16_to_wav_bytes(pcm)
    with wave.open(io.BytesIO(wav), "rb") as wf:
        assert wf.getframerate() == 24_000
        assert wf.getnchannels() == 1
        assert wf.getsampwidth() == 2
        assert wf.getnframes() == 24_000
    assert duration_s(pcm) == 1.0
    assert len(slice_pcm(pcm, 0.25, 0.75)) == 24_000
    assert rms_normalized(pcm) == 0.0
