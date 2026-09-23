from pydub import AudioSegment
import simpleaudio as sa

class KaraokeMixer:
    def __init__(self):
        self.original = None
        self.instrumental = None
        self.vocals = None
        self.playback = None
        
    def load_tracks(self, original_path, instrumental_path, vocals_path):
        self.original = AudioSegment.from_file(original_path)
        self.instrumental = AudioSegment.from_file(instrumental_path)
        self.vocals = AudioSegment.from_file(vocals_path)
    
    def mix_with_vocal_level(self, vocal_percentage):
        """
        Mix instrumental with adjustable vocal level
        0% = instrumental only
        100% = original track
        """
        if self.instrumental is None or self.vocals is None:
            return None
        
        # Adjust vocal volume
        vocal_db = -60 + (vocal_percentage / 100 * 60)  # -60dB to 0dB
        adjusted_vocals = self.vocals + vocal_db
        
        # Mix
        combined = self.instrumental.overlay(adjusted_vocals)
        return combined