"""
Test configuration and fixtures for integration testing
"""
import pytest
import asyncio
import time
from pathlib import Path
import tempfile
import yaml
from typing import Dict, Any

# Container testing imports (conditional for unit tests)
try:
    from testcontainers.core.container import DockerContainer
    TESTCONTAINERS_AVAILABLE = True
except ImportError:
    TESTCONTAINERS_AVAILABLE = False

import nats
from kafka.admin import KafkaAdminClient


@pytest.fixture(scope="function")
def nats_container():
    """Check for running NATS instance for integration testing"""

    # List of common NATS ports to check
    nats_ports = [
        "localhost:4222",   # Standard NATS port
        "localhost:14222",  # Alternative NATS port
        "localhost:24222",  # Another alternative
    ]

    print(f"🔍 Checking for running NATS instance...")

    for nats_server in nats_ports:
        try:
            import socket
            host, port = nats_server.split(':')
            port = int(port)
            print(f"⏳ Trying {nats_server}...")

            with socket.create_connection((host, port), timeout=3):
                print(f"✅ Found running NATS at {nats_server}")
                nats_url = f"nats://{nats_server}"
                yield nats_url
                return
        except Exception as e:
            print(f"❌ No NATS at {nats_server}: {e}")
            continue

    # If we get here, no NATS instance was found
    pytest.skip(
        "No running NATS instance found. "
        "Start one with: docker compose -f docker-compose-test.yaml --profile test up -d"
    )


@pytest.fixture(scope="function")
def kafka_container():
    """Check for running Kafka/Redpanda instance for integration testing"""

    # List of common Kafka/Redpanda ports to check
    kafka_ports = [
        "localhost:19092",  # docker-compose Redpanda external port
        "localhost:9092",   # Standard Kafka port
        "localhost:29092",  # Alternative Kafka port
    ]

    print(f"🔍 Checking for running Kafka/Redpanda instance...")

    for bootstrap_servers in kafka_ports:
        try:
            import socket
            host, port = bootstrap_servers.split(':')
            port = int(port)
            print(f"⏳ Trying {bootstrap_servers}...")

            # First do a quick socket check
            with socket.create_connection((host, port), timeout=3):
                pass  # Connection successful

            # Then try Kafka admin client with very short timeouts
            client = KafkaAdminClient(
                bootstrap_servers=bootstrap_servers,
                client_id="test-health-check",
                request_timeout_ms=2000,
                api_version_auto_timeout_ms=3000,
                connections_max_idle_ms=5000
            )
            cluster_info = client.describe_cluster()
            client.close()
            print(f"✅ Found running Kafka/Redpanda at {bootstrap_servers}")
            print(f"📋 Cluster info: {cluster_info}")
            yield bootstrap_servers
            return
        except Exception as e:
            print(f"❌ No Kafka at {bootstrap_servers}: {e}")
            continue

    # If we get here, no Kafka instance was found
    pytest.skip(
        "No running Kafka/Redpanda instance found. "
        "Start one with: docker compose -f docker-compose-test.yaml --profile test up -d"
    )


# Common test fixtures
@pytest.fixture
def sample_fanout_config() -> Dict[str, Any]:
    """Standard fanout configuration for testing"""
    return {
        "topic_prefix": "test_integration",
        "default_topic": "fallback",
        "topics": {
            "transfer": "transfers",
            "mint": "mints",
            "burn": "burns"
        }
    }


@pytest.fixture
def temp_fanout_file(sample_fanout_config):
    """Create temporary fanout config file"""
    with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
        yaml.dump(sample_fanout_config, f, default_flow_style=False)
        temp_file = Path(f.name)

    yield temp_file

    # Cleanup
    if temp_file.exists():
        temp_file.unlink()


@pytest.fixture
def minimal_fanout_config():
    """Minimal config with just defaults for edge case testing"""
    return {"topic_prefix": "minimal_test"}


@pytest.fixture
def temp_minimal_file(minimal_fanout_config):
    """Create temporary minimal config file"""
    with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
        yaml.dump(minimal_fanout_config, f, default_flow_style=False)
        temp_file = Path(f.name)

    yield temp_file

    if temp_file.exists():
        temp_file.unlink()