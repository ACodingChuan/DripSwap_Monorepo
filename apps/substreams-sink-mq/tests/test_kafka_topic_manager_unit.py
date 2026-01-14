#!/usr/bin/env python3
"""
Unit Tests for Kafka Topic Manager

These are FAST tests that test business logic without external dependencies.
Only mock external network calls, test everything else for real.
"""

import pytest
import tempfile
import yaml
from pathlib import Path
from typing import Dict, Any
from unittest.mock import Mock, patch
from concurrent.futures import Future

import sys
sys.path.append(str(Path(__file__).parent.parent / "scripts"))

from scripts.kafka_topic_manager import KafkaTopicManager


@pytest.mark.unit
class TestKafkaTopicManagerUnit:
    """Unit tests for Kafka topic manager business logic"""

    def test_manager_initialization(self):
        """Test manager initializes with correct default values"""
        manager = KafkaTopicManager()

        assert manager.bootstrap_servers == "localhost:19092"
        assert manager.client_id == "substreams-sink-admin"
        assert manager.admin_client is None

    def test_manager_initialization_custom_config(self):
        """Test manager initializes with custom configuration"""
        custom_servers = "custom-server:9092"
        custom_client_id = "test-client"

        manager = KafkaTopicManager(
            bootstrap_servers=custom_servers,
            client_id=custom_client_id
        )

        assert manager.bootstrap_servers == custom_servers
        assert manager.client_id == custom_client_id

    def test_parse_fanout_config_success(self, temp_fanout_file, sample_fanout_config):
        """Test successful fanout config parsing"""
        manager = KafkaTopicManager()

        config = manager._parse_fanout_config(temp_fanout_file)

        assert config == sample_fanout_config
        assert config["topic_prefix"] == "test_integration"
        assert config["default_topic"] == "fallback"
        assert len(config["topics"]) == 3

    def test_parse_fanout_config_file_not_found(self):
        """Test fanout config parsing with nonexistent file"""
        manager = KafkaTopicManager()
        nonexistent_file = Path("/nonexistent/config.yaml")

        with pytest.raises(RuntimeError, match="Failed to parse fanout config"):
            manager._parse_fanout_config(nonexistent_file)

    def test_extract_topic_names_logic(self, sample_fanout_config):
        """Test topic name extraction preserves underscore format"""
        manager = KafkaTopicManager()

        topic_names = manager._extract_topic_names(sample_fanout_config)

        # Kafka should preserve underscore format (no conversion)
        expected_topics = [
            "test_integration_fallback",  # default_topic
            "test_integration_transfers", # transfer -> transfers
            "test_integration_mints",     # mint -> mints
            "test_integration_burns"      # burn -> burns
        ]

        assert len(topic_names) == 4
        for expected in expected_topics:
            assert expected in topic_names

        # Verify NO conversion to dots happened
        for topic in topic_names:
            assert "." not in topic  # Kafka keeps underscores

    def test_extract_topic_names_with_defaults(self):
        """Test topic extraction uses proper defaults"""
        config = {}

        manager = KafkaTopicManager()
        topic_names = manager._extract_topic_names(config)

        # Should use defaults: topic_prefix="blockchain", default_topic="fallback"
        assert topic_names == ["blockchain_fallback"]

    def test_extract_topic_names_minimal_config(self):
        """Test topic extraction with minimal config"""
        config = {
            "topic_prefix": "test",
            "topics": {"transfer": "transfers"}
        }

        manager = KafkaTopicManager()
        topic_names = manager._extract_topic_names(config)

        # Should include both default and configured topics
        expected = ["test_fallback", "test_transfers"]
        assert set(topic_names) == set(expected)

    def test_create_topics_empty_list_handling(self):
        """Test topic creation with empty topic list"""
        manager = KafkaTopicManager()
        manager.admin_client = Mock()

        success = manager._create_topics([])

        # Should return False for empty list
        assert success is False

    def test_topic_creation_configuration_logic(self):
        """Test that NewTopic objects are configured correctly"""
        with patch('scripts.kafka_topic_manager.KafkaAdminClient'):
            manager = KafkaTopicManager()
            mock_admin = Mock()
            manager.admin_client = mock_admin

            # Setup successful futures
            future1 = Future()
            future1.set_result(None)
            future2 = Future()
            future2.set_result(None)

            mock_admin.create_topics.return_value = {
                "test_topic1": future1,
                "test_topic2": future2
            }

            topic_names = ["test_topic1", "test_topic2"]
            success = manager._create_topics(topic_names)

            assert success is True
            mock_admin.create_topics.assert_called_once()

            # Test our NewTopic configuration logic
            new_topics = mock_admin.create_topics.call_args[0][0]
            assert len(new_topics) == 2
            assert new_topics[0].name == "test_topic1"
            assert new_topics[0].num_partitions == 4
            assert new_topics[0].replication_factor == 1

    def test_topic_already_exists_handling_logic(self):
        """Test business logic for handling existing topics"""
        from kafka.errors import TopicAlreadyExistsError

        with patch('scripts.kafka_topic_manager.KafkaAdminClient'):
            manager = KafkaTopicManager()
            mock_admin = Mock()
            manager.admin_client = mock_admin

            # Setup future that raises TopicAlreadyExistsError
            future = Future()
            future.set_exception(TopicAlreadyExistsError("Topic already exists"))

            mock_admin.create_topics.return_value = {"existing_topic": future}

            success = manager._create_topics(["existing_topic"])

            # Test our business rule: existing topics should be treated as success
            assert success is True

    def test_topic_creation_partial_failure_logic(self):
        """Test logic for handling partial failures"""
        with patch('scripts.kafka_topic_manager.KafkaAdminClient'):
            manager = KafkaTopicManager()
            mock_admin = Mock()
            manager.admin_client = mock_admin

            # Setup mixed success/failure futures
            success_future = Future()
            success_future.set_result(None)

            failure_future = Future()
            failure_future.set_exception(Exception("Creation failed"))

            mock_admin.create_topics.return_value = {
                "success_topic": success_future,
                "failure_topic": failure_future
            }

            success = manager._create_topics(["success_topic", "failure_topic"])

            # Test that partial failures result in overall failure
            assert success is False

    def test_verify_topics_logic(self):
        """Test topic verification business logic"""
        manager = KafkaTopicManager()
        mock_admin = Mock()
        mock_admin.describe_cluster.return_value = {"brokers": []}
        mock_admin.list_topics.return_value = ["test_topic1", "test_topic2", "other_topic"]
        manager.admin_client = mock_admin

        # Test successful verification
        success = manager._verify_topics(["test_topic1", "test_topic2"])
        assert success is True

        # Test partial missing topics
        success = manager._verify_topics(["test_topic1", "missing_topic"])
        assert success is False

    def test_verify_topics_error_handling(self):
        """Test verification error handling logic"""
        manager = KafkaTopicManager()
        mock_admin = Mock()
        mock_admin.describe_cluster.return_value = {"brokers": []}
        mock_admin.list_topics.side_effect = Exception("API Error")
        manager.admin_client = mock_admin

        # Test that API errors are handled gracefully
        success = manager._verify_topics(["test_topic"])

        # Should return True (assumes success when verification fails)
        assert success is True

    def test_admin_client_configuration_parameters(self):
        """Test that admin client configuration parameters are correct"""
        with patch('scripts.kafka_topic_manager.KafkaAdminClient') as mock_admin_class:
            mock_admin = Mock()
            mock_admin.describe_cluster.return_value = {"brokers": [{"id": 1}]}
            mock_admin_class.return_value = mock_admin

            manager = KafkaTopicManager(
                bootstrap_servers="test:9092",
                client_id="test-client"
            )
            success = manager._create_admin_client()

            assert success is True

            # Test our configuration logic
            mock_admin_class.assert_called_once_with(
                bootstrap_servers="test:9092",
                client_id="test-client",
                request_timeout_ms=30000,
                connections_max_idle_ms=540000
            )

    def test_topic_naming_edge_cases(self):
        """Test edge cases in topic naming logic"""
        test_configs = [
            {
                "config": {"topic_prefix": "test", "topics": {"multi_word": "multi_word_topic"}},
                "expected": ["test_fallback", "test_multi_word_topic"]
            },
            {
                "config": {"topic_prefix": "simple", "topics": {"single": "single"}},
                "expected": ["simple_fallback", "simple_single"]
            },
            {
                "config": {"topic_prefix": "complex", "default_topic": "custom", "topics": {"a": "b"}},
                "expected": ["complex_custom", "complex_b"]
            }
        ]

        manager = KafkaTopicManager()

        for test_case in test_configs:
            topics = manager._extract_topic_names(test_case["config"])
            for expected in test_case["expected"]:
                assert expected in topics

    def test_cleanup_logic_on_exception(self):
        """Test that admin client cleanup works correctly"""
        mock_admin = Mock()

        with patch.object(KafkaTopicManager, '_create_admin_client', return_value=True):
            with patch.object(KafkaTopicManager, '_create_topics', side_effect=Exception("Test error")):
                manager = KafkaTopicManager()
                manager.admin_client = mock_admin

                # Should handle exception and cleanup
                success = manager.create_topics_from_fanout(temp_fanout_file := Path("/fake/file"))

                assert success is False
                # Admin client should be closed
                mock_admin.close.assert_called_once()