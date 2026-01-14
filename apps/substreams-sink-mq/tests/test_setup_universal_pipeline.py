#!/usr/bin/env python3
"""
Tests for the UniversalPipelineOrchestrator.
"""

import json
import pytest
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch

import sys
BASE_DIR = Path(__file__).parent.parent
sys.path.append(str(BASE_DIR / "scripts"))

from setup_universal_pipeline import UniversalPipelineOrchestrator  # noqa: E402


class TestUniversalPipelineOrchestrator:
    """Unit tests covering orchestrator behaviour."""

    @pytest.fixture
    def temp_workspace(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir)
            proto_file = workspace / "sample.proto"
            proto_file.write_text(
                'syntax = "proto3";\n'
                'package sample;\n'
                'message Demo { string id = 1; }\n'
            )
            yield {
                "workspace": workspace,
                "proto_file": proto_file,
                "output_dir": workspace / "output",
            }

    def test_initialization_valid_backend(self, temp_workspace):
        orchestrator = UniversalPipelineOrchestrator(
            backend="nats",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        assert orchestrator.backend == "nats"
        assert orchestrator.proto_name == "sample"
        assert orchestrator.analysis_file.name == "sample-analysis.json"
        assert orchestrator.fanout_config.name == "sample-fanout.yaml"

    def test_initialization_invalid_backend(self, temp_workspace):
        with pytest.raises(ValueError):
            UniversalPipelineOrchestrator(
                backend="invalid",
                proto_file=temp_workspace["proto_file"],
                output_dir=temp_workspace["output_dir"],
            )

    @patch("setup_universal_pipeline.ProtobufAnalyzer")
    def test_analyze_protobuf_creates_file(self, mock_analyzer, temp_workspace):
        mock_instance = Mock()
        mock_instance.analyze.return_value = {
            "routing_strategies": [],
            "field_patterns": {"semantic_analysis": {}},
            "recommendations": {},
        }
        mock_analyzer.return_value = mock_instance

        orchestrator = UniversalPipelineOrchestrator(
            backend="nats",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        temp_workspace["output_dir"].mkdir(parents=True, exist_ok=True)
        result = orchestrator._analyze_protobuf()

        mock_analyzer.assert_called_once_with(temp_workspace["proto_file"], None)
        assert orchestrator.analysis_file.exists()
        assert json.loads(orchestrator.analysis_file.read_text()) == {
            "routing_strategies": [],
            "field_patterns": {"semantic_analysis": {}},
            "recommendations": {},
            "schema_info": {
                "proto_file": str(temp_workspace["proto_file"]),
                "primary_message_type": "unknown.Message",
            },
        }
        assert result == mock_instance.analyze.return_value

    @patch("setup_universal_pipeline.FanoutConfigGenerator")
    def test_generate_fanout_config_writes_yaml(self, mock_generator, temp_workspace):
        mock_instance = Mock()
        mock_instance.generate.return_value = {"topic_prefix": "demo", "topics": {}}
        mock_generator.return_value = mock_instance

        orchestrator = UniversalPipelineOrchestrator(
            backend="kafka",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        temp_workspace["output_dir"].mkdir(parents=True, exist_ok=True)
        fanout = orchestrator._generate_fanout_config({"analysis": "data"}, strategy="auto")
        assert fanout == {"topic_prefix": "demo", "topics": {}}
        assert orchestrator.fanout_config.exists()

    @patch.object(UniversalPipelineOrchestrator, "_deploy_streams_topics")
    @patch.object(UniversalPipelineOrchestrator, "_generate_fanout_config")
    @patch.object(UniversalPipelineOrchestrator, "_analyze_protobuf")
    @patch.object(UniversalPipelineOrchestrator, "_display_summary")
    def test_run_success_no_deploy(
        self,
        mock_summary,
        mock_analyze,
        mock_generate,
        mock_deploy,
        temp_workspace,
    ):
        mock_analyze.return_value = {"analysis": "ok"}
        mock_generate.return_value = {"fanout": "ok"}

        orchestrator = UniversalPipelineOrchestrator(
            backend="kafka",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        success = orchestrator.run(strategy="auto", deploy=False, primary_message=None)

        assert success is True
        mock_analyze.assert_called_once()
        mock_generate.assert_called_once()
        mock_deploy.assert_not_called()
        mock_summary.assert_called_once_with({"analysis": "ok"}, {"fanout": "ok"}, False)

    @patch.object(UniversalPipelineOrchestrator, "_deploy_streams_topics")
    @patch.object(UniversalPipelineOrchestrator, "_generate_fanout_config")
    @patch.object(UniversalPipelineOrchestrator, "_analyze_protobuf")
    @patch.object(UniversalPipelineOrchestrator, "_display_summary")
    def test_run_with_deploy_failure(
        self,
        mock_summary,
        mock_analyze,
        mock_generate,
        mock_deploy,
        temp_workspace,
        capsys,
    ):
        mock_analyze.return_value = {"analysis": "ok"}
        mock_generate.return_value = {"fanout": "ok"}
        mock_deploy.return_value = False

        orchestrator = UniversalPipelineOrchestrator(
            backend="nats",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        success = orchestrator.run(strategy="auto", deploy=True)

        assert success is True  # pipeline still completes
        assert mock_deploy.called
        captured = capsys.readouterr()
        assert "Deployment failed" in captured.out

    def test_extract_primary_message_type(self, temp_workspace):
        orchestrator = UniversalPipelineOrchestrator(
            backend="nats",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        analysis = {
            "routing_strategies": [
                {"type": "OneofField", "details": {"message_type": "sample.Demo"}},
            ],
            "recommendations": {"reasoning": "Oneof variant detection"},
            "field_patterns": {"semantic_analysis": {"Demo.field": {}}},
        }

        assert orchestrator._extract_primary_message_type(analysis) == "sample.Demo"

    def test_extract_primary_message_type_fallback(self, temp_workspace):
        orchestrator = UniversalPipelineOrchestrator(
            backend="kafka",
            proto_file=temp_workspace["proto_file"],
            output_dir=temp_workspace["output_dir"],
        )

        analysis = {
            "routing_strategies": [],
            "recommendations": {},
            "field_patterns": {"semantic_analysis": {"Demo.field": {}}},
        }

        assert orchestrator._extract_primary_message_type(analysis) == "Demo"

