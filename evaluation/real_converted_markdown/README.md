# Real Converted Markdown Fixtures

这些样例用于模拟真实 PDF / Word 文档经过解析后得到的 markdown 结果，
专门覆盖以下边界：

- `word_toc_policy.md`：目录项 + 正文章节
- `pdf_figure_medical.md`：图片占位 + 图表标题 + 邻接正文
- `pdf_ocr_governance.md`：OCR 断句 + 换行噪声

公开来源参考：

- DOCX 目录样例：
  `https://rst.fujian.gov.cn/zw/zfxxgk/zfxxgkml/zyywgz/zynljs/202601/P020260115320246506269.docx`
- PDF 图表样例：
  `https://sheitc.sh.gov.cn/cmsres/2d/2d6caa1ad8fe4cd88c3f0c3925af7a38/45b2295e486321349c540c4252c2b825.pdf`
- 中文白皮书 / OCR 风格样例：
  `https://www.cac.gov.cn/files/pdf/baipishu/shuzijingjifazhan.pdf`

为避免夹具过大，这里只保留了用于检索评测的代表性 markdown 片段。
