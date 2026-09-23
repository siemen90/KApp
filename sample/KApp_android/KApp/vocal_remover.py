import torch
import torchaudio
from demucs.pretrained import get_model
from demucs.apply import apply_model
import numpy as np
from pydub import AudioSegment
import os

class VocalRemover:
    def __init__(self, model_name="htdemucs"):
        """
        Models available:
        - htdemucs: Best quality, slower
        - htdemucs_ft: Fine-tuned variant
        - htdemucs_6s: 6-source separation
        """
        self.model_name = model_name
        self.model = None
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        
    def load_model(self):
        """Lazy load the model"""
        if self.model is None:
            print(f"Loading Demucs model: {self.model_name}")
            self.model = get_model(self.model_name)
            self.model.to(self.device)
        return self.model
    
    def remove_vocals(self, input_path, output_path=None):
        """
        Separate vocals from instrumental
        Returns path to instrumental track
        """
        model = self.load_model()
        
        # Load audio using built-in wave module to bypass all external C dependencies
        import wave
        with wave.open(input_path, 'rb') as wf:
            n_channels = wf.getnchannels()
            sampwidth = wf.getsampwidth()
            sr = wf.getframerate()
            n_frames = wf.getnframes()
            audio_data = wf.readframes(n_frames)
            
            if sampwidth == 2:
                dtype = np.int16
            elif sampwidth == 4:
                dtype = np.int32
            elif sampwidth == 1:
                dtype = np.uint8
            else:
                raise ValueError(f"Unsupported sample width: {sampwidth}")
                
            np_audio = np.frombuffer(audio_data, dtype=dtype)
            
            if sampwidth == 1:
                np_audio = np_audio.astype(np.float32) - 128.0
                np_audio /= 128.0
            else:
                max_val = float(int((2 ** (sampwidth * 8)) / 2))
                np_audio = np_audio.astype(np.float32) / max_val
                
            if n_channels > 1:
                np_audio = np_audio.reshape(-1, n_channels)
                
            wav = torch.from_numpy(np_audio)
            if n_channels > 1:
                wav = wav.T
            else:
                wav = wav.unsqueeze(0)
        
        # Convert to stereo (Demucs htdemucs expects exactly 2 channels)
        if wav.shape[0] == 1:
            wav = wav.repeat(2, 1)  # duplicate mono to stereo
        elif wav.shape[0] > 2:
            wav = wav[:2, :]        # truncate if more than 2 channels
        
        # Apply model
        wav = wav.to(self.device)
        sources = apply_model(model, wav[None], split=True, overlap=0.25)[0]
        
        # sources order: drums, bass, other, vocals
        # Combine drums + bass + other = instrumental
        instrumental = sources[0] + sources[1] + sources[2]
        
        # Save output
        if output_path is None:
            base = os.path.splitext(input_path)[0]
            output_path = f"{base}_instrumental.wav"
        
        # Save audio using built-in wave module to bypass torchaudio dependencies
        def save_wav(filepath, tensor, sr):
            np_audio = tensor.numpy()
            np_audio = np.clip(np_audio, -1.0, 1.0)
            np_audio = (np_audio * 32767).astype(np.int16)
            if np_audio.ndim > 1 and np_audio.shape[0] > 1:
                np_audio = np_audio.T
            
            with wave.open(filepath, 'wb') as wf:
                wf.setnchannels(tensor.shape[0])
                wf.setsampwidth(2)
                wf.setframerate(sr)
                wf.writeframes(np_audio.tobytes())
                
        save_wav(output_path, instrumental.cpu(), sr)
        
        # Also save vocals separately
        vocals_path = output_path.replace("_instrumental", "_vocals")
        save_wav(vocals_path, sources[3].cpu(), sr)
        
        return output_path
    
    def quick_vocal_remove(self, input_path, output_path=None):
        """
        Faster method using phase inversion (less quality but instant)
        Good for real-time preview
        """
        audio = AudioSegment.from_file(input_path)
        
        # Convert to mono for phase inversion
        audio = audio.set_channels(1)
        
        # Invert phase (simple center channel removal approximation)
        # This works for vocals mixed in center
        inverted = audio.invert_phase()
        
        if output_path is None:
            base = os.path.splitext(input_path)[0]
            output_path = f"{base}_quick_instrumental.wav"
        
        # Mix original with inverted (this is simplified - real implementation needs stereo)
        # For true phase cancellation, you'd need the original stereo track
        # This is a placeholder for the concept
        
        audio.export(output_path, format="wav")
        return output_path