#!/usr/bin/env python3
"""
Test Runner with Coverage Reporting

Comprehensive test runner for all Python components with coverage analysis.
Generates both terminal and HTML coverage reports for detailed analysis.

Usage:
    uv run scripts/run_tests_with_coverage.py [--html] [--fail-under=80]
"""

import argparse
import subprocess
import sys
from pathlib import Path


def run_command(cmd, description):
    """Run a command and handle errors"""
    print(f"\n🧪 {description}")
    print(f"Running: {' '.join(cmd)}")

    try:
        result = subprocess.run(cmd, check=True, capture_output=True, text=True)
        if result.stdout:
            print(result.stdout)
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ {description} failed!")
        print(f"Error: {e}")
        if e.stderr:
            print(f"Stderr: {e.stderr}")
        if e.stdout:
            print(f"Stdout: {e.stdout}")
        return False


def main():
    parser = argparse.ArgumentParser(description="Run tests with coverage reporting")
    parser.add_argument("--html", action="store_true", help="Generate HTML coverage report")
    parser.add_argument("--fail-under", type=int, default=80, help="Minimum coverage percentage (default: 80)")
    parser.add_argument("--component", choices=["analyzer", "fanout", "pipeline", "all"], default="all",
                       help="Run tests for specific component or all")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose test output")

    args = parser.parse_args()

    # Ensure we're in the project root
    project_root = Path(__file__).parent.parent
    print(f"Running tests from: {project_root}")

    # Coverage configuration - use directory for better module detection
    if args.component == "all":
        coverage_source = "scripts"
    else:
        coverage_source = "scripts"  # Always use scripts directory for coverage

    # Base pytest command with coverage
    base_cmd = [
        "python3", "-m", "pytest",
        f"--cov={coverage_source}",
        "--cov-report=term-missing",
        f"--cov-fail-under={args.fail_under}"
    ]

    # Add component-specific coverage include filter
    if args.component != "all":
        component_files = {
            "analyzer": "scripts/analyze_protobuf_schema.py",
            "fanout": "scripts/generate_fanout_config.py",
            "pipeline": "scripts/setup_universal_pipeline.py"
        }
        if args.component in component_files:
            base_cmd.extend([f"--cov-include={component_files[args.component]}"])

    if args.html:
        base_cmd.append("--cov-report=html:htmlcov")

    if args.verbose:
        base_cmd.append("-v")

    # Define test suites
    test_suites = {
        "analyzer": ["tests/test_protobuf_analyzer.py"],
        "fanout": ["tests/test_generate_fanout_config.py"],
        "pipeline": ["tests/test_setup_universal_pipeline.py", "tests/test_integration_pipeline.py"],
        "all": [
            "tests/test_protobuf_analyzer.py",
            "tests/test_generate_fanout_config.py",
            "tests/test_setup_universal_pipeline.py",
            "tests/test_integration_pipeline.py"
        ]
    }

    # Select tests to run
    test_files = test_suites[args.component]

    success = True

    print(f"🎯 Running {args.component} tests with coverage analysis")
    print(f"📊 Minimum coverage threshold: {args.fail_under}%")

    # Run tests with coverage
    cmd = base_cmd + test_files
    if not run_command(cmd, f"Running {args.component} tests with coverage"):
        success = False

    # Generate additional coverage reports if requested
    if args.html and success:
        print(f"\n📈 HTML coverage report generated in: {project_root}/htmlcov/")
        print(f"Open: {project_root}/htmlcov/index.html")

    # Coverage summary for specific components
    if success:
        print(f"\n✅ All {args.component} tests passed with coverage >= {args.fail_under}%")

        # Component-specific coverage analysis
        coverage_cmd = [
            "python3", "-m", "coverage", "report",
            "--show-missing",
            "--include=scripts/*"
        ]

        if args.component != "all":
            # Show coverage for specific component files
            component_files = {
                "analyzer": "scripts/analyze_protobuf_schema.py",
                "fanout": "scripts/generate_fanout_config.py",
                "pipeline": "scripts/setup_universal_pipeline.py"
            }
            if args.component in component_files:
                coverage_cmd.extend(["--include", component_files[args.component]])

        run_command(coverage_cmd, "Detailed coverage report")

        print("\nTesting complete. All suites passed.")
    else:
        print("\n❌ Testing failed. Investigate the reported errors.")
        sys.exit(1)


if __name__ == "__main__":
    main()
