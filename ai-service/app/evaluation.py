from __future__ import annotations

import argparse
import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field


class EvaluationCase(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    case_id: str = Field(alias="caseId", min_length=1)
    relevant_chunk_ids: list[int] = Field(alias="relevantChunkIds", min_length=1)
    predicted_chunk_ids: list[int] = Field(alias="predictedChunkIds")


class EvaluationMetrics(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    cases: int
    recall_at_k: float = Field(alias="recallAtK")
    mean_reciprocal_rank: float = Field(alias="meanReciprocalRank")


def evaluate(cases: list[EvaluationCase]) -> EvaluationMetrics:
    if not cases:
        return EvaluationMetrics(cases=0, recall_at_k=0.0, mean_reciprocal_rank=0.0)
    recalls: list[float] = []
    reciprocal_ranks: list[float] = []
    for case in cases:
        relevant = set(case.relevant_chunk_ids)
        predicted = case.predicted_chunk_ids
        recalls.append(len(relevant & set(predicted)) / len(relevant))
        rank = next((index for index, chunk_id in enumerate(predicted, 1) if chunk_id in relevant), None)
        reciprocal_ranks.append(0.0 if rank is None else 1.0 / rank)
    return EvaluationMetrics(
        cases=len(cases),
        recall_at_k=round(sum(recalls) / len(recalls), 6),
        mean_reciprocal_rank=round(sum(reciprocal_ranks) / len(reciprocal_ranks), 6),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate saved RAG retrieval results from JSONL")
    parser.add_argument("dataset", type=Path)
    args = parser.parse_args()
    cases = [
        EvaluationCase.model_validate_json(line)
        for line in args.dataset.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    print(json.dumps(evaluate(cases).model_dump(by_alias=True), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
