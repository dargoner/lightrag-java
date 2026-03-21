import importlib.util
import sys
import types
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("eval_rag_quality_java.py")


def load_module():
    dotenv_module = types.ModuleType("dotenv")
    dotenv_module.load_dotenv = lambda *args, **kwargs: None

    datasets_module = types.ModuleType("datasets")

    class Dataset:
        @staticmethod
        def from_dict(data):
            return data

    datasets_module.Dataset = Dataset

    ragas_module = types.ModuleType("ragas")
    ragas_module.evaluate = lambda *args, **kwargs: None

    ragas_metrics_module = types.ModuleType("ragas.metrics")
    ragas_metrics_module.AnswerRelevancy = type("AnswerRelevancy", (), {})
    ragas_metrics_module.ContextPrecision = type("ContextPrecision", (), {})
    ragas_metrics_module.ContextRecall = type("ContextRecall", (), {})
    ragas_metrics_module.Faithfulness = type("Faithfulness", (), {})

    ragas_llms_module = types.ModuleType("ragas.llms")
    ragas_llms_module.LangchainLLMWrapper = type("LangchainLLMWrapper", (), {})

    langchain_openai_module = types.ModuleType("langchain_openai")
    langchain_openai_module.ChatOpenAI = type("ChatOpenAI", (), {})
    langchain_openai_module.OpenAIEmbeddings = type("OpenAIEmbeddings", (), {})

    stubbed_modules = {
        "dotenv": dotenv_module,
        "datasets": datasets_module,
        "ragas": ragas_module,
        "ragas.metrics": ragas_metrics_module,
        "ragas.llms": ragas_llms_module,
        "langchain_openai": langchain_openai_module,
    }

    with mock.patch.dict(sys.modules, stubbed_modules):
        spec = importlib.util.spec_from_file_location("eval_rag_quality_java_under_test", MODULE_PATH)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        return module


class NormalizeBatchResultsTest(unittest.TestCase):
    def test_keeps_legacy_batch_list_shape(self):
        module = load_module()

        normalized = module._normalize_batch_results([
            {
                "answer": "Alice works with Bob.",
                "contexts": ["Alice works with Bob", "Bob works on retrieval"],
            }
        ])

        self.assertEqual(
            normalized,
            [
                {
                    "answer": "Alice works with Bob.",
                    "contexts": ["Alice works with Bob", "Bob works on retrieval"],
                }
            ],
        )

    def test_unwraps_batch_envelope_and_structured_contexts(self):
        module = load_module()

        normalized = module._normalize_batch_results(
            {
                "request": {"mode": "MIX"},
                "summary": {"totalCases": 1},
                "results": [
                    {
                        "answer": "Alice works with Bob.",
                        "contexts": [
                            {
                                "sourceId": "chunk-1",
                                "referenceId": "",
                                "source": "",
                                "text": "Alice works with Bob",
                            },
                            {
                                "sourceId": "chunk-2",
                                "referenceId": "",
                                "source": "",
                                "text": "Bob works on retrieval",
                            },
                        ],
                    }
                ],
            }
        )

        self.assertEqual(
            normalized,
            [
                {
                    "answer": "Alice works with Bob.",
                    "contexts": ["Alice works with Bob", "Bob works on retrieval"],
                }
            ],
        )


if __name__ == "__main__":
    unittest.main()
