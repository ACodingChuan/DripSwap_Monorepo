#!/usr/bin/env python3
"""
Integration Tests for NATS Stream Manager

These tests use REAL NATS containers to validate that our code actually works
with real JetStream instances. No mocking of core functionality.
"""

import pytest
import asyncio
from pathlib import Path

import sys
sys.path.append(str(Path(__file__).parent.parent / "scripts"))

from scripts.nats_stream_manager import NATSStreamManager


@pytest.mark.integration
@pytest.mark.requires_docker
class TestNATSStreamManagerIntegration:
    """Integration tests with real NATS JetStream"""

    @pytest.mark.asyncio
    async def test_complete_stream_creation_flow(self, nats_container, temp_fanout_file):
        """Test the complete stream creation flow with real NATS"""

        # Use real NATS instance
        manager = NATSStreamManager(server_url=nats_container)

        # Test the complete integration flow
        success = await manager.create_streams_from_fanout(temp_fanout_file)

        # Verify it actually worked
        assert success is True

        # Verify the stream was created in NATS
        await manager.connect()

        try:
            stream_info = await manager.js.stream_info("substreams")

            # Verify stream configuration matches our business logic
            assert stream_info.config.name == "substreams"
            assert "test_integration.>" in stream_info.config.subjects
            assert stream_info.config.storage == "file"
            assert stream_info.config.retention == "limits"

        finally:
            await manager.disconnect()

    @pytest.mark.asyncio
    async def test_subject_routing_with_real_messages(self, nats_container, temp_fanout_file):
        """Test that subjects actually route messages to the stream"""

        manager = NATSStreamManager(server_url=nats_container)

        # Create the stream
        success = await manager.create_streams_from_fanout(temp_fanout_file)
        assert success is True

        # Test publishing real messages to different subjects
        await manager.connect()

        try:
            # Publish test messages to the subjects our config should create
            await manager.nc.publish("test_integration.transfers", b"transfer message")
            await manager.nc.publish("test_integration.mints", b"mint message")
            await manager.nc.publish("test_integration.burns", b"burn message")
            await manager.nc.publish("test_integration.fallback", b"fallback message")

            # Give NATS a moment to process
            await asyncio.sleep(0.1)

            # Verify messages were stored in the stream
            stream_info = await manager.js.stream_info("substreams")
            assert stream_info.state.messages >= 4

        finally:
            await manager.disconnect()

    @pytest.mark.asyncio
    async def test_stream_already_exists_real_scenario(self, nats_container, temp_fanout_file):
        """Test real 'stream already exists' scenario"""

        manager1 = NATSStreamManager(server_url=nats_container)
        manager2 = NATSStreamManager(server_url=nats_container)

        # Create stream with first manager
        success1 = await manager1.create_streams_from_fanout(temp_fanout_file)
        assert success1 is True

        # Try to create same stream with second manager - should succeed
        success2 = await manager2.create_streams_from_fanout(temp_fanout_file)
        assert success2 is True  # Our business logic treats existing as success

    @pytest.mark.asyncio
    async def test_minimal_config_creates_default_stream(self, nats_container, temp_minimal_file):
        """Test that minimal config creates stream with defaults"""

        # Use unique stream name to avoid conflicts with other tests
        manager = NATSStreamManager(server_url=nats_container, stream_name="minimal_test_stream")

        # Test with minimal config (only topic_prefix)
        success = await manager.create_streams_from_fanout(temp_minimal_file)
        assert success is True

        # Verify stream was created with default subject
        await manager.connect()

        try:
            stream_info = await manager.js.stream_info("minimal_test_stream")
            assert "minimal_test.>" in stream_info.config.subjects

            # Test that default subject works
            await manager.nc.publish("minimal_test.fallback", b"default test")
            await asyncio.sleep(0.1)

            updated_info = await manager.js.stream_info("minimal_test_stream")
            assert updated_info.state.messages >= 1

        finally:
            await manager.disconnect()

    @pytest.mark.asyncio
    async def test_connection_and_disconnection_lifecycle(self, nats_container):
        """Test real connection lifecycle management"""

        manager = NATSStreamManager(server_url=nats_container)

        # Test initial state
        assert manager.nc is None
        assert manager.js is None

        # Test connection
        success = await manager.connect()
        assert success is True
        assert manager.nc is not None
        assert manager.js is not None

        # Test that connection actually works
        assert manager.nc.is_connected

        # Test disconnection
        await manager.disconnect()
        assert manager.nc is None
        assert manager.js is None

    @pytest.mark.asyncio
    async def test_stream_verification_with_real_data(self, nats_container, temp_fanout_file):
        """Test stream verification shows real stream details"""

        manager = NATSStreamManager(server_url=nats_container)

        # Create stream
        await manager.create_streams_from_fanout(temp_fanout_file)

        # Connect and verify
        await manager.connect()

        try:
            # Test our verification logic
            success = await manager._verify_stream("substreams")
            assert success is True

            # Test verification of non-existent stream
            success = await manager._verify_stream("nonexistent")
            assert success is False

        finally:
            await manager.disconnect()

    @pytest.mark.asyncio
    async def test_error_handling_with_invalid_server(self):
        """Test error handling with unreachable server"""

        # Use invalid server URL
        manager = NATSStreamManager(server_url="nats://invalid-server:4222")

        # Connection should fail gracefully
        success = await manager.connect()
        assert success is False
        assert manager.nc is None
        assert manager.js is None

        # Disconnect should not error even when not connected
        await manager.disconnect()

    @pytest.mark.asyncio
    async def test_subject_pattern_matching(self, nats_container, temp_fanout_file):
        """Test that wildcard subject patterns work correctly"""

        manager = NATSStreamManager(server_url=nats_container)

        # Create stream with wildcard pattern
        await manager.create_streams_from_fanout(temp_fanout_file)
        await manager.connect()

        try:
            # Test various subject patterns that should match
            test_subjects = [
                "test_integration.transfers",
                "test_integration.mints",
                "test_integration.burns",
                "test_integration.fallback",
                "test_integration.custom.nested.subject"  # Should also match pattern
            ]

            for subject in test_subjects:
                await manager.nc.publish(subject, f"test message for {subject}".encode())

            await asyncio.sleep(0.1)

            # All messages should be captured by the stream
            stream_info = await manager.js.stream_info("substreams")
            assert stream_info.state.messages >= len(test_subjects)

        finally:
            await manager.disconnect()