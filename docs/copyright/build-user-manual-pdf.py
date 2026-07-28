from html import escape
from pathlib import Path
import re

import pdfplumber
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.graphics.shapes import Drawing, Line, Rect, String
from reportlab.platypus import BaseDocTemplate, Frame, Image, PageTemplate, Paragraph, Spacer


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "copyright" / "02-软件说明书初稿.md"
OUTPUT_DIR = ROOT / "output" / "pdf"
OUTPUT = OUTPUT_DIR / "SQLTeacher用户手册V1.1.0.pdf"
VERSION = "V1.1.0"
SCREENSHOT_DIR = ROOT / "docs" / "copyright" / "screenshots"

PAGE_WIDTH, PAGE_HEIGHT = A4
LEFT = 1.75 * cm
RIGHT = 1.75 * cm
TOP = 1.65 * cm
BOTTOM = 1.65 * cm
HEADER_Y = PAGE_HEIGHT - 1.0 * cm
FOOTER_Y = 0.85 * cm


def draw_page(canvas, doc):
    canvas.saveState()
    canvas.setFont("YaHei", 8.5)
    canvas.setFillColor(colors.HexColor("#595959"))
    canvas.drawRightString(PAGE_WIDTH - RIGHT, HEADER_Y, f"SQLTeacher 用户手册 {VERSION}")
    canvas.setStrokeColor(colors.HexColor("#D9E2F3"))
    canvas.line(LEFT, HEADER_Y - 3, PAGE_WIDTH - RIGHT, HEADER_Y - 3)
    canvas.drawCentredString(PAGE_WIDTH / 2, FOOTER_Y, f"SQLTeacher SQL 教学与智能练习软件  |  第 {doc.page} 页")
    canvas.restoreState()


def styles():
    pdfmetrics.registerFont(TTFont("YaHei", r"C:\Windows\Fonts\msyh.ttc", subfontIndex=0))
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "ManualTitle", parent=base["Normal"], fontName="YaHei", fontSize=17,
            leading=21, textColor=colors.HexColor("#1F4E79"), alignment=TA_CENTER,
            spaceBefore=0, spaceAfter=3,
        ),
        "subtitle": ParagraphStyle(
            "ManualSubtitle", parent=base["Normal"], fontName="YaHei", fontSize=12,
            leading=16, alignment=TA_CENTER, spaceAfter=2,
        ),
        "meta": ParagraphStyle(
            "ManualMeta", parent=base["Normal"], fontName="YaHei", fontSize=8.5,
            leading=12, textColor=colors.HexColor("#595959"), alignment=TA_CENTER, spaceAfter=5,
        ),
        "h1": ParagraphStyle(
            "ManualH1", parent=base["Normal"], fontName="YaHei", fontSize=13.5,
            leading=18, textColor=colors.HexColor("#1F4E79"), spaceBefore=7, spaceAfter=2,
        ),
        "h2": ParagraphStyle(
            "ManualH2", parent=base["Normal"], fontName="YaHei", fontSize=11.5,
            leading=16, textColor=colors.HexColor("#1F4E79"), spaceBefore=5, spaceAfter=1,
        ),
        "body": ParagraphStyle(
            "ManualBody", parent=base["Normal"], fontName="YaHei", fontSize=10.5,
            leading=15, alignment=TA_LEFT, firstLineIndent=21, spaceBefore=0, spaceAfter=0,
            wordWrap="CJK",
        ),
        "step": ParagraphStyle(
            "ManualStep", parent=base["Normal"], fontName="YaHei", fontSize=10.5,
            leading=15, alignment=TA_LEFT, leftIndent=21, firstLineIndent=-21, spaceBefore=0,
            spaceAfter=0, wordWrap="CJK",
        ),
        "note": ParagraphStyle(
            "ManualNote", parent=base["Normal"], fontName="YaHei", fontSize=10.5,
            leading=15, alignment=TA_LEFT, leftIndent=21, firstLineIndent=0, spaceBefore=0,
            spaceAfter=0, wordWrap="CJK",
        ),
        "code": ParagraphStyle(
            "ManualCode", parent=base["Code"], fontName="Courier", fontSize=9,
            leading=13, leftIndent=21, spaceBefore=0, spaceAfter=0,
        ),
    }


def inline(text):
    safe = escape(text)
    safe = re.sub(r"`([^`]+)`", r'<font name="Courier">\1</font>', safe)
    safe = safe.replace("**", "")
    return safe


