# Real Converted Markdown Retrieval Report

## Scope

本报告用于验收以下链路在“真实 PDF / Word 转 markdown”样例上的检索表现：

- `FIXED`
- `SMART`
- `SMART + PARENT`

评测目标聚焦四项指标：

- `Top1`
- `Top3`
- `命中文档`
- `命中片段`

## Fixture Set

样例文档：

- `01_word_toc_policy.md`
  - 类型：Word 转 markdown
  - 场景：目录项 + 正文章节
- `02_pdf_figure_medical.md`
  - 类型：PDF 转 markdown
  - 场景：图片占位 + 图表标题 + 邻接正文
- `03_pdf_ocr_governance.md`
  - 类型：PDF 转 markdown
  - 场景：OCR 断句 + 换行噪声

固定问题集：

1. `继续教育审核流程包括哪些环节？`
2. `医疗信息化政策重点一览图对应的说明是什么？`
3. `数字治理试点阶段的工作目标是什么？`

## Metrics

基于 `RealConvertedMarkdownRetrievalEvaluationTest` 的最新结果：

- `FIXED`
  - `Top1 = 1.0000`
  - `Top3 = 1.0000`
  - `FragmentHit = 1.0000`
- `SMART`
  - `Top1 = 1.0000`
  - `Top3 = 1.0000`
  - `FragmentHit = 0.6667`
- `SMART + PARENT`
  - `Top1 = 1.0000`
  - `Top3 = 1.0000`
  - `FragmentHit = 1.0000`

## Per Case

### 1. Word 目录 / 审核流程

- 问题：`继续教育审核流程包括哪些环节？`
- 期望文档：`01-word-toc-policy`
- 期望片段：`形式审查、专家复核和结果公示`

结果：

- `FIXED`
  - Top1 命中
  - 片段命中
- `SMART`
  - Top1 命中
  - 片段未命中
  - 首命中更偏向目录项 chunk
- `SMART + PARENT`
  - Top1 命中
  - 片段命中
  - 能回到正文审核流程 chunk

### 2. PDF 图表标题 / 医疗政策图

- 问题：`医疗信息化政策重点一览图对应的说明是什么？`
- 期望文档：`02-pdf-figure-medical`
- 期望片段：`图 1-4：历次医疗改革中医疗信息化政策重点一览图`

结果：

- `FIXED`
  - Top1 命中
  - 片段命中
- `SMART`
  - Top1 命中
  - 片段命中
- `SMART + PARENT`
  - Top1 命中
  - 片段命中
  - 图注 / 邻接图文链路稳定

### 3. PDF OCR 断句 / 数字治理目标

- 问题：`数字治理试点阶段的工作目标是什么？`
- 期望文档：`03-pdf-ocr-governance`
- 期望片段：`形成跨部门协同机制`

结果：

- `FIXED`
  - Top1 命中
  - 片段命中
- `SMART`
  - Top1 命中
  - 片段命中
- `SMART + PARENT`
  - Top1 命中
  - 片段命中

## Conclusion

当前这组真实 markdown 样例上：

- `SMART` 已经可以保证文档级召回
- `SMART + PARENT` 的主要收益体现在“片段级命中更准”
- 最明显的改进点出现在 `Word 目录 + 正文章节` 场景
  - `SMART` 更容易命中目录项
  - `SMART + PARENT` 能把命中从目录项拉回正文片段

因此，这一轮验收可以下结论：

- 文档级召回：三种模式都可用
- 片段级召回：`SMART + PARENT` 优于纯 `SMART`
- 图文混排与 OCR 断句场景：当前样例已通过

## Verification

对应自动化测试：

- `io.github.lightragjava.evaluation.RealConvertedMarkdownRetrievalEvaluationTest`

运行命令：

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests 'io.github.lightragjava.evaluation.RealConvertedMarkdownRetrievalEvaluationTest'
```
