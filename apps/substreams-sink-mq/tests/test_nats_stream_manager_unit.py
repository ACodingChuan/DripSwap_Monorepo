#!/usr/bin/env python3
"""
Unit Tests for NATS Stream Manager

These are FAST tests that test business logic without external dependencies.
Only mock external network calls, test everything else for real.
"""

import pytest
import tempfile
import yaml
from pathlib import Path
from typing import Dict, Any
from unittest.mock import Mock, patch, AsyncMock

import sys
sys.path.append(str(Path(__file__).parent.parent / "scripts"))

from scripts.nats_stream_manager import NATSStreamManager


@pytest.mark.unit
class TestNATSStreamManagerUnit:
    """Unit tests for NATS stream manager business logic"""

    def test_manager_initialization(self):
        """Test manager initializes with correct default values"""
        manager = NATSStreamManager()

        assert manager.server_url == "nats://localhost:4222"
        assert manager.nc is None
        assert manager.js is None

    def test_manager_initialization_custom_url(self):
        """Test manager initializes with custom server URL"""
        custom_url = "nats://custom-server:4222"
        manager = NATSStreamManager(server_url=custom_url)

        assert manager.server_url == custom_url

    def test_parse_fanout_config_success(self, temp_fanout_file, sample_fanout_config):
        """Test successful fanout config parsing"""
        manager = NATSStreamManager()

        config = manager._parse_fanout_config(temp_fanout_file)

        assert config == sample_fanout_config
        assert config["topic_prefix"] == "test_integration"
        assert config["default_topic"] == "fallback"
        assert len(config["topics"]) == 3

    def test_parse_fanout_config_file_not_found(self):
        """Test fanout config parsing with nonexistent file"""
        manager = NATSStreamManager()
        nonexistent_file = Path("/nonexistent/config.yaml")

        with pytest.raises(RuntimeError, match="Failed to parse fanout config"):
            manager._parse_fanout_config(nonexistent_file)

    def test_parse_fanout_config_invalid_yaml(self):
        """Test fanout config parsing with invalid YAML"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
            f.write("invalid: yaml: content: [unclosed")
            temp_file = Path(f.name)

        try:
            manager = NATSStreamManager()
            with pytest.raises(RuntimeError, match="Failed to parse fanout config"):
                manager._parse_fanout_config(temp_file)
        finally:
            if temp_file.exists():
                temp_file.unlink()

    def test_extract_subject_names_conversion_logic(self, sample_fanout_config):
        """Test subject name extraction and underscore-to-dot conversion"""
        manager = NATSStreamManager()

        subject_names = manager._extract_subject_names(sample_fanout_config)

        # Test conversion from underscore to dot notation (entire string converted)
        expected_subjects = [
            "test.integration.fallback",  # test_integration_fallback -> test.integration.fallback
            "test.integration.transfers", # test_integration_transfers -> test.integration.transfers
            "test.integration.mints",     # test_integration_mints -> test.integration.mints
            "test.integration.burns"      # test_integration_burns -> test.integration.burns
        ]

        assert len(subject_names) == 4
        for expected in expected_subjects:
            assert expected in subject_names

        # Verify conversion logic worked
        assert "test.integration.fallback" in subject_names
        assert "test_integration_fallback" not in subject_names

    def test_extract_subject_names_with_defaults(self):
        """Test subject extraction uses proper defaults"""
        config = {}

        manager = NATSStreamManager()
        subject_names = manager._extract_subject_names(config)

        # Should use defaults: topic_prefix="blockchain", default_topic="fallback"
        assert "blockchain.fallback" in subject_names

    def test_extract_subject_names_complex_conversion(self):
        """Test complex underscore to dot conversion"""
        config = {
            "topic_prefix": "test",
            "topics": {
                "multi_word_field": "multi_word_topic",
                "initialize_mint": "initialize_mints"
            }
        }

        manager = NATSStreamManager()
        subject_names = manager._extract_subject_names(config)

        # Test conversion of complex names
        expected = [
            "test.fallback",          # default
            "test.multi.word.topic",  # multi_word_topic -> multi.word.topic
            "test.initialize.mints"   # initialize_mints -> initialize.mints
        ]

        for expected_subject in expected:
            assert expected_subject in subject_names

    @pytest.mark.asyncio
    async def test_connect_with_proper_mocking(self):
        """Test connection logic with minimal external mocking"""
        with patch('scripts.nats_stream_manager.nats.connect', new_callable=AsyncMock) as mock_connect:
            mock_nc = AsyncMock()
            mock_js = Mock()
            mock_nc.jetstream = Mock(return_value=mock_js)  # Make jetstream a regular Mock
            mock_connect.return_value = mock_nc

            manager = NATSStreamManager(server_url="nats://test:4222")
            success = await manager.connect()

            # Test our business logic
            assert success is True
            assert manager.nc == mock_nc
            assert manager.js == mock_js

            # Test that we called external library correctly with timeout parameters
            mock_connect.assert_called_once_with(
                "nats://test:4222",
                connect_timeout=10,
                reconnect_time_wait=2,
                max_reconnect_attempts=3
            )

    @pytest.mark.asyncio
    async def test_connect_error_handling_logic(self):
        """Test that our error handling logic works correctly"""
        with patch('scripts.nats_stream_manager.nats.connect', new_callable=AsyncMock) as mock_connect:
            mock_connect.side_effect = Exception("Connection refused")

            manager = NATSStreamManager()
            success = await manager.connect()

            # Test our error handling
            assert success is False
            assert manager.nc is None
            assert manager.js is None

    @pytest.mark.asyncio
    async def test_disconnect_logic(self):
        """Test disconnection logic"""
        manager = NATSStreamManager()
        mock_nc = AsyncMock()
        manager.nc = mock_nc

        await manager.disconnect()

        # Test our cleanup logic
        mock_nc.close.assert_called_once()
        assert manager.nc is None
        assert manager.js is None

    @pytest.mark.asyncio
    async def test_disconnect_when_not_connected(self):
        """Test disconnection when not connected doesn't error"""
        manager = NATSStreamManager()

        # Should not raise exception
        await manager.disconnect()

        assert manager.nc is None

    @pytest.mark.asyncio
    async def test_create_jetstream_stream_configuration(self):
        """Test that stream configuration is built correctly"""
        manager = NATSStreamManager()
        mock_js = AsyncMock()
        manager.js = mock_js

        success = await manager._create_jetstream_stream("test_stream", "test.>")

        assert success is True
        mock_js.add_stream.assert_called_once()

        # Test our stream configuration logic
        stream_config = mock_js.add_stream.call_args[0][0]
        assert stream_config.name == "test_stream"
        assert stream_config.subjects == ["test.>"]
        assert stream_config.storage.name == "FILE"
        assert stream_config.retention.name == "LIMITS"

    @pytest.mark.asyncio
    async def test_stream_already_exists_handling_logic(self):
        """Test our business logic for handling existing streams"""
        manager = NATSStreamManager()
        mock_js = AsyncMock()
        mock_js.add_stream.side_effect = Exception("stream name already in use")
        manager.js = mock_js

        success = await manager._create_jetstream_stream("existing_stream", "test.>")

        # Test our business rule: existing streams should be treated as success
        assert success is True

    @pytest.mark.asyncio
    async def test_stream_creation_error_handling(self):
        """Test error handling for actual stream creation failures"""
        manager = NATSStreamManager()
        mock_js = AsyncMock()
        mock_js.add_stream.side_effect = Exception("Unknown stream error")
        manager.js = mock_js

        success = await manager._create_jetstream_stream("test_stream", "test.>")

        # Test that real errors are handled correctly
        assert success is False

    def test_subject_name_generation_edge_cases(self):
        """Test edge cases in subject name generation"""
        test_cases = [
            {
                "config": {"topic_prefix": "test", "topics": {"a_b_c": "x_y_z"}},
                "expected": ["test.fallback", "test.x.y.z"]
            },
            {
                "config": {"topic_prefix": "simple", "topics": {"single": "single"}},
                "expected": ["simple.fallback", "simple.single"]
            },
            {
                "config": {"topic_prefix": "complex", "default_topic": "custom", "topics": {"a": "b"}},
                "expected": ["complex.custom", "complex.b"]
            }
        ]

        manager = NATSStreamManager()

        for test_case in test_cases:
            subjects = manager._extract_subject_names(test_case["config"])
            for expected in test_case["expected"]:
                assert expected in subjects