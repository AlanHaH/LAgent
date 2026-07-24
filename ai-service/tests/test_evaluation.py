from app.evaluation import EvaluationCase, evaluate


def test_retrieval_metrics_are_reproducible() -> None:
    cases = [
        EvaluationCase(caseId="hit", relevantChunkIds=[1, 2], predictedChunkIds=[1, 3, 2]),
        EvaluationCase(caseId="miss", relevantChunkIds=[4], predictedChunkIds=[8, 9]),
    ]

    metrics = evaluate(cases)

    assert metrics.cases == 2
    assert metrics.recall_at_k == 0.5
    assert metrics.mean_reciprocal_rank == 0.5