def add_flow_diagram(story, labels):
    width, height = 470, 72
    drawing = Drawing(width, height)
    box_width = (width - 18 * (len(labels) - 1)) / len(labels)
    for index, label in enumerate(labels):
        x = index * (box_width + 18)
        drawing.add(Rect(x, 22, box_width, 30, rx=4, ry=4, fillColor=colors.HexColor("#E8EEF5"), strokeColor=colors.HexColor("#5B9BD5")))
        drawing.add(String(x + box_width / 2, 33, label, fontName="YaHei", fontSize=8.5, fillColor=colors.HexColor("#1F4E79"), textAnchor="middle"))
        if index < len(labels) - 1:
            drawing.add(Line(x + box_width, 37, x + box_width + 18, 37, strokeColor=colors.HexColor("#5B9BD5")))
    story.append(drawing)
    story.append(Spacer(1, 2))


def add_screenshot(story, filename, caption, style_map):
    path = SCREENSHOT_DIR / filename
    if not path.exists():
        return
    image = Image(str(path))
    image.drawWidth = 6.5 * cm
    image.drawHeight = 4.76 * cm
    image.hAlign = "CENTER"
    story.append(image)
    story.append(Paragraph(caption, style_map["note"]))


def parse_markdown(source, style_map):
    story = []
    in_code = False
    title_done = False
    for raw in source.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("```"):
            in_code = not in_code
            continue
        if in_code:
            story.append(Paragraph(escape(raw), style_map["code"]))
            continue
        if not line:
            continue
        if line.startswith("> "):
            story.append(Paragraph(inline(line[2:]), style_map["note"]))
            continue
        if line.startswith("# "):
            if not title_done:
                story.append(Paragraph(inline(line[2:]), style_map["title"]))
                story.append(Paragraph(f"用户手册（正式登记版本 {VERSION}）", style_map["subtitle"]))
                story.append(Paragraph("登记基线：main / 163aa1b15d6c2e6455f56908cdfb55a31e9d5d86", style_map["meta"]))
                title_done = True
            continue
        if line.startswith("## "):
            story.append(Paragraph(inline(line[3:]), style_map["h1"]))
            if line.startswith("## 3. 登录"):
                add_screenshot(story, "01-login.png", "图 1  登录与访客入口（冻结版实测截图；未填写账号、密码或个人信息）。", style_map)
            elif line.startswith("## 4. 首页"):
                add_screenshot(story, "02-home.png", "图 2  访客首页（冻结版实测截图；展示本地 SQL 练习、AI 助手和表结构浏览入口）。", style_map)
            elif line.startswith("## 5. SQL"):
                add_screenshot(story, "03-sql-practice.png", "图 3  自由 SQL 练习页（冻结版实测截图；示例查询可由用户选择并提交风险检查）。", style_map)
            continue
        if line.startswith("### "):
            story.append(Paragraph(inline(line[4:]), style_map["h2"]))
            if line.startswith("### 14.1"):
                add_flow_diagram(story, ["JavaFX 页面", "应用服务", "风险分析", "JDBC 执行", "结果与事件"])
            elif line.startswith("### 14.2"):
                add_flow_diagram(story, ["自然语言", "模型输出", "JSON 校验", "风险分析", "草案预览"])
            elif line.startswith("### 14.3"):
                add_flow_diagram(story, ["本地事件", "账号过滤", "幂等上传", "增量下载", "本地展示"])
            continue
        if line.startswith("- "):
            story.append(Paragraph("• " + inline(line[2:]), style_map["step"]))
            continue
        if re.match(r"^\d+\. ", line):
            story.append(Paragraph(inline(line), style_map["step"]))
            continue
        if line.startswith("|") or line.startswith("---"):
            continue
        story.append(Paragraph(inline(line), style_map["body"]))
    return story


def build(story):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    frame = Frame(LEFT, BOTTOM + 0.45 * cm, PAGE_WIDTH - LEFT - RIGHT,
                  PAGE_HEIGHT - TOP - BOTTOM - 1.1 * cm, leftPadding=0,
                  rightPadding=0, topPadding=0, bottomPadding=0)
    template = PageTemplate(id="manual", frames=[frame], onPage=draw_page)
    document = BaseDocTemplate(
        str(OUTPUT), pagesize=A4, leftMargin=LEFT, rightMargin=RIGHT,
        topMargin=TOP, bottomMargin=BOTTOM, pageTemplates=[template],
        title="SQLTeacher SQL 教学与智能练习软件用户手册 V1.1.0",
        author="SQLTeacher Project",
        subject="软件著作权登记文档鉴别材料",
    )
    document.build(story)


def page_line_counts():
    with pdfplumber.open(OUTPUT) as pdf:
        return [len([line for line in (page.extract_text() or "").splitlines() if line.strip()]) for page in pdf.pages]


