from __future__ import annotations

import pytest

from app.blocks.schemas import LearningBlockRequest
from app.blocks.service import LearningBlockAiService
from app.model.schemas import ModelCompletion


class RecordingBlockModel:
    def __init__(self) -> None:
        self.system_prompt = ""
        self.user_prompt = ""

    @property
    def configured(self) -> bool:
        return True

    @property
    def model_name(self) -> str:
        return "fake-block-model"

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del max_output_tokens
        self.system_prompt = system_prompt
        self.user_prompt = user_prompt
        return ModelCompletion(
            content=(
                '{"materialMarkdown":"## AI 生成的待核验学习材料\\n'
                '这一知识块用于解释核心概念、示例、易错点以及如何核验资料来源。'
                '学习完成以后应通过练习和测试验证理解，而不是要求学满预计时长。'
                '开始前先明确概念边界，再使用例子建立联系，最后用反例识别常见误区。'
                '如果资料不足，应优先检索官方网站、开放教材或课程讲义，并将文件上传知识库后重新生成。",'
                '"exercises":['
                '{"prompt":"解释核心概念","answer":"参考答案一","explanation":"解释一"},'
                '{"prompt":"给出应用例子","answer":"参考答案二","explanation":"解释二"}],'
                '"testQuestions":['
                '{"id":"q1","type":"TRUE_FALSE","stem":"判断一","options":["正确","错误"],"answer":"正确","analysis":"分析一"},'
                '{"id":"q2","type":"SINGLE_CHOICE","stem":"选择二","options":["甲","乙"],"answer":"乙","analysis":"分析二"},'
                '{"id":"q3","type":"TRUE_FALSE","stem":"判断三","options":["正确","错误"],"answer":"错误","analysis":"分析三"}],'
                '"sourceNotes":["请检索官方资料并上传知识库"]}'
            ),
            model="fake-block-model",
            latency_ms=4,
        )


@pytest.mark.asyncio
async def test_custom_direction_block_is_generated_independently_and_grounded() -> None:
    model = RecordingBlockModel()
    service = LearningBlockAiService(model)
    request = LearningBlockRequest.model_validate(
        {
            "userId": 1,
            "title": "探索自定义方向",
            "objective": "界定学习边界并掌握核心概念",
            "directionName": "自定义方向",
            "currentStage": "BEGINNER",
            "explorationRequired": True,
            "sources": [],
            "sourceQueries": ["site:official.example 核心概念"],
        }
    )

    result = await service.generate(request)

    assert result.prompt_version == "learning-block-v1-grounded"
    assert len(result.exercises) == 2
    assert len(result.test_questions) == 3
    assert "AI 生成的待核验学习材料" in result.material_markdown
    assert "是否处于探索阶段：True" in model.user_prompt
    assert "预计学习时间只是容量参考" in model.system_prompt
