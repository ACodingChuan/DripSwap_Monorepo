#!/usr/bin/env python3
"""
Test suite for protobuf analyzer with semantic type support.
"""

import json
import pytest
from pathlib import Path
import sys

# Add scripts directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent / "scripts"))

from analyze_protobuf_schema import ProtobufAnalyzer, ProtobufField  # noqa: E402


class TestProtobufField:
    """Unit tests for individual protobuf field helpers."""

    def test_field_creation(self):
        field = ProtobufField("test_field", "string", 1)
        assert field.name == "test_field"
        assert field.field_type == "string"
        assert field.field_number == 1
        assert field.semantic_type == ""
        assert field.primary_key is False
        assert field.foreign_key == ""
        assert field.format_hint == ""

    def test_semantic_analysis_output(self):
        field = ProtobufField("tx_hash", "string", 1)
        field.semantic_type = "hash"
        field.primary_key = True
        field.foreign_key = "transactions on id"
        field.format_hint = "hex"

        result = field.to_semantic_analysis("TestMessage")
        assert result == {
            "TestMessage.tx_hash": {
                "semantic_type": "hash",
                "primary_key": True,
                "foreign_key": "transactions on id",
                "format_hint": "hex",
            }
        }


class TestProtobufAnalyzer:
    """Functional tests for the analyzer end-to-end."""

    @pytest.fixture
    def test_proto_dir(self):
        return Path(__file__).parent / "protobuf"

    def test_comprehensive_semantic_types(self, test_proto_dir):
        proto_path = test_proto_dir / "comprehensive_semantic_types.proto"
        analyzer = ProtobufAnalyzer(str(proto_path))
        result = analyzer.analyze()

        assert "field_patterns" in result
        assert "routing_strategies" in result
        assert "recommendations" in result

        semantic_analysis = result["field_patterns"]["semantic_analysis"]

        expected_semantic_types = {
            "address",
            "base64",
            "block_timestamp",
            "hash",
            "hex",
            "int256",
            "json",
            "pubkey",
            "signature",
            "uint256",
            "unix_timestamp",
            "unix_timestamp_ms",
            "uuid",
        }

        detected_types = {
            field_data["semantic_type"]
            for field_data in semantic_analysis.values()
            if field_data["semantic_type"]
        }

        assert expected_semantic_types.issubset(detected_types)

        assert "ComprehensiveSemanticTest.record_id" in semantic_analysis
        record_id = semantic_analysis["ComprehensiveSemanticTest.record_id"]
        assert record_id["semantic_type"] == "uuid"
        assert record_id["primary_key"] is True

        amount_fields = [k for k in semantic_analysis if "amount" in k.lower()]
        assert amount_fields
        for field_key in amount_fields:
            field_data = semantic_analysis[field_key]
            if field_data["semantic_type"] == "uint256":
                assert "format_hint" in field_data

    def test_all_protobuf_types_regular(self, test_proto_dir):
        proto_path = test_proto_dir / "all_protobuf_types.proto"
        analyzer = ProtobufAnalyzer(str(proto_path))
        result = analyzer.analyze()

        semantic_analysis = result["field_patterns"]["semantic_analysis"]

        semantic_fields = [k for k, v in semantic_analysis.items() if v["semantic_type"]]
        assert len(semantic_fields) == 0

        primary_key_fields = [k for k, v in semantic_analysis.items() if v["primary_key"]]
        assert len(primary_key_fields) >= 1

        strategies = result["routing_strategies"]
        assert any(s["type"] == "SingleTopic" for s in strategies)

    def test_mixed_semantic_regular(self, test_proto_dir):
        proto_path = test_proto_dir / "mixed_semantic_regular.proto"
        analyzer = ProtobufAnalyzer(str(proto_path))
        result = analyzer.analyze()

        semantic_analysis = result["field_patterns"]["semantic_analysis"]

        semantic_fields = [k for k, v in semantic_analysis.items() if v["semantic_type"]]
        non_semantic_fields = [k for k, v in semantic_analysis.items() if not v["semantic_type"]]

        assert len(semantic_fields) > 10
        assert len(non_semantic_fields) > 0

        assert "MixedSemanticRegular.tx_hash" in semantic_analysis
        tx_hash = semantic_analysis["MixedSemanticRegular.tx_hash"]
        assert tx_hash["semantic_type"] == "hash"
        assert tx_hash["primary_key"] is True

        assert "TokenTransfer.amount" in semantic_analysis
        amount = semantic_analysis["TokenTransfer.amount"]
        assert amount["semantic_type"] == "uint256"

        strategies = result["routing_strategies"]
        assert any(s["type"] == "OneofField" for s in strategies)

    def test_routing_strategy_generation(self, test_proto_dir):
        proto_path = test_proto_dir / "mixed_semantic_regular.proto"
        analyzer = ProtobufAnalyzer(str(proto_path))
        result = analyzer.analyze()

        strategy_types = [s["type"] for s in result["routing_strategies"]]

        assert "OneofField" in strategy_types
        assert "MessageType" in strategy_types
        assert "SingleTopic" in strategy_types

        oneof_strategy = next(s for s in result["routing_strategies"] if s["type"] == "OneofField")
        assert oneof_strategy["recommended"] is True

    def test_json_output_format(self, test_proto_dir):
        proto_path = test_proto_dir / "comprehensive_semantic_types.proto"
        analyzer = ProtobufAnalyzer(str(proto_path))
        result = analyzer.analyze()

        required_keys = {
            "proto_file",
            "analyzed_at",
            "analyzer_version",
            "routing_strategies",
            "field_patterns",
            "recommendations",
        }
        assert set(result.keys()) >= required_keys

        field_patterns = result["field_patterns"]
        assert "extraction_patterns" in field_patterns
        assert "semantic_analysis" in field_patterns

        semantic_analysis = field_patterns["semantic_analysis"]
        for field_key, field_data in semantic_analysis.items():
            assert "." in field_key
            required_field_keys = {
                "semantic_type",
                "primary_key",
                "foreign_key",
                "format_hint",
            }
            assert set(field_data.keys()) == required_field_keys

        recommendations = result["recommendations"]
        required_rec_keys = {
            "preferred_strategy",
            "reasoning",
            "topic_prefix_suggestion",
            "next_steps",
        }
        assert set(recommendations.keys()) == required_rec_keys
        assert "Generate fanout configuration" in recommendations["next_steps"][0]

    def test_error_handling(self):
        with pytest.raises(FileNotFoundError):
            analyzer = ProtobufAnalyzer("nonexistent.proto")
            analyzer.analyze()

    def test_semantic_annotation_parsing(self):
        analyzer = ProtobufAnalyzer("dummy.proto")

        field_def = '''string tx_hash = 1 [(sf.substreams.sink.sql.schema.v1.field) = {
            semantic_type: "hash",
            primary_key: true,
            foreign_key: "transactions on id",
            format_hint: "hex"
        }];'''

        field = analyzer._parse_field_definition(field_def)
        assert field is not None
        assert field.name == "tx_hash"
        assert field.semantic_type == "hash"
        assert field.primary_key is True
        assert field.foreign_key == "transactions on id"
        assert field.format_hint == "hex"

    def test_multiline_annotation_parsing(self):
        analyzer = ProtobufAnalyzer("dummy.proto")

        field_def = '''string complex_field = 5 [(sf.substreams.sink.sql.schema.v1.field) = {
            semantic_type: "uint256",
            format_hint: "decimal"
        }];'''

        field = analyzer._parse_field_definition(field_def)
        assert field is not None
        assert field.semantic_type == "uint256"
        assert field.format_hint == "decimal"
        assert field.primary_key is False


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
