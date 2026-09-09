from speaker_bank import SpeakerBank


def test_speaker_bank_assigns_stable_chunk_aliases_and_refs() -> None:
    bank = SpeakerBank()
    bank.begin_chunk()
    a = bank.profile_for_label("speaker_0")
    assert a.name == "Speaker A"
    assert bank.profile_for_label("speaker_0") is a
    a.add_reference_audio(b"\x00\x00" * 48_000)  # 2 seconds
    names, refs = bank.known_references()
    assert names == ["Speaker A"]
    assert refs[0].startswith("data:audio/wav;base64,")
    bank.begin_chunk()
    assert bank.profile_for_label("Speaker A") is a


def test_speaker_bank_caps_profiles_on_noisy_unknown_labels() -> None:
    bank = SpeakerBank(max_profiles=3)
    for chunk in range(8):
        bank.begin_chunk()
        bank.profile_for_label(f"unknown_{chunk}")
    assert bank.profile_count == 3
    assert bank.overflow_aliases == 5


def test_reference_audio_caps_at_ten_seconds() -> None:
    bank = SpeakerBank()
    bank.begin_chunk()
    profile = bank.profile_for_label("speaker_0")
    profile.add_reference_audio(b"\x00\x00" * (24_000 * 20))
    assert len(profile.reference_pcm) == 24_000 * 2 * 10
