#!/usr/bin/env python3
"""
Universal Pipeline Setup with Backend Selection - Python Edition!

This script orchestrates a protobuf-driven data ingestion pipeline
with backend-specific fanout for either Kafka or NATS:
1. Analyzes any protobuf schema with semantic types
2. Generates fanout configuration
3. Sets up the complete pipeline for JSON-based streaming

Replaces the bash version with full Python consistency and better error handling.

Usage:
    python3 setup_universal_pipeline.py <backend> <proto-file> [options]

Example:
    python3 setup_universal_pipeline.py nats /path/to/schema.proto
    python3 setup_universal_pipeline.py kafka /path/to/schema.proto --output-dir ./configs
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, Any, Optional

# Import our existing pipeline components
from analyze_protobuf_schema import ProtobufAnalyzer
from generate_fanout_config import FanoutConfigGenerator
from nats_stream_manager import NATSStreamManager
from kafka_topic_manager import KafkaTopicManager
import yaml
import asyncio

# Ensure the project root is importable when this script is executed directly
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


class UniversalPipelineOrchestrator:
    """Orchestrates the complete protobuf pipeline setup"""

    def __init__(self, backend: str, proto_file: Path, output_dir: Path):
        self.backend = backend.lower()
        self.proto_file = proto_file
        self.output_dir = output_dir
        self.proto_name = proto_file.stem

        # Validate backend
        if self.backend not in ['kafka', 'nats']:
            raise ValueError(f"Unsupported backend: {backend}. Use 'kafka' or 'nats'")

        # Generate file paths
        self.analysis_file = output_dir / f"{self.proto_name}-analysis.json"
        self.fanout_config = output_dir / f"{self.proto_name}-fanout.yaml"

    def run(self, strategy: str = "auto", deploy: bool = False, primary_message: Optional[str] = None) -> bool:
        """Run the complete pipeline setup"""
        print("Universal Pipeline Setup - Python Edition! ")
        print(f"Backend: {self.backend}")
        print(f"Protobuf file: {self.proto_file}")
        print(f"Output directory: {self.output_dir}")
        print(f"Strategy: {strategy}")
        print("")

        try:
            # Ensure directories exist
            self.output_dir.mkdir(parents=True, exist_ok=True)

            # Step 1: Analyze protobuf schema
            print("🔍 Step 1: Analyzing protobuf schema...")
            analysis = self._analyze_protobuf(primary_message)
            print("✅ Protobuf analysis complete")
            print("")

            # Step 2: Generate fanout configuration
            print("🏗️ Step 2: Generating fanout configuration...")
            fanout_config = self._generate_fanout_config(analysis, strategy)
            print("✅ Fanout configuration complete")
            print("")

            # Step 4: Deploy streams/topics if requested
            if deploy:
                # Run deployment in async context
                deployment_success = asyncio.run(self._deploy_streams_topics(fanout_config))
                if not deployment_success:
                    print("⚠️  Deployment failed, but pipeline setup is complete")
                print("")

            # Step 5: Display summary and next steps
            self._display_summary(analysis, fanout_config, deploy)

            return True

        except Exception as e:
            print(f"❌ Error in pipeline setup: {e}")
            return False

    def _analyze_protobuf(self, primary_message: Optional[str] = None) -> Dict[str, Any]:
        """Step 1: Analyze protobuf schema with semantic types"""
        analyzer = ProtobufAnalyzer(self.proto_file, primary_message)
        analysis = analyzer.analyze()

        # Add schema information with override support
        if primary_message:
            primary_message_type = primary_message
        else:
            primary_message_type = self._extract_primary_message_type(analysis)

        analysis["schema_info"] = {
            "proto_file": str(self.proto_file),
            "primary_message_type": primary_message_type
        }

        # Write analysis file
        with open(self.analysis_file, 'w') as f:
            json.dump(analysis, f, indent=2)

        return analysis

    def _extract_primary_message_type(self, analysis: Dict[str, Any]) -> str:
        """Extract the primary message type from protobuf analysis"""
        # Look for the most complex message (likely the root type)
        routing_strategies = analysis.get("routing_strategies", [])

        # Prefer explicit message_type from strategy details when available
        for strategy in routing_strategies:
            if strategy.get("type") == "OneofField":
                details = strategy.get("details", {})
                message_type = details.get("message_type")
                if message_type:
                    return message_type

        # Fallback: extract message type from field patterns
        field_patterns = analysis.get("field_patterns", {})
        if field_patterns:
            semantic_analysis = field_patterns.get("semantic_analysis", {})
            if semantic_analysis:
                # Get first field and extract message type from it
                first_field = next(iter(semantic_analysis.keys()), "")
                if "." in first_field:
                    # Extract message type (e.g., "TestMessage.id" -> "TestMessage")
                    return first_field.split(".")[0]

        return "unknown.Message"


    def _generate_fanout_config(self, analysis: Dict[str, Any], strategy: str) -> Dict[str, Any]:
        """Step 2: Generate fanout configuration based on analysis"""
        # Import and use the fanout config generator

        generator = FanoutConfigGenerator(analysis)
        fanout_config = generator.generate(strategy)

        # Write fanout config file
        with open(self.fanout_config, 'w') as f:
            yaml.dump(fanout_config, f, default_flow_style=False)

        return fanout_config

    async def _deploy_streams_topics(self, fanout_config: Dict[str, Any]) -> bool:
        """Step 4: Deploy streams/topics based on backend"""
        print(f"🚀 Step 4: Deploying {self.backend} streams/topics...")

        if self.backend == "nats":
            return await self._deploy_nats_streams()
        elif self.backend == "kafka":
            return self._deploy_kafka_topics()
        else:
            print(f"❌ Unknown backend for deployment: {self.backend}")
            return False

    async def _deploy_nats_streams(self) -> bool:
        """Deploy NATS JetStream streams"""
        try:
            manager = NATSStreamManager()
            success = await manager.create_streams_from_fanout(self.fanout_config)
            if success:
                print("   ✅ NATS streams created successfully")
            return success
        except Exception as e:
            print(f"   ❌ Failed to create NATS streams: {e}")
            return False

    def _deploy_kafka_topics(self) -> bool:
        """Deploy Kafka/Redpanda topics"""
        try:
            manager = KafkaTopicManager()
            success = manager.create_topics_from_fanout(self.fanout_config)
            if success:
                print("   ✅ Kafka topics created successfully")
            return success
        except Exception as e:
            print(f"   ❌ Failed to create Kafka topics: {e}")
            return False

    def _display_summary(self, analysis: Dict[str, Any], fanout_config: Dict[str, Any], deploy: bool) -> None:
        """Step 5: Display setup summary and next steps"""
        print("📋 Universal Pipeline Setup Complete!")
        print("")
        print("🎯 Generated artifacts:")
        print(f"  📄 Protobuf analysis: {self.analysis_file.name}")
        print(f"  🔀 Fanout config: {self.fanout_config.name}")
        print("")

        # Extract configuration info
        recommendations = analysis.get("recommendations", {})
        strategy = recommendations.get("preferred_strategy", "unknown")
        topic_prefix = fanout_config.get("topic_prefix", "unknown")
        topics = fanout_config.get("topics", {})

        print("⚙️ Pipeline configuration:")
        print(f"  🎯 Backend: {self.backend}")
        print(f"  🎯 Strategy: {strategy}")
        print(f"  🏷️ Topic prefix: {topic_prefix}")
        print(f"  📦 Topics: {len(topics)}")
        print("")

        # Display next steps based on backend
        self._display_next_steps(deploy)

    def _display_next_steps(self, deploy: bool) -> None:
        """Display backend-specific deployment steps"""
        if deploy:
            print(f"📋 Ready to start {self.backend} sink! All infrastructure deployed:")
            print("")
        else:
            print(f"📋 Next steps to deploy {self.backend} stack:")
            print("")

        if self.backend == "kafka":
            if deploy:
                print("✅ Topics created automatically")
                print("Now you need to:")
                print("1. 🚀 Start Kafka sink (Redpanda service):")
                print("   docker compose -f docker-compose-kafka.yaml up -d substreams-kafka-sink")
            else:
                print("1. 🐳 Start Kafka infrastructure (Redpanda):")
                print("   docker compose -f docker-compose-kafka.yaml --profile kafka-basic up -d")
                print("")
                print("2. 🎯 Create Kafka topics:")
                print(f"   uv run scripts/kafka_topic_manager.py {self.fanout_config}")
                print("")
                print("3. 🚀 Start Kafka sink (Redpanda service):")
                print("   docker compose -f docker-compose-kafka.yaml up -d substreams-kafka-sink")

        elif self.backend == "nats":
            if deploy:
                print("✅ Streams created automatically")
                print("Now you need to:")
                print("1. 🚀 Start NATS sink:")
                print("   docker compose -f docker-compose-nats.yaml up -d substreams-nats-sink")
            else:
                print("1. 🐳 Start NATS infrastructure:")
                print("   docker compose -f docker-compose-nats.yaml --profile nats-basic up -d")
                print("")
                print("2. 🎯 Create NATS streams:")
                print(f"   uv run scripts/nats_stream_manager.py {self.fanout_config}")
                print("")
                print("3. 🚀 Start NATS sink:")
                print("   docker compose -f docker-compose-nats.yaml up -d substreams-nats-sink")

        print("")
        print("5. 📊 Monitor data flow:")
        print("   docker logs substreams-{}-sink --follow".format(self.backend))


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description="Universal Pipeline Setup - Python Edition! ",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 setup_universal_pipeline.py nats /path/to/schema.proto
  python3 setup_universal_pipeline.py kafka /path/to/schema.proto --output-dir ./configs
  python3 setup_universal_pipeline.py nats schema.proto --strategy oneof --deploy
  python3 setup_universal_pipeline.py nats contract.proto --primary-message Events
        """
    )

    parser.add_argument(
        "backend",
        choices=["kafka", "nats"],
        help="Backend type: kafka (Redpanda) or nats (JetStream)"
    )
    parser.add_argument(
        "proto_file",
        type=Path,
        help="Path to the protobuf (.proto) file to analyze"
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("./configs"),
        help="Output directory for generated files (default: ./configs)"
    )
    parser.add_argument(
        "--strategy",
        choices=["auto", "oneof", "message", "single"],
        default="auto",
        help="Fanout strategy (default: auto)"
    )
    parser.add_argument(
        "--primary-message",
        type=str,
        help="Override primary message type for analysis (e.g., 'Events', 'TroveOperation')"
    )
    parser.add_argument(
        "--deploy",
        action="store_true",
        help="Automatically deploy the pipeline after setup"
    )

    args = parser.parse_args()

    # Validate proto file exists
    if not args.proto_file.exists():
        print(f"❌ Error: Protobuf file not found: {args.proto_file}")
        sys.exit(1)

    try:
        orchestrator = UniversalPipelineOrchestrator(
            backend=args.backend,
            proto_file=args.proto_file,
            output_dir=args.output_dir
        )

        success = orchestrator.run(
            strategy=args.strategy,
            deploy=args.deploy,
            primary_message=args.primary_message
        )
        sys.exit(0 if success else 1)

    except Exception as e:
        print(f"❌ Pipeline setup failed: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
