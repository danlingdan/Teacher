from html import escape
from pathlib import Path
import re

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "copyright" / "06-材料清单与待提交报告.md"
OUTPUT_DIR = ROOT / "output" / "pdf"
OUTPUT = OUTPUT_DIR / "SQLTeacher软著材料交付报告V1.1.0.pdf"
PAGE_WIDTH, PAGE_HEIGHT = A4
LEFT = RIGHT = 1.75 * cm
TOP = BOTTOM = 1.65 * cm


def draw_page(canvas, doc):
    canvas.saveState()
    canvas.setFont("YaHei", 8.5)
    canvas.setFillColor(colors.HexColor("#595959"))
    canvas.drawRightString(PAGE_WIDTH - RIGHT, PAGE_HEIGHT - 1.0 * cm, "SQLTeacher 软著材料交付报告 V1.1.0")
    canvas.setStrokeColor(colors.HexColor("#D9E2F3"))
    canvas.line(LEFT, PAGE_HEIGHT - 1.1 * cm, PAGE_WIDTH - RIGHT, PAGE_HEIGHT - 1.1 * cm)
    canvas.drawCentredString(PAGE_WIDTH / 2, 0.85 * cm, f"第 {doc.page} 页")
    canvas.restoreState()


def make_styles():
    pdfmetrics.registerFont(TTFont("YaHei", r"C:\Windows\Fonts\msyh.ttc", subfontIndex=0))
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle("title", parent=base["Normal"], fontName="YaHei", fontSize=17, leading=22,
                                textColor=colors.HexColor("#1F4E79"), alignment=TA_CENTER, spaceAfter=8),
        "h1": ParagraphStyle("h1", parent=base["Normal"], fontName="YaHei", fontSize=13, leading=18,
                             textColor=colors.HexColor("#1F4E79"), spaceBefore=7, spaceAfter=3),
        "body": ParagraphStyle("body", parent=base["Normal"], fontName="YaHei", fontSize=10.3, leading=15,
                               firstLineIndent=20, wordWrap="CJK", spaceAfter=1),
        "item": ParagraphStyle("item", parent=base["Normal"], fontName="YaHei", fontSize=10.3, leading=15,
                               leftIndent=18, firstLineIndent=-18, wordWrap="CJK", spaceAfter=1),
        "note": ParagraphStyle("note", parent=base["Normal"], fontName="YaHei", fontSize=9.5, leading=14,
                               textColor=colors.HexColor("#595959"), wordWrap="CJK", spaceAfter=2),
    }


def inline(value):
    value = escape(value)
    value = re.sub(r"`([^`]+)`", r'<font name="Courier">\1</font>', value)
    return value.replace("**", "")


def parse(source, style_map):
    story = []
    title_written = False
    for raw in source.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("---"):
            continue
        if line.startswith("|"):
            cells = [cell.strip() for cell in line.strip("|").split("|")]
            if all(set(cell) <= {"-", ":"} for cell in cells):
                continue
            story.append(Paragraph("；".join(inline(cell) for cell in cells), style_map["item"]))
            continue
        if line.startswith("# "):
            if not title_written:
                story.append(Paragraph(inline(line[2:]), style_map["title"]))
                title_written = True
            continue
        if line.startswith("## "):
            story.append(Paragraph(inline(line[3:]), style_map["h1"]))
            continue
        if line.startswith("> "):
            story.append(Paragraph(inline(line[2:]), style_map["note"]))
            continue
        if line.startswith("- "):
            story.append(Paragraph("• " + inline(line[2:]), style_map["item"]))
            continue
        if re.match(r"^\d+\. ", line):
            story.append(Paragraph(inline(line), style_map["item"]))
            continue
        story.append(Paragraph(inline(line), style_map["body"]))
    return story


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    styles = make_styles()
    frame = Frame(LEFT, BOTTOM + 0.45 * cm, PAGE_WIDTH - LEFT - RIGHT, PAGE_HEIGHT - TOP - BOTTOM - 1.1 * cm,
                  leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
    document = BaseDocTemplate(str(OUTPUT), pagesize=A4, title="SQLTeacher软著材料交付报告 V1.1.0",
                               author="SQLTeacher Project", pageTemplates=[PageTemplate(id="report", frames=[frame], onPage=draw_page)])
    document.build(parse(SOURCE, styles))
    print(f"Created {OUTPUT}")


if __name__ == "__main__":
    main()
