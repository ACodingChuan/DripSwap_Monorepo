#!/usr/bin/env python3
"""
NATS JetStream Manager - Python Edition! 

This module provides Pythonic management of NATS JetStream streams.

Features:
- Parse fanout YAML configurations
- Convert topic names to NATS dot notation (spl_transfers → spl.transfers)
- Create JetStream streams with pattern matching (spl.>)
- Async/await support for NATS operations

Usage:
    from nats_stream_manager import NATSStreamManager

    manager = NATSStreamManager()
    success = await manager.create_streams_from_fanout("fanout.yaml")
"""

import asyncio
import yaml
from pathlib import Path
from typing import Dict, List, Any, Optional
import nats
from nats.js import JetStreamContext
from nats.js.api import StreamConfig, RetentionPolicy, DiscardPolicy, StorageType


class NATSStreamManager:
    """Manages NATS JetStream streams from fanout configurations"""

    def __init__(self, server_url: str = "nats://localhost:4222", stream_name: str = "substreams"):
        self.server_url = server_url
        self.stream_name = stream_name
        self.nc: Optional[nats.NATS] = None
        self.js: Optional[JetStreamContext] = None

    async def connect(self) -> bool:
        """Connect to NATS server"""
        try:
            # Add reasonable timeout to prevent hanging on invalid servers
            self.nc = await nats.connect(
                self.server_url,
                connect_timeout=10,  # 10 second connection timeout
                reconnect_time_wait=2,  # 2 seconds between reconnect attempts
                max_reconnect_attempts=3  # Maximum 3 reconnect attempts
            )
            self.js = self.nc.jetstream()
            return True
        except Exception as e:
            print(f"❌ Failed to connect to NATS server {self.server_url}: {e}")
            return False

    async def disconnect(self) -> None:
        """Disconnect from NATS server"""
        if self.nc:
            await self.nc.close()
            self.nc = None
            self.js = None

    def _parse_fanout_config(self, fanout_file: Path) -> Dict[str, Any]:
        """Parse fanout YAML config file (same logic as bash script)"""
        try:
            with open(fanout_file, 'r') as f:
                config = yaml.safe_load(f)

            print(f"📂 Using fanout config: {fanout_file}")
            return config
        except Exception as e:
            raise RuntimeError(f"Failed to parse fanout config {fanout_file}: {e}")

    def _extract_subject_names(self, fanout_config: Dict[str, Any]) -> List[str]:
        """Extract and convert topic names to NATS subjects (same logic as bash)"""
        topic_prefix = fanout_config.get("topic_prefix", "blockchain")
        default_topic = fanout_config.get("default_topic", "fallback")
        topics_map = fanout_config.get("topics", {})

        print(f"🏷️  Subject prefix (from fanout config): {topic_prefix}")
        print(f"🎯 Default topic: {default_topic}")

        subject_names = []

        # Add default topic subject (convert to NATS dot notation)
        if default_topic:
            # Convert spl_fallback -> spl.fallback
            nats_subject = f"{topic_prefix}_{default_topic}".replace('_', '.')
            subject_names.append(nats_subject)

        # Extract mapped topics and convert to NATS subjects
        for topic_name in topics_map.values():
            if topic_name:
                # Convert spl_transfers -> spl.transfers (NATS dot notation)
                nats_subject = f"{topic_prefix}_{topic_name}".replace('_', '.')
                subject_names.append(nats_subject)

        print(f"🎯 Subjects to create streams for: {subject_names}")
        return subject_names

    async def _create_jetstream_stream(self, stream_name: str, subjects_pattern: str) -> bool:
        """Create JetStream stream with same config as bash script"""
        try:
            print(f"  🌊 Creating main stream: {stream_name}")
            print(f"  📍 Subject pattern: {subjects_pattern}")

            # Create stream config matching bash script parameters
            config = StreamConfig(
                name=stream_name,
                subjects=[subjects_pattern],
                storage=StorageType.FILE,
                retention=RetentionPolicy.LIMITS,
                max_msgs=-1,
                max_bytes=-1,
                max_age=24 * 60 * 60,  # 24 hours in seconds
                discard=DiscardPolicy.OLD
            )

            # Create the stream
            await self.js.add_stream(config)
            print(f"  ✅ Created JetStream stream: {stream_name}")
            return True

        except Exception as e:
            # Check if stream already exists (same logic as bash script)
            if "stream name already in use" in str(e).lower():
                print(f"  ℹ️  JetStream stream already exists: {stream_name}")
                return True
            else:
                print(f"  ❌ Failed to create JetStream stream {stream_name}: {e}")
                return False

    async def _verify_stream(self, stream_name: str) -> bool:
        """Verify stream exists and is properly configured"""
        try:
            stream_info = await self.js.stream_info(stream_name)
            print(f"  ✅ JetStream stream verified: {stream_name}")

            # Display stream details (matching bash script output)
            config = stream_info.config
            print(f"📋 Stream Details:")
            print(f"  Stream Name: {config.name}")
            print(f"  Subject Pattern: {', '.join(config.subjects)}")
            print(f"  Storage: {config.storage}")
            print(f"  Retention: {config.retention}")

            return True
        except Exception as e:
            print(f"  ⚠️  Could not verify JetStream stream {stream_name}: {e}")
            return False

    async def create_streams_from_fanout(self, fanout_file: Path) -> bool:
        """
        Create NATS streams from fanout configuration

        This is the main entry point that replicates the bash script logic:
        1. Parse fanout YAML config
        2. Extract topic names and convert to NATS subjects
        3. Create single JetStream stream with pattern matching
        4. Verify stream creation

        Args:
            fanout_file: Path to fanout YAML configuration file

        Returns:
            bool: True if successful, False if failed
        """
        print("🎯 Dynamic NATS Stream Creator")
        print()

        try:
            # Step 1: Parse fanout configuration
            fanout_config = self._parse_fanout_config(fanout_file)

            # Step 2: Extract subject names (converted from topics)
            subject_names = self._extract_subject_names(fanout_config)

            if not subject_names:
                print("❌ No topics found in fanout config")
                return False

            # Step 3: Connect to NATS server
            if not await self.connect():
                return False

            try:
                # Step 4: Create main stream with pattern matching
                # Same logic as bash: create single stream for all subjects
                topic_prefix = fanout_config.get("topic_prefix", "blockchain")
                stream_name = self.stream_name
                subjects_pattern = f"{topic_prefix}.>"  # Multi-level wildcard

                print("📦 Creating NATS JetStream streams...")
                success = await self._create_jetstream_stream(stream_name, subjects_pattern)

                if not success:
                    return False

                # Step 5: Verify stream creation
                print()
                print("📋 Verification - Stream info:")
                await self._verify_stream(stream_name)

                # Step 6: Display individual subjects (matching bash output)
                print()
                print(f"📋 All configured subjects will route to stream '{stream_name}':")
                for subject in subject_names:
                    print(f"  📍 {subject}")

                print()
                print("✅ JetStream stream creation complete!")
                print()
                print("🎉 Dynamic NATS stream creation complete!")
                print("Downstream consumers can now subscribe without connection errors.")
                print()
                print("💡 Stream Details:")
                print(f"  Stream Name: {stream_name}")
                print(f"  Subject Pattern: {subjects_pattern}")
                print(f"  Individual subjects: {', '.join(subject_names)}")

                return True

            finally:
                # Always disconnect
                await self.disconnect()

        except Exception as e:
            print(f"❌ Error creating NATS streams: {e}")
            return False


async def main():
    """CLI entry point for standalone usage"""
    import argparse

    parser = argparse.ArgumentParser(description="Create NATS JetStream streams from fanout config")
    parser.add_argument("fanout_config", type=Path, help="Path to fanout YAML configuration file")
    parser.add_argument("--server", default="nats://localhost:4222", help="NATS server URL")

    args = parser.parse_args()

    if not args.fanout_config.exists():
        print(f"❌ Error: Fanout config file not found: {args.fanout_config}")
        return False

    manager = NATSStreamManager(server_url=args.server)
    success = await manager.create_streams_from_fanout(args.fanout_config)

    return success


if __name__ == "__main__":
    import sys

    try:
        success = asyncio.run(main())
        sys.exit(0 if success else 1)
    except KeyboardInterrupt:
        print("\n❌ Interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)
