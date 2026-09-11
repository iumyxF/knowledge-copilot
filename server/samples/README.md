# 自编中文示例与评测基线

这些资料和业务事实完全为本项目编写，仅用于测试，不代表真实企业政策。

- documents：五种格式各一份。原文在 source.json。
- evaluation/baseline.json：20 条用例，需替换 knowledgeBaseId 和 documentMapping。
- prepare-import.ps1：根据真实 ID 生成 target/baseline-import.json，不自动发请求。
- generate-office.ps1：在已安装 Microsoft Word 的 Windows 环境重新生成 PDF/DOC/DOCX。
- invalid：损坏 PDF 和只有图像、没有文本层的 PDF，仅用于失败验证，不上传到正向基线知识库。

人工拒答验收表可记录：question、actualAnswer、expectedRefusal、人工结论、备注。不要给无答案用例填写虚假的正向相关文档。
