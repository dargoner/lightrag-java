import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPO_ROOT / "docs/slides/generate_knowledge_governance_ppt.py"


def load_module():
    spec = importlib.util.spec_from_file_location("knowledge_governance_ppt", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class KnowledgeGovernancePptTest(unittest.TestCase):
    def test_generates_three_slide_ppt_with_expected_content(self):
        module = load_module()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "knowledge-governance.pptx"
            module.generate_ppt(output_path)

            self.assertTrue(output_path.exists())

            with zipfile.ZipFile(output_path) as archive:
                names = set(archive.namelist())
                self.assertIn("ppt/presentation.xml", names)
                self.assertIn("ppt/_rels/presentation.xml.rels", names)
                self.assertIn("[Content_Types].xml", names)

                ns = {
                    "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
                    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                    "ct": "http://schemas.openxmlformats.org/package/2006/content-types",
                    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
                }

                presentation = ET.fromstring(archive.read("ppt/presentation.xml"))
                slides = presentation.findall(".//p:sldId", ns)
                self.assertEqual(3, len(slides))

                rels = ET.fromstring(archive.read("ppt/_rels/presentation.xml.rels"))
                targets = {
                    rel.attrib["Target"]
                    for rel in rels.findall(
                        ".//{http://schemas.openxmlformats.org/package/2006/relationships}Relationship"
                    )
                    if rel.attrib.get("Type", "").endswith("/slide")
                }
                self.assertEqual(
                    {"slides/slide4.xml", "slides/slide5.xml", "slides/slide6.xml"},
                    targets,
                )

                content_types = ET.fromstring(archive.read("[Content_Types].xml"))
                overrides = {
                    item.attrib["PartName"]
                    for item in content_types.findall(".//ct:Override", ns)
                }
                self.assertIn("/ppt/slides/slide4.xml", overrides)
                self.assertIn("/ppt/slides/slide5.xml", overrides)
                self.assertIn("/ppt/slides/slide6.xml", overrides)
                self.assertNotIn("/ppt/slides/slide1.xml", overrides)
                self.assertNotIn("/ppt/slides/slide2.xml", overrides)
                self.assertNotIn("/ppt/slides/slide3.xml", overrides)

                slide4_text = "\n".join(
                    t.text or ""
                    for t in ET.fromstring(archive.read("ppt/slides/slide4.xml")).findall(".//a:t", ns)
                )
                slide5_text = "\n".join(
                    t.text or ""
                    for t in ET.fromstring(archive.read("ppt/slides/slide5.xml")).findall(".//a:t", ns)
                )
                slide6_text = "\n".join(
                    t.text or ""
                    for t in ET.fromstring(archive.read("ppt/slides/slide6.xml")).findall(".//a:t", ns)
                )

                self.assertIn("知识库效果差，很多时候不是模型问题", slide4_text)
                self.assertIn("最大问题不是知识不够，而是知识不可控", slide4_text)
                self.assertIn("知识库治理，本质是治理知识供给系统", slide5_text)
                self.assertIn("从准入到淘汰", slide5_text)
                self.assertIn("真正落地时，优先做这 5 件事", slide6_text)
                self.assertIn("治理才真正工程化", slide6_text)

    def test_generates_visual_enhanced_variant_with_expected_slides(self):
        module = load_module()

        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "knowledge-governance-visual.pptx"
            module.generate_visual_ppt(output_path)

            self.assertTrue(output_path.exists())

            with zipfile.ZipFile(output_path) as archive:
                ns = {
                    "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
                    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
                }

                presentation = ET.fromstring(archive.read("ppt/presentation.xml"))
                slides = presentation.findall(".//p:sldId", ns)
                self.assertEqual(3, len(slides))

                slide2_text = "\n".join(
                    t.text or ""
                    for t in ET.fromstring(archive.read("ppt/slides/slide2.xml")).findall(".//a:t", ns)
                )
                slide5_text = "\n".join(
                    t.text or ""
                    for t in ET.fromstring(archive.read("ppt/slides/slide5.xml")).findall(".//a:t", ns)
                )
                slide6_text = "\n".join(
                    t.text or ""
                    for t in ET.fromstring(archive.read("ppt/slides/slide6.xml")).findall(".//a:t", ns)
                )

                self.assertIn("知识库治理工程", slide2_text)
                self.assertIn("TECH SALON", slide2_text)
                self.assertIn("来源杂乱", slide2_text)
                self.assertIn("责任缺位", slide2_text)
                self.assertIn("知识供给系统", slide5_text)
                self.assertIn("全生命周期机制", slide5_text)
                self.assertIn("优先做这 5 件事", slide6_text)
                self.assertIn("反馈真正接回治理流程", slide6_text)


if __name__ == "__main__":
    unittest.main()
