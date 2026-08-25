from html.parser import HTMLParser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "backend" / "static" / "index.html"


class ElementIndex(HTMLParser):
    def __init__(self):
        super().__init__()
        self.by_id: dict[str, dict[str, str | None]] = {}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):
        attributes = dict(attrs)
        element_id = attributes.get("id")
        if element_id:
            self.by_id[element_id] = {"tag": tag, **attributes}


def parse_index() -> tuple[str, ElementIndex]:
    html = INDEX.read_text(encoding="utf-8")
    parser = ElementIndex()
    parser.feed(html)
    return html, parser


def test_camera_and_upload_inputs_have_safe_contract():
    _, parser = parse_index()
    camera = parser.by_id["cameraInput"]
    upload = parser.by_id["uploadInput"]
    accepted = "image/jpeg,image/png,image/webp"

    assert camera["type"] == "file"
    assert camera["accept"] == accepted
    assert camera["capture"] == "environment"
    assert "hidden" in camera
    assert upload["type"] == "file"
    assert upload["accept"] == accepted
    assert "capture" not in upload
    assert "hidden" in upload


def test_camera_preview_controls_and_privacy_hooks_exist():
    html, parser = parse_index()

    for element_id in {
        "cameraButton",
        "uploadButton",
        "photoPanel",
        "photoPreview",
        "removePhoto",
        "retakePhoto",
        "usePhoto",
    }:
        assert element_id in parser.by_id

    assert "const MAX_PHOTO_BYTES = 10 * 1024 * 1024;" in html
    assert "URL.createObjectURL(file)" in html
    assert "URL.revokeObjectURL" in html
    assert "new FormData" not in html
    assert "/v1/ocr" not in html
