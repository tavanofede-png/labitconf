Synthetic speech fixtures generated locally with macOS Paulina (Spanish, Mexico), 16 kHz mono PCM16.
No microphone recording or personal data.
Positive: Hola Tino
Negative: Buenos días. Necesito ayuda con mi teléfono.
Command: Quiero enviar un correo

Local ending fixtures generated with Windows System.Speech, Microsoft Helena Desktop (es-ES), 16 kHz mono PCM16.
Synthesizer output went directly to temporary WAV files; no speaker playback, microphone recording, network service or personal audio.
Temporary WAV headers were checked and removed after extracting PCM.
End positive: ya terminé, gracias
End negative: todavía no terminé
The native smoke decodes both ending fixtures with unrestricted local Vosk dictation and word confidences.

Inline wake fixture: Hola Tino estás ahí
Generated locally with the same Windows Helena voice and PCM format, without punctuation or inserted breaks.
The native smoke verifies a single final free-dictation utterance, verified wake-prefix word times,
no audio before Hola, inclusion of the following query, and PCM16 16-to-24 kHz resampling.
