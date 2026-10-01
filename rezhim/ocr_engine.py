"""Local OCR boundary. Engines return observations; they never write the database."""
from __future__ import annotations

from dataclasses import asdict, dataclass
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Literal, Protocol

from PIL import Image, ImageEnhance, ImageOps

Mode = Literal['latin', 'number']
Region = tuple[int, int, int, int] | None


@dataclass(frozen=True)
class OcrToken:
    text: str
    box: tuple[int, int, int, int]
    confidence: float | None


@dataclass(frozen=True)
class OcrRead:
    engine: str
    tokens: list[OcrToken]
    warning: str = ''

    def json(self):
        return {'engine': self.engine, 'tokens': [asdict(token) for token in self.tokens], 'warning': self.warning}


class OcrEngine(Protocol):
    name: str
    def status(self) -> dict: ...
    def read(self, image: Path, region: Region = None, mode: Mode = 'latin') -> OcrRead: ...


def _prepared(image: Path, region: Region) -> Image.Image:
    """The original image is untouched; this is only a temporary OCR derivative."""
    with Image.open(image) as original:
        result = ImageOps.exif_transpose(original).convert('L')
    if region:
        x, y, width, height = region
        if width <= 0 or height <= 0 or x < 0 or y < 0 or x + width > result.width or y + height > result.height:
            raise ValueError('Область OCR выходит за пределы изображения')
        result = result.crop((x, y, x + width, y + height))
    result = ImageEnhance.Contrast(result).enhance(1.8)
    return result.resize((result.width * 2, result.height * 2))


class UnavailableEngine:
    name = 'unavailable'
    def __init__(self, message: str): self.message = message
    def status(self): return {'name': self.name, 'ready': False, 'message': self.message}
    def read(self, image: Path, region: Region = None, mode: Mode = 'latin') -> OcrRead: return OcrRead(self.name, [], self.message)


class TesseractEngine:
    name = 'tesseract'
    def __init__(self, executable: str): self.executable = executable
    def status(self):
        try:
            version = subprocess.run([self.executable, '--version'], capture_output=True, text=True, timeout=5, check=True).stdout.splitlines()[0]
            return {'name': self.name, 'ready': True, 'message': version}
        except (OSError, subprocess.SubprocessError) as error:
            return {'name': self.name, 'ready': False, 'message': str(error)}
    def read(self, image: Path, region: Region = None, mode: Mode = 'latin') -> OcrRead:
        allowed = '0123456789,.-' if mode == 'number' else 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-/.'
        with tempfile.TemporaryDirectory(prefix='rezhim-ocr-') as directory:
            target = Path(directory) / 'crop.png'
            _prepared(image, region).save(target)
            command = [self.executable, str(target), 'stdout', '--psm', '6', 'tsv', '-c', f'tessedit_char_whitelist={allowed}']
            try: output = subprocess.run(command, capture_output=True, text=True, timeout=45, check=True).stdout
            except (OSError, subprocess.SubprocessError) as error: return OcrRead(self.name, [], f'Tesseract не выполнил распознавание: {error}')
        tokens = []
        for line in output.splitlines()[1:]:
            cells = line.split('\t')
            if len(cells) != 12 or not cells[11].strip(): continue
            try:
                box = tuple(int(cells[i]) for i in (6, 7, 8, 9)); confidence = float(cells[10]) / 100 if float(cells[10]) >= 0 else None
            except ValueError: continue
            tokens.append(OcrToken(cells[11].strip(), box, confidence))
        return OcrRead(self.name, tokens)


class PaddleEngine:
    """Adapter kept behind the same boundary; model loading is deferred until first read."""
    name = 'paddleocr'
    def status(self):
        try: import paddleocr  # noqa: F401
        except ImportError: return {'name': self.name, 'ready': False, 'message': 'PaddleOCR не установлен'}
        return {'name': self.name, 'ready': True, 'message': 'PaddleOCR установлен; модели будут загружены при первом запуске'}
    def read(self, image: Path, region: Region = None, mode: Mode = 'latin') -> OcrRead:
        status = self.status()
        if not status['ready']: return OcrRead(self.name, [], status['message'])
        return OcrRead(self.name, [], 'Адаптер PaddleOCR подготовлен; загрузка согласованных CPU-моделей будет включена перед этапом RulerControl.')


def available_engine(preference: str | None = None) -> OcrEngine:
    preference = (preference or os.environ.get('REZHIM_OCR_ENGINE', 'auto')).lower()
    tess = os.environ.get('REZHIM_TESSERACT') or shutil.which('tesseract')
    if preference in ('paddle', 'paddleocr'): return PaddleEngine()
    if preference in ('tesseract', 'tess'): return TesseractEngine(tess) if tess else UnavailableEngine('Tesseract 5 не найден')
    paddle = PaddleEngine()
    if paddle.status()['ready']: return paddle
    if tess: return TesseractEngine(tess)
    return UnavailableEngine('Не установлен PaddleOCR и не найден Tesseract 5')


def installation_status() -> dict:
    primary = PaddleEngine().status()
    executable = os.environ.get('REZHIM_TESSERACT') or shutil.which('tesseract')
    fallback = TesseractEngine(executable).status() if executable else {'name': 'tesseract', 'ready': False, 'message': 'Tesseract 5 не найден'}
    return {'selected': available_engine().status(), 'engines': [primary, fallback], 'offline': True, 'writes_to_database': False}
