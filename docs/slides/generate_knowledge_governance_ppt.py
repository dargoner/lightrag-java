from __future__ import annotations

import shutil
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_PATH = Path("/home/dargoner/work/Yuxi-Know/test/data/测试演示.pptx")
DEFAULT_OUTPUT = REPO_ROOT / "docs/slides/知识库治理工程-技术沙龙-3页版.pptx"
VISUAL_OUTPUT = REPO_ROOT / "docs/slides/知识库治理工程-技术沙龙-视觉强化版.pptx"

P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
VT_NS = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
APP_NS = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"

NS = {"p": P_NS, "r": R_NS, "a": A_NS, "ct": CT_NS, "vt": VT_NS, "app": APP_NS}

ET.register_namespace("a", A_NS)
ET.register_namespace("p", P_NS)
ET.register_namespace("r", R_NS)
ET.register_namespace("", REL_NS)


SLIDE_TEXTS = {
    4: [
        "来源与口径杂乱",
        "制度、SOP、FAQ、经验稿混在一起；同一问题多版本、多口径，检索能召回，模型却不知道该信谁。",
        "内容质量不可控",
        "重复、冲突、过期、缺上下文最常见。不是知识少，而是脏知识进入系统后持续污染答案。",
        "生命周期和责任缺位",
        "有新增、无版本；有发布、无失效；无 owner、无反馈闭环。最大问题不是知识不够，而是知识不可控。",
        "知识库效果差，很多时候不是模型问题",
    ],
    5: [
        "范围治理",
        "先定义准入边界，只收高频、稳定、可复用知识，避免把所有材料一股脑塞进库里。",
        "内容治理",
        "统一模板、来源、摘要和证据字段，优先消除重复、冲突和无主知识。",
        "分类治理",
        "统一分类、标签、元数据，支撑检索召回、权限控制和问答路由。",
        "版本治理",
        "建立发布、生效、失效、回滚机制，避免旧知识长期污染答案。",
        "运营治理",
        "明确 owner、反馈回流、命中率和更新时效。核心是建立从准入到淘汰的全生命周期机制。",
        "知识库治理，本质是治理知识供给系统",
    ],
    6: [
        "1",
        "知识分层",
        "按制度、流程、FAQ、案例分层，不同层采用不同的准入、审核和更新策略。",
        "2",
        "统一知识模型",
        "统一分类、标签、来源、时效、适用范围。先把知识标准化，再谈检索和生成。",
        "3",
        "版本与失效机制",
        "新版发布要能覆盖旧版，过期内容必须自动下线或预警，不能把历史垃圾留给模型兜底。",
        "4",
        "Owner 与指标闭环",
        "每类知识都要有人负责，持续看命中率、错答率、更新时效，把治理效果量化出来。",
        "第五件事是把反馈真正接回治理流程：用户追问、人工纠错、低分答案都要回流。先把分层、模型、版本、owner、指标做起来，治理才真正工程化。",
        "真正落地时，优先做这 5 件事",
    ],
}

VISUAL_SLIDE_TEXTS = {
    2: [
        "01",
        "02",
        "03",
        "04",
        "来源杂乱",
        "质量失控",
        "结构失配",
        "责任缺位",
        "知识库治理工程",
        "TECH SALON",
    ],
    5: [
        "范围治理",
        "先定准入边界，只收高频、稳定、可复用知识。",
        "内容治理",
        "优先消除重复、冲突、过期和缺上下文内容。",
        "分类治理",
        "统一分类、标签、来源、时效，支撑检索和路由。",
        "版本治理",
        "建立发布、生效、失效、回滚机制，切断旧知识污染。",
        "运营治理",
        "明确 owner、反馈回流和指标看板。核心是建立从准入到淘汰的全生命周期机制。",
        "知识库治理，本质是治理知识供给系统",
    ],
    6: [
        "1",
        "知识分层",
        "制度、流程、FAQ、案例分层治理，不同层用不同规则，避免一个桶装所有知识。",
        "2",
        "统一知识模型",
        "统一分类、标签、来源、时效和适用范围，让知识先标准化，再被模型消费。",
        "3",
        "版本与失效",
        "新版覆盖旧版，过期自动下线或预警，不能把历史垃圾留给模型兜底。",
        "4",
        "Owner 与指标",
        "每类知识都要有人负责，并持续看命中率、错答率、更新时效。",
        "第五件事是把反馈真正接回治理流程：追问、纠错、低分答案都要回流。先把分层、模型、版本、owner、指标做起来，治理才真正工程化。",
        "真正落地时，优先做这 5 件事",
    ],
}

