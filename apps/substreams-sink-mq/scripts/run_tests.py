#!/usr/bin/env python3
"""
Test runner for all Python components

This script provides different test execution modes:
- Unit tests: Fast, no external dependencies (default)
- Integration tests: Real Docker containers, slower
- All tests: Both unit and integration tests

Use this to validate functionality after any changes to Python scripts.
"""

import subprocess
import sys
import argparse
from pathlib import Path


def run_unit_tests():
    """Run fast unit tests only (default)"""
    print("🧪 Running unit tests (fast, no external dependencies)...")
    print("=" * 60)

    project_root = Path(__file__).parent.parent

    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "-v",
        "--tb=short",
        "--cov=scripts",
        "--cov-report=term-missing",
        "--cov-fail-under=20",  # Lower threshold for unit tests
        "--strict-markers",
        "--strict-config",
        "-m", "unit"  # Only unit tests
    ]

    return run_pytest_command(cmd, project_root)


def run_integration_tests():
    """Run integration tests with Docker containers (slower)"""
    print("🐳 Running integration tests (requires Docker)...")
    print("=" * 60)
    print("⚠️  This will start Docker containers and may take several minutes...")

    project_root = Path(__file__).parent.parent

    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "-v",
        "--tb=short",
        "--cov=scripts",
        "--cov-report=term-missing",
        "--cov-fail-under=20",  # Lower threshold for integration tests
        "--strict-markers",
        "--strict-config",
        "-m", "integration"  # Only integration tests
    ]

    return run_pytest_command(cmd, project_root)



def run_all_tests():
    """Run both unit and integration tests"""
    print("🧪🐳 Running ALL tests (unit + integration)...")
    print("=" * 60)
    print("⚠️  This includes Docker containers and may take several minutes...")

    project_root = Path(__file__).parent.parent

    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "-v",
        "--tb=short",
        "--cov=scripts",
        "--cov-report=term-missing",
        "--cov-fail-under=60",  # Reasonable threshold for all tests
        "--strict-markers",
        "--strict-config",
        "-o", "addopts="  # Override pytest.ini addopts to remove marker filter
    ]

    return run_pytest_command(cmd, project_root)


def run_pytest_command(cmd, project_root):
    """Execute pytest command and handle results"""
    try:
        result = subprocess.run(cmd, cwd=project_root, check=True)
        print("\n✅ All tests passed!")
        return True
    except subprocess.CalledProcessError as e:
        print(f"\n❌ Tests failed with return code {e.returncode}")
        return False
    except FileNotFoundError:
        print("❌ pytest not found. Install requirements first:")
        print("   uv pip install -r requirements-test.txt")
        return False


def main():
    """Main entry point with argument parsing"""
    parser = argparse.ArgumentParser(
        description="Run Python test suite",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Test Types:
  unit         Fast tests with no external dependencies (default)
  integration  Slower tests with real Docker containers
  all          Both unit and integration tests

Examples:
  python3 scripts/run_tests.py                    # Unit tests only (fast)
  python3 scripts/run_tests.py --type integration # Integration tests
  python3 scripts/run_tests.py --type all         # All tests

Environment:
  Make sure Docker is running for integration tests
  Install deps: uv pip install -r requirements-test.txt
        """
    )

    parser.add_argument(
        "--type",
        choices=["unit", "integration", "all"],
        default="unit",
        help="Type of tests to run (default: unit)"
    )

    args = parser.parse_args()

    if args.type == "unit":
        success = run_unit_tests()
    elif args.type == "integration":
        success = run_integration_tests()
    elif args.type == "all":
        success = run_all_tests()

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()