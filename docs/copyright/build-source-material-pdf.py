from pathlib import Path
import textwrap

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "copyright" / "generated" / "source-program-master.txt"
METADATA = ROOT / "docs" / "copyright" / "generated" / "source-material-metadata.txt"
OUTPUT_DIR = ROOT / "output" / "pdf"
OUTPUT = OUTPUT_DIR / "SQLTeacher源程序鉴别材料V1.1.0.pdf"
TITLE = "SQLTeacher SQL 教学与智能练习软件 V1.1.0 源程序鉴别材料"
LINES_PER_PAGE = 50
CHARS_PER_DISPLAY_LINE = 118


def wrap_source_line(line):
    if not line:
        return [""]
    parts = textwrap.wrap(
        line, width=CHARS_PER_DISPLAY_LINE, replace_whitespace=False,
        drop_whitespace=False, break_long_words=True, break_on_hyphens=False,
    )
    return [parts[0]] + ["    > " + part for part in parts[1:]]


def read_display_lines():
    result = []
    for line in SOURCE.read_text(encoding="utf-8").splitlines():
        # Omit purely blank formatting rows so every submitted page contains
        # 50 visible source/comment rows rather than padded empty rows.
        if not line.strip():
            continue
        result.extend(wrap_source_line(line))
    return result


def paginate(lines):
    pages = [lines[index:index + LINES_PER_PAGE] for index in range(0, len(lines), LINES_PER_PAGE)]
    # Keep the final source row with the preceding page.  This preserves the
    # actual end of the program while keeping every submitted page at 50+ rows.
    if len(pages) > 1 and len(pages[-1]) < LINES_PER_PAGE:
        pages[-2].extend(pages.pop())
    return pages


def main():
    if not SOURCE.exists() or not METADATA.exists():
        raise RuntimeError("Generate the frozen source master first: .\\docs\\copyright\\generate-source-material.ps1")
    pdfmetrics.registerFont(TTFont("YaHei", r"C:\Windows\Fonts\msyh.ttc", subfontIndex=0))
    all_pages = paginate(read_display_lines())
    if len(all_pages) < 60:
        raise RuntimeError("The full source is below 60 pages; submit the complete source instead.")
    selected = all_pages[:30] + all_pages[-30:]
    if any(len(page) < LINES_PER_PAGE for page in selected):
        raise RuntimeError("Every submitted source page must contain 50 visible rows.")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    document = canvas.Canvas(str(OUTPUT), pagesize=A4)
    width, height = A4
    left, right = 1.25 * cm, 1.25 * cm
    top, bottom = 1.8 * cm, 1.25 * cm
    source_font_size = 5.8
    metadata = dict(line.split("=", 1) for line in METADATA.read_text(encoding="utf-8").splitlines() if "=" in line)
    for material_index, page_lines in enumerate(selected, start=1):
        master_page = material_index if material_index <= 30 else len(all_pages) - 30 + material_index - 30
        document.setFillColor(colors.black)
        document.setFont("YaHei", 7.5)
        document.drawString(left, height - 1.05 * cm, TITLE)
        document.drawRightString(width - right, height - 1.05 * cm, f"母版第 {master_page} 页")
        document.setStrokeColor(colors.HexColor("#B7C9E2"))
        document.line(left, height - 1.22 * cm, width - right, height - 1.22 * cm)
        document.setFont("Courier", source_font_size)
        y = height - top
        row_height = (height - top - bottom) / max(LINES_PER_PAGE, len(page_lines))
        for row, line in enumerate(page_lines, start=1):
            document.drawRightString(left - 3, y, f"{row:02d}")
            document.drawString(left + 4, y, line)
            y -= row_height
        document.setFont("YaHei", 7.5)
        document.setFillColor(colors.HexColor("#595959"))
        document.drawCentredString(width / 2, 0.72 * cm, f"材料第 {material_index} / 60 页  |  冻结提交 {metadata['git_commit'][:12]}")
        document.showPage()
    document.save()
    print(f"Created {OUTPUT}; full master pages={len(all_pages)}; submitted pages=60; each page has at least {LINES_PER_PAGE} visible rows")


if __name__ == "__main__":
    main()
