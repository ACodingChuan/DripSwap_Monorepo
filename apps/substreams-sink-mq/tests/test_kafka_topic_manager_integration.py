#!/usr/bin/env python3
"""
Integration Tests for Kafka Topic Manager

These tests use REAL Redpanda containers to validate that our code actually works
with real Kafka instances. No mocking of core functionality.
"""

import pytest
from pathlib import Path
from kafka.admin import KafkaAdminClient
from kafka.producer import KafkaProducer
from kafka.consumer import KafkaConsumer
import time

import sys
sys.path.append(str(Path(__file__).parent.parent / "scripts"))

from scripts.kafka_topic_manager import KafkaTopicManager


@pytest.mark.integration
@pytest.mark.requires_docker
class TestKafkaTopicManagerIntegration:
    """Integration tests with real Redpanda/Kafka"""

    def test_complete_topic_creation_flow(self, kafka_container, temp_fanout_file):
        """Test the complete topic creation flow with real Kafka"""

        # Use real Redpanda instance
        manager = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Test the complete integration flow
        success = manager.create_topics_from_fanout(temp_fanout_file)

        # Verify it actually worked
        assert success is True

        # Verify topics were created in Kafka
        admin_client = KafkaAdminClient(
            bootstrap_servers=kafka_container,
            client_id="test-verification"
        )

        try:
            topic_metadata = admin_client.list_topics()

            # Verify expected topics exist
            expected_topics = [
                "test_integration_fallback",
                "test_integration_transfers",
                "test_integration_mints",
                "test_integration_burns"
            ]

            for topic in expected_topics:
                assert topic in topic_metadata

        finally:
            admin_client.close()

    def test_topic_configuration_verification(self, kafka_container, temp_fanout_file):
        """Test that topics are created with correct configuration"""

        manager = KafkaTopicManager(bootstrap_servers=kafka_container)
        success = manager.create_topics_from_fanout(temp_fanout_file)
        assert success is True

        # Verify topic configurations
        admin_client = KafkaAdminClient(bootstrap_servers=kafka_container)

        try:
            # Get topic metadata to check partition configuration
            topic_metadata = admin_client.list_topics()

            # Check that our topics exist and have expected properties
            test_topic = "test_integration_transfers"
            assert test_topic in topic_metadata

            # Get topic descriptions
            topic_descriptions = admin_client.describe_topics([test_topic])

            # topic_descriptions can be dict or list depending on kafka-python version
            if isinstance(topic_descriptions, dict):
                topic_desc = topic_descriptions[test_topic]
                assert len(topic_desc.partitions) == 4
            else:
                # List format
                for topic_desc in topic_descriptions:
                    if hasattr(topic_desc, 'topic') and topic_desc.topic == test_topic:
                        assert len(topic_desc.partitions) == 4

        finally:
            admin_client.close()

    def test_topic_already_exists_real_scenario(self, kafka_container, temp_fanout_file):
        """Test real 'topic already exists' scenario"""

        manager1 = KafkaTopicManager(bootstrap_servers=kafka_container)
        manager2 = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Create topics with first manager
        success1 = manager1.create_topics_from_fanout(temp_fanout_file)
        assert success1 is True

        # Try to create same topics with second manager - should succeed
        success2 = manager2.create_topics_from_fanout(temp_fanout_file)
        assert success2 is True  # Our business logic treats existing as success

    def test_real_message_production_and_consumption(self, kafka_container, temp_fanout_file):
        """Test that created topics can actually handle messages"""

        manager = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Create topics
        success = manager.create_topics_from_fanout(temp_fanout_file)
        assert success is True

        # Test producing messages to the topics
        producer = KafkaProducer(
            bootstrap_servers=kafka_container,
            value_serializer=lambda v: v.encode('utf-8')
        )

        test_topics = [
            "test_integration_transfers",
            "test_integration_mints",
            "test_integration_fallback"
        ]

        try:
            # Send test messages
            for topic in test_topics:
                producer.send(topic, f"test message for {topic}")

            # Ensure messages are sent
            producer.flush()

            # Verify messages can be consumed
            consumer = KafkaConsumer(
                *test_topics,
                bootstrap_servers=kafka_container,
                auto_offset_reset='earliest',
                enable_auto_commit=True,
                consumer_timeout_ms=5000  # 5 second timeout
            )

            try:
                messages_received = 0
                for message in consumer:
                    messages_received += 1
                    assert message.topic in test_topics
                    assert b"test message" in message.value

                    if messages_received >= len(test_topics):
                        break

                assert messages_received >= len(test_topics)

            finally:
                consumer.close()

        finally:
            producer.close()

    def test_minimal_config_creates_default_topic(self, kafka_container, temp_minimal_file):
        """Test that minimal config creates topic with defaults"""

        manager = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Test with minimal config (only topic_prefix)
        success = manager.create_topics_from_fanout(temp_minimal_file)
        assert success is True

        # Verify default topic was created
        admin_client = KafkaAdminClient(bootstrap_servers=kafka_container)

        try:
            topic_metadata = admin_client.list_topics()
            assert "minimal_test_fallback" in topic_metadata

        finally:
            admin_client.close()

    def test_admin_client_connection_and_operations(self, kafka_container):
        """Test real admin client connection and basic operations"""

        manager = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Test connection
        success = manager._create_admin_client()
        assert success is True
        assert manager.admin_client is not None

        try:
            # Test that connection actually works
            cluster_info = manager.admin_client.describe_cluster()
            assert "brokers" in cluster_info
            assert len(cluster_info["brokers"]) > 0

            # Test listing topics
            topics = manager.admin_client.list_topics()
            assert isinstance(topics, list)

        finally:
            if manager.admin_client:
                manager.admin_client.close()

    def test_error_handling_with_invalid_server(self):
        """Test error handling with unreachable server"""

        # Use invalid server URL
        manager = KafkaTopicManager(bootstrap_servers="invalid-server:9092")

        # Connection should fail gracefully
        success = manager._create_admin_client()
        assert success is False
        assert manager.admin_client is None

    def test_topic_verification_with_real_data(self, kafka_container, temp_fanout_file):
        """Test topic verification with real Kafka"""

        manager = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Create topics
        success = manager.create_topics_from_fanout(temp_fanout_file)
        assert success is True

        # Test verification with real admin client
        success = manager._create_admin_client()
        assert success is True

        try:
            # Test verification of existing topics
            existing_topics = [
                "test_integration_transfers",
                "test_integration_mints"
            ]
            success = manager._verify_topics(existing_topics)
            assert success is True

            # Test verification with missing topic
            mixed_topics = [
                "test_integration_transfers",  # exists
                "nonexistent_topic"           # doesn't exist
            ]
            success = manager._verify_topics(mixed_topics)
            assert success is False

        finally:
            if manager.admin_client:
                manager.admin_client.close()

    def test_multiple_topic_batches(self, kafka_container):
        """Test creating multiple batches of topics"""

        manager = KafkaTopicManager(bootstrap_servers=kafka_container)

        # Test multiple topic creation operations
        topic_batches = [
            ["batch1_topic1", "batch1_topic2"],
            ["batch2_topic1", "batch2_topic2", "batch2_topic3"]
        ]

        success = manager._create_admin_client()
        assert success is True

        try:
            for batch in topic_batches:
                success = manager._create_topics(batch)
                assert success is True

            # Verify all topics exist
            all_topics = [topic for batch in topic_batches for topic in batch]
            topic_metadata = manager.admin_client.list_topics()

            for topic in all_topics:
                assert topic in topic_metadata

        finally:
            if manager.admin_client:
                manager.admin_client.close()

    def test_custom_configuration_parameters(self, kafka_container):
        """Test that custom configuration parameters work"""

        # Test with custom client configuration
        manager = KafkaTopicManager(
            bootstrap_servers=kafka_container,
            client_id="custom-test-client"
        )

        success = manager._create_admin_client()
        assert success is True

        try:
            # Verify connection works with custom config
            cluster_info = manager.admin_client.describe_cluster()
            assert "brokers" in cluster_info

        finally:
            if manager.admin_client:
                manager.admin_client.close()