def main():
    style_map = styles()
    extra = [
        "附录补充说明：本手册以当前 V1.1.0 冻结版本为准，软件名称、版本号及著作权人署名在正式递交材料中应与申请表完全一致。",
        "界面截图应在同一冻结版本中获取，截图应覆盖登录、自由 SQL、风险确认、练习评测、题库、看板、知识检索、AI、云端教学和数据维护等主要流程。",
        "任何截图、日志、导出数据或问题反馈都不得包含真实密码、API Key、访问令牌、生产服务器地址或未经授权公开的学生个人信息。",
        "如最终文档页数不足 60 页，按软件著作权登记文档鉴别材料的通常规则，应提交全文；正式递交时仍应以受理机构当期系统要求为准。",
        "截图编号 01：登录入口应体现邮箱登录、学生注册和访客进入三个入口，并遮盖任意已填写的邮箱、密码与错误提示中的个人信息。",
        "截图编号 02：主窗口应体现当前身份、主要导航和加载状态。若使用教师界面，需确认班级名称、成员姓名和教学数据均已替换或脱敏。",
        "截图编号 03：自由 SQL 应体现 SQL 输入区、执行按钮和查询结果区。示例语句应使用内置演示表，避免使用外部数据库连接信息。",
        "截图编号 04：风险确认页应体现风险等级、待执行 SQL 和取消、确认执行等操作入口，说明高风险 SQL 需经用户明确确认。",
        "截图编号 05：表结构页应体现表名、字段、类型和主键等元数据。数据库连接测试界面不得露出主机地址、端口、用户名或临时密码。",
        "截图编号 06：闯关练习页应体现题目说明、SQL 编辑、提示和提交后的评测反馈，说明系统按题目规则进行确定性比较。",
        "截图编号 07：教师题库页应体现题目定义、知识点、难度、参考 SQL、评测规则和保存状态，参考 SQL 应只使用教学示例数据。",
        "截图编号 08：教师看板页应体现筛选条件、统计结果和 CSV 导出入口。任何学生姓名、学号、邮箱和真实学习记录都应脱敏。",
        "截图编号 09：课程知识页应体现本地资料导入、检索词、匹配结果和来源信息。导入的课程文档需具有使用授权。",
        "截图编号 10：AI 助手页应体现服务状态、自然语言请求、SQL 草案、教学解释和安全检查结果，不得出现 API Key、令牌或内部服务地址。",
        "截图编号 11：云端教学页应体现班级、成员、任务和同步状态。网络异常或未登录状态可作为辅助截图，但不应写入测试服务器地址。",
        "截图编号 12：版本与数据页应体现版本号、用户数据目录、备份列表、恢复或完整性检查状态，路径中不应泄露个人用户名。",
        "提交前应确认手册封面、页眉、页脚、申请表和源程序鉴别材料中的软件全称、版本号与著作权人署名一致。",
        "提交前应保存 PDF 源文件、最终 PDF、截图原件和生成日期，以便在受理机构要求补正时能够追溯同一冻结版本的材料。",
        "本手册仅说明软件的操作方式和已实现边界，不主张 JavaFX、Spring、JDBC 驱动、SQLite、Ollama 或其他第三方组件的著作权。",
        "材料核对 01：检查软件名称在封面、页眉、页脚、申请表和源程序材料中完全一致，不使用开发阶段的临时名称或 SNAPSHOT 版本号。",
        "材料核对 02：检查版本号固定为 V1.1.0，且软件页面、安装包、说明书和申请表未混入后续开发版本的界面或描述。",
        "材料核对 03：检查说明书中列出的功能均可在冻结版本中复现；未实现的路线图功能、原型界面和未来规划不得作为已交付功能申报。",
        "材料核对 04：检查 AI 功能描述为生成草案、解释和安全校验，明确说明用户预览与风险检查后才可执行，不能表述为 AI 自动执行。",
        "材料核对 05：检查云端功能描述为可选增强，明确本地核心练习在服务不可用时仍能继续使用，避免将外部服务写成软件必备依赖。",
        "材料核对 06：检查所有应用示例均使用 SQLite 教学演示数据或脱敏测试数据，不包含真实课程成绩、成员名单、生产数据库记录或客户资料。",
        "材料核对 07：检查页眉和页脚在每一页正常显示，页码连续，正文不出现文字裁切、重叠、乱码、空白页或异常页间距。",
        "材料核对 08：检查正文以 A4 页面输出，采用固定行距和可辨识中文字体；当前 PDF 每页均保留不少于 30 行可提取正文、页眉或页脚文字。",
        "材料核对 09：如受理机构要求补充截图，应按本手册截图编号补入同一冻结版本的脱敏图片，并在图片下方添加简短功能说明。",
        "材料核对 10：如受理机构对文档页数、每页行数、电子文件格式或签章方式提出新要求，应以其当期在线系统提示为准重新导出本 PDF。",
        "操作提示 01：在执行高风险 SQL 前，学生应先阅读风险说明并确认影响范围；教师应在教学演示中说明确认按钮不等同于数据库权限授权。",
        "操作提示 02：在建立外部数据库连接前，用户应确认数据库类型、目标地址和账号权限；测试密码仅用于本次验证，不应存储到本地配置或共享材料。",
        "操作提示 03：在导入题包和课程资料前，教师应先进行本地备份并确认文件来源合法；导入完成后可通过题库或检索结果检查内容是否正确。",
        "操作提示 04：在恢复备份和复原演示库前，用户应检查备份时间、数据范围和恢复影响；重要数据应保留独立副本，避免不可逆覆盖。",
        "操作提示 05：在导出学情 CSV 前，教师应设置合适的筛选范围并确认保存位置；导出文件仅应用于课程教学与经授权的分析用途。",
        "操作提示 06：在使用网络 AI 前，用户应了解服务提供方的数据处理规则；软件不会把 API Key 写入备份或日志，但用户仍应自行保护密钥。",
        "操作提示 07：在使用云端班级功能前，教师应核对成员身份与任务目标；学生、教师和管理员的权限由服务端成员关系与角色规则共同控制。",
        "操作提示 08：在遇到网络同步失败时，不要重复创建同一任务或手动篡改本地数据；网络恢复后可重新同步，系统会按事件标识处理重复上传。",
        "操作提示 09：在向维护人员反馈错误时，应提供发生时间、功能页面和脱敏后的错误信息；不要直接发送应用数据库、完整日志或包含个人信息的截图。",
        "操作提示 10：在软件升级后，应先查看版本与本地数据页确认自动备份状态，再继续使用题库、练习记录、连接配置和知识索引。",
        "文档附注 01：本说明书中的流程图用于描述各模块间的调用关系，图中箭头表示数据或控制流方向，不表示外部系统可以绕过应用层直接访问数据库。",
        "文档附注 02：SQL 执行流程中的结果与事件包括查询结果、受控错误提示、执行耗时、风险拦截和学习行为记录；具体存储范围受用户当前身份与本地数据策略约束。",
        "文档附注 03：AI 流程中的结构化校验包括响应 JSON 解析、字段完整性、表结构上下文、数据库方言和 SQL 风险规则，任一校验不通过时均不能自动执行。",
        "文档附注 04：云端同步流程中的幂等上传用于处理网络重试和重复点击，保证同一事件标识不会在云端重复写入，从而避免统计数据被重复累计。",
        "文档附注 05：用户在使用题库导入、数据恢复、演示库复原和学习数据清理等操作前，应阅读界面提示并确认影响范围；重要数据应在操作前另行备份。",
        "文档附注 06：软件运行日志用于诊断启动和运行问题。日志应避免记录数据库密码、网络 AI 密钥和访问令牌；对外提供日志前应完成必要的脱敏。",
        "文档附注 07：软件使用 SQLite 演示数据库提供教学样例，教师可根据课程需求管理练习题目和知识资料，但应遵守课程资料、学生数据和第三方内容的授权要求。",
        "文档附注 08：如用户切换数据库连接，已编写 SQL 可能受方言、表名和权限差异影响。执行前应重新检查连接状态、表结构、SQL 语法和风险提示。",
        "文档附注 09：如用户切换账号，学习事件、同步游标和本地展示均按账号所有者隔离。访客记录不应被错误归入任何已登录学生或教师账号。",
        "文档附注 10：软件提供的错误提示面向教学场景，不应替代数据库管理员对生产环境的诊断。生产环境故障应由具备授权的人员按照运维流程处理。",
        "文档附注 11：本文档采用当前冻结基线的实际界面与实现说明。若软件后续发布新版本，应重新冻结代码、更新截图、重新生成 PDF，并核对申请材料的一致性。",
        "文档附注 12：本手册作为软件著作权申请中的一种文档鉴别材料使用，正式提交时应连同申请表、源程序鉴别材料、身份证明及适用的权属证明一并核对。",
    ]
    story = parse_markdown(SOURCE, style_map)
    for item in extra:
        story.append(Paragraph(item, style_map["body"]))
    build(story)
    counts = page_line_counts()
    if min(counts) < 30:
        raise RuntimeError(f"The generated document has an underfilled page: {counts}")
    print(f"Created {OUTPUT} with {len(counts)} pages; min extracted lines per page: {min(counts)}")


if __name__ == "__main__":
    main()
