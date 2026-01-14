#!/usr/bin/env python3
"""
Fanout Configuration Generator

Generates optimized fanout configurations from protobuf analysis,
supporting multiple routing strategies for dynamic message distribution.

This replaces the bash fanout config generator with full Python implementation
and better strategy selection logic.

Usage:
    python3 generate_fanout_config.py <analysis.json> [output.yaml] [strategy]
"""

import argparse
import json
import sys
import yaml
from pathlib import Path
from typing import Dict, List, Any, Optional


class FanoutConfigGenerator:
    """Generates fanout configurations from protobuf analysis"""

    def __init__(self, analysis_data: Dict[str, Any]):
        self.analysis = analysis_data

        # Handle malformed data gracefully
        raw_strategies = analysis_data.get("routing_strategies", [])
        self.routing_strategies = raw_strategies if isinstance(raw_strategies, list) else []

        raw_recommendations = analysis_data.get("recommendations", {})
        self.recommendations = raw_recommendations if isinstance(raw_recommendations, dict) else {}

    def generate(self, strategy: str = "auto") -> Dict[str, Any]:
        """Generate fanout configuration based on strategy"""
        if strategy == "auto":
            strategy = self._select_best_strategy()

        if strategy == "oneof":
            return self._generate_oneof_strategy()
        elif strategy == "repeated":
            return self._generate_repeated_fields_strategy()
        elif strategy == "message":
            return self._generate_message_strategy()
        elif strategy == "single":
            return self._generate_single_strategy()
        else:
            raise ValueError(f"Unknown strategy: {strategy}")

    def _select_best_strategy(self) -> str:
        """Automatically select the best strategy based on analysis"""
        preferred = self.recommendations.get("preferred_strategy")
        if preferred:
            # Map strategy names to internal identifiers
            strategy_map = {
                "OneofField": "oneof",
                "RepeatedFields": "repeated",
                "MessageType": "message",
                "SingleTopic": "single"
            }
            return strategy_map.get(preferred, "single")

        # Fallback logic - check list of strategies
        for strategy in self.routing_strategies:
            if strategy.get("type") == "OneofField":
                return "oneof"
            elif strategy.get("type") == "MessageType":
                return "message"

        return "single"

    def _generate_oneof_strategy(self) -> Dict[str, Any]:
        """Generate OneofField strategy configuration"""
        # Find OneofField strategy in list
        oneof_strategy = None
        for strategy in self.routing_strategies:
            if strategy.get("type") == "OneofField":
                oneof_strategy = strategy
                break

        if not oneof_strategy:
            # Default fallback
            field_path = "transaction_type"
            oneof_field = "transaction_type"
            variants = ["transfer", "mint"]
        else:
            details = oneof_strategy.get("details", {})
            field_path = details.get("field_path", "transaction_type")
            oneof_field = details.get("oneof_field", "transaction_type")
            variants = details.get("variants", ["transfer", "mint"])

        # Generate topic mappings from variants
        topics = {}
        for variant in variants:
            # Convert variant name to topic name
            # e.g., "InitializeMint" -> "initialize_mints"
            topic_name = self._variant_to_topic(variant)
            topics[variant.lower()] = topic_name

        # Extract topic prefix suggestion
        topic_prefix = self.recommendations.get("topic_prefix_suggestion", "blockchain")

        return {
            "fanout_strategy": {
                "type": "OneofField",
                "field_path": field_path,
                "oneof_field": oneof_field
            },
            "topics": topics,
            "default_topic": "fallback",
            "topic_prefix": topic_prefix
        }

    def _generate_repeated_fields_strategy(self) -> Dict[str, Any]:
        """Generate RepeatedFields strategy configuration"""
        # Find RepeatedFields strategy in list
        repeated_strategy = None
        for strategy in self.routing_strategies:
            if strategy.get("type") == "RepeatedFields":
                repeated_strategy = strategy
                break

        if not repeated_strategy:
            # Fallback if no repeated fields strategy found
            return self._generate_single_strategy()

        details = repeated_strategy.get("details", {})
        repeated_fields = details.get("repeated_fields", [])
        message_type = details.get("message_type", "Events")

        # Generate topic mappings from repeated field names
        topics = {}
        for field_name in repeated_fields:
            # Convert field name to topic name
            # e.g., "trove_operations" -> "trove_operations"
            # e.g., "batch_operations" -> "batch_operations"
            topic_name = field_name  # Keep original field name as topic
            topics[field_name] = topic_name

        # Extract topic prefix suggestion
        topic_prefix = self.recommendations.get("topic_prefix_suggestion", "events")

        return {
            "fanout_strategy": {
                "type": "RepeatedFields",
                "message_type": message_type,
                "repeated_fields": repeated_fields
            },
            "topics": topics,
            "default_topic": "fallback",
            "topic_prefix": topic_prefix
        }

    def _generate_message_strategy(self) -> Dict[str, Any]:
        """Generate MessageType strategy configuration"""
        # Find MessageType strategy in list
        message_strategy = None
        for strategy in self.routing_strategies:
            if strategy.get("type") == "MessageType":
                message_strategy = strategy
                break

        if not message_strategy:
            message_types = ["SimpleMessage"]
        else:
            details = message_strategy.get("details", {})
            message_types = details.get("message_types", ["SimpleMessage"])

        # Generate topic mappings from message types
        topics = {}
        for msg_type in message_types:
            # Convert message type to topic name
            # e.g., "TransferEvent" -> "transfer_events"
            topic_name = self._message_to_topic(msg_type)
            topics[msg_type] = topic_name

        topic_prefix = self.recommendations.get("topic_prefix_suggestion", "blockchain")

        return {
            "fanout_strategy": {
                "type": "MessageType",
                "type_mapping": topics
            },
            "topics": topics,
            "default_topic": "fallback",
            "topic_prefix": topic_prefix
        }

    def _generate_single_strategy(self) -> Dict[str, Any]:
        """Generate SingleTopic strategy configuration"""
        topic_prefix = self.recommendations.get("topic_prefix_suggestion", "blockchain")

        return {
            "fanout_strategy": {
                "type": "SingleTopic"
            },
            "topics": {
                "all": "data"
            },
            "default_topic": "data",
            "topic_prefix": topic_prefix
        }

    def _variant_to_topic(self, variant: str) -> str:
        """Convert protobuf oneof variant to topic name"""
        # Convert CamelCase to snake_case and pluralize
        import re

        # Insert underscores before capital letters
        s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', variant)
        s2 = re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()

        # Simple pluralization rules
        if s2.endswith('y'):
            return s2[:-1] + "ies"
        elif s2.endswith(('s', 'sh', 'ch', 'x', 'z')):
            return s2 + "es"
        else:
            return s2 + "s"

    def _message_to_topic(self, message_type: str) -> str:
        """Convert message type to topic name"""
        # Similar to variant conversion but keep plurals
        import re

        s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', message_type)
        s2 = re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()

        # If already plural or ends with 'data', don't pluralize
        if s2.endswith(('s', 'data', 'info')):
            return s2
        else:
            return self._variant_to_topic(message_type)

    def generate_deployment_summary(self, config: Dict[str, Any]) -> Dict[str, Any]:
        """Generate deployment summary information"""
        strategy = config["fanout_strategy"]
        topics = config["topics"]
        prefix = config["topic_prefix"]

        return {
            "strategy_type": strategy["type"],
            "topic_count": len(topics),
            "prefixed_topics": [f"{prefix}_{topic}" for topic in topics.values()],
            "deployment_ready": True,
            "recommended_partitions": 4 if len(topics) > 3 else 1
        }


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description="Generate fanout configuration from protobuf analysis"
    )
    parser.add_argument(
        "analysis_file",
        type=Path,
        help="Path to protobuf analysis JSON file"
    )
    parser.add_argument(
        "output_file",
        type=Path,
        nargs="?",
        help="Output YAML file path (default: stdout)"
    )
    parser.add_argument(
        "--strategy",
        choices=["auto", "oneof", "repeated", "message", "single"],
        default="auto",
        help="Fanout strategy to use (default: auto)"
    )
    parser.add_argument(
        "--summary",
        action="store_true",
        help="Include deployment summary in output"
    )

    args = parser.parse_args()

    # Load analysis data
    try:
        with open(args.analysis_file) as f:
            analysis_data = json.load(f)
    except FileNotFoundError:
        print(f"❌ Error: Analysis file not found: {args.analysis_file}")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"❌ Error: Invalid JSON in analysis file: {e}")
        sys.exit(1)

    try:
        # Generate fanout configuration
        generator = FanoutConfigGenerator(analysis_data)
        config = generator.generate(args.strategy)

        # Add deployment summary if requested
        if args.summary:
            config["deployment_summary"] = generator.generate_deployment_summary(config)

        # Output configuration
        yaml_output = yaml.dump(config, default_flow_style=False, sort_keys=False)

        if args.output_file:
            with open(args.output_file, 'w') as f:
                f.write(yaml_output)
            print(f"✅ Fanout configuration written to: {args.output_file}")
        else:
            print(yaml_output)

    except Exception as e:
        print(f"❌ Error generating fanout configuration: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()