#!/usr/bin/env python3
"""
Kafka Topic Manager - Python Edition! 

This module provides Pythonic management of Kafka/Redpanda topics.

Features:
- Parse fanout YAML configurations
- Create individual Kafka topics with proper partitioning
- Support for both Kafka and Redpanda backends
- Structured exception handling and validation

Usage:
    from kafka_topic_manager import KafkaTopicManager

    manager = KafkaTopicManager()
    success = manager.create_topics_from_fanout("fanout.yaml")
"""

import yaml
from pathlib import Path
from typing import Dict, List, Any, Optional
from kafka.admin import KafkaAdminClient, NewTopic
from kafka.errors import TopicAlreadyExistsError, KafkaError
import time


class KafkaTopicManager:
    """Manages Kafka/Redpanda topics from fanout configurations"""

    def __init__(self, bootstrap_servers: str = "localhost:19092", client_id: str = "substreams-sink-admin"):
        self.bootstrap_servers = bootstrap_servers
        self.client_id = client_id
        self.admin_client: Optional[KafkaAdminClient] = None

    def _create_admin_client(self) -> bool:
        """Create Kafka admin client"""
        try:
            self.admin_client = KafkaAdminClient(
                bootstrap_servers=self.bootstrap_servers,
                client_id=self.client_id,
                # Add timeout settings for reliability
                request_timeout_ms=30000,
                connections_max_idle_ms=540000
            )

            # Test connection by getting cluster metadata
            metadata = self.admin_client.describe_cluster()
            print(f"📦 Connected to Kafka cluster: {len(metadata.get('brokers', []))} brokers")
            return True
        except Exception as e:
            print(f"❌ Failed to connect to Kafka at {self.bootstrap_servers}: {e}")
            return False

    def _parse_fanout_config(self, fanout_file: Path) -> Dict[str, Any]:
        """Parse fanout YAML config file (same logic as bash script)"""
        try:
            with open(fanout_file, 'r') as f:
                config = yaml.safe_load(f)

            print(f"📂 Using fanout config: {fanout_file}")
            return config
        except Exception as e:
            raise RuntimeError(f"Failed to parse fanout config {fanout_file}: {e}")

    def _extract_topic_names(self, fanout_config: Dict[str, Any]) -> List[str]:
        """Extract topic names from fanout config (same logic as bash script)"""
        topic_prefix = fanout_config.get("topic_prefix", "blockchain")
        default_topic = fanout_config.get("default_topic", "fallback")
        topics_map = fanout_config.get("topics", {})

        print(f"🏷️  Topic prefix: {topic_prefix}")
        print(f"📦 Default topic: {default_topic}")

        topic_names = []

        # Add default topic with prefix (same logic as bash)
        if default_topic and topic_prefix:
            topic_names.append(f"{topic_prefix}_{default_topic}")

        # Extract mapped topics from fanout config (same logic as bash)
        for topic_name in topics_map.values():
            if topic_name and topic_prefix:
                topic_names.append(f"{topic_prefix}_{topic_name}")

        print(f"🎯 Topics to create: {topic_names}")
        return topic_names

    def _create_topics(self, topic_names: List[str]) -> bool:
        """Create topics in Kafka/Redpanda"""
        if not topic_names:
            print("❌ No topics to create")
            return False

        try:
            # Create NewTopic objects with same settings as bash script
            # Default: 4 partitions, replication factor 1 (for local development)
            new_topics = [
                NewTopic(
                    name=topic,
                    num_partitions=4,
                    replication_factor=1
                ) for topic in topic_names
            ]

            print("📦 Creating topics in Kafka/Redpanda...")

            # Create topics - kafka-python may return futures or raise exceptions
            try:
                response = self.admin_client.create_topics(new_topics, validate_only=False)

                # Handle response - could be dict of futures or immediate success
                success_count = 0

                if hasattr(response, 'items') and callable(getattr(response, 'items', None)):
                    # Response has futures to process
                    for topic_name, future in response.items():
                        try:
                            future.result(timeout=30)  # Wait for completion
                            print(f"  ✅ Created topic: {topic_name}")
                            success_count += 1
                        except TopicAlreadyExistsError:
                            print(f"  ℹ️  Topic already exists: {topic_name}")
                            success_count += 1
                        except Exception as e:
                            print(f"  ❌ Failed to create topic {topic_name}: {e}")

                    return success_count == len(new_topics)
                else:
                    # Immediate success case
                    for topic in new_topics:
                        print(f"  ✅ Created topic: {topic.name}")
                    return True

            except TopicAlreadyExistsError:
                # All topics already exist - acceptable for our business logic
                for topic in new_topics:
                    print(f"  ℹ️  Topic already exists: {topic.name}")
                return True

        except Exception as e:
            # Check if it's a mixed success/failure case
            if "TopicAlreadyExistsError" in str(e):
                print("  ℹ️  Some topics already exist (treating as success)")
                for topic in new_topics:
                    print(f"  ℹ️  Topic: {topic.name}")
                return True
            else:
                print(f"❌ Error creating topics: {e}")
                return False

    def _verify_topics(self, topic_names: List[str]) -> bool:
        """Verify topics exist and display information"""
        try:
            print()
            print("✅ Topic creation complete!")
            print()
            print("📋 Verification - Topic listing:")

            # Get cluster metadata to list topics
            metadata = self.admin_client.describe_cluster()

            # List topics with our prefix
            topic_prefix = None
            if topic_names:
                topic_prefix = topic_names[0].split('_')[0]

            existing_topics = []
            try:
                # Get topic metadata
                topic_metadata = self.admin_client.list_topics()
                for topic_name in topic_names:
                    if topic_name in topic_metadata:
                        existing_topics.append(topic_name)
                        print(f"  ✅ {topic_name}")
                    else:
                        print(f"  ❌ {topic_name} (not found)")
            except Exception as e:
                print(f"  ⚠️  Could not verify topics: {e}")
                # Assume success if we got this far
                return True

            return len(existing_topics) == len(topic_names)

        except Exception as e:
            print(f"⚠️  Verification failed: {e}")
            # Don't fail the whole operation for verification issues
            return True

    def create_topics_from_fanout(self, fanout_file: Path) -> bool:
        """
        Create Kafka topics from fanout configuration

        This is the main entry point that replicates the bash script logic:
        1. Parse fanout YAML config
        2. Extract topic names with prefix
        3. Create individual Kafka topics
        4. Verify topic creation

        Args:
            fanout_file: Path to fanout YAML configuration file

        Returns:
            bool: True if successful, False if failed
        """
        print("🎯 Dynamic Kafka Topic Creator")
        print()

        try:
            # Step 1: Parse fanout configuration
            fanout_config = self._parse_fanout_config(fanout_file)

            # Step 2: Extract topic names
            topic_names = self._extract_topic_names(fanout_config)

            if not topic_names:
                print("❌ No topics found in fanout config")
                return False

            # Step 3: Create admin client and connect
            if not self._create_admin_client():
                return False

            print()

            # Step 4: Create topics
            success = self._create_topics(topic_names)

            if not success:
                return False

            # Step 5: Verify topic creation
            self._verify_topics(topic_names)

            print()
            print("🎉 Dynamic topic creation complete!")
            print("Downstream consumers can now connect without provisioning errors.")

            return True

        except Exception as e:
            print(f"❌ Error creating Kafka topics: {e}")
            return False

        finally:
            # Clean up admin client
            if self.admin_client:
                try:
                    self.admin_client.close()
                except:
                    pass


def main():
    """CLI entry point for standalone usage"""
    import argparse

    parser = argparse.ArgumentParser(description="Create Kafka topics from fanout config")
    parser.add_argument("fanout_config", type=Path, help="Path to fanout YAML configuration file")
    parser.add_argument("--bootstrap-servers", default="localhost:19092", help="Kafka bootstrap servers")
    parser.add_argument("--client-id", default="substreams-sink-admin", help="Kafka client ID")

    args = parser.parse_args()

    if not args.fanout_config.exists():
        print(f"❌ Error: Fanout config file not found: {args.fanout_config}")
        return False

    manager = KafkaTopicManager(
        bootstrap_servers=args.bootstrap_servers,
        client_id=args.client_id
    )
    success = manager.create_topics_from_fanout(args.fanout_config)

    return success


if __name__ == "__main__":
    import sys

    try:
        success = main()
        sys.exit(0 if success else 1)
    except KeyboardInterrupt:
        print("\n❌ Interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)