STANDARD_SLIDES = [4, 5, 6]
VISUAL_SLIDES = [2, 5, 6]


def write_xml(path: Path, root: ET.Element) -> None:
    tree = ET.ElementTree(root)
    tree.write(path, encoding="UTF-8", xml_declaration=True)


def replace_slide_texts(slide_path: Path, replacements: list[str]) -> None:
    root = ET.parse(slide_path).getroot()
    text_nodes = root.findall(".//a:t", NS)
    if len(text_nodes) != len(replacements):
        raise ValueError(f"{slide_path.name} text node count mismatch: {len(text_nodes)} != {len(replacements)}")
    for node, replacement in zip(text_nodes, replacements):
        node.text = replacement
    write_xml(slide_path, root)


def keep_selected_slides(presentation_path: Path, slide_numbers: list[int]) -> None:
    root = ET.parse(presentation_path).getroot()
    sld_list = root.find("p:sldIdLst", NS)
    if sld_list is None:
        raise ValueError("presentation.xml missing p:sldIdLst")
    keep_ids = {f"rId{slide_number + 2}" for slide_number in slide_numbers}
    for child in list(sld_list):
        if child.attrib.get(f"{{{R_NS}}}id") not in keep_ids:
            sld_list.remove(child)
    write_xml(presentation_path, root)


def keep_slide_relationships(rels_path: Path, slide_numbers: list[int]) -> None:
    root = ET.parse(rels_path).getroot()
    keep_targets = {f"slides/slide{slide_number}.xml" for slide_number in slide_numbers}
    for child in list(root):
        rel_type = child.attrib.get("Type", "")
        target = child.attrib.get("Target", "")
        if rel_type.endswith("/slide") and target not in keep_targets:
            root.remove(child)
    write_xml(rels_path, root)


def keep_slide_overrides(content_types_path: Path, slide_numbers: list[int]) -> None:
    ET.register_namespace("", CT_NS)
    root = ET.parse(content_types_path).getroot()
    keep_parts = {f"/ppt/slides/slide{slide_number}.xml" for slide_number in slide_numbers}
    for child in list(root):
        part_name = child.attrib.get("PartName", "")
        if part_name.startswith("/ppt/slides/slide") and part_name not in keep_parts:
            root.remove(child)
    write_xml(content_types_path, root)


def update_app_properties(app_path: Path) -> None:
    ET.register_namespace("", APP_NS)
    ET.register_namespace("vt", VT_NS)
    root = ET.parse(app_path).getroot()

    slides = root.find("app:Slides", NS)
    if slides is not None:
        slides.text = "3"

    heading_pairs = root.find("app:HeadingPairs/vt:vector", NS)
    if heading_pairs is not None:
        i4_nodes = heading_pairs.findall("vt:variant/vt:i4", NS)
        if i4_nodes:
            i4_nodes[-1].text = "3"

    write_xml(app_path, root)


def pack_pptx(source_dir: Path, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source_dir.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source_dir))


def generate_variant(output_path: Path, slide_numbers: list[int], slide_texts: dict[int, list[str]]) -> Path:
    if not TEMPLATE_PATH.exists():
        raise FileNotFoundError(f"template not found: {TEMPLATE_PATH}")

    with tempfile.TemporaryDirectory(prefix="knowledge-governance-ppt-") as temp_dir:
        workspace = Path(temp_dir)
        with zipfile.ZipFile(TEMPLATE_PATH) as archive:
            archive.extractall(workspace)

        keep_selected_slides(workspace / "ppt/presentation.xml", slide_numbers)
        keep_slide_relationships(workspace / "ppt/_rels/presentation.xml.rels", slide_numbers)
        keep_slide_overrides(workspace / "[Content_Types].xml", slide_numbers)
        update_app_properties(workspace / "docProps/app.xml")

        for slide_number, replacements in slide_texts.items():
            replace_slide_texts(workspace / f"ppt/slides/slide{slide_number}.xml", replacements)

        pack_pptx(workspace, output_path)

    return output_path


def generate_ppt(output_path: Path = DEFAULT_OUTPUT) -> Path:
    return generate_variant(output_path, STANDARD_SLIDES, SLIDE_TEXTS)


def generate_visual_ppt(output_path: Path = VISUAL_OUTPUT) -> Path:
    return generate_variant(output_path, VISUAL_SLIDES, VISUAL_SLIDE_TEXTS)


if __name__ == "__main__":
    generated = generate_ppt()
    print(generated)
